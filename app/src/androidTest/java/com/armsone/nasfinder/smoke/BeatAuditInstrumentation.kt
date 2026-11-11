package com.armsone.nasfinder.smoke

import android.app.Activity
import android.app.Instrumentation
import android.media.MediaMetadataRetriever
import android.os.Bundle
import com.armsone.nasfinder.ui.AudioAnalysisResult
import com.armsone.nasfinder.ui.AudioAnalysisService
import com.armsone.nasfinder.ui.EditorProject
import com.armsone.nasfinder.ui.EditorScene
import com.armsone.nasfinder.ui.TrailerDraftStore
import com.armsone.nasfinder.ui.TrailerDraft
import com.armsone.nasfinder.ui.TrailerExporter
import com.armsone.nasfinder.ui.TrailerFormat
import com.armsone.nasfinder.ui.TrailerMusicPolicy
import com.armsone.nasfinder.ui.TrailerMusicTrack
import com.armsone.nasfinder.ui.TrailerScene
import com.armsone.nasfinder.ui.TrailerSource
import com.armsone.nasfinder.ui.TrailerTitleCard
import com.armsone.nasfinder.ui.SceneCaption
import com.armsone.nasfinder.ui.TrailerClipAdjustments
import com.armsone.nasfinder.ui.TrailerColorAdjustment
import com.armsone.nasfinder.ui.TrailerTimedCaptionCue
import com.armsone.nasfinder.ui.TrailerTransformKeyframe
import com.armsone.nasfinder.ui.TrailerTransition
import com.armsone.nasfinder.ui.TrailerVoiceoverTake
import com.armsone.nasfinder.ui.redistributeManualTotal
import com.armsone.nasfinder.ui.resolveSceneDurationBounds
import com.armsone.nasfinder.ui.syncPhotoScenesToBeatOnsets
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Focused beat audit (not part of the default smoke): renders a real MP4 through the app's own
 * AudioAnalysisService.extractBeatOnsets -> syncPhotoScenesToBeatOnsets -> TrailerExporter.export path and writes the plan
 * (scene boundaries, detected beats) next to the artifact. The rendered video/audio are measured afterwards by tools/analyze_render.py.
 *
 *   mode=synthetic  fixtureDir=<dir with music.mp3 + src-*.mp4/jpg>  [fit=3] [cards=1]
 *   mode=av-init|av-run|av-cleanup|av-meta-snapshot|av-store-proof  ownUuid=<uuid> nonce=<n> [cases=a,b1,...]  (own synthetic namespace only, see AvProof)
 *   mode=draft      [fit=1] [draftId=<id>]   (drafts_meta.json is copied to a scratch dir; the original draft/media are read only)
 */
class BeatAuditInstrumentation : Instrumentation() {
    private var args: Bundle = Bundle()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val result = Bundle()
        var ok = false
        try {
            val out = if ((args.getString("mode") ?: "").startsWith("av-")) AvProof(this, args).run() else BeatAudit(this, args).run()
            result.putString("beat_audit_out", out.absolutePath)
            ok = true
        } catch (error: Throwable) {
            result.putString("beat_audit_error", error.stackTraceToString().take(3000))
        }
        finish(if (ok) Activity.RESULT_OK else Activity.RESULT_FIRST_USER, result)
    }
}

private class BeatAudit(private val inst: Instrumentation, private val args: Bundle) {
    private val target = inst.targetContext
    private val mode = args.getString("mode") ?: "synthetic"
    private val outDir = File(target.getExternalFilesDir(null) ?: target.filesDir, "beat-audit-$mode${args.getString("tag")?.let { "-$it" } ?: ""}").apply { deleteRecursively(); mkdirs() }

    private fun status(text: String) = inst.sendStatus(0, Bundle().apply { putString("beat_audit", text) })

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(1 shl 16); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun durationOf(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
        } finally { retriever.release() }
    }

    // mode=probe: where the first kick sits in the raw MediaCodec PCM (pts-based vs counted frames) for the fixture music.
    private fun probeDecoder(): File {
        val fixtureDir = File(args.getString("fixtureDir") ?: "/data/local/tmp/beat-audit-fixture")
        val extractor = android.media.MediaExtractor().apply { setDataSource(File(fixtureDir, "music.mp3").absolutePath) }
        extractor.selectTrack(0)
        val format = extractor.getTrackFormat(0)
        val decoder = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
        decoder.configure(format, null, null, 0); decoder.start()
        val info = android.media.MediaCodec.BufferInfo()
        val lines = JSONArray()
        lines.put("format=$format codec=${decoder.name} firstSampleTimeUs=${extractor.sampleTime}")
        var counted = 0L; var hitCounted = -1L; var hitPts = -1.0; var buffers = 0; var inputDone = false; var done = false
        while (!done && hitCounted < 0) {
            if (!inputDone) {
                val i = decoder.dequeueInputBuffer(10_000)
                if (i >= 0) {
                    val size = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                    if (size < 0) { decoder.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val o = decoder.dequeueOutputBuffer(info, 10_000)
            if (o >= 0) {
                val ch = decoder.outputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                val sr = decoder.outputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                val shorts = decoder.getOutputBuffer(o)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val frames = info.size / 2 / ch
                if (buffers < 6) lines.put("buffer#$buffers pts=${info.presentationTimeUs} frames=$frames ch=$ch sr=$sr flags=${info.flags}")
                buffers++
                for (f in 0 until frames) {
                    if (hitCounted < 0 && Math.abs(shorts.get(f * ch).toInt()) > 0.12 * 32768) { hitCounted = counted + f; hitPts = info.presentationTimeUs / 1e6 + f / sr.toDouble(); break }
                }
                counted += frames
                decoder.releaseOutputBuffer(o, false)
                done = (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
            }
        }
        decoder.stop(); decoder.release(); extractor.release()
        lines.put("firstKickByCountedFrames=${hitCounted / 44100.0}s (frame $hitCounted) firstKickByPts=$hitPts (truth 0.21)")
        File(outDir, "probe.json").writeText(lines.toString(2))
        return outDir
    }

    fun run(): File {
        if (mode == "probe") return probeDecoder()
        val fitCount = args.getString("fit")?.toIntOrNull()?.coerceAtLeast(1) ?: if (mode == "draft") 1 else 3
        return if (mode == "draft") runDraft(fitCount) else runSynthetic(fitCount)
    }

    // ------------------------------------------------------------------ synthetic fixture

    private fun fixtureSources(fixtureDir: File): List<TrailerSource> =
        fixtureDir.listFiles { f -> f.name.startsWith("src-") }.orEmpty().sortedBy { it.name }.mapIndexed { index, file ->
            val isImage = file.extension.equals("jpg", true)
            val (w, h) = if (isImage) 1600 to 1200 else 1280 to 720
            TrailerSource(
                id = index, name = file.name, file = file, duration = if (isImage) 0.0 else durationOf(file), width = w, height = h,
                analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = isImage,
            )
        }

    private fun runSynthetic(fitCount: Int): File {
        val fixtureDir = File(args.getString("fixtureDir") ?: File(target.getExternalFilesDir(null), "beat-audit-fixture").absolutePath)
        val musicFile = File(fixtureDir, "music.mp3")
        require(musicFile.isFile) { "fixture music missing: $musicFile" }
        val music = TrailerMusicTrack(musicFile, "music.mp3", AudioAnalysisService.playableMusicSeconds(musicFile, durationOf(musicFile)))
        val sources = fixtureSources(fixtureDir)
        val cards = args.getString("cards") != "0"
        val intro = TrailerTitleCard(enabled = cards, title = "BEAT AUDIT", subtitle = "intro", duration = 2.0, usesFirstOrLastImage = false)
        val outro = TrailerTitleCard(enabled = cards, title = "END", subtitle = "outro", duration = 2.0, usesFirstOrLastImage = false)
        val cardTime = (if (intro.enabled) intro.clampedDuration() else 0.0) + (if (outro.enabled) outro.clampedDuration() else 0.0)
        val bodyTarget = music.durationSeconds * fitCount - cardTime
        // Same shape as a manual project after 음악길이: scenes cycle through the sources and share the body time by redistributeManualTotal.
        val sceneCount = Math.round(bodyTarget / 3.0).toInt().coerceAtLeast(1)
        val lengths = DoubleArray(sceneCount) { i -> 2.2 + (i * 7 % 5) * 0.4 }
        val scale = bodyTarget / lengths.sum()
        val scenes = List(sceneCount) { i -> EditorScene(key = i + 1L, sourceId = sources[i % sources.size].id, start = 0.0, length = lengths[i] * scale, sourceVolume = 0f) }
        return render(sources, scenes, music, TrailerMusicPolicy.FIT_MUSIC_LENGTH, fitCount, intro, outro, JSONObject().put("kind", "synthetic"))
    }

    // ------------------------------------------------------------------ the user's real draft (read only)

    private fun runDraft(fitCount: Int): File {
        val scratch = File(outDir, "scratch-store").apply { mkdirs() }
        val originalMeta = File(target.filesDir, "trailer_drafts/drafts_meta.json")
        require(originalMeta.isFile) { "drafts_meta.json missing" }
        File(scratch, "trailer_drafts").apply { mkdirs() }.let { originalMeta.copyTo(File(it, "drafts_meta.json"), overwrite = true) }
        TrailerDraftStore.configureTrailersRoot(File(scratch, "cache").apply { mkdirs() }, scratch)
        val drafts = TrailerDraftStore.getEveryDraft()
        val wanted = args.getString("draftId")
        val candidates = JSONArray()
        drafts.forEach { d ->
            candidates.put(JSONObject().put("id", d.id).put("title", d.displayTitle).put("sources", d.sources.size).put("scenes", d.editor?.scenes?.size ?: -1)
                .put("musicName", d.musicTrack?.name).put("musicDuration", d.musicTrack?.durationSeconds).put("policy", d.musicPolicy.name).put("fitCount", d.musicFitCount)
                .put("timestamp", d.timestamp).put("valid", d.isValid()))
        }
        File(outDir, "draft-candidates.json").writeText(candidates.toString(2))
        val draft = (if (wanted != null) drafts.firstOrNull { it.id == wanted } else drafts
            .filter { it.musicTrack != null && it.editor != null && it.isValid() && it.musicTrack.durationSeconds in 80.0..86.0 && it.sources.size == 54 }
            .maxByOrNull { it.timestamp }) ?: error("no matching draft (see draft-candidates.json)")
        val music = requireNotNull(draft.musicTrack)
        val editor = requireNotNull(draft.editor)

        fun snapshot() = JSONObject().put("draftsMetaSha256", sha256(originalMeta)).put("musicSha256", sha256(music.file))
            .put("sources", JSONArray(draft.sources.map { "${it.file.name}:${it.file.length()}:${it.file.lastModified()}" }))
        val readOnlyBefore = snapshot()
        val intro = editor.introCard
        val outro = editor.outroCard
        val cardTime = (if (intro.enabled) intro.clampedDuration() else 0.0) + (if (outro.enabled) outro.clampedDuration() else 0.0)
        // Same as editorSetMusic(FIT_MUSIC_LENGTH, n) on a manual project: redistribute scene time to the music total.
        val fitted = if (args.getString("fit") != "0")
            redistributeManualTotal(editor, draft.sources, (music.durationSeconds * fitCount - cardTime).coerceAtLeast(0.1)) else editor.scenes to 0.0
        val policy = if (args.getString("fit") != "0") TrailerMusicPolicy.FIT_MUSIC_LENGTH else draft.musicPolicy
        val count = if (args.getString("fit") != "0") fitCount else draft.musicFitCount
        val info = JSONObject().put("kind", "draft").put("draftId", draft.id).put("draftTitle", draft.displayTitle).put("sourceCount", draft.sources.size)
            .put("originalSceneCount", editor.scenes.size).put("originalScenesTotal", editor.scenes.sumOf { it.length }).put("redistributedTotal", fitted.second)
            .put("draftScenes", JSONArray(editor.scenes.map { JSONArray(listOf(it.sourceId, it.start, it.length, it.sourceVolume.toDouble())) }))
            .put("originalPolicy", draft.musicPolicy.name).put("originalFitCount", draft.musicFitCount).put("readOnlyBefore", readOnlyBefore)
        // visuals=synthetic: the real song and the draft's real scene lengths / photo-video order, but strongly distinct colour clips,
        // so every rendered cut is unambiguous to measure (real footage often cuts between near-identical frames).
        val (renderSources, renderScenes) = if (args.getString("visuals") == "synthetic") {
            val pool = fixtureSources(File(args.getString("fixtureDir") ?: "/data/local/tmp/beat-audit-fixture"))
            val images = pool.filter { it.isImage }; val videos = pool.filter { !it.isImage }
            val sourceById = draft.sources.associateBy { it.id }
            var vi = 0; var ii = 0
            pool to fitted.first.map { sc ->
                val pick = if (sourceById[sc.sourceId]?.isImage == true) images[ii++ % images.size] else videos[vi++ % videos.size]
                sc.copy(sourceId = pick.id, start = 0.0, sourceVolume = 0f)
            }
        } else {
            // muteOriginal=true: test copy only (the stored draft is never touched) silences the footage's own audio so only the BGM is measured.
            // sourceId / start / length / source list stay exactly as redistributeManualTotal produced them.
            draft.sources to if (args.getString("muteOriginal") == "true") fitted.first.map { it.copy(sourceVolume = 0f) } else fitted.first
        }
        val rendered = render(renderSources, renderScenes, music, policy, count, intro, outro, info.put("visuals", args.getString("visuals") ?: "draft"))
        val after = snapshot()
        File(outDir, "read-only-check.json").writeText(JSONObject().put("before", readOnlyBefore).put("after", after)
            .put("unchanged", readOnlyBefore.toString() == after.toString()).toString(2))
        File(scratch.absolutePath).deleteRecursively()
        return rendered
    }

    // ------------------------------------------------------------------ shared: analysis -> beat sync -> real export

    private fun render(
        sources: List<TrailerSource>, scenes: List<EditorScene>, musicIn: TrailerMusicTrack, policy: TrailerMusicPolicy, fitCount: Int,
        intro: TrailerTitleCard, outro: TrailerTitleCard, info: JSONObject,
    ): File = runBlocking {
        val music = args.getString("musicDur")?.toDoubleOrNull()?.let { musicIn.copy(durationSeconds = it) } ?: musicIn
        status("analyzing beats")
        val beats = AudioAnalysisService.extractBeatOnsets(target, music.file, music.durationSeconds.coerceAtLeast(1.0))
        val extractor = android.media.MediaExtractor()
        val musicFormat = try { extractor.setDataSource(music.file.absolutePath); extractor.getTrackFormat(0).toString() } finally { extractor.release() }
        val introOffset = if (intro.enabled) intro.clampedDuration() else 0.0
        val outroDur = if (outro.enabled) outro.clampedDuration() else 0.0
        val maxBudget = if (policy == TrailerMusicPolicy.FIT_MUSIC_LENGTH) music.durationSeconds * fitCount - outroDur else null
        // Experiment knobs (default off): what-if the planner used a different loop period / beat offset. Measured, never assumed.
        val shift = args.getString("beatShift")?.toDoubleOrNull() ?: 0.0
        val planBeats = beats.map { it + shift }
        val (synced, sync) = syncPhotoScenesToBeatOnsets(scenes, sources, planBeats, music.durationSeconds, introOffset, policy, maxBudget)
        val byId = sources.associateBy { it.id }
        val trailerScenes = EditorProject(synced, introCard = intro, outroCard = outro).toTrailerScenes(sources)

        var t = introOffset
        val boundaries = JSONArray()
        val sceneJson = JSONArray()
        trailerScenes.forEachIndexed { i, s ->
            // Real-source constraint record (same resolveSceneDurationBounds the planner used) so a measured length can be checked against the file's own min/max.
            val before = scenes.getOrNull(i); val after = synced.getOrNull(i)
            val bounds = after?.let { resolveSceneDurationBounds(it, byId[it.sourceId]) }
            sceneJson.put(JSONObject().put("index", i).put("source", s.source.name).put("isImage", s.source.isImage).put("start", s.start).put("length", s.length)
                .put("plannedStart", t).put("plannedEnd", t + s.length).put("beforeSyncLength", before?.length)
                .put("sourceId", s.source.id).put("sourceDuration", s.source.duration).put("beforeSyncStart", before?.start).put("afterSyncStart", after?.start)
                .put("boundsMin", bounds?.minLen).put("boundsMax", bounds?.maxLen).put("boundsSafeStart", bounds?.safeStart).put("boundsFixed", bounds?.isFixed))
            t += s.length
            boundaries.put(t)
        }
        val plan = JSONObject().put("info", info).put("mode", mode).put("policy", policy.name).put("fitCount", fitCount)
            .put("musicDuration", music.durationSeconds).put("musicName", music.name).put("musicFormat", musicFormat).put("introSeconds", introOffset).put("outroSeconds", outroDur)
            .put("beatsDetected", JSONArray(planBeats)).put("beatShiftApplied", shift).put("sync", JSONObject().put("adjusted", sync.adjustedCount).put("unaligned", sync.unalignedCount)
                .put("avgShift", sync.averageShift).put("fitResidual", sync.fitResidual)).put("scenes", sceneJson).put("plannedBoundaries", boundaries)
            .put("plannedVideoTotal", introOffset + trailerScenes.sumOf { it.length } + outroDur)
        File(outDir, "plan.json").writeText(plan.toString(2))
        status("exporting ${trailerScenes.size} scenes")

        val output = File(outDir, "render.mp4")
        val started = System.currentTimeMillis()
        var lastStatus = ""
        TrailerExporter.export(
            context = target, scenes = trailerScenes, format = TrailerFormat.WIDE, destination = output,
            musicTrack = music, musicPolicy = policy, musicFitCount = fitCount, autoDucking = true,
            aspectSources = trailerScenes.map { it.source }, musicVolume = 1f,
            introCard = intro.takeIf { it.enabled }, outroCard = outro.takeIf { it.enabled },
            cardBackgroundSources = sources,
            onSuccess = { recovered, excluded -> File(outDir, "export-result.json").writeText(JSONObject().put("recovered", recovered).put("excluded", excluded).toString()) },
        ) { text, progress -> if (text != lastStatus) { lastStatus = text; status("$text ${progress ?: ""}") } }
        File(outDir, "timing.json").writeText(JSONObject().put("exportMillis", System.currentTimeMillis() - started).put("outputBytes", output.length())
            .put("outputSha256", sha256(output)).put("sourcesUsed", byId.size).toString(2))
        status("done")
        outDir
    }
}

/**
 * Own-UUID synthetic AV proof. Never touches drafts/prefs/default beat-audit dirs: everything lives under
 * <externalFiles>/nfa-proof-<uuid> (created only if absent, nonce-bound) and MediaStore Download/nfa-proof-<uuid>/.
 * Inputs (in/seg.mp4, in/voice.wav) are pushed by the host after av-init. Exports go through the real TrailerExporter.
 */
private class AvProof(private val inst: Instrumentation, private val args: Bundle) {
    private val target = inst.targetContext
    private val mode = args.getString("mode")!!
    private val uuid = java.util.UUID.fromString(args.getString("ownUuid") ?: error("ownUuid required"))
    private val nonce = args.getString("nonce") ?: error("nonce required")
    private val base = File(target.getExternalFilesDir(null) ?: error("no external files dir"), "nfa-proof-$uuid")
    private val folder = "Download/nfa-proof-$uuid/"

    init { require(uuid.toString() == args.getString("ownUuid")) { "ownUuid must be canonical" } }

    private fun ownerOk() = File(base, ".owner").let { it.isFile && it.readText() == "$uuid:$nonce" }

    fun run(): File {
        when (mode) {
            "av-init" -> { check(!base.exists()) { "namespace exists: not ours" }; check(base.mkdirs()); File(base, ".owner").writeText("$uuid:$nonce") }
            "av-run" -> { check(ownerOk()) { "owner mismatch" }; runCases() }
            "av-seed" -> { check(ownerOk()) { "owner mismatch" }; seed() }
            "av-cleanup" -> { check(ownerOk()) { "owner mismatch" }; cleanup() }
            "av-meta-snapshot" -> { check(ownerOk()) { "owner mismatch" }; metaSnapshot() }
            "av-store-proof" -> { check(ownerOk()) { "owner mismatch" }; storeProof() }
            else -> error("unknown mode $mode")
        }
        return base
    }

    private fun metaRecordHashes(): List<String> {
        val meta = File(target.filesDir, "trailer_drafts/drafts_meta.json")
        if (!meta.isFile) return emptyList()
        val arr = JSONArray(meta.readText())
        return (0 until arr.length()).map { i -> MessageDigest.getInstance("SHA-256").digest(arr.get(i).toString().toByteArray()).joinToString("") { "%02x".format(it) }.take(16) }
    }

    // Adds ONE own project through the app's normal store API (id = fresh UUID, collision-checked); existing records are only hashed, never read out.
    private fun seed() {
        check(!File(base, "seed.json").exists()) { "already seeded" }
        TrailerDraftStore.configureTrailersRoot(target.cacheDir, target.filesDir)
        val before = metaRecordHashes() // hashed only after the normal app root is configured, before adding the own record
        val projectDir = TrailerDraftStore.newProjectDirectory(target.cacheDir)
        val id = java.util.UUID.randomUUID().toString()
        check(TrailerDraftStore.getEveryDraft().none { it.id == id })
        val sources = (0..1).map { i ->
            val f = File(projectDir, "own-$uuid-$i.mp4"); File(base, "in/seg.mp4").copyTo(f)
            TrailerSource(id = i, name = f.name, file = f, duration = 12.0, width = 640, height = 360, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = false)
        }
        val scenes = (0 until 5).map { k -> EditorScene(key = k + 1L, sourceId = k % 2, start = k * 2.0, length = 2.0, sourceVolume = 1f) }
        val title = "QA Edits ${uuid.toString().take(8)}"
        val draft = TrailerDraft(id = id, isMusicOverlayMode = false, directory = projectDir, sources = sources, total = 10.0, clipLength = 2.0, photoClipLength = 3.0,
            format = TrailerFormat.WIDE, musicTrack = null, musicPolicy = TrailerMusicPolicy.LOOP_MUSIC, musicFitCount = 1, output = null, customTitle = title,
            editor = EditorProject(scenes))
        if (!TrailerDraftStore.saveDraft(draft)) { projectDir.deleteRecursively(); error("saveDraft failed") }
        val after = metaRecordHashes()
        File(base, "seed.json").writeText(JSONObject().put("draftId", id).put("dir", projectDir.absolutePath).put("title", title)
            .put("beforeCount", before.size).put("afterCount", after.size).put("originalsPreserved", after.containsAll(before)).toString(2))
    }


    private fun shaBytes(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun canonical(v: Any?, sb: StringBuilder) {
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> { sb.append("{"); val keys = v.keys().asSequence().toList().sorted(); keys.forEachIndexed { i, k -> if (i > 0) sb.append(","); sb.append("s:").append(k.length).append(":").append(k).append("="); canonical(v.get(k), sb) }; sb.append("}") }
            is JSONArray -> { sb.append("["); for (i in 0 until v.length()) { if (i > 0) sb.append(","); canonical(v.get(i), sb) }; sb.append("]") }
            is String -> sb.append("s:").append(v.length).append(":").append(v)
            is Boolean -> sb.append("b:").append(v)
            is Number -> sb.append("n:").append(v.javaClass.simpleName).append(":").append(v.toString())
            else -> sb.append("?:").append(v.javaClass.simpleName)
        }
    }

    private fun typeOf(v: Any?) = when (v) { null, JSONObject.NULL -> "null"; is JSONObject -> "object"; is JSONArray -> "array"; is String -> "string"; is Boolean -> "boolean"; is Number -> "number"; else -> "other" }

    private fun modeOf(f: File) = android.system.Os.stat(f.absolutePath).st_mode and 511

    // Read-only snapshot of the current drafts_meta.json (exact bytes, taken under the store's own lock) into an app-private 0700 dir.
    // Never writes/renames the original metadata. No record value is logged; the receipt carries only hashes/counts/types.
    private fun metaSnapshot() {
        val storage = File(target.filesDir, "trailer_drafts")
        val metaFile = File(storage, "drafts_meta.json"); val bakFile = File(storage, "drafts_meta.json.bak"); val tmpFile = File(storage, "drafts_meta.json.tmp")
        val priv = File(target.filesDir, "nfa-meta-safety-$uuid")
        val ownerFile = File(priv, ".owner")
        if (priv.exists()) check(ownerFile.isFile && ownerFile.readText() == "$uuid:$nonce") { "private snapshot dir exists and is not ours" }
        else { check(priv.mkdirs()); android.system.Os.chmod(priv.absolutePath, 448 /*0700*/); ownerFile.writeText("$uuid:$nonce"); android.system.Os.chmod(ownerFile.absolutePath, 384 /*0600*/) }
        android.system.Os.chmod(priv.absolutePath, 448)
        val raw: ByteArray; val bak: ByteArray?; val tmpExisted: Boolean
        synchronized(TrailerDraftStore) {
            check(metaFile.isFile) { "no drafts_meta.json" }
            raw = metaFile.readBytes(); bak = bakFile.takeIf { it.isFile }?.readBytes(); tmpExisted = tmpFile.exists()
        }
        val fullSha = shaBytes(raw)
        fun keep(name: String, bytes: ByteArray): JSONObject {
            val f = File(priv, name); val want = shaBytes(bytes)
            val created = !f.exists()
            if (created) { f.writeBytes(bytes); android.system.Os.chmod(f.absolutePath, 384) }
            val have = f.readBytes()
            return JSONObject().put("file", f.absolutePath).put("created", created).put("bytes", have.size).put("sha256", shaBytes(have)).put("mode", Integer.toOctalString(modeOf(f)))
                .put("equalsCurrentRead", shaBytes(have) == want)
        }
        val baseline = keep("baseline-drafts_meta.json", raw)
        val bakInfo = bak?.let { keep("baseline-drafts_meta.json.bak", it) }
        // per-record canonical digests from the exact raw copy (values never leave this function)
        val copyBytes = File(priv, "baseline-drafts_meta.json").readBytes()
        val arr = JSONArray(String(copyBytes, Charsets.UTF_8))
        val seed = File(base, "seed.json").takeIf { it.isFile }?.let { JSONObject(it.readText()) }
        val ownId = seed?.optString("draftId")
        val records = JSONArray(); var ownIdx = -1; var ownMatches = 0
        for (i in 0 until arr.length()) {
            val v = arr.get(i); val sb = StringBuilder(); canonical(v, sb)
            val isOwn = v is JSONObject && ownId != null && v.opt("id") == ownId
            if (isOwn) { ownMatches++; ownIdx = i }
            records.put(JSONObject().put("index", i).put("type", typeOf(v)).put("canonicalSha256", shaBytes(sb.toString().toByteArray(Charsets.UTF_8))).put("isOwnSeed", isOwn))
        }
        val receipt = JSONObject().put("pid", android.os.Process.myPid()).put("currentFullSha256", fullSha).put("currentBytes", raw.size)
            .put("baseline", baseline).put("bak", bakInfo ?: JSONObject.NULL).put("bakPresent", bak != null).put("tmpPresent", tmpExisted)
            .put("privateDir", priv.absolutePath).put("privateDirMode", Integer.toOctalString(modeOf(priv)))
            .put("recordCount", arr.length()).put("records", records).put("ownMatches", ownMatches).put("ownIndex", ownIdx)
            .put("nonOwnCount", arr.length() - ownMatches).put("seedAfterCount", seed?.optInt("afterCount", -1) ?: -1)
            .put("countsAsExpected", ownMatches == 1 && seed != null && arr.length() == seed.optInt("afterCount", -2))
        File(base, "metadata-safety.json").writeText(receipt.toString(2))
        inst.sendStatus(0, Bundle().apply { putString("beat_audit", JSONObject().put("metaSnapshot", receipt.getString("currentFullSha256")).put("recordCount", arr.length()).put("ownMatches", ownMatches).toString()) })
    }

    // ------------------------------------------------------------------ av-store-proof (forward only, from the current baseline)
    // Canonical v2 differs from `canonical` ONLY in numbers: every JSON number is its BigDecimal value (stripTrailingZeros, plain string), so 1.0 / 1 / 1e0 are
    // one value after the store re-serializes a Double; Boolean/String/null stay distinct, array order and key sets (keys sorted) are kept. Nothing is discarded.

    private data class Fp(val type: String, val digest: String, val unknownSha: String)

    private val knownDraftKeys = setOf("id", "isMusicOverlayMode", "directory", "total", "clipLength", "photoClipLength", "format", "musicPolicy", "musicFitCount", "autoDucking",
        "output", "showingSettings", "orderMode", "timestamp", "customTitle", "isPrivate", "revision", "outputRevision", "musicFile", "musicName", "musicDuration", "editor", "sources",
        "voiceovers", "batchCaption")

    private var proofStep = ""

    private fun numberValue(n: Number): String = runCatching { java.math.BigDecimal(n.toString()).stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() } }
        .getOrElse { "x:" + n.javaClass.simpleName + ":" + n }

    private fun canonicalV2(v: Any?, sb: StringBuilder) {
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> { sb.append("{"); v.keys().asSequence().toList().sorted().forEachIndexed { i, k -> if (i > 0) sb.append(","); sb.append("s:").append(k.length).append(":").append(k).append("="); canonicalV2(v.get(k), sb) }; sb.append("}") }
            is JSONArray -> { sb.append("["); for (i in 0 until v.length()) { if (i > 0) sb.append(","); canonicalV2(v.get(i), sb) }; sb.append("]") }
            is String -> sb.append("s:").append(v.length).append(":").append(v)
            is Boolean -> sb.append("b:").append(v)
            is Number -> sb.append("nv:").append(numberValue(v))
            else -> sb.append("?:").append(v.javaClass.simpleName)
        }
    }

    private fun fingerprints(arr: JSONArray): List<Fp> = (0 until arr.length()).map { i ->
        val v = arr.get(i); val sb = StringBuilder(); canonicalV2(v, sb)
        val unknown = (v as? JSONObject)?.keys()?.asSequence()?.filter { it !in knownDraftKeys }?.toList()?.sorted()?.joinToString("\u0000") { "${it.length}:$it" } ?: ""
        Fp(typeOf(v), shaBytes(sb.toString().toByteArray(Charsets.UTF_8)), shaBytes(unknown.toByteArray(Charsets.UTF_8)))
    }

    private fun orderSha(fp: List<Fp>) = shaBytes(fp.joinToString(",") { it.digest }.toByteArray())

    private fun typeCounts(fp: List<Fp>) = JSONObject().also { o -> fp.groupBy { it.type }.forEach { (k, v) -> o.put(k, v.size) } }

    private fun indexOfId(arr: JSONArray, id: String) = (0 until arr.length()).filter { (arr.get(it) as? JSONObject)?.opt("id") == id }

    // Index-only reason (never a value). Compares everything except the one target slot on each side.
    private fun diffNonTarget(old: List<Fp>, now: List<Fp>, oldTarget: Int, nowTarget: Int): String? {
        val a = old.filterIndexed { i, _ -> i != oldTarget }; val b = now.filterIndexed { i, _ -> i != nowTarget }
        if (a.size != b.size) return "non-target count ${a.size}->${b.size}"
        for (i in a.indices) if (a[i] != b[i]) return "non-target #$i differs (type=${a[i].type}->${b[i].type} payload=${a[i].digest == b[i].digest} unknownKeys=${a[i].unknownSha == b[i].unknownSha})"
        return null
    }

    private fun readMeta(meta: File): Pair<ByteArray, JSONArray> {
        val raw = synchronized(TrailerDraftStore) { check(meta.isFile) { "metadata file missing" }; meta.readBytes() }
        return raw to JSONArray(String(raw, Charsets.UTF_8))
    }

    // Synthetic draft: marker first, then one copy of the own seg.mp4 (never user media) inside [dir].
    private fun synthDraft(id: String, dir: File, title: String, tag: String): TrailerDraft {
        val seg = File(base, "in/seg.mp4"); check(seg.isFile && seg.length() > 0L) { "own in/seg.mp4 missing" }
        check(dir.isDirectory && dir.listFiles().orEmpty().isEmpty()) { "synthetic project dir not fresh/empty" }
        File(dir, "own-$uuid-$tag.marker").writeText("$uuid:$nonce")
        val f = File(dir, "own-$uuid-$tag-0.mp4"); seg.copyTo(f)
        val src = TrailerSource(id = 0, name = f.name, file = f, duration = 12.0, width = 640, height = 360, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = false)
        return TrailerDraft(id = id, isMusicOverlayMode = false, directory = dir, sources = listOf(src), total = 4.0, clipLength = 2.0, photoClipLength = 3.0,
            format = TrailerFormat.WIDE, musicTrack = null, musicPolicy = TrailerMusicPolicy.LOOP_MUSIC, musicFitCount = 1, output = null, customTitle = title,
            editor = EditorProject(listOf(EditorScene(key = 1L, sourceId = 0, start = 0.0, length = 2.0, sourceVolume = 1f))))
    }

    private fun storeProof() {
        val runMillis = System.currentTimeMillis()
        val seedFile = File(base, "seed.json"); check(seedFile.isFile) { "own seed.json missing" }
        val seed = JSONObject(seedFile.readText())
        val priv = File(target.filesDir, "nfa-meta-safety-$uuid")
        check(File(priv, ".owner").let { it.isFile && it.readText() == "$uuid:$nonce" }) { "private safety dir is not ours" }
        android.system.Os.chmod(priv.absolutePath, 448)
        val receiptFile = File(base, "store-proof.json").let { if (it.exists()) File(base, "store-proof-$runMillis.json") else it }
        val real = JSONObject(); val iso = JSONObject()
        val receipt = JSONObject().put("version", 2).put("runMillis", runMillis).put("pid", android.os.Process.myPid())
            .put("canonicalAlgo", "v2: numbers=BigDecimal(stripTrailingZeros).toPlainString (0 for zero); bool/string/null distinct; array order kept; object keys sorted; sha256 per record")
            .put("realForward", real).put("isolated", iso)
            .put("historicalUnknown", JSONObject().put("tabletBeforeSeed", "UNKNOWN").put("note", "forward proof from the current baseline only; the original history receipt is unchanged"))
        var failure: String? = null
        try {
            proofStep = "real"
            val (realFp, realIds) = realForward(runMillis, priv, real, seed)
            var isoError: Throwable? = null
            proofStep = "isolated"
            try { isolatedProof(runMillis, iso) } catch (e: Throwable) { isoError = e; iso.put("failedStep", proofStep) }
            // the real roots were restored by isolatedProof's finally; the real store must still match the end of the forward proof
            synchronized(TrailerDraftStore) {
                proofStep = "real-after-isolated"
                val (_, arr) = readMeta(File(target.filesDir, "trailer_drafts/drafts_meta.json"))
                real.put("afterIsolatedUnchanged", fingerprints(arr) == realFp)
                real.put("afterIsolatedIdsUnchanged", TrailerDraftStore.getEveryDraft().map { it.id }.toSet() == realIds)
                check(real.getBoolean("afterIsolatedUnchanged") && real.getBoolean("afterIsolatedIdsUnchanged")) { "real store changed after isolated proof" }
            }
            if (isoError != null) throw isoError
        } catch (e: Throwable) { failure = if (e is IllegalStateException) e.message else e.javaClass.simpleName }
        receipt.put("pass", failure == null).put("failedStep", if (failure == null) JSONObject.NULL else proofStep).put("failure", failure ?: JSONObject.NULL)
        receiptFile.writeText(receipt.toString(2))
        inst.sendStatus(0, Bundle().apply { putString("beat_audit", JSONObject().put("storeProof", failure == null).put("step", proofStep).put("failure", failure ?: JSONObject.NULL).toString()) })
        // nothing is restored automatically: the private before copy and the receipt stay for review
        check(failure == null) { "store proof failed at $proofStep: $failure" }
    }

    // REAL store: seed noop upsert, then add and remove ONE extra temp owned record. Only Store API mutations; the real metadata is never written by hand.
    private fun realForward(runMillis: Long, priv: File, out: JSONObject, seed: JSONObject): Pair<List<Fp>, Set<String>> {
        val metaFile = File(target.filesDir, "trailer_drafts/drafts_meta.json")
        val seedId = seed.getString("draftId"); val seedDir = File(seed.getString("dir")); val seedTitle = seed.getString("title")
        check(seedTitle.startsWith("QA Edits ${uuid.toString().take(8)}")) { "seed title guard" }
        check(seedDir.isDirectory) { "seed dir missing" }
        val durableRoot = File(target.filesDir, "trailer_projects").canonicalFile

        proofStep = "1 snapshot"
        val raw = synchronized(TrailerDraftStore) { check(metaFile.isFile) { "no drafts_meta.json" }; metaFile.readBytes() }
        val beforeCopy = File(priv, "store-proof-before-$runMillis.json"); check(!beforeCopy.exists()) { "before copy exists" }
        beforeCopy.writeBytes(raw); android.system.Os.chmod(beforeCopy.absolutePath, 384)
        val copyBytes = beforeCopy.readBytes(); val fullSha = shaBytes(raw)
        check(shaBytes(copyBytes) == fullSha && modeOf(beforeCopy) == 384 && modeOf(priv) == 448) { "before copy not equal/private" }
        val beforeArr = JSONArray(String(copyBytes, Charsets.UTF_8)); val beforeFp = fingerprints(beforeArr)
        out.put("beforeFullSha256", fullSha).put("beforeBytes", raw.size).put("beforeCopyMode", Integer.toOctalString(modeOf(beforeCopy))).put("privateDirMode", Integer.toOctalString(modeOf(priv)))
            .put("beforeCount", beforeFp.size).put("beforeOrderSha256", orderSha(beforeFp)).put("beforeTypes", typeCounts(beforeFp))

        return synchronized(TrailerDraftStore) {
            proofStep = "1b configure"
            TrailerDraftStore.configureTrailersRoot(target.cacheDir, target.filesDir)
            val (cfgRaw, _) = readMeta(metaFile)
            out.put("configureKeptBytes", shaBytes(cfgRaw) == fullSha).put("metadataProblemAfterConfigure", TrailerDraftStore.metadataProblem() != null)
            check(shaBytes(cfgRaw) == fullSha) { "configure changed the metadata bytes" }

            proofStep = "2 seed noop upsert"
            val ownIdx = indexOfId(beforeArr, seedId); check(ownIdx.size == 1) { "seed record count in raw = ${ownIdx.size}" }
            val seedDraft = TrailerDraftStore.getEveryDraft().filter { it.id == seedId }.also { check(it.size == 1) { "seed not loaded once" } }.single()
            check(seedDraft.directory.canonicalPath == seedDir.canonicalPath && TrailerDraftStore.isSafePrivateTrailerDirectory(seedDir)) { "seed directory guard" }
            check(seedDraft.customTitle == seedTitle) { "seed title mismatch" }
            check(seedDraft.sources.isNotEmpty() && seedDraft.sources.all { it.file.name.startsWith("own-$uuid-") && it.file.parentFile?.canonicalPath == seedDir.canonicalPath }) { "seed media guard" }
            val idsBefore = TrailerDraftStore.getEveryDraft().map { it.id }.toSet()
            check(TrailerDraftStore.saveDraft(seedDraft)) { "seed noop upsert refused" }
            val (_, arr2) = readMeta(metaFile); val fp2 = fingerprints(arr2)
            check(fp2.size == beforeFp.size && indexOfId(arr2, seedId) == ownIdx) { "seed upsert changed count/position" }
            val d2 = diffNonTarget(beforeFp, fp2, ownIdx[0], ownIdx[0]); check(d2 == null) { "after seed upsert: $d2" }
            check(TrailerDraftStore.getEveryDraft().map { it.id }.toSet() == idsBefore && TrailerDraftStore.getEveryDraft().single { it.id == seedId }.customTitle == seedTitle) { "seed in-memory changed" }
            out.put("seedNonTargetUnchanged", true).put("seedRecordChanged", fp2[ownIdx[0]] != beforeFp[ownIdx[0]])

            proofStep = "3 temp record save"
            val tmpId = java.util.UUID.randomUUID().toString(); check(tmpId !in idsBefore) { "temp id collides" }
            val tmpDir = TrailerDraftStore.newProjectDirectory(target.cacheDir)
            val ledgerFile = File(priv, "store-proof-ledger-$runMillis.json")
            fun ledger(status: String) { ledgerFile.writeText(JSONObject().put("tmpId", tmpId).put("dir", tmpDir.absolutePath).put("status", status).toString()); android.system.Os.chmod(ledgerFile.absolutePath, 384) }
            ledger("created")
            check(tmpDir.parentFile?.canonicalFile == durableRoot && tmpDir.name != seedDir.name && TrailerDraftStore.isSafePrivateTrailerDirectory(tmpDir)) { "temp dir guard" }
            val tmpDraft = synthDraft(tmpId, tmpDir, "QA StoreProof ${uuid.toString().take(8)} $runMillis", "sp")
            ledger("saving")
            val saved = TrailerDraftStore.saveDraft(tmpDraft)
            val (_, arr3) = readMeta(metaFile); val fp3 = fingerprints(arr3)
            if (!saved) {
                val cleaned = fp3 == fp2 && TrailerDraftStore.safeDeletePrivateTrailerDirectory(tmpDir)
                ledger(if (cleaned) "saveFalseCleaned" else "saveFalseRetained")
                error("temp saveDraft returned false (raw unchanged=${fp3 == fp2}, dirCleaned=$cleaned)")
            }
            ledger("saved")
            check(fp3.size == fp2.size + 1 && indexOfId(arr3, tmpId) == listOf(fp3.lastIndex) && indexOfId(arr3, seedId) == ownIdx) { "temp record not appended once" }
            val d3 = diffNonTarget(fp2, fp3, -1, fp3.lastIndex); check(d3 == null) { "after temp save: $d3" }
            check(TrailerDraftStore.getEveryDraft().map { it.id }.toSet() == idsBefore + tmpId) { "temp in-memory ids" }
            out.put("tempSavedCount", fp3.size).put("tempSaveNonTargetUnchanged", true)

            proofStep = "3 temp record remove"
            val removed = TrailerDraftStore.removeDraft(tmpDraft)
            val (_, arr4) = readMeta(metaFile); val fp4 = fingerprints(arr4)
            if (!removed) { ledger("removeFalseRetained"); error("temp removeDraft returned false (raw unchanged=${fp4 == fp3}); temp retained, see private ledger") }
            check(fp4 == fp2) { "after temp remove the metadata is not exactly the post-seed state" }
            if (tmpDir.exists()) TrailerDraftStore.safeDeletePrivateTrailerDirectory(tmpDir)
            check(!tmpDir.exists()) { "temp dir still exists" }
            ledger("removed")
            check(TrailerDraftStore.getEveryDraft().map { it.id }.toSet() == idsBefore && seedDir.isDirectory && seedDraft.sources.all { it.file.length() > 0L }) { "originals/seed not intact after temp remove" }
            out.put("tempRemovedRestoredExact", true).put("tempDirCleaned", true)

            proofStep = "4 after copy"
            val (endRaw, endArr) = readMeta(metaFile)
            val afterCopy = File(priv, "store-proof-after-$runMillis.json"); check(!afterCopy.exists()) { "after copy exists" }
            afterCopy.writeBytes(endRaw); android.system.Os.chmod(afterCopy.absolutePath, 384)
            check(shaBytes(afterCopy.readBytes()) == shaBytes(endRaw) && modeOf(afterCopy) == 384) { "after copy verify" }
            val endFp = fingerprints(endArr)
            out.put("afterFullSha256", shaBytes(endRaw)).put("afterCount", endFp.size).put("afterOrderSha256", orderSha(endFp)).put("afterTypes", typeCounts(endFp))
                .put("afterEqualsBeforeFullBytes", shaBytes(endRaw) == fullSha).put("realForwardPass", true)
            endFp to idsBefore
        }
    }

    // ISOLATED store under own base/isolated-store-proof-<runMillis>/{cache,files}; the real roots are always re-configured in finally.
    private fun isolatedProof(runMillis: Long, out: JSONObject) {
        val root = File(base, "isolated-store-proof-$runMillis"); check(!root.exists() && root.mkdirs()) { "isolated root exists/uncreatable" }
        val cache = File(root, "cache").apply { mkdirs() }; val files = File(root, "files").apply { mkdirs() }
        val storage = File(files, "trailer_drafts"); val meta = File(storage, "drafts_meta.json"); val tmp = File(storage, "drafts_meta.json.tmp")
        val rootPrefix = root.canonicalPath + File.separator
        fun fresh(): File = TrailerDraftStore.newProjectDirectory(cache).also { check(it.canonicalPath.startsWith(rootPrefix) && TrailerDraftStore.isSafePrivateTrailerDirectory(it)) { "isolated project dir escaped" } }
        fun ids() = TrailerDraftStore.getEveryDraft().map { it.id }.toSet()
        fun cur() = readMeta(meta).second.let { it to fingerprints(it) }
        synchronized(TrailerDraftStore) {
            try {
                proofStep = "iso setup"
                TrailerDraftStore.configureTrailersRoot(cache, files)
                val idA = java.util.UUID.randomUUID().toString(); val idT = java.util.UUID.randomUUID().toString()
                val dirA = fresh(); val dirT = fresh()
                check(TrailerDraftStore.saveDraft(synthDraft(idA, dirA, "iso-A", "a")) && TrailerDraftStore.saveDraft(synthDraft(idT, dirT, "iso-T", "t"))) { "isolated seed saves refused" }
                val made = readMeta(meta).second; check(made.length() == 2) { "isolated seed count" }
                val aRaw = made.getJSONObject(0); val tRaw = made.getJSONObject(1)
                val unknown = JSONObject().put("futureKey", JSONObject().put("nested", JSONArray().put(1).put(2.0).put(JSONArray().put(true).put(JSONObject.NULL)).put(JSONObject().put("x", "y"))))
                    .put("num", 1.0).put("big", 12345678901L).put("flag", false).put("nil", JSONObject.NULL)
                val dupA = JSONObject(aRaw.toString()).put("customTitle", "iso-A duplicate revision")
                val setup = JSONArray().put(unknown).put("plain-string").put(JSONObject.NULL).put(7).put(aRaw).put(dupA).put(JSONObject().put("id", "iso-unparseable").put("futureOnly", JSONArray().put(JSONArray())))
                    .put(tRaw).put(JSONArray().put(JSONObject().put("tail", 1.50)))
                meta.writeText(setup.toString(2))
                val preLoad = fingerprints(readMeta(meta).second)
                TrailerDraftStore.configureTrailersRoot(cache, files) // reload from disk: duplicate/unparsed records go to the raw-preserved list
                val (baseArr, baseFp) = cur()
                check(preLoad == baseFp) { "isolated load changed the fixture (preLoad != baseline)" } // exact: unknown/null/duplicate must survive load unchanged
                check(ids() == setOf(idA, idT)) { "isolated load ids" }
                out.put("loadChangedFile", preLoad != baseFp).put("baselineCount", baseFp.size).put("baselineTypes", typeCounts(baseFp)).put("baselineOrderSha256", orderSha(baseFp))
                    .put("metadataProblemAfterLoad", TrailerDraftStore.metadataProblem() != null)
                val tIdx = indexOfId(baseArr, idT); check(tIdx.size == 1 && indexOfId(baseArr, idA).size == 2) { "isolated baseline ids" }

                proofStep = "iso upsert"
                val tNow = TrailerDraftStore.getEveryDraft().single { it.id == idT }
                check(TrailerDraftStore.saveDraft(tNow.copy(customTitle = "iso-T changed"))) { "isolated upsert refused" }
                val (a1, fp1) = cur()
                check(fp1.size == baseFp.size && indexOfId(a1, idT) == tIdx && fp1[tIdx[0]] != baseFp[tIdx[0]]) { "isolated upsert position/target" }
                diffNonTarget(baseFp, fp1, tIdx[0], tIdx[0]).let { check(it == null) { "iso upsert: $it" } }
                out.put("upsertNonTargetRawUnchanged", true)

                proofStep = "iso add/remove"
                val idN = java.util.UUID.randomUUID().toString(); val dirN = fresh()
                val nDraft = synthDraft(idN, dirN, "iso-N", "n")
                check(TrailerDraftStore.saveDraft(nDraft)) { "isolated add refused" }
                val (_, fp2) = cur()
                check(fp2.size == fp1.size + 1) { "isolated add count" }
                diffNonTarget(fp1, fp2, -1, fp2.lastIndex).let { check(it == null) { "iso add: $it" } }
                check(TrailerDraftStore.removeDraft(nDraft)) { "isolated remove refused" }
                check(cur().second == fp1 && !dirN.exists()) { "iso add/remove not exactly restored" }
                out.put("addRemoveExact", true)

                proofStep = "iso write failure"
                val shaBeforeFail = shaBytes(meta.readBytes())
                check(!tmp.exists() && tmp.mkdir()) { "tmp collision could not be set up" } // own .tmp directory => FileOutputStream(temp) throws => persist false
                val saveFalse: Boolean; val removeFalse: Boolean
                try {
                    val tCur = TrailerDraftStore.getEveryDraft().single { it.id == idT }
                    saveFalse = TrailerDraftStore.saveDraft(tCur.copy(customTitle = "must not persist"))
                    removeFalse = TrailerDraftStore.removeDraft(tCur)
                    check(!saveFalse && !removeFalse) { "write failure not reported (save=$saveFalse remove=$removeFalse)" }
                    check(shaBytes(meta.readBytes()) == shaBeforeFail) { "meta bytes changed despite failed persist" }
                    check(ids() == setOf(idA, idT) && TrailerDraftStore.getEveryDraft().single { it.id == idT }.customTitle == "iso-T changed") { "typed state not rolled back" }
                    check(dirT.isDirectory && tCur.sources.all { it.file.length() > 0L }) { "project files lost on failed remove" }
                } finally { tmp.delete() }
                check(!tmp.exists()) { "tmp collision not cleaned" }
                check(cur().second == fp1) { "raw changed after failure injection" }
                out.put("failedSaveReturnedFalse", true).put("failedRemoveReturnedFalse", true).put("failurePriorShaUnchanged", true).put("collisionCleaned", true)

                proofStep = "iso delete target"
                check(TrailerDraftStore.removeDraft(TrailerDraftStore.getEveryDraft().single { it.id == idT })) { "isolated delete refused" }
                val (a5, fp5) = cur()
                check(fp5.size == baseFp.size - 1 && indexOfId(a5, idT).isEmpty() && indexOfId(a5, idA).size == 2) { "iso delete shape" }
                diffNonTarget(baseFp, fp5, tIdx[0], -1).let { check(it == null) { "iso delete: $it" } }
                check(!dirT.exists() && dirA.isDirectory && ids() == setOf(idA)) { "iso delete touched more than the target" }
                out.put("deleteOnlyTarget", true).put("finalCount", fp5.size)
            } finally {
                runCatching { if (tmp.isDirectory) tmp.delete() }
                TrailerDraftStore.configureTrailersRoot(target.cacheDir, target.filesDir) // always back on the real roots
            }
        }
        out.put("isolatedPass", true)
        root.deleteRecursively() // own isolated fixture only (kept when any step failed, for review)
    }

    // ---- cleanup: the whole plan (private + public) is built read-only first; only proved targets are then removed.

    private class PrivatePlan(val seedDir: File, val draft: TrailerDraft?, val draftId: String, val ownIdx: List<Int>, val beforeFp: List<Fp>, val keep: List<String>)

    private class PublicPlan(val recorded: Int, val delete: List<String>, val gone: List<String>, val retained: List<String>, val unverifiable: List<String>, val unrecorded: Int)

    private val uiLedgerTags = listOf("phone-portrait", "tablet-portrait", "tablet-landscape")
    private val hex64 = Regex("[0-9a-f]{64}")

    private fun jsonStr(o: JSONObject?, key: String): String? = o?.optString(key)?.takeIf { it.isNotEmpty() && it != "null" }

    private fun isLink(f: File) = java.nio.file.Files.isSymbolicLink(f.toPath())

    private fun shaOf(i: java.io.InputStream): String =
        MessageDigest.getInstance("SHA-256").let { d -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) }; d.digest().joinToString("") { "%02x".format(it) } }

    // Existing UI export ledgers (PhoneSmoke.exportOwn) under the own base only; accepted when projectId and canonical dir are the seed's.
    private fun uiLedgers(draftId: String, seedDir: File): List<JSONObject> = uiLedgerTags.mapNotNull { tag ->
        val lf = File(base, "ui/$tag/export-ledger.json")
        runCatching { if (!lf.isFile || isLink(lf)) null else JSONObject(lf.readText()) }.getOrNull()
            ?.takeIf { it.optString("projectId") == draftId && jsonStr(it, "dir")?.let { d -> File(d).canonicalFile == seedDir } == true }
    }

    // Read-only. Creation proof = own draft record + seed sources whose bytes equal base/in/seg.mp4 + outputs recorded by the UI export ledger (exact path + recorded sha).
    // There is no project marker for the real seed and none is created. Anything else inside the seed dir (unknown file, subdir, symlink, changed bytes) keeps the project.
    private fun privatePlan(): PrivatePlan? {
        val seedFile = File(base, "seed.json").takeIf { it.isFile } ?: return null
        val j = JSONObject(seedFile.readText())
        val draftId = j.getString("draftId")
        val seedRaw = File(j.getString("dir")); val seedDir = seedRaw.canonicalFile
        val beforeArr = readMeta(File(target.filesDir, "trailer_drafts/drafts_meta.json")).second
        val keep = ArrayList<String>()
        val ownIdx = indexOfId(beforeArr, draftId)
        fun plan(d: TrailerDraft?) = PrivatePlan(seedDir, d, draftId, ownIdx, fingerprints(beforeArr), keep)
        if (ownIdx.size > 1) keep += "duplicate own draft id in metadata"
        if (seedDir.parentFile != File(target.filesDir, "trailer_projects").canonicalFile) keep += "seed dir is not a direct child of trailer_projects"
        if (keep.isNotEmpty()) return plan(null)
        val d = TrailerDraftStore.getEveryDraft().singleOrNull { it.id == draftId }
        if (d == null) {
            // no exact draft record: idempotent only when the seed dir is already gone and no unloadable own record remains
            if (seedDir.exists()) keep += "seed dir exists without its own loaded draft record"
            if (ownIdx.isNotEmpty()) keep += "own metadata record is not loadable"
            return plan(null)
        }
        if (isLink(seedRaw) || !seedDir.isDirectory) keep += "seed dir is missing or a symlink while its draft record exists"
        if (d.directory.canonicalFile != seedDir) keep += "draft directory is not the seed dir"
        if (!d.customTitle.orEmpty().startsWith("QA Edits ${uuid.toString().take(8)}")) keep += "draft title is not the own title"
        val seg = File(base, "in/seg.mp4")
        if (!seg.isFile) keep += "creation reference in/seg.mp4 is missing"
        if (d.sources.isEmpty()) keep += "draft has no sources"
        if (keep.isNotEmpty()) return plan(d)
        val segSha = sha(seg)
        val allowed = HashMap<String, Set<String>>() // canonical path -> creation shas it may have
        for (s in d.sources) {
            val c = s.file.canonicalFile
            if (c.parentFile == seedDir && c.name.startsWith("own-$uuid-")) allowed[c.path] = setOf(segSha) else keep += "a draft source is not own synthetic media inside the seed dir"
        }
        for (l in uiLedgers(draftId, seedDir)) for (o in listOf(l.optJSONObject("before"), l.optJSONObject("after"), l)) {
            val p = jsonStr(o, "file")?.takeIf { File(it).isAbsolute } ?: continue
            val h = jsonStr(o, "sha256")?.takeIf { hex64.matches(it) } ?: continue
            val c = File(p).canonicalFile
            if (c.parentFile == seedDir) allowed[c.path] = (allowed[c.path] ?: emptySet()) + h
        }
        val entries = java.nio.file.Files.walk(seedDir.toPath(), 8).use { s -> s.iterator().asSequence().take(5001).toList() }
        if (entries.size > 5000) keep += "seed dir has too many entries"
        var links = 0; var unknown = 0
        for (p in entries) {
            val f = p.toFile()
            if (p == seedDir.toPath()) continue
            if (isLink(f) || !f.canonicalPath.startsWith(seedDir.path + File.separator)) { links++; continue }
            if (!(f.isFile && allowed[f.canonicalPath]?.let { sha(f) in it } == true)) unknown++
        }
        if (links > 0) keep += "$links symlink/escaping entr${if (links == 1) "y" else "ies"} in the seed dir"
        if (unknown > 0) keep += "$unknown unreferenced, changed or non-file entr${if (unknown == 1) "y" else "ies"} in the seed dir"
        return plan(d)
    }

    // Read-only. A MediaStore row is a delete target only when its exact URI is recorded with a creation sha, it is a row of the own folder+prefix, and its bytes still hash to that sha.
    private fun publicPlan(draftId: String?, seedDir: File?): PublicPlan {
        val downloads = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val prefix = "nfa-$uuid-"
        val creation = LinkedHashMap<String, String>(); val conflicts = LinkedHashSet<String>()
        fun record(uri: String?, sha: String?, publicSha: String? = sha) {
            if (uri == null || !uri.startsWith(downloads.toString() + "/")) return
            if (sha == null || !hex64.matches(sha) || publicSha != sha) { creation.putIfAbsent(uri, ""); conflicts += uri; return }
            val prev = creation.putIfAbsent(uri, sha); if (prev != null && prev != sha) conflicts += uri
        }
        fun entries(arr: JSONArray?) { for (i in 0 until (arr?.length() ?: 0)) arr!!.optJSONObject(i)?.let { e -> record(jsonStr(e, "uri"), jsonStr(e, "privateSha"), jsonStr(e, "publicSha")) } }
        File(base, "receipt.json").takeIf { it.isFile }?.let { entries(JSONObject(it.readText()).optJSONArray("public")) }
        File(base, "result.json").takeIf { it.isFile }?.let { r ->
            val cases = JSONObject(r.readText()).optJSONArray("cases")
            for (i in 0 until (cases?.length() ?: 0)) cases!!.optJSONObject(i)?.optJSONObject("public")?.let { e -> record(jsonStr(e, "uri"), jsonStr(e, "privateSha"), jsonStr(e, "publicSha")) }
        }
        if (draftId != null && seedDir != null) for (l in uiLedgers(draftId, seedDir)) for (k in listOf("contentUri", "publicUri", "uri")) {
            jsonStr(l, k)?.takeIf { it.startsWith("content://") }?.let { record(it, jsonStr(l, "sha256")) }
        }
        val delete = ArrayList<String>(); val gone = ArrayList<String>(); val retained = ArrayList<String>(); val unverifiable = ArrayList<String>()
        val proj = arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
        for ((u, want) in creation) {
            if (u in conflicts) { retained += u; continue }
            val uri = android.net.Uri.parse(u)
            try {
                val own = target.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) null else c.getString(0) == folder && (c.getString(1) ?: "").startsWith(prefix)
                }
                if (own == null) { gone += u; continue }
                if (!own) { retained += u; continue }
                val have = target.contentResolver.openInputStream(uri)?.use { shaOf(it) }
                if (have == null) unverifiable += u else if (have == want) delete += u else retained += u
            } catch (e: Throwable) { unverifiable += u + " " + e.javaClass.simpleName }
        }
        // own folder+prefix rows that no ledger records: never deleted on name alone, reported as pending
        var unrecorded = 0
        target.contentResolver.query(downloads, arrayOf(android.provider.MediaStore.MediaColumns._ID),
            "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(folder, "$prefix%"), null)?.use { c ->
            while (c.moveToNext()) if (android.content.ContentUris.withAppendedId(downloads, c.getLong(0)).toString() !in creation) unrecorded++
        }
        return PublicPlan(creation.size, delete, gone, retained, unverifiable, unrecorded)
    }

    private fun cleanup() {
        // av-cleanup is a fresh instrumentation without MainActivity: configure the normal app roots first (same call as seed()); it does not rewrite real metadata.
        TrailerDraftStore.configureTrailersRoot(target.cacheDir, target.filesDir)
        // 1) plans, read-only (nothing is removed until both exist)
        val priv = privatePlan()
        val pub = publicPlan(priv?.draftId, priv?.seedDir)
        // 2) public: only the proved targets
        var deleted = 0; val failed = JSONArray(); val goneNow = ArrayList<String>()
        for (u in pub.delete) {
            try { if (target.contentResolver.delete(android.net.Uri.parse(u), null, null) > 0) deleted++ else goneNow += u }
            catch (e: Throwable) { failed.put(u + " " + e.javaClass.simpleName + ":" + e.message?.take(120)) }
        }
        inst.sendStatus(0, Bundle().apply {
            putString("beat_audit", JSONObject().put("cleanup", uuid.toString()).put("recorded", pub.recorded).put("deleted", deleted).put("gone", pub.gone.size + goneNow.size)
                .put("retainedMismatched", JSONArray(pub.retained)).put("unverifiable", JSONArray(pub.unverifiable)).put("unrecordedOwnFolderRows", pub.unrecorded)
                .put("failed", failed).toString())
        })
        // 3) private: removeDraft + safe delete only after the whole private plan passed; the plan keeps the project on any unknown entry
        var nonOwnUnchanged: Boolean? = null; var remainingRecords = -1; var projectRemoved = true
        if (priv != null) {
            val metaFile = File(target.filesDir, "trailer_drafts/drafts_meta.json")
            if (priv.keep.isEmpty() && priv.draft != null) {
                check(TrailerDraftStore.removeDraft(priv.draft)) { "cleanup: normal removeDraft failed; namespace and ledger kept" }
                if (priv.seedDir.exists()) TrailerDraftStore.safeDeletePrivateTrailerDirectory(priv.seedDir)
                check(!priv.seedDir.exists()) { "cleanup: normal safe delete left the seed dir; namespace and ledger kept" }
            }
            projectRemoved = priv.keep.isEmpty() && !priv.seedDir.exists()
            val afterArr = readMeta(metaFile).second
            val afterFp = fingerprints(afterArr)
            if (priv.keep.isEmpty()) check(indexOfId(afterArr, priv.draftId).isEmpty()) { "cleanup: own draft record still present; namespace and ledger kept" }
            diffNonTarget(priv.beforeFp, afterFp, if (priv.keep.isEmpty()) priv.ownIdx.firstOrNull() ?: -1 else -1, -1).let { check(it == null || priv.keep.isNotEmpty() && afterFp == priv.beforeFp) { "cleanup: non-own metadata changed ($it); namespace and ledger kept" } }
            nonOwnUnchanged = true; remainingRecords = afterFp.size
        }
        // 4) the namespace/ledger goes only when nothing is left pending
        val pending = JSONObject().put("publicRetainedMismatched", pub.retained.size).put("publicUnverifiable", pub.unverifiable.size).put("publicDeleteFailed", failed.length())
            .put("publicUnrecordedOwnFolderRows", pub.unrecorded).put("privateKeepReasons", JSONArray(priv?.keep.orEmpty()))
        val anyPending = pub.retained.isNotEmpty() || pub.unverifiable.isNotEmpty() || failed.length() > 0 || pub.unrecorded > 0 || priv?.keep.orEmpty().isNotEmpty()
        var namespaceRemoved = false
        if (!anyPending) {
            check(base.deleteRecursively()) { "cleanup: namespace delete failed" }
            check(!base.exists()) { "cleanup: namespace still exists" }
            namespaceRemoved = true
        }
        inst.sendStatus(0, Bundle().apply {
            putString("beat_audit", JSONObject().put("cleanupFinal", uuid.toString()).put("nonOwnUnchanged", nonOwnUnchanged ?: JSONObject.NULL)
                .put("remainingRecordCount", remainingRecords).put("projectRemoved", projectRemoved).put("publicDeleted", deleted).put("publicGone", pub.gone.size + goneNow.size)
                .put("pending", if (anyPending) pending else JSONObject.NULL).put("namespaceRemoved", namespaceRemoved).toString())
        })
        check(!anyPending) { "cleanup incomplete (public deleted=$deleted gone=${pub.gone.size + goneNow.size} retained=${pub.retained.size} unverifiable=${pub.unverifiable.size} failed=${failed.length()} unrecorded=${pub.unrecorded}; private kept=${priv?.keep.orEmpty().size}); namespace and ledger kept: $pending" }
    }

    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").let { d -> f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; d.update(b, 0, n) } }; d.digest().joinToString("") { "%02x".format(it) } }

    private fun durMs(f: File): Long = MediaMetadataRetriever().let { r -> try { r.setDataSource(f.absolutePath); r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: -1 } finally { r.release() } }

    private fun publish(f: File, name: String): JSONObject {
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name); put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, folder); put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("insert failed")
        target.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
        target.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val copy = File(base, "readback-$name")
        target.contentResolver.openInputStream(uri)!!.use { i -> copy.outputStream().use { i.copyTo(it) } }
        val sha = sha(copy); copy.delete()
        return JSONObject().put("uri", uri.toString()).put("name", name).put("privateSha", sha(f)).put("publicSha", sha).put("bytes", f.length())
    }

    private fun source(file: File, dur: Double, id: Int = 0) =
        TrailerSource(id = id, name = file.name, file = file, duration = dur, width = 640, height = 360, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = false)

    private fun cue(s: Double, e: Double, text: String, col: Int, row: Int, oldText: String = text) =
        TrailerTimedCaptionCue(sourceId = 0, sourceStart = s, sourceEnd = e, text = text, style = SceneCaption(text = oldText, column = col, row = row))

    private fun export(scenes: List<TrailerScene>, dest: File, voices: List<TrailerVoiceoverTake> = emptyList(), direct: Boolean = false): File = runBlocking {
        TrailerExporter.export(context = target, scenes = scenes, format = TrailerFormat.WIDE, destination = dest, autoDucking = true,
            voiceovers = voices, projectDirectory = File(base, "in"), preferDirectVideoInputs = direct) { _, _ -> }
    }

    private fun runCases() {
        val seg = source(File(base, "in/seg.mp4"), 12.0)
        val wanted = (args.getString("cases") ?: "a,b1,b2,c1,c2,d1,d2").split(",")
        val out = File(base, "out").apply { mkdirs() }
        // ledger accumulates across invocations: earlier results/public URIs are never dropped (cleanup needs every recorded URI)
        val results = File(base, "result.json").takeIf { it.isFile }?.let { JSONObject(it.readText()).optJSONArray("cases") } ?: JSONArray()
        val pub = File(base, "receipt.json").takeIf { it.isFile }?.let { JSONObject(it.readText()).optJSONArray("public") } ?: JSONArray()
        fun scene(key: Long, start: Double, len: Double, adj: TrailerClipAdjustments?, caption: SceneCaption? = null) =
            TrailerScene(source = seg, start = start, length = len, sourceVolume = 1f, caption = caption, adjustments = adj, sceneKey = key)
        val cases = linkedMapOf<String, Pair<JSONObject, () -> List<TrailerScene>>>()
        fun case(id: String, j: JSONObject, f: () -> List<TrailerScene>) { cases[id] = j to f }
        run {
            case("a", JSONObject().put("input", "src 0..8 rate2; static caption row4; cue 2..3 text NEWTEXT (style OLD) col2 row0; cue2 same time col0 row2; kf src2.5 opacity .5 (0,8 =1)")
                .put("expectOutputSec", 4.0).put("expectCueOut", JSONArray(listOf(1.0, 1.5))).put("expectKfOut", 1.25)) {
                listOf(scene(1, 0.0, 8.0, TrailerClipAdjustments(rate = 2.0, cues = listOf(cue(2.0, 3.0, "NEWTEXT", 2, 0, "OLD"), cue(2.0, 3.0, "SECOND", 0, 2)),
                    keyframes = listOf(TrailerTransformKeyframe(sourceTime = 0.0), TrailerTransformKeyframe(sourceTime = 2.5, opacity = 0.5), TrailerTransformKeyframe(sourceTime = 8.0))),
                    SceneCaption(text = "STATIC", column = 2, row = 4)))
            }
            case("b1", JSONObject().put("input", "src 2..4 rate .25").put("expectOutputSec", 8.0)) { listOf(scene(1, 2.0, 2.0, TrailerClipAdjustments(rate = 0.25))) }
            case("b2", JSONObject().put("input", "src 1/120 rate .25 (one output frame)").put("expectOutputSec", 1.0 / 30)) { listOf(scene(1, 2.0, 1.0 / 120, TrailerClipAdjustments(rate = 0.25))) }
            // Preview path: the same direct-original branch ProjectPreviewEngine uses (directVideoInputs=true), then the real final Composition.
            case("p2", JSONObject().put("input", "src 1/120 rate .25 via DIRECT original branch (preview path)").put("expectOutputSec", 1.0 / 30)) { listOf(scene(1, 2.0, 1.0 / 120, TrailerClipAdjustments(rate = 0.25))) }
            case("c1", JSONObject().put("input", "2 clips src 0..3 dissolve1, 6..9").put("expectOutputSec", 5.0)) {
                listOf(scene(1, 0.0, 3.0, TrailerClipAdjustments(transition = TrailerTransition(1.0))), scene(2, 6.0, 3.0, null))
            }
            case("c2", JSONObject().put("input", "2 clips 0.5s each dissolve 1.0 (half clamp: overlap .25)").put("expectOutputSec", 0.75)) {
                listOf(scene(1, 0.0, 0.5, TrailerClipAdjustments(transition = TrailerTransition(1.0))), scene(2, 6.0, 0.5, null))
            }
            case("c3", JSONObject().put("input", "2 clips 1.0s each dissolve 2.0 (half clamp: overlap .5)").put("expectOutputSec", 1.5)) {
                listOf(scene(1, 0.0, 1.0, TrailerClipAdjustments(transition = TrailerTransition(2.0))), scene(2, 6.0, 1.0, null))
            }
            case("c4", JSONObject().put("input", "single 0.5s clip, no dissolve").put("expectOutputSec", 0.5)) { listOf(scene(1, 0.0, 0.5, null)) }
        }
        for (id in wanted) {
            val entry = cases[id]
            val res = JSONObject().put("case", id).put("runAtMillis", System.currentTimeMillis())
                .put("pid", android.os.Process.myPid()).put("procStartElapsedMs", android.os.Process.getStartElapsedRealtime()).put("nowElapsedMs", android.os.SystemClock.elapsedRealtime())
            try {
                if (entry != null) {
                    res.put("expected", entry.first)
                    val f = File(out, "nfa-$uuid-$id.mp4"); f.delete()
                    val t0 = System.currentTimeMillis()
                    // failure forensics (own namespace only): keep a copy of each own intermediate chunk the exporter writes into out/ before its cleanup deletes it
                    val diag = File(base, "diag").apply { mkdirs() }
                    val watching = java.util.concurrent.atomic.AtomicBoolean(true)
                    // stage copies are named <case>-<runAtMillis>-<stage> so the analysis only reads this run's files
                    val stagePrefix = "$id-${res.getLong("runAtMillis")}-"
                    // fds are held (never blocking) for every own chunk and exact-audio WAV; the exporter may unlink them before the export call returns
                    val held = java.util.concurrent.ConcurrentHashMap<String, java.io.FileInputStream>()
                    val watcher = Thread {
                        while (watching.get()) {
                            out.listFiles { x -> (x.name.startsWith("chunk-") || x.name.startsWith("exact-audio-")) && !held.containsKey(x.name) }.orEmpty().forEach { c ->
                                runCatching { java.io.FileInputStream(c) }.getOrNull()?.let { held[c.name] = it }
                            }
                            Thread.sleep(15)
                        }
                    }.apply { isDaemon = true; start() }
                    TrailerExporter.beginStageDiagnostics(File(diag, stagePrefix).absolutePath)
                    try {
                        if (id == "p2") {
                            // The exact WAV must exist while the prepared composition is held and be removed on its release.
                            val built = runBlocking { TrailerExporter.prepareCompositionForPreview(context = target, scenes = entry.second(), format = TrailerFormat.WIDE, destinationParent = out, directVideoInputs = true) }
                            val wavs = built.ownedTempFiles.filter { it.name.startsWith("exact-audio-") }
                            res.put("p2WavCount", wavs.size).put("p2WavBytesWhileHeld", JSONArray(wavs.map { if (it.isFile) it.length() else -1L }))
                            built.releaseOwnedTempFiles()
                            res.put("p2WavDeletedAfterRelease", wavs.isNotEmpty() && wavs.none { it.exists() })
                        }
                        export(entry.second(), f, direct = id == "p2")
                    } finally {
                        watching.set(false); watcher.join(5000); TrailerExporter.beginStageDiagnostics(null)
                        // copy the final bytes (moov is written last) while the fd still reads the unlinked file
                        held.forEach { (name, ins) -> ins.use { i -> runCatching { File(diag, "$stagePrefix$name").outputStream().use { o -> i.channel.position(0); i.copyTo(o) } } } }
                    }
                    res.put("exportMillis", System.currentTimeMillis() - t0).put("durationMs", durMs(f)).put("sha", sha(f))
                    pub.put(publish(f, f.name).also { res.put("public", it) })
                } else if (id == "d1" || id == "d2") {
                    // good committed file first; failing candidate must never replace it
                    val committed = File(out, "nfa-$uuid-$id-committed.mp4"); committed.delete()
                    export(listOf(scene(1, 0.0, 2.0, null)), committed)
                    val before = sha(committed)
                    val voiceFile = File(base, "in/voice.wav")
                    val cov = voiceFile.length() // wav bytes only for host cross-check
                    val takes = if (id == "d1") listOf(TrailerVoiceoverTake(relativeFile = "voice.wav", outputStart = 0.0, sourceIn = 0.0, sourceOut = args.getString("voiceOver")?.toDoubleOrNull() ?: 60.0))
                    else listOf(TrailerVoiceoverTake(relativeFile = "voice.wav", outputStart = 0.0, sourceIn = 0.0, sourceOut = 1.5), TrailerVoiceoverTake(relativeFile = "voice.wav", outputStart = 1.0, sourceIn = 0.0, sourceOut = 1.5))
                    val cand = File(out, "nfa-$uuid-$id-candidate.mp4"); cand.delete()
                    var failure: String? = null
                    try { export(listOf(scene(1, 0.0, 2.0, null)), cand, takes) } catch (e: Throwable) { failure = e.javaClass.name + ": " + e.message?.take(200) }
                    res.put("voiceWavBytes", cov).put("failure", failure).put("candidateExists", cand.isFile)
                    if (failure == null && cand.isFile) { res.put("candidateDurationMs", durMs(cand)); pub.put(publish(cand, cand.name).also { res.put("public", it) }) }
                    res.put("committedShaBefore", before).put("committedShaAfter", sha(committed)).put("committedUnchanged", before == sha(committed))
                }
            } catch (e: Throwable) { res.put("error", generateSequence<Throwable>(e) { it.cause }.joinToString(" <- ") { it.javaClass.simpleName + ":" + it.message?.take(900) })
                // cause chain + own-frame stack (media3/mp4 parse frames included) so the failing library location can be narrowed
                res.put("errorStack", JSONArray(generateSequence<Throwable>(e) { it.cause }.take(6).map { t ->
                    JSONObject().put("type", t.javaClass.name).put("msg", t.message?.take(300))
                        .put("frames", JSONArray(t.stackTrace.filter { f -> f.className.startsWith("androidx.media3") || f.className.startsWith("com.armsone") || f.className.startsWith("android.media") }.take(14).map { "${it.className}.${it.methodName}:${it.lineNumber}" }))
                }.toList())) }
            results.put(res)
            File(base, "result.json").writeText(JSONObject().put("uuid", uuid.toString()).put("cases", results).toString(2))
            File(base, "receipt.json").writeText(JSONObject().put("uuid", uuid.toString()).put("public", pub).toString(2))
        }
    }
}
