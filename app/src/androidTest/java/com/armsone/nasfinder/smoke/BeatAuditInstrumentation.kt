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
import com.armsone.nasfinder.ui.TrailerExporter
import com.armsone.nasfinder.ui.TrailerFormat
import com.armsone.nasfinder.ui.TrailerMusicPolicy
import com.armsone.nasfinder.ui.TrailerMusicTrack
import com.armsone.nasfinder.ui.TrailerScene
import com.armsone.nasfinder.ui.TrailerSource
import com.armsone.nasfinder.ui.TrailerTitleCard
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
            val out = BeatAudit(this, args).run()
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
