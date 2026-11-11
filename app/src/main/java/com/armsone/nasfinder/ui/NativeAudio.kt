package com.armsone.nasfinder.ui

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

/** Local microphone recorder: one take at a time into an app-private file; cancel removes the partial file. */
internal class VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L
    val recording: Boolean get() = recorder != null

    @Suppress("DEPRECATION")
    fun start(context: Context, file: File): Boolean {
        if (recorder != null) return false
        file.parentFile?.mkdirs()
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            target = file
            startedAt = android.os.SystemClock.elapsedRealtime()
            true
        } catch (_: Throwable) {
            runCatching { r.release() }
            file.delete()
            false
        }
    }

    /** Stops and returns the saved take, or null (and no file) when nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        val file = target
        recorder = null
        target = null
        val ok = runCatching { r.stop() }.isSuccess && android.os.SystemClock.elapsedRealtime() - startedAt >= 300L
        runCatching { r.release() }
        if (!ok || file == null || file.length() <= 0L) { file?.delete(); return null }
        return file
    }

    fun cancel() {
        val r = recorder ?: return
        val file = target
        recorder = null
        target = null
        runCatching { r.stop() }
        runCatching { r.release() }
        file?.delete()
    }
}

internal enum class SpeechState { UNSUPPORTED_DEVICE, SERVICE_UNAVAILABLE, FILE_INPUT_UNSUPPORTED, MODEL_MISSING, LANGUAGE_UNSUPPORTED, TRANSIENT, READY }

internal data class SpeechAvailability(val state: SpeechState, val languageTag: String) {
    val message: String
        get() = when (state) {
            SpeechState.UNSUPPORTED_DEVICE -> "이 기기(Android 12 미만)는 온디바이스 음성 인식을 지원하지 않습니다. 자막은 직접 입력하거나 시간 지정 자막으로 만들 수 있어요."
            SpeechState.SERVICE_UNAVAILABLE -> "이 기기에 온디바이스 음성 인식 서비스가 없습니다. 자막은 직접 입력할 수 있어요."
            SpeechState.FILE_INPUT_UNSUPPORTED -> "이 기기(Android 13 미만)의 온디바이스 인식은 영상 소리 파일 입력을 지원하지 않습니다. 마이크 대신 쓰지 않고, 자막은 직접 입력할 수 있어요."
            SpeechState.MODEL_MISSING -> "기기에 $languageTag 음성 모델이 설치되어 있지 않습니다."
            SpeechState.LANGUAGE_UNSUPPORTED -> "$languageTag 언어는 이 기기의 온디바이스 인식이 지원하지 않습니다."
            SpeechState.TRANSIENT -> "음성 인식 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."
            SpeechState.READY -> "기기 안에서 음성을 자막으로 만듭니다 ($languageTag)."
        }
}

/** On-device (never cloud) speech to timed cues for a source window. */
internal object OnDeviceSpeech {
    private const val SAMPLE_RATE = 16_000
    private const val CHUNK_SECONDS = 4.0

    fun languageTag(): String = Locale.getDefault().toLanguageTag()

    private fun baseIntent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
    }

    suspend fun availability(context: Context): SpeechAvailability = withContext(Dispatchers.Main.immediate) {
        val tag = languageTag()
        if (Build.VERSION.SDK_INT < 31) return@withContext SpeechAvailability(SpeechState.UNSUPPORTED_DEVICE, tag)
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext SpeechAvailability(SpeechState.SERVICE_UNAVAILABLE, tag)
        if (Build.VERSION.SDK_INT < 33) return@withContext SpeechAvailability(SpeechState.FILE_INPUT_UNSUPPORTED, tag)
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?: return@withContext SpeechAvailability(SpeechState.TRANSIENT, tag)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.checkRecognitionSupport(baseIntent(tag), context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        fun has(list: List<String>) = list.any { it.equals(tag, true) || it.substringBefore('-').equals(tag.substringBefore('-'), true) }
                        val state = when {
                            has(support.installedOnDeviceLanguages) -> SpeechState.READY
                            has(support.supportedOnDeviceLanguages) || has(support.pendingOnDeviceLanguages) -> SpeechState.MODEL_MISSING
                            else -> SpeechState.LANGUAGE_UNSUPPORTED
                        }
                        if (cont.isActive) cont.resume(SpeechAvailability(state, tag))
                    }
                    override fun onError(error: Int) {
                        if (cont.isActive) cont.resume(SpeechAvailability(SpeechState.TRANSIENT, tag))
                    }
                })
            }
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    /** Explicit user action only: asks the system speech service to download the on-device language model. */
    fun requestModelDownload(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull() ?: return false
        val released = java.util.concurrent.atomic.AtomicBoolean(false)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val release = Runnable {
            if (released.compareAndSet(false, true)) runCatching { recognizer.destroy() }
        }
        val intent = baseIntent(languageTag())
        // True only means the request was queued, not that the download completed. No fixed timer: it could destroy a pending connection or an active download.
        val queued = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Released only by the listener's terminal/scheduled/error callbacks.
                recognizer.triggerModelDownload(intent, context.mainExecutor, object : android.speech.ModelDownloadListener {
                    override fun onProgress(completedPercent: Int) {}
                    override fun onSuccess() = release.run()
                    override fun onScheduled() = release.run()
                    override fun onError(error: Int) = release.run()
                })
            } else {
                // API 33 has no download listener: the support callback is the connection handshake. Either result (even CANNOT_CHECK_SUPPORT) means the service answered, so trigger on the bound recognizer, then release after the trigger dispatch (main-looper FIFO).
                recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    private fun triggerThenRelease() {
                        if (!released.get()) runCatching { recognizer.triggerModelDownload(intent) }
                        handler.post(release)
                    }
                    override fun onSupportResult(support: RecognitionSupport) = triggerThenRelease()
                    override fun onError(error: Int) = triggerThenRelease()
                })
            }
            true
        }.getOrDefault(false)
        if (!queued) release.run()
        return queued
    }

    /** Decodes [startSec, endSec) of the source's audio to 16 kHz mono PCM16, or null when it has no audio. */
    private fun decodeWindow(file: File, startSec: Double, endSec: Double): ByteArray? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo((startSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val endUs = (endSec * 1_000_000).toLong()
            val out = ByteArrayOutputStream()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val startUs = (startSec * 1_000_000).toLong()
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buffer = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time > endUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            if (info.size > 0 && info.presentationTimeUs >= startUs - 50_000) {
                                val bytes = ByteArray(info.size)
                                codec.getOutputBuffer(o)!!.apply { position(info.offset); get(bytes) }
                                out.write(bytes)
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            val raw = out.toByteArray()
            val frames = raw.size / (2 * channels.coerceAtLeast(1))
            if (frames <= 0) return null
            val mono = ShortArray(frames) { f ->
                var sum = 0
                for (c in 0 until channels) {
                    val p = (f * channels + c) * 2
                    sum += (raw[p].toInt() and 0xFF) or (raw[p + 1].toInt() shl 8)
                }
                (sum / channels).toShort()
            }
            val outFrames = (frames.toLong() * SAMPLE_RATE / rate).toInt().coerceAtLeast(1)
            val pcm = ByteArray(outFrames * 2)
            for (n in 0 until outFrames) {
                val pos = n.toDouble() * rate / SAMPLE_RATE
                val a = mono[pos.toInt().coerceAtMost(frames - 1)].toInt()
                val b = mono[(pos.toInt() + 1).coerceAtMost(frames - 1)].toInt()
                val v = (a + (b - a) * (pos - pos.toInt())).toInt().coerceIn(-32768, 32767)
                pcm[n * 2] = (v and 0xFF).toByte()
                pcm[n * 2 + 1] = (v shr 8).toByte()
            }
            return pcm
        } finally {
            extractor.release()
        }
    }

    sealed interface ChunkResult {
        data class Text(val text: String) : ChunkResult
        object Silence : ChunkResult
        data class Failure(val state: SpeechState, val code: Int) : ChunkResult
    }

    private suspend fun recognizeChunk(context: Context, pcm: ByteArray, tag: String): ChunkResult = withContext(Dispatchers.Main.immediate) {
        val pipe = ParcelFileDescriptor.createPipe()
        val read = pipe[0]
        val write = pipe[1]
        val recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        if (recognizer == null) {
            read.close(); write.close()
            return@withContext ChunkResult.Failure(SpeechState.SERVICE_UNAVAILABLE, -1)
        }
        val writer = Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(write).use { it.write(pcm) } }
        }
        try {
            suspendCancellableCoroutine<ChunkResult> { cont: CancellableContinuation<ChunkResult> ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                        if (cont.isActive) cont.resume(if (text.isEmpty()) ChunkResult.Silence else ChunkResult.Text(text))
                    }
                    override fun onError(error: Int) {
                        val result = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> ChunkResult.Silence
                            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> ChunkResult.Failure(SpeechState.LANGUAGE_UNSUPPORTED, error)
                            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> ChunkResult.Failure(SpeechState.MODEL_MISSING, error)
                            else -> ChunkResult.Failure(SpeechState.TRANSIENT, error)
                        }
                        if (cont.isActive) cont.resume(result)
                    }
                })
                cont.invokeOnCancellation { runCatching { recognizer.cancel() } }
                val intent = baseIntent(tag).apply {
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                }
                writer.start()
                recognizer.startListening(intent)
            }
        } finally {
            runCatching { recognizer.destroy() }
            runCatching { read.close() }
        }
    }

    /**
     * Source window -> generated timed cues (source time). Stops at the first hard failure so a missing model or an
     * unsupported language is reported instead of silently producing nothing.
     */
    suspend fun transcribe(
        context: Context,
        source: TrailerSource,
        startSec: Double,
        endSec: Double,
        onProgress: (Float) -> Unit,
    ): Result<List<TrailerTimedCaptionCue>> {
        val tag = languageTag()
        // Only the selected video's own audio window is transcribed (EXTRA_AUDIO_SOURCE, API 33+); never the live mic.
        if (Build.VERSION.SDK_INT < 33) return Result.failure(IllegalStateException(SpeechAvailability(SpeechState.FILE_INPUT_UNSUPPORTED, tag).message))
        val pcm = withContext(Dispatchers.IO) { runCatching { decodeWindow(source.file, startSec, endSec) }.getOrNull() }
            ?: return Result.failure(IllegalStateException("이 구간에서 소리를 읽을 수 없습니다."))
        val chunkBytes = (CHUNK_SECONDS * SAMPLE_RATE).toInt() * 2
        val cues = ArrayList<TrailerTimedCaptionCue>()
        var offset = 0
        while (offset < pcm.size) {
            coroutineContext.ensureActive()
            val end = minOf(pcm.size, offset + chunkBytes)
            val chunk = pcm.copyOfRange(offset, end)
            val chunkStart = startSec + offset / 2.0 / SAMPLE_RATE
            val chunkEnd = startSec + end / 2.0 / SAMPLE_RATE
            when (val r = recognizeChunk(context, chunk, tag)) {
                is ChunkResult.Text -> cues += TrailerTimedCaptionCue(
                    sourceId = source.id, sourceStart = chunkStart, sourceEnd = chunkEnd, text = r.text, origin = CueOrigin.GENERATED,
                )
                ChunkResult.Silence -> {}
                is ChunkResult.Failure -> return Result.failure(IllegalStateException(SpeechAvailability(r.state, tag).message))
            }
            offset = end
            onProgress(offset.toFloat() / pcm.size)
        }
        return Result.success(cues)
    }
}

internal fun copyToVoiceFile(input: java.io.InputStream, target: File) {
    target.parentFile?.mkdirs()
    FileOutputStream(target).use { out -> input.use { it.copyTo(out) } }
}
