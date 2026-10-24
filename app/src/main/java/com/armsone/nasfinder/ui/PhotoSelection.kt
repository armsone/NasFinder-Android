package com.armsone.nasfinder.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.hanai.core.ImageDiversityRejectionReason
import com.hanai.core.ImageDiversitySelector
import com.hanai.core.ImagePairDistance
import com.hanai.core.ImageQualityCandidate
import com.hanai.core.ImageSelectionRole
import java.io.File
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 사진 선별 (photo selection): everything below runs on this device with HanAI's ImageDiversitySelector and the
 * existing HanYang photo measurements. Originals are never moved, renamed or deleted; staged copies live in
 * app-private storage until the user finishes or cancels this one batch.
 */
internal data class StagedPhoto(
    /** Ordinal in the staged list; also the candidate index handed to the selector. */
    val id: Int,
    val file: File,
    val displayName: String,
    val originalUri: String,
)

/** An image to stage: [displayName]/[originalUri] keep the shared item's own identity when [uri] is an app-private copy. */
internal data class PhotoSelectionSource(val uri: Uri, val displayName: String? = null, val originalUri: String? = null)

/** A per-item problem (unreadable, not a photo, analysis failure). Never turned into an empty or "all selected" result. */
internal data class PhotoSelectionIssue(val name: String, val reason: String)

internal data class PhotoSelectionBatch(
    val id: String,
    val photos: List<StagedPhoto>,
    val skippedVideos: Int,
    val issues: List<PhotoSelectionIssue>,
    /** Set when a pending batch's record or staged files are damaged; the batch stays listed until the user decides. */
    val problem: String? = null,
)

internal object PhotoSelectionStaging {
    private const val ROOT = "photo-selection-staging"
    private const val MANIFEST = "manifest.json"
    private val ID_PATTERN = Regex("^[0-9a-f-]{36}$")

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun batchDir(context: Context, id: String): File {
        require(ID_PATTERN.matches(id)) { "잘못된 선별 묶음 식별자입니다." }
        return File(root(context), id)
    }

    /** Deletes exactly this batch (never another pending batch). */
    fun delete(context: Context, id: String) {
        runCatching { batchDir(context, id).deleteRecursively() }
    }

    /**
     * Copies every picked image into app-private storage (the picked/shared URI grants may be temporary).
     * One unreadable item becomes an [PhotoSelectionIssue]; it never aborts the others. Videos are only counted.
     */
    suspend fun stage(
        context: Context,
        sources: List<PhotoSelectionSource>,
        extraSkippedVideos: Int,
        extraIssues: List<PhotoSelectionIssue> = emptyList(),
        onProgress: (done: Int, total: Int) -> Unit,
    ): PhotoSelectionBatch = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = batchDir(context, id)
        check(dir.mkdirs()) { "선별용 임시 저장 공간을 만들지 못했습니다." }
        val photos = mutableListOf<StagedPhoto>()
        val issues = extraIssues.toMutableList()
        var videos = extraSkippedVideos
        try {
            sources.forEachIndexed { index, source ->
                ensureActive()
                onProgress(index, sources.size)
                val uri = source.uri
                val name = source.displayName ?: displayName(context, uri, index)
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                    ?: runCatching {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    }.getOrNull()
                when {
                    mime?.startsWith("video/") == true -> videos += 1
                    mime != null && !mime.startsWith("image/") -> issues += PhotoSelectionIssue(name, "사진 파일이 아닙니다.")
                    else -> {
                        val extension = name.substringAfterLast('.', "").lowercase()
                            .takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isLetterOrDigit) } ?: "img"
                        val target = File(dir, "${photos.size}.$extension")
                        val partial = File(dir, "${target.name}.part")
                        try {
                            val opened = context.contentResolver.openInputStream(uri)
                                ?: throw java.io.IOException("파일을 열 수 없습니다.")
                            opened.use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(partial.absolutePath, bounds)
                            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "이미지로 읽을 수 없습니다." }
                            check(partial.renameTo(target)) { "임시 사본을 저장하지 못했습니다." }
                            photos += StagedPhoto(photos.size, target, name, source.originalUri ?: uri.toString())
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            partial.delete()
                            issues += PhotoSelectionIssue(name, error.message ?: "사본을 만들지 못했습니다.")
                        }
                    }
                }
            }
            onProgress(sources.size, sources.size)
            val batch = PhotoSelectionBatch(id, photos, videos, issues)
            writeManifest(dir, batch)
            batch
        } catch (cancelled: CancellationException) {
            // Only this batch's own folder is removed; other pending batches are never touched.
            dir.deleteRecursively()
            throw cancelled
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    /**
     * Every earlier batch folder (newest first). A folder with a missing/unreadable record or missing staged files is
     * still listed, with [PhotoSelectionBatch.problem]; nothing is hidden or deleted without the user's choice.
     */
    suspend fun listAll(context: Context): List<PhotoSelectionBatch> = withContext(Dispatchers.IO) {
        root(context).listFiles { file -> file.isDirectory && ID_PATTERN.matches(file.name) }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .map { dir -> runCatching { readManifest(dir) }.getOrElse { damaged(dir, "선별 기록을 읽지 못했습니다.") } }
    }

    private fun damaged(dir: File, reason: String) =
        PhotoSelectionBatch(dir.name, emptyList(), 0, emptyList(), problem = "$reason 파일은 지우지 않았습니다.")

    private fun writeManifest(dir: File, batch: PhotoSelectionBatch) {
        val json = JSONObject()
            .put("id", batch.id)
            .put("skippedVideos", batch.skippedVideos)
            .put(
                "photos",
                JSONArray().also { array ->
                    batch.photos.forEach { photo ->
                        array.put(
                            JSONObject().put("file", photo.file.name).put("name", photo.displayName).put("uri", photo.originalUri),
                        )
                    }
                },
            )
            .put(
                "issues",
                JSONArray().also { array ->
                    batch.issues.forEach { array.put(JSONObject().put("name", it.name).put("reason", it.reason)) }
                },
            )
        val temp = File(dir, "$MANIFEST.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        check(temp.renameTo(File(dir, MANIFEST))) { "선별 기록을 저장하지 못했습니다." }
    }

    private fun readManifest(dir: File): PhotoSelectionBatch {
        val manifest = File(dir, MANIFEST)
        if (!manifest.isFile) return damaged(dir, "선별 기록이 없습니다. 준비 도중 앱이 종료되었을 수 있습니다.")
        val json = JSONObject(manifest.readText(Charsets.UTF_8))
        val array = json.getJSONArray("photos")
        val listed = (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            StagedPhoto(index, File(dir, entry.getString("file")), entry.getString("name"), entry.getString("uri"))
        }
        val issueArray = json.getJSONArray("issues")
        val issues = (0 until issueArray.length()).map { index ->
            issueArray.getJSONObject(index).let { PhotoSelectionIssue(it.getString("name"), it.getString("reason")) }
        }.toMutableList()
        if (listed.isEmpty() && issues.isEmpty()) return damaged(dir, "비어 있는 선별 기록입니다.")
        val (present, missing) = listed.partition { it.file.isFile }
        missing.forEach { issues += PhotoSelectionIssue(it.displayName, "임시 사본이 사라졌습니다.") }
        val problem = if (missing.isEmpty()) null
        else "사진 ${missing.size}장의 임시 사본이 사라졌습니다. 남은 사진만 이어서 선별하거나, 이 묶음을 직접 제외할 수 있습니다."
        return PhotoSelectionBatch(
            json.getString("id"), present.mapIndexed { index, photo -> photo.copy(id = index) },
            json.optInt("skippedVideos", 0), issues, problem,
        )
    }

    fun displayName(context: Context, uri: Uri, index: Int): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() }?.substringAfterLast('/')
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "사진 ${index + 1}"
    }
}

/** One decoded photo with the measurements the policy needs. */
internal class AnalyzedPhoto(
    val photo: StagedPhoto,
    val sharpness: Double,
    val exposure: Double,
    val pixelCount: Int,
    val aspectRatio: Double,
    val metrics: PhotoSelectionMetrics,
    var embedding: FloatArray?,
)

internal class PhotoSelectionAnalysis(
    val batch: PhotoSelectionBatch,
    val analyzed: List<AnalyzedPhoto>,
    /** Photos that could not be analysed, each with its own reason. */
    val failures: List<PhotoSelectionIssue>,
    val distances: List<ImagePairDistance>,
    val usedDino: Boolean,
)

/**
 * Measures every staged photo on this device. A photo that cannot be decoded becomes a [PhotoSelectionIssue];
 * the other photos are still analysed. DINO embeddings (when the bundled model works for every photo) only add an
 * AND condition to the existing fingerprint similarity gates, exactly as the trailer analysis does.
 */
internal suspend fun analyzePhotoSelection(
    application: Application,
    batch: PhotoSelectionBatch,
    onProgress: (phase: String, done: Int, total: Int) -> Unit,
): PhotoSelectionAnalysis = withContext(Dispatchers.Default) {
    val analyzed = mutableListOf<AnalyzedPhoto>()
    val failures = batch.issues.toMutableList()
    batch.photos.forEachIndexed { index, photo ->
        ensureActive()
        onProgress("사진 품질 분석", index, batch.photos.size)
        val measured = runCatching { measureStagedPhoto(photo) }.getOrNull()
        if (measured == null) failures += PhotoSelectionIssue(photo.displayName, "사진을 분석하지 못했습니다.") else analyzed += measured
    }
    onProgress("사진 품질 분석", batch.photos.size, batch.photos.size)

    var usedDino = false
    if (analyzed.size >= 2) {
        val vectors = ArrayList<FloatArray>(analyzed.size)
        for ((index, item) in analyzed.withIndex()) {
            ensureActive()
            onProgress("비슷한 장면 확인", index, analyzed.size)
            val vector = HanYangVisualFeatures.computeEmbedding(application, item.photo.file) ?: break
            vectors += vector
        }
        // Mixed availability is never used: either every analysed photo has a vector, or none does.
        if (vectors.size == analyzed.size) {
            analyzed.forEachIndexed { index, item -> item.embedding = vectors[index] }
            usedDino = true
        }
    }

    val distances = ArrayList<ImagePairDistance>()
    for (first in analyzed.indices) {
        ensureActive()
        if (first % 16 == 0) onProgress("비슷한 장면 묶기", first, analyzed.size)
        for (second in first + 1 until analyzed.size) {
            val a = analyzed[first]
            val b = analyzed[second]
            val closeness = photoSelectionCloseness(a.metrics, a.aspectRatio, b.metrics, b.aspectRatio) ?: continue
            if (usedDino) {
                val dino = photoSelectionCosineDistance(checkNotNull(a.embedding), checkNotNull(b.embedding))
                if (dino > PHOTO_SELECTION_DINO_DISTANCE_GATE) continue
            }
            distances += ImagePairDistance(a.photo.id, b.photo.id, 1.0 - closeness)
        }
    }
    PhotoSelectionAnalysis(batch, analyzed, failures, distances, usedDino)
}

private fun measureStagedPhoto(photo: StagedPhoto): AnalyzedPhoto? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(photo.file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = runCatching {
        ExifInterface(photo.file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val swap = orientation in setOf(
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
    )
    val width = if (swap) bounds.outHeight else bounds.outWidth
    val height = if (swap) bounds.outWidth else bounds.outHeight
    var sample = 1
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / (sample * 2) >= 768) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val decoded = BitmapFactory.decodeFile(photo.file.absolutePath, options) ?: return null
    val oriented = orientBitmap(decoded, orientation)
    return try {
        val metrics = measurePhotoSelectionBitmap(oriented)
        AnalyzedPhoto(
            photo = photo,
            sharpness = metrics.sharpness,
            exposure = metrics.exposure,
            pixelCount = (width.toLong() * height.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            aspectRatio = width.toDouble() / height.coerceAtLeast(1),
            metrics = metrics,
            embedding = null,
        )
    } finally {
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
    }
}

private fun orientBitmap(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

internal data class PhotoRecommendation(
    /** M: photos that are eligible for selection. */
    val eligible: Int,
    /** G: groups of similar scenes. */
    val groups: Int,
    /** L: group representatives whose quality is markedly low. */
    val lowQualityRepresentatives: Int,
    /** N_rec. */
    val recommended: Int,
    val allLowQuality: Boolean,
    val lowQualityIndices: Set<Int>,
    /** Photos the selector itself rejected (invalid measurements); shown as item errors. */
    val rejected: List<PhotoSelectionIssue>,
)

internal data class PhotoSelectionOutcome(
    /** Original ordinals ascending (input order). */
    val selectedIndices: List<Int>,
    val roleByIndex: Map<Int, ImageSelectionRole>,
    val groups: Int,
    val lowQualityIndices: Set<Int>,
) {
    /** True when N exceeded the number of similar-scene groups, so extra members of similar scenes were added. */
    val includesSimilarExtras: Boolean get() = roleByIndex.values.any { it == ImageSelectionRole.EXTRA_GROUP_MEMBER }
}

/**
 * Recommendation policy shared with Apple (shared-contract/recommendation-policy.md, Apple single writer).
 * Constants marked provisional are unverified against real photo distributions; the implementation stays here so a
 * calibrated value is a one-line change. No fixed ratio, no item cap, user N is never overwritten.
 */
internal object PhotoSelectionPolicy {
    /** provisional: complete-link near-duplicate threshold on the normalised pair distance (policy §2). */
    const val GROUP_THRESHOLD = 0.60
    /** provisional absolute floors for "markedly low quality" (policy §3.2). */
    const val MIN_SHARPNESS = 0.02
    const val MIN_EXPOSURE = 0.10

    private fun candidates(analysis: PhotoSelectionAnalysis): List<ImageQualityCandidate> =
        analysis.analyzed.map {
            ImageQualityCandidate(it.photo.id, it.sharpness, it.exposure, it.pixelCount, aestheticsScore = null)
        }

    /** Null when no photo is eligible: recommendation and results are blocked, never fabricated. */
    fun recommend(analysis: PhotoSelectionAnalysis): PhotoRecommendation? {
        val all = candidates(analysis)
        if (all.isEmpty()) return null
        val result = ImageDiversitySelector.selectDiverse(all, analysis.distances, GROUP_THRESHOLD, all.size)
        val eligible = result.scoreByIndex.size
        if (eligible == 0) return null
        val byId = analysis.analyzed.associateBy { it.photo.id }
        val representatives = result.groups.map { it.representativeIndex }
        val absoluteLow = representatives.filter { id ->
            val item = byId.getValue(id)
            item.sharpness < MIN_SHARPNESS || item.exposure < MIN_EXPOSURE
        }.toSet()
        val tukeyLow = if (representatives.size >= 4) {
            val scores = representatives.map { result.scoreByIndex.getValue(it) }.sorted()
            val q1 = quantile(scores, 0.25)
            val q3 = quantile(scores, 0.75)
            val fence = q1 - 1.5 * (q3 - q1)
            representatives.filter { result.scoreByIndex.getValue(it) < fence }.toSet()
        } else emptySet()
        val low = absoluteLow + tukeyLow
        val allLow = low.size == representatives.size
        val recommended = if (allLow) 1 else max(1, result.groups.size - low.size)
        val rejected = result.rejectionReasonsByIndex.map { (index, reason) ->
            PhotoSelectionIssue(byId[index]?.photo?.displayName ?: "사진 ${index + 1}", describe(reason))
        }
        return PhotoRecommendation(eligible, result.groups.size, low.size, recommended, allLow, low, rejected)
    }

    /**
     * Selection for an explicit count (policy §4). [count] must be in 1..eligible; a larger value is an error for the user to fix,
     * it is never reduced silently.
     * Selection priority:
     * 1. Normal-quality group representatives in descending order of score Q.
     * 2. If count > normal representatives, low-quality group representatives in descending order of score Q.
     * 3. If count > group count G, extra group members in donor quality/diversity order.
     */
    fun select(analysis: PhotoSelectionAnalysis, count: Int, recommendation: PhotoRecommendation): PhotoSelectionOutcome {
        require(count in 1..recommendation.eligible) { "선택 가능한 사진은 ${recommendation.eligible}장입니다." }
        val all = candidates(analysis)
        val full = ImageDiversitySelector.selectDiverse(all, analysis.distances, GROUP_THRESHOLD, all.size)
        check(full.scoreByIndex.size == recommendation.eligible) { "선별 가능한 사진 수가 일치하지 않습니다." }
        val groups = full.groups
        val scores = full.scoreByIndex
        val lowSet = recommendation.lowQualityIndices

        val allRepresentatives = groups.map { it.representativeIndex }
        val repSet = allRepresentatives.toSet()
        val normalReps = allRepresentatives.filter { it !in lowSet }
            .sortedWith(compareByDescending<Int> { scores[it] ?: 0.0 }.thenBy { it })
        val lowReps = allRepresentatives.filter { it in lowSet }
            .sortedWith(compareByDescending<Int> { scores[it] ?: 0.0 }.thenBy { it })

        val selectedRoles = LinkedHashMap<Int, ImageSelectionRole>()
        if (count <= normalReps.size) {
            normalReps.take(count).forEach { id ->
                selectedRoles[id] = ImageSelectionRole.GROUP_REPRESENTATIVE
            }
        } else if (count <= groups.size) {
            normalReps.forEach { id ->
                selectedRoles[id] = ImageSelectionRole.GROUP_REPRESENTATIVE
            }
            val neededLow = count - normalReps.size
            lowReps.take(neededLow).forEach { id ->
                selectedRoles[id] = ImageSelectionRole.GROUP_REPRESENTATIVE
            }
        } else {
            // count > groups.size: select all representatives first, then fill remaining slots with donor's extra members
            normalReps.forEach { id ->
                selectedRoles[id] = ImageSelectionRole.GROUP_REPRESENTATIVE
            }
            lowReps.forEach { id ->
                selectedRoles[id] = ImageSelectionRole.GROUP_REPRESENTATIVE
            }
            val extraNeeded = count - groups.size
            val donorExtras = full.selectionOrderIndices
                .filter { it !in repSet }
                .take(extraNeeded)
            donorExtras.forEach { id ->
                selectedRoles[id] = ImageSelectionRole.EXTRA_GROUP_MEMBER
            }
        }
        check(selectedRoles.size == count) { "선별 결과 개수가 요청과 다릅니다." }
        return PhotoSelectionOutcome(
            selectedIndices = selectedRoles.keys.sorted(),
            roleByIndex = selectedRoles,
            groups = groups.size,
            lowQualityIndices = recommendation.lowQualityIndices,
        )
    }

    /** Linear-interpolated quantile of an ascending list. */
    private fun quantile(sorted: List<Double>, p: Double): Double {
        val position = p * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = kotlin.math.ceil(position).toInt()
        return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower)
    }

    private fun describe(reason: ImageDiversityRejectionReason): String = when (reason) {
        ImageDiversityRejectionReason.NON_POSITIVE_PIXEL_COUNT -> "사진 크기를 확인하지 못했습니다."
        ImageDiversityRejectionReason.NON_FINITE_OR_OUT_OF_RANGE_SHARPNESS,
        ImageDiversityRejectionReason.NON_FINITE_OR_OUT_OF_RANGE_EXPOSURE,
        ImageDiversityRejectionReason.NON_FINITE_OR_OUT_OF_RANGE_AESTHETICS,
        -> "품질 값을 계산하지 못했습니다."
        else -> "분석 대상에서 제외되었습니다."
    }

    /** User-facing sentence built only from observed counts. */
    fun explanation(recommendation: PhotoRecommendation, analysis: PhotoSelectionAnalysis): List<String> = buildList {
        add(
            if (recommendation.allLowQuality) {
                "분석한 ${recommendation.eligible}장 모두 품질이 낮아 가장 나은 1장만 추천합니다."
            } else {
                "분석한 ${recommendation.eligible}장 → 비슷한 장면 ${recommendation.groups}묶음" +
                    (if (recommendation.lowQualityRepresentatives > 0) ", 품질이 낮은 대표 ${recommendation.lowQualityRepresentatives}장 제외" else "") +
                    " → 추천 ${recommendation.recommended}장"
            },
        )
        val blurry = analysis.analyzed.count { it.sharpness < MIN_SHARPNESS }
        val badLight = analysis.analyzed.count { it.exposure < MIN_EXPOSURE }
        if (blurry > 0 || badLight > 0) {
            add("선명도가 매우 낮은 사진 ${blurry}장, 너무 어둡거나 밝은 사진 ${badLight}장이 있습니다.")
        }
        val failed = analysis.failures.size + recommendation.rejected.size
        if (failed > 0) add("분석하지 못한 사진 ${failed}장은 아래에 따로 표시합니다.")
        if (analysis.batch.skippedVideos > 0) add("동영상 ${analysis.batch.skippedVideos}개는 선별에서 제외했습니다.")
        add("이 기기 안에서 선명도·밝기·해상도를 계산하고 비슷한 장면을 묶어 고릅니다. 추천은 참고용이라 장수는 직접 바꿀 수 있고, 사진이 많을수록 분석 시간이 길어집니다.")
    }
}

internal enum class PhotoSelectionPhase { IDLE, STAGING, ANALYZING, BLOCKED, SETUP, PREVIEW, WORKING }

internal data class PhotoSelectionState(
    val phase: PhotoSelectionPhase = PhotoSelectionPhase.IDLE,
    val progressLabel: String? = null,
    val progress: Float? = null,
    /** Every earlier staged batch (the pending inbox), distinct from the active [batch]; none is auto-cleared or capped. */
    val pendingBatches: List<PhotoSelectionBatch> = emptyList(),
    val batch: PhotoSelectionBatch? = null,
    val analysis: PhotoSelectionAnalysis? = null,
    val recommendation: PhotoRecommendation? = null,
    /** What the user typed; never replaced by the recommendation once edited. */
    val countText: String = "",
    val countEdited: Boolean = false,
    val countError: String? = null,
    val outcome: PhotoSelectionOutcome? = null,
    val confirmed: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val skippedVideosNotice: Int = 0,
)

/**
 * Owns the one active photo-selection batch. Cancelling removes only this batch's staging folder; the pending
 * share dialog, the PhoneHard inbox and other staged batches are separate and untouched.
 */
internal class PhotoSelectionSession(
    private val application: Application,
    private val scope: CoroutineScope,
    private val addToMaterials: suspend (batchId: String, photos: List<StagedPhoto>, pocketId: String) -> Result<String>,
) {
    private val mutableState = MutableStateFlow(PhotoSelectionState())
    val state: StateFlow<PhotoSelectionState> = mutableState.asStateFlow()
    private var job: Job? = null

    private fun busy(): Boolean = job?.isActive == true

    /** "다시 확인": re-reads the pending list (e.g. after a damaged record was reported). */
    fun recheck() {
        scope.launch { refreshResumable() }
    }

    suspend fun refreshResumable() {
        if (busy() || mutableState.value.phase != PhotoSelectionPhase.IDLE) return
        val all = PhotoSelectionStaging.listAll(application)
        mutableState.update { if (it.phase == PhotoSelectionPhase.IDLE) it.copy(pendingBatches = all) else it }
    }

    fun start(uris: List<Uri>, skippedVideos: Int = 0): Unit = start(uris.map { PhotoSelectionSource(it) }, skippedVideos)

    /**
     * Stages [sources] (images only count; [skippedVideos] are already-known videos) and analyses them.
     * [onStaged] runs once this batch's own staging is complete (a shared pending copy may then be released);
     * [onAbort] runs when staging did not finish (cancel/failure) so the caller keeps its own pending data.
     */
    fun start(
        sources: List<PhotoSelectionSource>,
        skippedVideos: Int = 0,
        extraIssues: List<PhotoSelectionIssue> = emptyList(),
        onStaged: () -> Unit = {},
        onAbort: () -> Unit = {},
    ) {
        if (busy() || mutableState.value.phase != PhotoSelectionPhase.IDLE) {
            mutableState.update { it.copy(message = "진행 중인 사진 선별이 있습니다. 먼저 완료하거나 취소해 주세요.") }
            onAbort()
            return
        }
        mutableState.value = PhotoSelectionState(phase = PhotoSelectionPhase.STAGING, progressLabel = "사진 준비 중", skippedVideosNotice = skippedVideos)
        job = scope.launch {
            var staged = false
            try {
                val batch = PhotoSelectionStaging.stage(application, sources, skippedVideos, extraIssues) { done, total ->
                    mutableState.update { it.copy(progressLabel = "사진 준비 ${done}/${total}", progress = if (total > 0) done.toFloat() / total else null) }
                }
                staged = true
                onStaged()
                analyze(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = PhotoSelectionState(error = error.message ?: "사진을 준비하지 못했습니다.")
            } finally {
                if (!staged) onAbort()
            }
        }
    }

    /** Continues one earlier pending batch by id; the other pending batches stay as they are. */
    fun resume(batchId: String) {
        val batch = mutableState.value.pendingBatches.firstOrNull { it.id == batchId } ?: return
        if (busy() || mutableState.value.phase != PhotoSelectionPhase.IDLE) return
        mutableState.value = PhotoSelectionState(phase = PhotoSelectionPhase.ANALYZING, batch = batch)
        job = scope.launch {
            try {
                analyze(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = PhotoSelectionState(phase = PhotoSelectionPhase.BLOCKED, batch = batch, error = error.message ?: "분석하지 못했습니다.")
            }
        }
    }

    fun retryAnalysis() {
        val batch = mutableState.value.batch ?: return
        if (busy()) return
        mutableState.update { it.copy(phase = PhotoSelectionPhase.ANALYZING, error = null) }
        job = scope.launch {
            try {
                analyze(batch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = PhotoSelectionState(phase = PhotoSelectionPhase.BLOCKED, batch = batch, error = error.message ?: "분석하지 못했습니다.")
            }
        }
    }

    private suspend fun analyze(batch: PhotoSelectionBatch) {
        mutableState.update {
            it.copy(phase = PhotoSelectionPhase.ANALYZING, batch = batch, progressLabel = "사진 분석 중", progress = null)
        }
        val analysis = analyzePhotoSelection(application, batch) { label, done, total ->
            mutableState.update {
                it.copy(progressLabel = "$label ${done}/${total}", progress = if (total > 0) done.toFloat() / total else null)
            }
        }
        val recommendation = withContext(Dispatchers.Default) { PhotoSelectionPolicy.recommend(analysis) }
        if (recommendation == null) {
            // M = 0 (all failed, video-only or empty): explicit item errors and retry, never a manufactured result.
            mutableState.value = PhotoSelectionState(
                phase = PhotoSelectionPhase.BLOCKED,
                batch = batch,
                analysis = analysis,
                error = "분석할 수 있는 사진이 없어 추천과 선별을 진행할 수 없습니다.",
            )
            return
        }
        mutableState.value = PhotoSelectionState(
            phase = PhotoSelectionPhase.SETUP,
            batch = batch,
            analysis = analysis,
            recommendation = recommendation,
            countText = recommendation.recommended.toString(),
        )
    }

    fun setCountText(text: String) {
        val filtered = text.filter(Char::isDigit).take(9)
        mutableState.update { it.copy(countText = filtered, countEdited = true, countError = validate(filtered, it.recommendation)) }
    }

    private fun validate(text: String, recommendation: PhotoRecommendation?): String? {
        val value = text.toIntOrNull() ?: return "개수를 숫자로 입력해 주세요."
        val eligible = recommendation?.eligible ?: return null
        return when {
            value < 1 -> "1장 이상을 입력해 주세요."
            value > eligible -> "분석된 사진은 ${eligible}장입니다. ${eligible}장 이하로 직접 바꿔 주세요."
            else -> null
        }
    }

    fun showPreview() {
        val current = mutableState.value
        val analysis = current.analysis ?: return
        val recommendation = current.recommendation ?: return
        val error = validate(current.countText, recommendation)
        if (error != null) {
            mutableState.update { it.copy(countError = error) }
            return
        }
        val count = current.countText.toInt()
        mutableState.update { it.copy(phase = PhotoSelectionPhase.WORKING, progressLabel = "결과 계산 중", progress = null) }
        job = scope.launch {
            val outcome = runCatching { withContext(Dispatchers.Default) { PhotoSelectionPolicy.select(analysis, count, recommendation) } }
            outcome.onSuccess { selected ->
                mutableState.update { it.copy(phase = PhotoSelectionPhase.PREVIEW, outcome = selected, confirmed = false, countError = null, message = null) }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(phase = PhotoSelectionPhase.SETUP, countError = failure.message ?: "선별 결과를 만들지 못했습니다.") }
            }
        }
    }

    fun backToSetup() {
        mutableState.update { it.copy(phase = PhotoSelectionPhase.SETUP, outcome = null, confirmed = false, message = null) }
    }

    fun confirm() {
        mutableState.update { if (it.outcome != null) it.copy(confirmed = true) else it }
    }

    private fun selectedPhotos(): Pair<PhotoSelectionBatch, List<StagedPhoto>>? {
        val current = mutableState.value
        val batch = current.batch ?: return null
        val outcome = current.outcome ?: return null
        if (!current.confirmed) return null
        val byId = batch.photos.associateBy { it.id }
        return batch to outcome.selectedIndices.mapNotNull(byId::get)
    }

    /** Copies the confirmed photos into Pictures/NasFinder/사진 선별; originals are untouched. */
    fun saveToGallery() {
        val (batch, photos) = selectedPhotos() ?: return
        if (busy()) return
        mutableState.update { it.copy(message = "저장하는 중…") }
        job = scope.launch {
            val saved = withContext(Dispatchers.IO) { photos.count { runCatching { savePhoto(application, it) }.isSuccess } }
            val message = when {
                saved == photos.size -> "${saved}장을 사진 보관함(Pictures/NasFinder/사진 선별)에 저장했습니다."
                saved == 0 -> "저장하지 못했습니다. 저장 공간과 권한을 확인한 뒤 다시 시도해 주세요."
                else -> "${saved}/${photos.size}장만 저장했습니다. 나머지는 다시 시도할 수 있습니다."
            }
            mutableState.update { it.copy(message = message) }
        }
    }

    /** Files for a share intent (copied under cache/shares so the existing FileProvider grant scope applies). */
    suspend fun prepareShareFiles(): List<File>? {
        val (batch, photos) = selectedPhotos() ?: return null
        return withContext(Dispatchers.IO) {
            val dir = File(application.cacheDir, "shares/photo-selection-${batch.id}").also { it.mkdirs() }
            photos.map { photo ->
                val target = File(dir, "${photo.id}-${photo.file.name}")
                if (!target.isFile) photo.file.copyTo(target, overwrite = true)
                target
            }
        }
    }

    fun addToVideoMaterials(pocketId: String) {
        val (batch, photos) = selectedPhotos() ?: return
        if (busy()) return
        mutableState.update { it.copy(message = "작업대에 담는 중…") }
        job = scope.launch {
            val result = addToMaterials(batch.id, photos, pocketId)
            mutableState.update { it.copy(message = result.getOrElse { error -> error.message ?: "작업대에 담지 못했습니다." }) }
        }
    }

    /** Explicit finish: removes this batch's staging and share copies only. */
    fun finish() {
        cleanup(mutableState.value.batch?.id)
        mutableState.value = PhotoSelectionState()
    }

    /** Cancels running work and removes only this batch; nothing else pending is touched. */
    fun cancel() {
        job?.cancel()
        job = null
        cleanup(mutableState.value.batch?.id)
        mutableState.value = PhotoSelectionState()
    }

    /** Dismisses a previous failure without touching a staged batch that is still resumable. */
    fun clearError() {
        mutableState.update { it.copy(error = null, message = null) }
    }

    /** Explicitly excludes and cleans exactly one pending batch (the user's choice); no other batch is touched. */
    fun discard(batchId: String) {
        if (mutableState.value.batch?.id == batchId) return
        PhotoSelectionStaging.delete(application, batchId)
        runCatching { File(application.cacheDir, "shares/photo-selection-$batchId").deleteRecursively() }
        mutableState.update { it.copy(pendingBatches = it.pendingBatches.filterNot { batch -> batch.id == batchId }) }
    }

    private fun cleanup(batchId: String?) {
        if (batchId == null) return
        PhotoSelectionStaging.delete(application, batchId)
        runCatching { File(application.cacheDir, "shares/photo-selection-$batchId").deleteRecursively() }
    }

    private fun savePhoto(context: Context, photo: StagedPhoto) {
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(photo.file.extension.lowercase()) ?: "image/jpeg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, photo.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/NasFinder/사진 선별")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val resolver = context.contentResolver
        val destination = checkNotNull(resolver.insert(collection, values)) { "저장 위치를 만들지 못했습니다." }
        try {
            resolver.openOutputStream(destination, "w")!!.use { output -> photo.file.inputStream().use { it.copyTo(output) } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (error: Throwable) {
            resolver.delete(destination, null, null)
            throw error
        }
    }
}
