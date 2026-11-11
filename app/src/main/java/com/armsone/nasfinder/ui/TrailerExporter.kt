package com.armsone.nasfinder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.SpeedParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

import android.opengl.Matrix
import androidx.media3.effect.GlMatrixTransformation
import java.util.Random

/** Constant per-scene original-audio gain (0 mutes); applied only to that scene's own clip audio. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SceneGainAudioProcessor(private val gain: Float) : androidx.media3.common.audio.BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val input = inputBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        output.order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT) {
            while (input.remaining() >= 2) {
                val scaled = (input.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(scaled.toShort())
            }
        } else {
            while (input.remaining() >= 4) output.putFloat((input.float * gain).coerceIn(-1f, 1f))
        }
        output.flip()
    }
}

/**
 * Appends [padFrames] of silence after the last input sample. Some AAC encoders (API 29 c2.android.aac.encoder) never flush the
 * ~1600-sample encoder delay at end of stream, so an output shorter than that delay would lose all of its real samples.
 * The muxer drops the frames that start after the real output end.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class TrailingSilenceAudioProcessor(
    private val padFrames: Int,
    private val padSeconds: Double = 0.0,
    // Test-host forensics only (null = off, no copies/logging): PCM before the pad and PCM as handed to the consumer.
    private val trace: PcmTrace? = null,
) : androidx.media3.common.audio.BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_FLOAT) {
            throw androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        trace?.configured(inputAudioFormat)
        return inputAudioFormat
    }

    /** What the consumer receives. Internal draining below uses super.getOutput(), so it is never counted as consumer output. */
    override fun getOutput(): java.nio.ByteBuffer {
        val delivered = super.getOutput()
        if (delivered.hasRemaining()) handedToConsumer = delivered
        trace?.delivered(delivered)
        if (trace != null && isEnded) trace.flush()
        return delivered
    }

    // The buffer last handed to the consumer. getOutput() returns the processor's own storage by reference, so while the consumer has not
    // read it all, replaceOutputBuffer() must not reuse (clear/re-limit) that same storage.
    private var handedToConsumer: java.nio.ByteBuffer? = null

    /** Like replaceOutputBuffer, but never reuses storage the consumer still holds unread: then a fresh, larger buffer is allocated. */
    private fun replaceOutputBufferKeepingHeld(count: Int): java.nio.ByteBuffer {
        val held = handedToConsumer
        if (held != null && !held.hasRemaining()) handedToConsumer = null
        val heldCapacity = if (held != null && held.hasRemaining()) held.capacity() else 0
        return replaceOutputBuffer(max(count, heldCapacity + 1))
    }

    override fun onFlush() {
        handedToConsumer = null
    }

    /**
     * Takes the not-yet-drained output as an independent copy. getOutput() hands back the processor's own buffer and replaceOutputBuffer()
     * reuses and clears that same buffer when it is large enough, so the pending bytes must be copied out BEFORE the replacement.
     */
    private fun takePendingCopy(where: String): ByteArray {
        val pending = super.getOutput()
        trace?.beforeReuse(where, pending)
        return ByteArray(pending.remaining()).also { pending.get(it) }
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        trace?.input(inputBuffer)
        // Keep any output the pipeline has not drained yet (replaceOutputBuffer alone would overwrite it).
        val pending = takePendingCopy("queueInput")
        val out = replaceOutputBufferKeepingHeld(pending.size + remaining)
        out.put(pending)
        out.put(inputBuffer)
        out.flip()
    }

    override fun onQueueEndOfStream() {
        val pending = takePendingCopy("endOfStream")
        // Pad by at least the encoder's delay plus an AAC frame at the actual stream rate, so the real tail samples reach the output.
        val pad = max(padFrames, Math.round(padSeconds * inputAudioFormat.sampleRate).toInt()) * inputAudioFormat.bytesPerFrame
        trace?.endOfStream(pending.size, pad)
        val out = replaceOutputBufferKeepingHeld(pending.size + pad)
        out.put(pending)
        out.put(ByteArray(pad))
        out.flip()
    }

    override fun onReset() {
        handedToConsumer = null
        trace?.flush()
    }
}

/**
 * Test-host forensics only: a pad processor instance's PCM (before the pad / as delivered to the consumer) as PCM WAVs plus an event log
 * (instance, order, buffer identity, positions), all under the caller's own diagnostic prefix. Sample contents are never logged.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class PcmTrace(private val prefix: String) {
    private val inputPcm = java.io.ByteArrayOutputStream()
    private val deliveredPcm = java.io.ByteArrayOutputStream()
    private val log = StringBuilder()
    private var format: androidx.media3.common.audio.AudioProcessor.AudioFormat? = null
    private var sequence = 0
    private var emptyPolls = 0
    private var flushed = false
    private var lastHanded: java.nio.ByteBuffer? = null

    private fun event(text: String) { log.append("#").append(sequence++).append(' ').append(text).append('\n') }
    private fun id(buffer: java.nio.ByteBuffer) = "id=${System.identityHashCode(buffer)} ${if (buffer.isDirect) "direct" else "heap"}"
    private fun copyOf(buffer: java.nio.ByteBuffer): ByteArray = buffer.duplicate().let { dup -> ByteArray(dup.remaining()).also { dup.get(it) } }
    private fun frames(bytes: Int) = format?.bytesPerFrame?.takeIf { it > 0 }?.let { bytes / it } ?: -1

    @Synchronized fun configured(audioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat) {
        format = audioFormat
        val type = if (audioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT) "float32" else "pcm16"
        event("instance=${System.identityHashCode(this)} configure rate=${audioFormat.sampleRate} channels=${audioFormat.channelCount} type=$type")
    }

    @Synchronized fun input(buffer: java.nio.ByteBuffer) {
        val bytes = copyOf(buffer)
        inputPcm.write(bytes)
        event("input ${id(buffer)} position=${buffer.position()} remaining=${buffer.remaining()} limit=${buffer.limit()} frames=${frames(bytes.size)}")
    }

    /** Called before the internal pending drain / buffer reuse: the buffer the consumer was last handed, and the pending output taken back. */
    @Synchronized fun beforeReuse(where: String, pending: java.nio.ByteBuffer) {
        val handed = lastHanded
        val handedText = if (handed == null) "none" else "${id(handed)} position=${handed.position()} remaining=${handed.remaining()}"
        event("$where lastHanded=$handedText pending ${id(pending)} position=${pending.position()} remaining=${pending.remaining()}")
    }

    @Synchronized fun endOfStream(pendingBytes: Int, padBytes: Int) {
        event("queueEndOfStream pendingBytes=$pendingBytes padBytes=$padBytes padFrames=${frames(padBytes)}")
    }

    @Synchronized fun delivered(buffer: java.nio.ByteBuffer) {
        if (!buffer.hasRemaining()) { emptyPolls++; return }
        val bytes = copyOf(buffer)
        deliveredPcm.write(bytes)
        lastHanded = buffer
        event("getOutput ${id(buffer)} position=${buffer.position()} remaining=${buffer.remaining()} frames=${frames(bytes.size)}")
    }

    @Synchronized fun flush() {
        if (flushed) return
        flushed = true
        val audioFormat = format
        runCatching {
            if (audioFormat != null) {
                writePcmWav(File("$prefix-in.wav"), inputPcm.toByteArray(), audioFormat.channelCount, audioFormat.sampleRate, audioFormat.encoding)
                writePcmWav(File("$prefix-out.wav"), deliveredPcm.toByteArray(), audioFormat.channelCount, audioFormat.sampleRate, audioFormat.encoding)
            }
            event("flush inputBytes=${inputPcm.size()} deliveredBytes=${deliveredPcm.size()} emptyPolls=$emptyPolls")
            File("$prefix-trace.txt").writeText(log.toString())
        }
    }
}

/** PCM16 WAV (format tag 1, 16 bit) or float WAV (format tag 3, 32 bit); other encodings are not written. */
private fun writePcmWav(target: File, data: ByteArray, channels: Int, rate: Int, encoding: Int) {
    val isFloat = encoding == androidx.media3.common.C.ENCODING_PCM_FLOAT
    if (!isFloat && encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) return
    val bits = if (isFloat) 32 else 16
    val blockAlign = channels * bits / 8
    val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray()); put("fmt ".toByteArray())
        putInt(16); putShort(if (isFloat) 3 else 1); putShort(channels.toShort()); putInt(rate); putInt(rate * blockAlign)
        putShort(blockAlign.toShort()); putShort(bits.toShort()); put("data".toByteArray()); putInt(data.size)
    }
    target.outputStream().use { it.write(header.array()); it.write(data) }
}

/**
 * Per-scene framing effects shared by export and the editor's playback preview (ExoPlayer.setVideoEffects), so both
 * render the same fit/focus in the same output size. FIT letterboxes; FILL crops to cover. A non-centered FILL focus
 * first scales the clip to its cover size, crops the output-shaped window at the focus, then normalizes to the exact
 * output size (all supported Media3 effects).
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun sceneFramingVideoEffects(
    fit: SceneFit,
    focusX: Float,
    focusY: Float,
    sourceWidth: Int,
    sourceHeight: Int,
    outputWidth: Int,
    outputHeight: Int,
): List<androidx.media3.common.Effect> {
    val fitPresentation = Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT)
    if (fit == SceneFit.FIT) return listOf(fitPresentation)
    if ((focusX == 0f && focusY == 0f) || sourceWidth <= 0 || sourceHeight <= 0) {
        return listOf(Presentation.createForWidthAndHeight(outputWidth, outputHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
    }
    val sourceAspect = sourceWidth.toDouble() / sourceHeight
    val outputAspect = outputWidth.toDouble() / outputHeight
    val (coverWidth, coverHeight) = if (sourceAspect > outputAspect) {
        (outputHeight * sourceAspect) to outputHeight.toDouble()
    } else {
        outputWidth.toDouble() to (outputWidth / sourceAspect)
    }
    val evenCoverWidth = (((coverWidth / 2.0).toInt()) * 2).coerceAtLeast(outputWidth)
    val evenCoverHeight = (((coverHeight / 2.0).toInt()) * 2).coerceAtLeast(outputHeight)
    val halfWidth = (outputWidth.toFloat() / evenCoverWidth).coerceIn(0.01f, 1f)
    val halfHeight = (outputHeight.toFloat() / evenCoverHeight).coerceIn(0.01f, 1f)
    val centerX = focusX.coerceIn(-1f, 1f) * (1f - halfWidth)
    // focusY -1 = top of the picture; NDC +1 is the top edge.
    val centerY = -focusY.coerceIn(-1f, 1f) * (1f - halfHeight)
    return listOf(
        Presentation.createForWidthAndHeight(evenCoverWidth, evenCoverHeight, Presentation.LAYOUT_SCALE_TO_FIT),
        androidx.media3.effect.Crop(centerX - halfWidth, centerX + halfWidth, centerY - halfHeight, centerY + halfHeight),
        fitPresentation,
    )
}

// HanClip's composition, crop presentation and cancellation/export validation path.
@androidx.annotation.OptIn(UnstableApi::class)
internal object TrailerExporter {
    private const val MAX_GROUP_SIZE = 8
    private const val OUTPUT_FPS = 30.0
    // H.264 레벨 4.1이 허용하는 최대 프레임 크기(8192 매크로블록 = 2,097,152 화소). 1920x1080/1080x1920 모두 이 안에 든다.
    private const val H264_LEVEL_41_MAX_PIXELS = 2_097_152L
    // 이 길이(초) 미만 출력(약 3프레임 미만)만 ShortOutputMuxerFactory로 쓴다. AAC 프라이밍(1600샘플≈36ms)보다 오디오가 짧을 때의 음수 edit list 회피.
    internal const val FRAMEWORK_MUXER_BELOW_SECONDS = 0.1
    // 짧은 출력 끝에 붙이는 무음(샘플). AAC 인코더 지연(1600샘플)을 밀어내 실제 샘플이 담긴 프레임까지 나오게 한다.
    internal const val ENCODER_FLUSH_PAD_FRAMES = 2048
    // 스트림 샘플레이트 기준 최소 꼬리(초): AAC 지연(1600~2048)+프레임(1024)을 어떤 레이트에서도 덮는다.
    internal const val ENCODER_FLUSH_PAD_SECONDS = 0.1

    /**
     * In-app MP4 muxer for outputs shorter than the AAC priming. When the encoder delivers only ONE audio sample and it starts before 0 (priming),
     * the in-app muxer writes a negative edit-list duration; that single sample is written at 0 instead. Two or more audio samples pass through unchanged.
     * The video duration closes the one-frame video track with a real length (otherwise the next stage sees no frame).
     */
    private class ShortOutputMuxerFactory(
        private val videoDurationUs: Long,
        private val diagnosticPath: String? = null,
        // false: a normal-length output only drops the processing-only pad packets beyond the canonical end (no video duration / priming hold).
        private val shortOutput: Boolean = true,
    ) : androidx.media3.muxer.Muxer.Factory {
        private val delegate = if (shortOutput) InAppMp4Muxer.Factory().setVideoDurationUs(videoDurationUs) else InAppMp4Muxer.Factory()
        override fun create(path: String): androidx.media3.muxer.Muxer {
            val inner = delegate.create(path)
            return object : androidx.media3.muxer.Muxer {
                private val audioTracks = HashSet<Int>()
                private var heldTrack = -1
                private var heldData: java.nio.ByteBuffer? = null
                private var heldInfo: androidx.media3.muxer.BufferInfo? = null
                // Test-host forensics only (diagnosticPath null = off): every encoder audio packet, kept or dropped, as ADTS + a pts list.
                private var dumpAac: java.io.OutputStream? = null
                private var dumpList: java.io.Writer? = null
                private var dumpRate = 44100
                private var dumpChannels = 2
                private var dumpProfile = 1
                override fun addTrack(format: androidx.media3.common.Format): Int =
                    inner.addTrack(format).also {
                        if (MimeTypes.isAudio(format.sampleMimeType)) {
                            audioTracks.add(it)
                            if (diagnosticPath != null && dumpAac == null) {
                                dumpRate = format.sampleRate.takeIf { r -> r > 0 } ?: 44100
                                dumpChannels = format.channelCount.takeIf { c -> c > 0 } ?: 2
                                val asc = format.initializationData.firstOrNull()
                                if (asc != null && asc.isNotEmpty()) dumpProfile = ((asc[0].toInt() and 0xFF) shr 3).coerceAtLeast(1) - 1
                                dumpAac = runCatching { java.io.BufferedOutputStream(FileOutputStream("$diagnosticPath-allpackets.aac")) }.getOrNull()
                                dumpList = runCatching { java.io.FileWriter("$diagnosticPath-packets.txt").also { w -> w.write("rate=$dumpRate channels=$dumpChannels profile=$dumpProfile videoDurationUs=$videoDurationUs\n") } }.getOrNull()
                            }
                        }
                    }
                private var audioSamples = 0
                private fun dumpPacket(byteBuffer: java.nio.ByteBuffer, info: androidx.media3.muxer.BufferInfo) {
                    val out = dumpAac ?: return
                    runCatching {
                        val dup = byteBuffer.duplicate()
                        val raw = ByteArray(dup.remaining()).also { dup.get(it) }
                        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
                        val freqIndex = rates.indexOf(dumpRate).coerceAtLeast(0)
                        val frameLen = raw.size + 7
                        val header = byteArrayOf(
                            0xFF.toByte(), 0xF1.toByte(),
                            ((dumpProfile shl 6) or (freqIndex shl 2) or (dumpChannels shr 2)).toByte(),
                            (((dumpChannels and 3) shl 6) or (frameLen shr 11)).toByte(),
                            ((frameLen shr 3) and 0xFF).toByte(),
                            (((frameLen and 7) shl 5) or 0x1F).toByte(),
                            0xFC.toByte(),
                        )
                        out.write(header); out.write(raw)
                        dumpList?.write("pts=${info.presentationTimeUs} size=${raw.size} kept=${info.presentationTimeUs < videoDurationUs}\n")
                    }
                }
                override fun writeSampleData(trackId: Int, byteBuffer: java.nio.ByteBuffer, bufferInfo: androidx.media3.muxer.BufferInfo) {
                    if (trackId in audioTracks) {
                        dumpPacket(byteBuffer, bufferInfo)
                        // 인코더 flush용으로 덧붙인 무음 꼬리(TrailingSilenceAudioProcessor)에서 나온, 출력 끝 이후의 프레임은 쓰지 않는다.
                        if (bufferInfo.presentationTimeUs >= videoDurationUs) return
                        audioSamples++
                        if (shortOutput && audioSamples == 1 && bufferInfo.presentationTimeUs < 0L) {
                            heldTrack = trackId
                            heldData = java.nio.ByteBuffer.allocate(byteBuffer.remaining()).also { it.put(byteBuffer.duplicate()); it.flip() }
                            heldInfo = bufferInfo
                            return
                        }
                        val held = heldData
                        if (held != null) { inner.writeSampleData(heldTrack, held, heldInfo!!); heldData = null; heldInfo = null }
                    }
                    inner.writeSampleData(trackId, byteBuffer, bufferInfo)
                }
                override fun addMetadataEntry(metadata: androidx.media3.common.Metadata.Entry) = inner.addMetadataEntry(metadata)
                override fun close() {
                    runCatching { dumpAac?.close() }
                    runCatching { dumpList?.close() }
                    val held = heldData
                    val info = heldInfo
                    if (held != null && info != null) {
                        heldData = null; heldInfo = null
                        inner.writeSampleData(heldTrack, held, androidx.media3.muxer.BufferInfo(0L, info.size, info.flags))
                    }
                    inner.close()
                }
            }
        }
        override fun getSupportedSampleMimeTypes(trackType: Int) = delegate.getSupportedSampleMimeTypes(trackType)
    }

    // 소스 AAC 프레임(약 23ms)보다 짧은 장면은 Media3 클립이 "시작 pts보다 앞선" 프레임을 통째로 버려 오디오가 무음으로 채워진다.
    // 이런 아주 짧은 장면만 MediaCodec으로 정확한 창(클립 ms 경계와 동일)의 PCM을 뽑아 WAV로 쓰고, 별도 오디오 시퀀스로 같은 위치에 얹는다.
    private const val SHORT_AUDIO_WINDOW_SECONDS = 0.05
    private const val EXACT_AUDIO_ROLL_US = 100_000L

    private fun needsExactAudioWindow(scene: TrailerScene): Boolean =
        !scene.source.isImage && scene.length < SHORT_AUDIO_WINDOW_SECONDS && scene.sourceVolume > 0.005f

    /** Decodes [startMs, endMs) of the file's first audio track sample-accurately (by decoder pts) and writes it as PCM16 WAV. Null when there is no audio. */
    private fun writeExactWindowWav(file: File, startMs: Long, endMs: Long, speed: Float, target: File): Long? {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(index)
            val format = extractor.getTrackFormat(index)
            val codec = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            val startUs = startMs * 1000L
            val endUs = endMs * 1000L
            // 속도 변경(Sonic)은 입력이 아주 짧으면 거의 무음에 가깝게 처리한다. 창 앞뒤 100ms(프리/포스트롤)까지 디코드해 속도 처리한 뒤, 정확한 출력 창만 잘라 쓴다.
            val loUs = (startUs - EXACT_AUDIO_ROLL_US).coerceAtLeast(0L)
            val hiUs = endUs + EXACT_AUDIO_ROLL_US
            val pcm = java.io.ByteArrayOutputStream()
            var rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                // 시크 직후 첫 AAC 프레임들은 이전 프레임과의 겹침이 없어 무음/왜곡이 나오므로, 창보다 100ms 앞에서 디코드를 시작한다(프리롤). 창 밖 샘플은 pts로 버린다.
                extractor.seekTo((loUs - 100_000L).coerceAtLeast(0L), android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val info = android.media.MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                var guard = 0
                while (!outputDone && guard++ < 20_000) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buffer = codec.getInputBuffer(i)!!
                            val size = extractor.readSampleData(buffer, 0)
                            val time = extractor.sampleTime
                            if (size < 0 || time >= hiUs) {
                                codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, size, time, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            if (info.size > 0) {
                                val frameBytes = 2 * channels
                                val frames = info.size / frameBytes
                                // 이 출력 버퍼의 프레임 i는 pts + i / rate 시각이다. 창 [startUs, endUs) 안의 프레임만 남긴다.
                                val first = Math.round((loUs - info.presentationTimeUs) * rate / 1_000_000.0).coerceAtLeast(0L).toInt()
                                val last = Math.round((hiUs - info.presentationTimeUs) * rate / 1_000_000.0).coerceAtMost(frames.toLong()).toInt()
                                if (last > first) {
                                    val bytes = ByteArray((last - first) * frameBytes)
                                    codec.getOutputBuffer(o)!!.apply { position(info.offset + first * frameBytes); get(bytes) }
                                    pcm.write(bytes)
                                }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
            val decoded = pcm.toByteArray()
            if (decoded.isEmpty()) throw ExactAudioException("exact audio window produced no samples for an existing audio track")
            val frameBytes = 2 * channels
            val winStartFrame = Math.round((startUs - loUs) * rate / 1_000_000.0).toInt()
            val winFrames = Math.round((endUs - startUs) * rate / 1_000_000.0).toInt()
            val processed: ByteArray
            var outStart: Int
            var outFrames: Int
            if (abs(speed - 1f) < 0.005f) {
                processed = decoded
                outStart = winStartFrame
                outFrames = winFrames
            } else {
                val sonic = androidx.media3.common.audio.SonicAudioProcessor()
                sonic.setSpeed(speed)
                sonic.setPitch(1f)
                sonic.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(rate, channels, androidx.media3.common.C.ENCODING_PCM_16BIT))
                sonic.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
                val sink = java.io.ByteArrayOutputStream()
                fun drain() {
                    val out = sonic.output
                    if (out.hasRemaining()) { val b = ByteArray(out.remaining()); out.get(b); sink.write(b) }
                }
                sonic.queueInput(java.nio.ByteBuffer.wrap(decoded).order(java.nio.ByteOrder.LITTLE_ENDIAN))
                drain()
                sonic.queueEndOfStream()
                while (!sonic.isEnded) { val before = sink.size(); drain(); if (sink.size() == before && !sonic.isEnded) break }
                drain()
                processed = sink.toByteArray()
                outStart = Math.round(winStartFrame / speed.toDouble()).toInt()
                outFrames = Math.round(winFrames / speed.toDouble()).toInt()
            }
            val totalFrames = processed.size / frameBytes
            outStart = outStart.coerceIn(0, totalFrames)
            outFrames = outFrames.coerceAtMost(totalFrames - outStart)
            if (outFrames <= 0) throw ExactAudioException("exact audio window is empty after processing (total=$totalFrames start=$outStart)")
            val data = processed.copyOfRange(outStart * frameBytes, (outStart + outFrames) * frameBytes)
            android.util.Log.i("NfExactAudio", "window ${startMs}..${endMs}ms speed=$speed decoded=${decoded.size / frameBytes} out=$outFrames")
            stageDiagnosticPrefix?.let { prefix ->
                // Test-host forensics (off by default): the decoded +-100ms source window and the full rate-processed PCM, before the exact crop.
                runCatching {
                    writePcm16Wav(File("${prefix}s0-decoded-window.wav"), decoded, channels, rate)
                    writePcm16Wav(File("${prefix}s1-processed-full.wav"), processed, channels, rate)
                    File("${prefix}s0-window.txt").writeText("rate=$rate channels=$channels speed=$speed decodedFrames=${decoded.size / frameBytes} processedFrames=$totalFrames outStart=$outStart outFrames=$outFrames winStartFrame=$winStartFrame winFrames=$winFrames\n")
                }
            }
            writePcm16Wav(target, data, channels, rate)
            return Math.round(data.size / (2.0 * channels) * 1_000_000.0 / rate)
        } finally {
            extractor.release()
        }
    }

    private fun writePcm16Wav(target: File, data: ByteArray, channels: Int, rate: Int) {
        val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray()); put("fmt ".toByteArray())
            putInt(16); putShort(1); putShort(channels.toShort()); putInt(rate); putInt(rate * channels * 2)
            putShort((channels * 2).toShort()); putShort(16); put("data".toByteArray()); putInt(data.size)
        }
        target.outputStream().use { it.write(header.array()); it.write(data) }
    }

    /** Test-host-only forensics (null = off, the default): `<own diag dir>/b2-<runAtMillis>-` prefix for per-stage copies of the synthetic fixture audio. */
    @Volatile private var stageDiagnosticPrefix: String? = null
    private var stageDiagnosticPass = 0

    internal fun beginStageDiagnostics(prefix: String?) {
        stageDiagnosticPrefix = prefix
        stageDiagnosticPass = 0
    }

    /** An existing audio track could not be turned into the exact window; the export fails instead of silently keeping the silent clip path. */
    private class ExactAudioException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private class ExactAudioWindow(val file: File, val durationUs: Long)

    // chunk file path -> its exact audio window, handed from exportChunk to the final composition (which plays the WAV instead of the chunk's re-decoded AAC).
    private val exactAudioByChunk = java.util.concurrent.ConcurrentHashMap<String, ExactAudioWindow>()

    /** The exact (already rate-processed) PCM window of a very short clip as a task-owned WAV, or null when no window is needed / the source has no audio. */
    private fun prepareExactAudioWindow(scene: TrailerScene, destinationParent: File, tempFiles: MutableList<File>): ExactAudioWindow? {
        if (!needsExactAudioWindow(scene)) return null
        val startMs = (scene.start * 1000).toLong()
        val endMs = ((scene.start + scene.length) * 1000).toLong()
        val wav = File(destinationParent, "exact-audio-${java.util.UUID.randomUUID()}.wav")
        tempFiles.add(wav)
        // null = 소스에 오디오 트랙이 없음(정상). 트랙이 있는데 디코드/처리가 실패하면 무음 경로로 넘기지 않고 타입 있는 실패로 전파한다.
        val durationUs = try {
            writeExactWindowWav(scene.source.file, startMs, endMs, (scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0).toFloat(), wav)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExactAudioException) {
            android.util.Log.w("NfExactAudio", "exact audio window failed", e)
            throw e
        } catch (e: Exception) {
            android.util.Log.w("NfExactAudio", "exact audio window failed", e)
            throw ExactAudioException("exact audio window failed: ${e.javaClass.simpleName}", e)
        }
        if (durationUs != null && durationUs <= 0L) throw ExactAudioException("exact audio window has no duration")
        return durationUs?.let { ExactAudioWindow(wav, it) }
    }

    /** Encoder/decoder allocation failure (not a corrupt source): the same composition may succeed with the healthy originals fed directly. */
    private fun isCodecResourceFailure(e: Throwable): Boolean {
        var current: Throwable? = e
        while (current != null) {
            if (current is CancellationException) return false
            if (current is ExportException && (current.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED ||
                    current.errorCode == ExportException.ERROR_CODE_DECODER_INIT_FAILED)) return true
            current = current.cause
        }
        return false
    }

    private class ImageDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class GroupExportResult(
        val files: List<File>,
        // files와 같은 순서로, 각 파일이 담아야 할 계획 길이(초). 이어 붙일 때 파일 끝의 인코더/AAC 꼬리를 잘라내는 기준이다.
        val fileDurations: List<Double>,
        val effectiveScenes: List<TrailerScene>,
        val recoveredCount: Int = 0,
        val excludedCount: Int = 0,
    )

    private fun isMediaDecodingFailure(e: Throwable): Boolean {
        if (e is CancellationException) return false
        if (e is java.io.FileNotFoundException || e is SecurityException) return false

        val msg = e.message?.lowercase() ?: ""
        if (msg.contains("enospc") || msg.contains("no space") || msg.contains("permission denied") || msg.contains("read-only")) {
            return false
        }

        var current: Throwable? = e
        var isDecoding = false
        var isEncoderOrMuxerOrIo = false

        while (current != null) {
            if (current is CancellationException) return false
            if (current is java.io.FileNotFoundException || current is SecurityException) return false

            val curMsg = current.message?.lowercase() ?: ""
            if (curMsg.contains("enospc") || curMsg.contains("no space") || curMsg.contains("permission denied")) {
                return false
            }

            if (current is ImageDecodeException) {
                isDecoding = true
            }

            if (current is ExportException) {
                when (current.errorCode) {
                    ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                    ExportException.ERROR_CODE_ENCODING_FAILED,
                    ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                    ExportException.ERROR_CODE_MUXING_FAILED,
                    ExportException.ERROR_CODE_MUXING_TIMEOUT -> {
                        isEncoderOrMuxerOrIo = true
                    }
                    ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    ExportException.ERROR_CODE_IO_NO_PERMISSION,
                    ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    ExportException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> {
                        return false
                    }
                    ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                    ExportException.ERROR_CODE_DECODING_FAILED,
                    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> {
                        isDecoding = true
                    }
                }
            }

            if (current is android.media.MediaCodec.CodecException) {
                if (!isEncoderOrMuxerOrIo) {
                    isDecoding = true
                }
            }

            current = current.cause
        }

        if (isEncoderOrMuxerOrIo) return false
        return isDecoding || msg.contains("decode") || msg.contains("extractor") || msg.contains("demux")
    }

    private fun renderBitmapToJpeg(
        sourceBitmap: Bitmap,
        targetW: Int,
        targetH: Int,
        output: File,
        fit: SceneFit = SceneFit.FILL,
        focusX: Float = 0f,
        focusY: Float = 0f,
    ) {
        val resultBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(resultBitmap)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

            val srcW = sourceBitmap.width.toFloat()
            val srcH = sourceBitmap.height.toFloat()
            if (fit == SceneFit.FIT) {
                canvas.drawColor(android.graphics.Color.BLACK)
                val scale = minOf(targetW / srcW, targetH / srcH)
                val drawW = srcW * scale
                val drawH = srcH * scale
                val left = (targetW - drawW) / 2f
                val top = (targetH - drawH) / 2f
                canvas.drawBitmap(sourceBitmap, null, RectF(left, top, left + drawW, top + drawH), paint)
            } else {
                val scale = maxOf(targetW / srcW, targetH / srcH)
                val cropW = targetW / scale
                val cropH = targetH / scale
                val cropX = ((srcW - cropW) / 2f * (1f + focusX.coerceIn(-1f, 1f))).coerceIn(0f, (srcW - cropW).coerceAtLeast(0f))
                val cropY = ((srcH - cropH) / 2f * (1f + focusY.coerceIn(-1f, 1f))).coerceIn(0f, (srcH - cropH).coerceAtLeast(0f))

                val srcRect = Rect(
                    cropX.toInt().coerceIn(0, sourceBitmap.width),
                    cropY.toInt().coerceIn(0, sourceBitmap.height),
                    (cropX + cropW).toInt().coerceIn(0, sourceBitmap.width),
                    (cropY + cropH).toInt().coerceIn(0, sourceBitmap.height),
                )
                val dstRect = Rect(0, 0, targetW, targetH)
                canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)
            }

            FileOutputStream(output).use { fos ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        } finally {
            resultBitmap.recycle()
        }
    }

    private fun preprocessImage(
        source: File,
        targetW: Int,
        targetH: Int,
        output: File,
        fit: SceneFit = SceneFit.FILL,
        focusX: Float = 0f,
        focusY: Float = 0f,
    ) {
        if (!source.exists() || !source.isFile) {
            throw java.io.FileNotFoundException("Source image not found: ${source.absolutePath}")
        }
        if (!source.canRead()) {
            throw SecurityException("Source image not readable: ${source.absolutePath}")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageDecodeException("이미지를 읽을 수 없습니다: ${source.name}")
        }

        val exif = try {
            ExifInterface(source.absolutePath)
        } catch (e: Exception) {
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 메타데이터를 읽을 수 없습니다: ${source.name}", e)
        }
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val isRotated90 = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270

        val (rawW, rawH) = if (isRotated90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        val sample = maxOf(1, minOf(rawW / targetW, rawH / targetH))
        val samplePowerOfTwo = Integer.highestOneBit(sample).coerceAtLeast(1)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = samplePowerOfTwo
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
        } catch (e: Exception) {
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 디코딩에 실패했습니다: ${source.name}", e)
        } ?: throw ImageDecodeException("이미지 디코딩에 실패했습니다: ${source.name}")

        val orientedBitmap = try {
            val matrix = AndroidMatrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }
        } catch (e: Exception) {
            decoded.recycle()
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 변환 처리에 실패했습니다: ${source.name}", e)
        }

        try {
            renderBitmapToJpeg(orientedBitmap, targetW, targetH, output, fit, focusX, focusY)
        } finally {
            orientedBitmap.recycle()
        }
    }

    private fun preprocessImageOrFallback(
        source: TrailerSource,
        targetW: Int,
        targetH: Int,
        output: File,
        fit: SceneFit,
        focusX: Float,
        focusY: Float,
    ): Boolean {
        try {
            preprocessImage(source.file, targetW, targetH, output, fit, focusX, focusY)
            return false
        } catch (e: ImageDecodeException) {
            var cause: Throwable? = e.cause
            while (cause != null) {
                if (cause is CancellationException || cause is SecurityException || cause is java.io.IOException || cause is Error) {
                    throw cause
                }
                cause = cause.cause
            }
            val thumb = source.thumbnail
            if (thumb != null && !thumb.isRecycled) {
                renderBitmapToJpeg(thumb, targetW, targetH, output, fit, focusX, focusY)
                return true
            } else {
                throw e
            }
        }
    }

    private fun renderFullOrientedBitmapToJpeg(
        orientedBitmap: Bitmap,
        output: File,
        maxLongEdge: Int = 2560,
        maxPixels: Long = 4_194_304L,
    ) {
        val srcW = orientedBitmap.width
        val srcH = orientedBitmap.height
        val longEdge = maxOf(srcW, srcH)
        val totalPixels = srcW.toLong() * srcH.toLong()

        val scale = if (longEdge > maxLongEdge || totalPixels > maxPixels) {
            val scaleEdge = maxLongEdge.toFloat() / longEdge
            val scalePixels = kotlin.math.sqrt(maxPixels.toDouble() / totalPixels).toFloat()
            minOf(scaleEdge, scalePixels)
        } else {
            1f
        }

        val finalW = (((srcW * scale).toInt() / 2) * 2).coerceAtLeast(2)
        val finalH = (((srcH * scale).toInt() / 2) * 2).coerceAtLeast(2)

        val workingBitmap = if (finalW != srcW || finalH != srcH) {
            Bitmap.createScaledBitmap(orientedBitmap, finalW, finalH, true)
        } else {
            orientedBitmap
        }

        try {
            FileOutputStream(output).use { fos ->
                workingBitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        } finally {
            if (workingBitmap !== orientedBitmap) {
                workingBitmap.recycle()
            }
        }
    }

    private fun preprocessFullPhoto(source: File, output: File) {
        if (!source.exists() || !source.isFile) {
            throw java.io.FileNotFoundException("Source image not found: ${source.absolutePath}")
        }
        if (!source.canRead()) {
            throw SecurityException("Source image not readable: ${source.absolutePath}")
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw ImageDecodeException("이미지를 읽을 수 없습니다: ${source.name}")
        }

        val exif = try {
            ExifInterface(source.absolutePath)
        } catch (e: Exception) {
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 메타데이터를 읽을 수 없습니다: ${source.name}", e)
        }
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val isRotated90 = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270

        val (rawW, rawH) = if (isRotated90) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
        val maxLongEdge = 2560
        val maxPixels = 4_194_304L

        var sample = 1
        var w = rawW
        var h = rawH
        while (maxOf(w, h) > maxLongEdge * 2 || (w.toLong() * h.toLong()) > maxPixels * 4L) {
            sample *= 2
            w = rawW / sample
            h = rawH / sample
        }
        val samplePowerOfTwo = Integer.highestOneBit(sample).coerceAtLeast(1)

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = samplePowerOfTwo
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeFile(source.absolutePath, decodeOptions)
        } catch (e: Exception) {
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 디코딩에 실패했습니다: ${source.name}", e)
        } ?: throw ImageDecodeException("이미지 디코딩에 실패했습니다: ${source.name}")

        val orientedBitmap = try {
            val matrix = AndroidMatrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                if (rotated !== decoded) decoded.recycle()
                rotated
            } else {
                decoded
            }
        } catch (e: Exception) {
            decoded.recycle()
            if (e is CancellationException || e is SecurityException || e is java.io.IOException) throw e
            throw ImageDecodeException("이미지 변환 처리에 실패했습니다: ${source.name}", e)
        }

        try {
            renderFullOrientedBitmapToJpeg(orientedBitmap, output, maxLongEdge, maxPixels)
        } finally {
            orientedBitmap.recycle()
        }
    }

    private fun preprocessFullPhotoOrFallback(source: TrailerSource, output: File): Boolean {
        try {
            preprocessFullPhoto(source.file, output)
            return false
        } catch (e: ImageDecodeException) {
            var cause: Throwable? = e.cause
            while (cause != null) {
                if (cause is CancellationException || cause is SecurityException || cause is java.io.IOException || cause is Error) {
                    throw cause
                }
                cause = cause.cause
            }
            val thumb = source.thumbnail
            if (thumb != null && !thumb.isRecycled) {
                renderFullOrientedBitmapToJpeg(thumb, output)
                return true
            } else {
                throw e
            }
        }
    }

    private suspend fun createRecoveredStillScene(
        scene: TrailerScene,
        targetW: Int,
        targetH: Int,
        destinationParent: File,
        tempFiles: MutableList<File>,
        preprocessedBySource: MutableMap<String, File>,
    ): TrailerScene? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()

        var retrievedBitmap: Bitmap? = null
        var isRetrievedOwned = false

        val sourceThumb = scene.source.thumbnail?.takeIf { !it.isRecycled }
        val cachedFramingKey = "${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}"
        val cachedFullKey = "full:${scene.source.id}"
        val cachedPreprocessedFile = preprocessedBySource[cachedFramingKey]?.takeIf { it.isFile && it.length() > 0L }
            ?: preprocessedBySource[cachedFullKey]?.takeIf { it.isFile && it.length() > 0L }
        try {
            val bitmap: Bitmap? = if (sourceThumb != null) {
                sourceThumb
            } else if (cachedPreprocessedFile != null) {
                retrievedBitmap = BitmapFactory.decodeFile(cachedPreprocessedFile.absolutePath)
                if (retrievedBitmap != null) {
                    isRetrievedOwned = true
                }
                retrievedBitmap
            } else if (scene.source.isImage) {
                if (!scene.source.file.exists() || !scene.source.file.isFile) {
                    throw java.io.FileNotFoundException("Source image not found: ${scene.source.file.absolutePath}")
                }
                if (!scene.source.file.canRead()) {
                    throw SecurityException("Source image not readable: ${scene.source.file.absolutePath}")
                }
                retrievedBitmap = runCatching { BitmapFactory.decodeFile(scene.source.file.absolutePath) }.getOrNull()
                if (retrievedBitmap != null) {
                    isRetrievedOwned = true
                }
                retrievedBitmap
            } else {
                if (!scene.source.file.exists() || !scene.source.file.isFile) {
                    throw java.io.FileNotFoundException("Source video not found: ${scene.source.file.absolutePath}")
                }
                if (!scene.source.file.canRead()) {
                    throw SecurityException("Source video not readable: ${scene.source.file.absolutePath}")
                }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(scene.source.file.absolutePath)
                    currentCoroutineContext().ensureActive()
                    val targetUs = (scene.start * 1_000_000).toLong().coerceAtLeast(0L)
                    val midUs = ((scene.start + scene.length * 0.5) * 1_000_000).toLong().coerceAtLeast(0L)
                    retrievedBitmap = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(midUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: retriever.getFrameAtTime(0L)
                    if (retrievedBitmap != null) {
                        isRetrievedOwned = true
                    }
                    retrievedBitmap
                } catch (e: Exception) {
                    if (e is CancellationException || e is SecurityException || e is java.io.IOException) {
                        throw e
                    }
                    null
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            if (bitmap == null) return@withContext null

            val stillFile = File(destinationParent, "recovered-still-${scene.source.id}-${UUID.randomUUID()}.jpg")
            tempFiles.add(stillFile)
            try {
                renderBitmapToJpeg(bitmap, targetW, targetH, stillFile, scene.fit, scene.focusX, scene.focusY)
            } catch (t: Throwable) {
                stillFile.delete()
                tempFiles.remove(stillFile)
                throw t
            }

            currentCoroutineContext().ensureActive()

            val framingKey = "${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}"
            preprocessedBySource[framingKey] = stillFile

            val recoveredSource = scene.source.copy(
                file = stillFile,
                isImage = true,
                width = targetW,
                height = targetH,
                thumbnail = sourceThumb,
            )
            scene.copy(
                source = recoveredSource,
                start = scene.start,
                length = scene.length,
                fit = scene.fit,
                focusX = scene.focusX,
                focusY = scene.focusY,
                sourceVolume = 0f,
                caption = scene.caption,
                actionStart = scene.actionStart,
                actionEnd = scene.actionEnd,
                adjustments = scene.adjustments,
                sceneKey = scene.sceneKey,
                manual = scene.manual,
                automationProtected = scene.automationProtected,
            )
        } finally {
            if (isRetrievedOwned && retrievedBitmap != null) {
                retrievedBitmap.recycle()
            }
        }
    }

    /**
     * The scene's caption as a full-frame transparent bitmap overlay, appended AFTER framing/photo motion so it stays
     * fixed to the output frame. Bitmaps are shared per identical caption within a chunk and never recycled here
     * (the GL pipeline may still sample them until the export ends).
     */
    private fun captionOverlayEffects(
        context: Context,
        caption: SceneCaption?,
        exportWidth: Int,
        exportHeight: Int,
        captionBitmaps: MutableMap<SceneCaption, Bitmap>,
    ): List<androidx.media3.common.Effect> {
        val normalized = caption?.normalizedOrNull() ?: return emptyList()
        val bitmap = captionBitmaps.getOrPut(normalized) {
            SceneCaptionRenderer.render(context, normalized, exportWidth, exportHeight) ?: return emptyList()
        }
        val overlay: androidx.media3.effect.TextureOverlay = androidx.media3.effect.BitmapOverlay.createStaticBitmapOverlay(bitmap)
        return listOf(androidx.media3.effect.OverlayEffect(com.google.common.collect.ImmutableList.of(overlay)))
    }

    private suspend fun buildVideoEditedMediaItem(
        context: Context,
        captionBitmaps: MutableMap<SceneCaption, Bitmap>,
        scene: TrailerScene,
        exportWidth: Int,
        exportHeight: Int,
        presentation: Presentation,
        random: Random,
        targetWidth: Int,
        targetHeight: Int,
        preprocessedBySource: MutableMap<String, File>,
        thumbnailFallbackKeys: MutableSet<String>,
        tempFiles: MutableList<File>,
        destinationParent: File,
        removeAudio: Boolean = false,
    ): EditedMediaItem {
        return if (scene.source.isImage) {
            val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
            val outputDuration = scene.length / rate
            val plan = TrailerPhotoMotionPlanner.planMotion(
                source = scene.source,
                fit = scene.fit,
                focusX = scene.focusX,
                focusY = scene.focusY,
                outputWidth = exportWidth,
                outputHeight = exportHeight,
                durationSeconds = outputDuration,
            )
            // Every photo (FIT, FILL and manual focus) renders the full photo through the planned camera path, the same
            // path the editor preview draws; FILL frames are clamped to the photo so no frame can letterbox.
            val fullPhotoKey = "full:${scene.source.id}"
            val preprocessedFile = preprocessedBySource.getOrPut(fullPhotoKey) {
                val tempFile = File(destinationParent, "preprocessed-full-${scene.source.id}-${UUID.randomUUID()}.jpg")
                tempFiles.add(tempFile)
                val usedFallback = try {
                    preprocessFullPhotoOrFallback(scene.source, tempFile)
                } catch (t: Throwable) {
                    tempFile.delete()
                    tempFiles.remove(tempFile)
                    throw t
                }
                if (usedFallback) {
                    thumbnailFallbackKeys.add(fullPhotoKey)
                }
                tempFile
            }
            val inputUri = Uri.fromFile(preprocessedFile)
            val durationUs = (outputDuration * 1_000_000).toLong()
            val plannedAspect = if (scene.source.width > 0 && scene.source.height > 0) {
                scene.source.width.toFloat() / scene.source.height
            } else {
                exportWidth.toFloat() / exportHeight
            }
            // The camera path renders straight into the export size (zoom/pan in photo coordinates, no later crop).
            val effectsList = listOf<androidx.media3.common.Effect>(
                PhotoMotionGlTransformation(plan, durationUs, exportWidth, exportHeight, plannedAspect),
            ) + TrailerAdvancedComposer.buildSceneEffects(context, scene, exportWidth, exportHeight, captionBitmaps)
            val input = MediaItem.Builder()
                .setUri(inputUri)
                .setImageDurationMs((outputDuration * 1000).toLong())
                .build()
            val builder = EditedMediaItem.Builder(input)
                .setDurationUs(durationUs)
                .setFrameRate(30)
                .setEffects(Effects(emptyList(), effectsList))
            builder.build()
        } else {
            val inputUri = Uri.fromFile(scene.source.file)
            val input = MediaItem.Builder().setUri(inputUri)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((scene.start * 1000).toLong())
                    .setEndPositionMs(((scene.start + scene.length) * 1000).toLong()).build()).build()
            val gain = scene.sourceVolume.coerceIn(0f, 2f)
            val audioEffects = if (abs(gain - 1f) < 0.005f) emptyList() else listOf<androidx.media3.common.audio.AudioProcessor>(SceneGainAudioProcessor(gain))
            val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
            // CompositionPlayer needs every item's duration. Media3 1.11.0 EditedMediaItem.durationUs is the ORIGINAL full media
            // duration (getClippedDuration = clipEnd - clipStart and requires clipEnd <= durationUs; speed is applied afterwards),
            // so it is the source's full length, never the clipped scene length or the rate-scaled output length.
            val clipEndUs = ((scene.start + scene.length) * 1000).toLong() * 1000L
            val fullDurationUs = maxOf(Math.round(scene.source.duration * 1_000_000.0), clipEndUs)
            val builder = EditedMediaItem.Builder(input).setFrameRate(30)
                .setDurationUs(fullDurationUs)
                .setEffects(Effects(
                    audioEffects,
                    sceneFramingVideoEffects(scene.fit, scene.focusX, scene.focusY, scene.source.width, scene.source.height, exportWidth, exportHeight) +
                        TrailerAdvancedComposer.buildSceneEffects(context, scene, exportWidth, exportHeight, captionBitmaps),
                ))
            if (rate != 1.0) {
                // Pitch is kept (as Mac's spectral time-stretch): SpeedParameters' default would resample and shift the pitch.
                builder.setSpeed(SpeedParameters(ConstantSpeedProvider(rate.toFloat()), true))
            }
            if (removeAudio) builder.setRemoveAudio(true)
            builder.build()
        }
    }

    private suspend fun buildMusicSequence(
        context: Context,
        scenes: List<TrailerScene>,
        copiedMusicFile: File,
        musicTrack: TrailerMusicTrack,
        musicPolicy: TrailerMusicPolicy,
        autoDucking: Boolean,
        musicGain: Float,
        totalVideoDurationSeconds: Double,
    ): EditedMediaItemSequence? {
        val musicDurationSeconds = musicTrack.durationSeconds
        val duckingTimeline = if (autoDucking) {
            val uniqueVideoSources = scenes.map { it.source }
                .filter { !it.isImage }
                .distinctBy { it.id }

            val envelopes = mutableMapOf<Int, FloatArray>()
            for (src in uniqueVideoSources) {
                val env = AudioAnalysisService.extractLoudnessEnvelope(
                    context = context,
                    uri = Uri.fromFile(src.file),
                    sourceDurationSeconds = src.duration,
                    binSeconds = 0.05,
                )
                envelopes[src.id] = env
            }
            AudioDuckingTimeline.buildWithLoudnessEnvelopes(
                scenes = scenes,
                envelopes = envelopes,
                binSeconds = 0.05,
                originalClipGain = 1.0f,
            )
        } else {
            AudioDuckingTimeline.constant()
        }

        fun makeMusicEffects(startOffsetSeconds: Double): Effects {
            val audioProcessors = listOf<androidx.media3.common.audio.AudioProcessor>(
                AutoDuckingAudioProcessor(
                    timeline = duckingTimeline,
                    outputStartOffsetSeconds = startOffsetSeconds,
                    autoDucking = autoDucking,
                    duckedGain = 0.18f * musicGain,
                    fullGain = 0.85f * musicGain,
                    constantGain = 0.85f * musicGain,
                )
            )
            return Effects(audioProcessors, emptyList())
        }

        val musicUri = Uri.fromFile(copiedMusicFile)
        val musicItems = mutableListOf<EditedMediaItem>()

        // 음악 길이 맞춤도 반복 재생이다: 영상이 음악×반복 횟수와 같으면 정확히 끝나고, 직접 편집으로 더 길어져도 음악이 끝까지 이어진다.
        if (musicPolicy != TrailerMusicPolicy.SINGLE) {
            var accumulated = 0.0
            while (accumulated < totalVideoDurationSeconds - 0.05) {
                val remaining = totalVideoDurationSeconds - accumulated
                val clipDuration = min(musicDurationSeconds, remaining)
                val input = MediaItem.Builder()
                    .setUri(musicUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(0L)
                            .setEndPositionMs((clipDuration * 1000).toLong())
                            .build()
                    ).build()
                val musicEffects = makeMusicEffects(startOffsetSeconds = accumulated)
                musicItems.add(
                    EditedMediaItem.Builder(input)
                        .setDurationUs(Math.round(clipDuration * 1_000_000.0))
                        .setEffects(musicEffects)
                        .build()
                )
                accumulated += clipDuration
            }
        } else {
            val clipDuration = min(musicDurationSeconds, totalVideoDurationSeconds)
            val input = MediaItem.Builder()
                .setUri(musicUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs((clipDuration * 1000).toLong())
                        .build()
                ).build()
            val musicEffects = makeMusicEffects(startOffsetSeconds = 0.0)
            musicItems.add(
                EditedMediaItem.Builder(input)
                    .setDurationUs(Math.round(clipDuration * 1_000_000.0))
                    .setEffects(musicEffects)
                    .build()
            )
        }

        return if (musicItems.isNotEmpty()) {
            EditedMediaItemSequence.withAudioFrom(musicItems)
        } else {
            null
        }
    }

    private suspend fun runTransformerExport(
        context: Context,
        composition: Composition,
        outputFile: File,
        statusText: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        onTransformerCreated: (Transformer) -> Unit = {},
        outputPixels: Long = 0L,
        outputSeconds: Double = Double.MAX_VALUE,
    ) {
        val scope = CoroutineScope(currentCoroutineContext())
        var poll: Job? = null
        suspendCancellableCoroutine<Unit> { continuation ->
            val transformerBuilder = Transformer.Builder(context)
            // 일부 기기(예: Fold6)의 H.264 인코더는 1080p 출력에 레벨 6.0을 기록하고, 같은 기기의 내장 플레이어가 그 영상을
            // 디코드하지 못해 외부 플레이어로 넘어간다. 1080p 이하는 레벨 4.1을 요청하고, 인코더가 지원하지 않으면 기본 폴백을 따른다.
            if (outputPixels in 1..H264_LEVEL_41_MAX_PIXELS) {
                transformerBuilder.setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder()
                                .setEncodingProfileLevel(
                                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                                    MediaCodecInfo.CodecProfileLevel.AVCLevel41,
                                )
                                .build(),
                        )
                        .build(),
                )
            }
            // 출력이 AAC 프라이밍(1600샘플)보다 짧으면 Media3 인앱 muxer가 오디오 트랙에 음수 edit-list 길이를 기록해(API 29 확인: 1프레임 청크)
            // 다음 단계 mp4 파서가 읽지 못한다. 이런 아주 짧은 출력만 오디오 시작 음수 타임스탬프(프라이밍)를 0으로 고정해 쓴다.
            // 그보다 긴 출력도 AAC 인코더가 EOS에서 마지막 실제 샘플(지연+프레임)을 내보내지 않으므로, 처리 전용 무음 꼬리로 밀어내고 정규 끝 이후 패킷만 버린다.
            val shortOutput = outputSeconds < FRAMEWORK_MUXER_BELOW_SECONDS
            val boundedOutput = outputSeconds < Double.MAX_VALUE
            val diagnosticPath = if (boundedOutput) stageDiagnosticPrefix?.let { "${it}pass${++stageDiagnosticPass}" } else null
            if (boundedOutput) {
                transformerBuilder.setMuxerFactory(ShortOutputMuxerFactory(Math.round(outputSeconds * 1_000_000.0), diagnosticPath, shortOutput))
            }
            // Every bounded output gets the processing-only pad (the muxer drops the packets past the canonical end); the user's own composition effects stay.
            val exportComposition = if (boundedOutput) {
                Composition.Builder(composition.sequences)
                    .setHdrMode(composition.hdrMode)
                    .setVideoCompositorSettings(composition.videoCompositorSettings)
                    .setTransmuxAudio(composition.transmuxAudio)
                    .setTransmuxVideo(composition.transmuxVideo)
                    .experimentalSetForceAudioTrack(composition.forceAudioTrack)
                    .experimentalSetRetainHdrFromUltraHdrImage(composition.retainHdrFromUltraHdrImage)
                    .setEffects(Effects(
                        listOf<androidx.media3.common.audio.AudioProcessor>(
                            TrailingSilenceAudioProcessor(ENCODER_FLUSH_PAD_FRAMES, ENCODER_FLUSH_PAD_SECONDS, diagnosticPath?.let { PcmTrace("$it-pad1") }),
                        ) + composition.effects.audioProcessors,
                        composition.effects.videoEffects,
                    ))
                    .build()
            } else composition
            diagnosticPath?.let { path ->
                runCatching {
                    File("$path-chain.txt").writeText(
                        "bounded=$boundedOutput short=$shortOutput composition.audioProcessors=" +
                            exportComposition.effects.audioProcessors.joinToString(",") { it.javaClass.simpleName } + "\n",
                    )
                }
            }
            val instance = transformerBuilder
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        poll?.cancel()
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        poll?.cancel()
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }).build()

            onTransformerCreated(instance)

            continuation.invokeOnCancellation {
                poll?.cancel()
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    instance.cancel()
                } else {
                    Handler(Looper.getMainLooper()).post { instance.cancel() }
                }
            }

            if (!continuation.isActive) return@suspendCancellableCoroutine

            safeProgress(statusText, progressStart)

            try {
                instance.start(exportComposition, outputFile.absolutePath)
            } catch (e: Throwable) {
                poll?.cancel()
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    instance.cancel()
                } else {
                    Handler(Looper.getMainLooper()).post { instance.cancel() }
                }
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                } else {
                    throw e
                }
                return@suspendCancellableCoroutine
            }

            poll = scope.launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (instance.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val fraction = (holder.progress / 100f).coerceIn(0f, 1f)
                        val current = progressStart + fraction * (progressEnd - progressStart)
                        safeProgress(statusText, current)
                    }
                    delay(250)
                }
            }
        }
    }

    private suspend fun exportChunk(
        context: Context,
        scenes: List<TrailerScene>,
        exportWidth: Int,
        exportHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        outputFile: File,
        statusText: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        random: Random,
        preprocessedBySource: MutableMap<String, File>,
        thumbnailFallbackKeys: MutableSet<String>,
        tempFiles: MutableList<File>,
        destinationParent: File,
        onTransformerCreated: (Transformer) -> Unit,
    ) {
        val presentation = Presentation.createForWidthAndHeight(exportWidth, exportHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)
        val captionBitmaps = mutableMapOf<SceneCaption, Bitmap>()
        // 아주 짧은 장면의 정확한 오디오 창(장면 인덱스 → WAV). 없으면 기존 클립 오디오 경로를 그대로 쓴다.
        val exactAudio = withContext(Dispatchers.IO) {
            val map = HashMap<Int, ExactAudioWindow>()
            scenes.forEachIndexed { index, scene ->
                prepareExactAudioWindow(scene, destinationParent, tempFiles)?.let { map[index] = it }
            }
            map
        }
        val edited = withContext(Dispatchers.IO) {
            scenes.mapIndexed { sceneIndex, scene ->
                buildVideoEditedMediaItem(
                    context = context,
                    captionBitmaps = captionBitmaps,
                    scene = scene,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    presentation = presentation,
                    random = random,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    preprocessedBySource = preprocessedBySource,
                    thumbnailFallbackKeys = thumbnailFallbackKeys,
                    tempFiles = tempFiles,
                    destinationParent = destinationParent,
                    removeAudio = sceneIndex in exactAudio,
                )
            }
        }
        currentCoroutineContext().ensureActive()
        val videoSequence = EditedMediaItemSequence.withAudioAndVideoFrom(edited)
        val exactAudioSequences = exactAudio.entries.sortedBy { it.key }.map { (index, window) ->
            val scene = scenes[index]
            val gain = scene.sourceVolume.coerceIn(0f, 2f)
            val audioEffects = if (abs(gain - 1f) < 0.005f) emptyList() else listOf<androidx.media3.common.audio.AudioProcessor>(SceneGainAudioProcessor(gain))
            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(window.file)))
                .setRemoveVideo(true)
                .setDurationUs(window.durationUs)
                .setEffects(Effects(audioEffects, emptyList()))
                .build()
            val prefixUs = Math.round(scenes.take(index).sumOf { sceneOutputDuration(it) } * 1_000_000.0)
            EditedMediaItemSequence.Builder(setOf(androidx.media3.common.C.TRACK_TYPE_AUDIO))
                .apply { if (prefixUs > 0L) addGap(prefixUs) }
                .addItem(item)
                .build()
        }
        val composition = Composition.Builder(listOf(videoSequence) + exactAudioSequences)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        currentCoroutineContext().ensureActive()
        runTransformerExport(
            context = context,
            composition = composition,
            outputFile = outputFile,
            statusText = statusText,
            safeProgress = safeProgress,
            progressStart = progressStart,
            progressEnd = progressEnd,
            onTransformerCreated = onTransformerCreated,
            outputPixels = exportWidth.toLong() * exportHeight,
            outputSeconds = scenes.sumOf { sceneOutputDuration(it) },
        )
        if (scenes.size == 1) exactAudio[0]?.let { exactAudioByChunk[outputFile.absolutePath] = it }
    }

    private fun sceneOutputDuration(scene: TrailerScene): Double {
        val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
        return scene.length / rate
    }

    private fun isManualScene(scene: TrailerScene): Boolean {
        val adj = scene.adjustments
        val hasManualAdjustments = adj != null && (
            abs(adj.rate - 1.0) >= 0.005 ||
                (adj.transition?.dissolveDuration ?: 0.0) > 0.0 ||
                adj.keyframes.isNotEmpty() ||
                adj.cues.isNotEmpty() ||
                adj.color?.isNeutral == false
            )
        return scene.automationProtected ||
            scene.manual.hasManualContent() ||
            scene.caption != null ||
            hasManualAdjustments
    }

    private suspend fun exportGroupWithRecovery(
        context: Context,
        scenes: List<TrailerScene>,
        exportWidth: Int,
        exportHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        statusPrefix: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        random: Random,
        preprocessedBySource: MutableMap<String, File>,
        thumbnailFallbackKeys: MutableSet<String>,
        tempFiles: MutableList<File>,
        destinationParent: File,
        depth: Int = 0,
        maxDepth: Int = 8,
        clipOffset: Int,
        totalClips: Int,
        onTransformerCreated: (Transformer) -> Unit,
    ): GroupExportResult {
        currentCoroutineContext().ensureActive()

        val chunkFile = File(destinationParent, "chunk-${UUID.randomUUID()}.mp4")
        tempFiles.add(chunkFile)

        val currentClipEnd = clipOffset + scenes.size
        val statusText = "$statusPrefix (${clipOffset + 1}~$currentClipEnd/$totalClips 클립 준비 중)"

        try {
            exportChunk(
                context = context,
                scenes = scenes,
                exportWidth = exportWidth,
                exportHeight = exportHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                outputFile = chunkFile,
                statusText = statusText,
                safeProgress = safeProgress,
                progressStart = progressStart,
                progressEnd = progressEnd,
                random = random,
                preprocessedBySource = preprocessedBySource,
                thumbnailFallbackKeys = thumbnailFallbackKeys,
                tempFiles = tempFiles,
                destinationParent = destinationParent,
                onTransformerCreated = onTransformerCreated,
            )
            val fallbackRecoveries = scenes.count { scene ->
                scene.source.isImage && (
                    thumbnailFallbackKeys.contains("${scene.source.id}:${scene.fit}:${scene.focusX}:${scene.focusY}") ||
                    thumbnailFallbackKeys.contains("full:${scene.source.id}")
                )
            }
            return GroupExportResult(
                files = listOf(chunkFile),
                fileDurations = listOf(scenes.sumOf { sceneOutputDuration(it) }),
                effectiveScenes = scenes,
                recoveredCount = fallbackRecoveries,
                excludedCount = 0,
            )
        } catch (e: Throwable) {
            chunkFile.delete()
            tempFiles.remove(chunkFile)
            if (e is CancellationException) {
                throw e
            }
            if (e is SecurityException || e is java.io.IOException || !isMediaDecodingFailure(e)) {
                throw e
            }
            if (depth >= maxDepth) {
                throw e
            }

            if (scenes.size > 1) {
                val mid = scenes.size / 2
                val left = scenes.subList(0, mid)
                val right = scenes.subList(mid, scenes.size)
                val midProgress = progressStart + (progressEnd - progressStart) * (mid.toFloat() / scenes.size)

                val leftResult = exportGroupWithRecovery(
                    context = context,
                    scenes = left,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    statusPrefix = statusPrefix,
                    safeProgress = safeProgress,
                    progressStart = progressStart,
                    progressEnd = midProgress,
                    random = random,
                    preprocessedBySource = preprocessedBySource,
                    thumbnailFallbackKeys = thumbnailFallbackKeys,
                    tempFiles = tempFiles,
                    destinationParent = destinationParent,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    clipOffset = clipOffset,
                    totalClips = totalClips,
                    onTransformerCreated = onTransformerCreated,
                )

                val rightResult = exportGroupWithRecovery(
                    context = context,
                    scenes = right,
                    exportWidth = exportWidth,
                    exportHeight = exportHeight,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    statusPrefix = statusPrefix,
                    safeProgress = safeProgress,
                    progressStart = midProgress,
                    progressEnd = progressEnd,
                    random = random,
                    preprocessedBySource = preprocessedBySource,
                    thumbnailFallbackKeys = thumbnailFallbackKeys,
                    tempFiles = tempFiles,
                    destinationParent = destinationParent,
                    depth = depth + 1,
                    maxDepth = maxDepth,
                    clipOffset = clipOffset + mid,
                    totalClips = totalClips,
                    onTransformerCreated = onTransformerCreated,
                )

                return GroupExportResult(
                    files = leftResult.files + rightResult.files,
                    fileDurations = leftResult.fileDurations + rightResult.fileDurations,
                    effectiveScenes = leftResult.effectiveScenes + rightResult.effectiveScenes,
                    recoveredCount = leftResult.recoveredCount + rightResult.recoveredCount,
                    excludedCount = leftResult.excludedCount + rightResult.excludedCount,
                )
            } else {
                val badScene = scenes[0]
                val clipNum = clipOffset + 1
                safeProgress("손상된 미디어 복구 중 ($clipNum/$totalClips 클립)…", progressStart)

                val recoveredScene = try {
                    createRecoveredStillScene(
                        scene = badScene,
                        targetW = targetWidth,
                        targetH = targetHeight,
                        destinationParent = destinationParent,
                        tempFiles = tempFiles,
                        preprocessedBySource = preprocessedBySource,
                    )
                } catch (stillErr: Throwable) {
                    if (stillErr is CancellationException) throw stillErr
                    if (stillErr is SecurityException || stillErr is java.io.IOException || !isMediaDecodingFailure(stillErr)) {
                        throw stillErr
                    }
                    null
                }

                if (recoveredScene == null) {
                    if (isManualScene(badScene)) {
                        throw IllegalStateException("직접 편집한 장면(클립 $clipNum)의 미디어를 읽을 수 없어 영상을 완성하지 못했습니다. 원본 파일을 확인해 주세요.")
                    }
                    return GroupExportResult(
                        files = emptyList(),
                        fileDurations = emptyList(),
                        effectiveScenes = emptyList(),
                        recoveredCount = 0,
                        excludedCount = 1,
                    )
                }

                val recoveredChunkFile = File(destinationParent, "chunk-recovered-${UUID.randomUUID()}.mp4")
                tempFiles.add(recoveredChunkFile)

                try {
                    exportChunk(
                        context = context,
                        scenes = listOf(recoveredScene),
                        exportWidth = exportWidth,
                        exportHeight = exportHeight,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        outputFile = recoveredChunkFile,
                        statusText = "복구된 스틸로 저장 중 ($clipNum/$totalClips 클립)",
                        safeProgress = safeProgress,
                        progressStart = progressStart,
                        progressEnd = progressEnd,
                        random = random,
                        preprocessedBySource = preprocessedBySource,
                        thumbnailFallbackKeys = thumbnailFallbackKeys,
                        tempFiles = tempFiles,
                        destinationParent = destinationParent,
                        onTransformerCreated = onTransformerCreated,
                    )
                    return GroupExportResult(
                        files = listOf(recoveredChunkFile),
                        fileDurations = listOf(sceneOutputDuration(recoveredScene)),
                        effectiveScenes = listOf(recoveredScene),
                        recoveredCount = 1,
                        excludedCount = 0,
                    )
                } catch (recoveryError: Throwable) {
                    recoveredChunkFile.delete()
                    tempFiles.remove(recoveredChunkFile)
                    recoveredScene.source.file.delete()
                    tempFiles.remove(recoveredScene.source.file)
                    preprocessedBySource.remove("${badScene.source.id}:${badScene.fit}:${badScene.focusX}:${badScene.focusY}")
                    if (recoveryError is CancellationException) throw recoveryError
                    if (recoveryError is SecurityException || recoveryError is java.io.IOException || !isMediaDecodingFailure(recoveryError)) {
                        throw recoveryError
                    }
                    if (isManualScene(badScene)) {
                        throw IllegalStateException("직접 편집한 장면(클립 $clipNum)의 미디어를 복구하지 못했습니다. 원본 파일을 확인해 주세요.", recoveryError)
                    }
                    return GroupExportResult(
                        files = emptyList(),
                        fileDurations = emptyList(),
                        effectiveScenes = emptyList(),
                        recoveredCount = 0,
                        excludedCount = 1,
                    )
                }
            }
        }
    }

    private suspend fun exportTitleCardChunk(
        context: Context,
        cardBitmap: Bitmap,
        durationSeconds: Double,
        exportWidth: Int,
        exportHeight: Int,
        outputFile: File,
        destinationParent: File,
        tempFiles: MutableList<File>,
        statusText: String,
        safeProgress: (status: String, progress: Float?) -> Unit,
        progressStart: Float,
        progressEnd: Float,
        onTransformerCreated: (Transformer) -> Unit,
    ) {
        val tempJpeg = File(destinationParent, "titlecard-frame-${UUID.randomUUID()}.jpg")
        tempFiles.add(tempJpeg)
        withContext(Dispatchers.IO) {
            FileOutputStream(tempJpeg).use { fos ->
                cardBitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
            }
        }
        val durationUs = (durationSeconds * 1_000_000.0).toLong()
        val presentation = Presentation.createForWidthAndHeight(exportWidth, exportHeight, Presentation.LAYOUT_SCALE_TO_FIT)
        val input = MediaItem.Builder()
            .setUri(Uri.fromFile(tempJpeg))
            .setImageDurationMs((durationSeconds * 1000.0).toLong())
            .build()
        val editedItem = EditedMediaItem.Builder(input)
            .setDurationUs(durationUs)
            .setFrameRate(30)
            .setEffects(Effects(emptyList(), listOf(presentation)))
            .build()
        val sequence = EditedMediaItemSequence.withAudioAndVideoFrom(listOf(editedItem))
        val composition = Composition.Builder(listOf(sequence))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()
        runTransformerExport(
            context = context,
            composition = composition,
            outputFile = outputFile,
            statusText = statusText,
            safeProgress = safeProgress,
            progressStart = progressStart,
            progressEnd = progressEnd,
            onTransformerCreated = onTransformerCreated,
            outputPixels = exportWidth.toLong() * exportHeight,
        )
    }

    /**
     * 장면 끝 시각을 30fps 프레임 격자에 누적 반올림한다. 인코더는 장면마다 프레임 단위로 길이를 맞추므로, 계획 길이를 그대로 쓰면
     * 장면 수만큼 오차가 쌓여 영상 끝·비트 전환·음악 길이에서 벗어난다(Media3 이미지 프레임 수는 장면마다 Math.round(fps×길이)).
     * 누적 반올림은 모든 전환을 계획 시각의 반 프레임 안에 둔다. 영상 장면은 시작 위치부터 원본 끝을 넘지 않도록 내림해 남은 오차를 다음 장면이 흡수한다.
     */
    private fun snapSceneLengthsToFrames(scenes: List<TrailerScene>): List<TrailerScene> {
        var plannedEndOutput = 0.0
        var snappedFrames = 0L
        return scenes.map { scene ->
            val rate = scene.adjustments?.rate?.takeIf { it.isFinite() && it in 0.25..4.0 } ?: 1.0
            val outputDuration = scene.length / rate
            plannedEndOutput += outputDuration
            var endFrame = Math.round(plannedEndOutput * OUTPUT_FPS).coerceAtLeast(snappedFrames + 1)
            val source = scene.source
            if (!source.isImage && source.duration.isFinite() && source.duration > 0.0) {
                val maxOutputFrames = Math.floor(((source.duration - scene.start) / rate) * OUTPUT_FPS + 1e-6).toLong()
                if (endFrame - snappedFrames > maxOutputFrames) endFrame = snappedFrames + maxOutputFrames.coerceAtLeast(1)
            }
            val snappedOutputDuration = (endFrame - snappedFrames) / OUTPUT_FPS
            snappedFrames = endFrame
            val snappedSourceLength = snappedOutputDuration * rate
            if (abs(snappedSourceLength - scene.length) < 1e-9) scene else scene.copy(length = snappedSourceLength)
        }
    }

    suspend fun prepareCompositionForPreview(
        context: Context,
        scenes: List<TrailerScene>,
        format: TrailerFormat,
        destinationParent: File = context.cacheDir,
        musicTrack: TrailerMusicTrack? = null,
        musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        musicFitCount: Int = 1,
        autoDucking: Boolean = true,
        aspectSources: List<TrailerSource> = scenes.map { it.source },
        musicVolume: Float = 1f,
        introCard: TrailerTitleCard? = null,
        outroCard: TrailerTitleCard? = null,
        cardBackgroundSources: List<TrailerSource> = scenes.map { it.source },
        voiceovers: List<TrailerVoiceoverTake> = emptyList(),
        projectDirectory: File? = null,
        progressEndFraction: Float = 1.0f,
        // Preview only (export keeps the defaults): healthy original videos feed the same composition builder directly,
        // clips that still need an intermediate (stills, recovery) come from the leased per-clip cache at a bounded resolution.
        directVideoInputs: Boolean = false,
        // Preview only: stable scene keys (scene.sceneKey, as buildClipInputs assigns) whose original failed to decode;
        // only these clips take the intermediate/recovery route, every other healthy video stays a direct original.
        recoverySceneKeys: Set<Long> = emptySet(),
        previewMaxLongSide: Int? = null,
        useClipCache: Boolean = false,
        onTransformerCreated: (Transformer) -> Unit = {},
        progress: (status: String, progress: Float?) -> Unit = { _, _ -> },
    ): TrailerAdvancedComposer.BuiltTimelineComposition = withContext(Dispatchers.Main.immediate) {
        require(scenes.isNotEmpty()) { "이 장면 길이로 만들 수 있는 영상이 없습니다. 장면 길이를 줄여 주세요." }
        val framedScenes = snapSceneLengthsToFrames(scenes)
        val totalClips = framedScenes.size
        val random = Random()
        val (fullWidth, fullHeight) = format.resolveDimensions(aspectSources)
        // Same aspect at a smaller even size; every effect, caption and card is rendered for this size consistently.
        val previewScale = previewMaxLongSide?.let { limit -> (limit.toDouble() / max(fullWidth, fullHeight)).coerceAtMost(1.0) } ?: 1.0
        fun scaledEven(v: Int) = if (previewScale >= 1.0) v else ((kotlin.math.round(v * previewScale) / 2.0).toInt() * 2).coerceAtLeast(2)
        val exportWidth = scaledEven(fullWidth)
        val exportHeight = scaledEven(fullHeight)
        val musicGain = musicVolume.coerceIn(0f, 2f)

        val targetWidth = ((kotlin.math.round(exportWidth * 0.60) / 2.0).toInt() * 2).coerceAtLeast(2)
        val targetHeight = ((kotlin.math.round(exportHeight * 0.60) / 2.0).toInt() * 2).coerceAtLeast(2)

        val taskOwnedTempFiles = mutableListOf<File>()
        val preprocessedBySource = mutableMapOf<String, File>()
        val thumbnailFallbackKeys = mutableSetOf<String>()
        val preflightedVoiceovers = TrailerAdvancedComposer.preflightVoiceovers(voiceovers, projectDirectory)

        var lastReportedProgress = 0f
        fun safeProgress(status: String, p: Float?) {
            if (p == null) {
                progress(status, null)
            } else {
                val monotonic = maxOf(lastReportedProgress, p.coerceIn(0f, 1f))
                lastReportedProgress = monotonic
                progress(status, monotonic)
            }
        }

        val copiedMusicFile: File? = if (musicTrack != null) {
            try {
                require(musicTrack.file.isFile) { "선택한 음악 파일을 찾을 수 없습니다. 음악을 다시 선택해 주세요." }
                require(musicTrack.durationSeconds > 0.0) { "선택한 음악의 길이를 확인할 수 없습니다. 음악을 다시 선택해 주세요." }
                withContext(Dispatchers.IO) {
                    val sourceExt = musicTrack.file.extension.takeIf { it.isNotBlank() && it.all(Char::isLetterOrDigit) } ?: "tmp"
                    val copy = File(destinationParent, "music-${UUID.randomUUID()}.$sourceExt")
                    taskOwnedTempFiles.add(copy)
                    musicTrack.file.copyTo(copy, overwrite = true)
                    copy
                }
            } catch (e: Exception) {
                taskOwnedTempFiles.forEach { it.delete() }
                throw e
            }
        } else null

        var currentTransformer: Transformer? = null
        var handoverSucceeded = false
        val leasedCacheFiles = mutableListOf<File>()
        val directCaptionBitmaps = mutableMapOf<SceneCaption, Bitmap>()
        // Position in effectiveScenes -> direct original item (several clips may share one source file).
        val directItems = mutableMapOf<Int, EditedMediaItem>()
        val directPresentation = Presentation.createForWidthAndHeight(exportWidth, exportHeight, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)

        try {
            val groups = framedScenes.chunked(1)
            val totalGroups = groups.size
            val introCardEnabled = introCard?.enabled == true
            val outroCardEnabled = outroCard?.enabled == true
            val chunkPhaseEnd = if (!introCardEnabled && !outroCardEnabled) {
                progressEndFraction
            } else {
                progressEndFraction * 0.9f
            }

            val intermediateFiles = mutableListOf<File>()
            val effectiveScenes = mutableListOf<TrailerScene>()
            // intermediateFiles index -> exact audio window of a very short clip (see needsExactAudioWindow)
            val exactAudioByIndex = mutableMapOf<Int, TrailerAdvancedComposer.ExactClipAudio>()
            var clipOffset = 0

            var totalRecoveredScenes = 0
            var totalExcludedScenes = 0

            for ((groupIndex, groupScenes) in groups.withIndex()) {
                currentCoroutineContext().ensureActive()
                val groupStartProgress = (groupIndex.toFloat() / totalGroups) * chunkPhaseEnd
                val groupEndProgress = ((groupIndex + 1).toFloat() / totalGroups) * chunkPhaseEnd

                val groupScene = groupScenes.single()
                val cacheDir = if (useClipCache) PreviewClipCache.dir(destinationParent) else null
                // A cached chunk would not carry its exact audio window, so such clips always take the fresh route.
                val cacheFile = cacheDir?.takeIf { !needsExactAudioWindow(groupScene) }?.let { File(it, PreviewClipCache.keyOf(groupScene, exportWidth, exportHeight) + ".mp4") }
                val stableSceneKey = groupScene.sceneKey ?: (groupIndex + 1).toLong()
                // A very short direct clip: its audio is the exact PCM window (task-owned WAV), the original's own audio is dropped.
                var directExact: ExactAudioWindow? = null
                val groupResult = if (directVideoInputs && !groupScene.source.isImage && stableSceneKey !in recoverySceneKeys) {
                    // Healthy original: same clipping/rate/effects as the intermediate would bake, played by the one composition.
                    directExact = withContext(Dispatchers.IO) { prepareExactAudioWindow(groupScene, destinationParent, taskOwnedTempFiles) }
                    val item = withContext(Dispatchers.IO) {
                        buildVideoEditedMediaItem(
                            context = context,
                            captionBitmaps = directCaptionBitmaps,
                            scene = groupScene,
                            exportWidth = exportWidth,
                            exportHeight = exportHeight,
                            presentation = directPresentation,
                            random = random,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            preprocessedBySource = preprocessedBySource,
                            thumbnailFallbackKeys = thumbnailFallbackKeys,
                            tempFiles = taskOwnedTempFiles,
                            destinationParent = destinationParent,
                            removeAudio = directExact != null,
                        )
                    }
                    directItems[intermediateFiles.size] = item
                    GroupExportResult(
                        files = listOf(groupScene.source.file),
                        fileDurations = listOf(sceneOutputDuration(groupScene)),
                        effectiveScenes = groupScenes,
                        recoveredCount = 0,
                        excludedCount = 0,
                    )
                } else if (cacheFile != null && PreviewClipCache.lease(cacheFile)) {
                    leasedCacheFiles.add(cacheFile)
                    safeProgress("클립 준비 중 (${clipOffset + 1}/$totalClips 클립 재사용)", groupEndProgress)
                    GroupExportResult(
                        files = listOf(cacheFile),
                        fileDurations = listOf(sceneOutputDuration(groupScene)),
                        effectiveScenes = groupScenes,
                        recoveredCount = 0,
                        excludedCount = 0,
                    )
                } else {
                    val fresh = exportGroupWithRecovery(
                        context = context,
                        scenes = groupScenes,
                        exportWidth = exportWidth,
                        exportHeight = exportHeight,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        statusPrefix = "클립 준비 중",
                        safeProgress = ::safeProgress,
                        progressStart = groupStartProgress,
                        progressEnd = groupEndProgress,
                        random = random,
                        preprocessedBySource = preprocessedBySource,
                        thumbnailFallbackKeys = thumbnailFallbackKeys,
                        tempFiles = taskOwnedTempFiles,
                        destinationParent = destinationParent,
                        depth = 0,
                        maxDepth = 8,
                        clipOffset = clipOffset,
                        totalClips = totalClips,
                        onTransformerCreated = {
                            currentTransformer = it
                            onTransformerCreated(it)
                        },
                    )
                    // A clean (not recovered/excluded) single clip moves into the cache; the cache then owns the file, this task only leases it.
                    val produced = fresh.files.singleOrNull()
                    if (cacheFile != null && produced != null && fresh.recoveredCount == 0 && fresh.excludedCount == 0 &&
                        fresh.effectiveScenes.size == 1 && PreviewClipCache.adopt(produced, cacheFile)
                    ) {
                        taskOwnedTempFiles.remove(produced)
                        leasedCacheFiles.add(cacheFile)
                        GroupExportResult(
                            files = listOf(cacheFile),
                            fileDurations = fresh.fileDurations,
                            effectiveScenes = fresh.effectiveScenes,
                            recoveredCount = 0,
                            excludedCount = 0,
                        )
                    } else fresh
                }
                groupResult.files.forEachIndexed { i, produced ->
                    val exact = (directExact ?: exactAudioByChunk.remove(produced.absolutePath)) ?: return@forEachIndexed
                    val gain = groupResult.effectiveScenes.singleOrNull()?.sourceVolume?.coerceIn(0f, 2f) ?: 1f
                    exactAudioByIndex[intermediateFiles.size + i] = TrailerAdvancedComposer.ExactClipAudio(exact.file, exact.durationUs, gain)
                }
                intermediateFiles.addAll(groupResult.files)
                effectiveScenes.addAll(groupResult.effectiveScenes)
                totalRecoveredScenes += groupResult.recoveredCount
                totalExcludedScenes += groupResult.excludedCount
                clipOffset += groupScenes.size
            }

            currentCoroutineContext().ensureActive()

            if (effectiveScenes.isEmpty() || intermediateFiles.isEmpty() || effectiveScenes.size != intermediateFiles.size) {
                throw IllegalStateException("내보낼 수 있는 유효한 장면이 없습니다.")
            }

            var introChunkFile: File? = null
            var outroChunkFile: File? = null

            val firstIncludedScene = effectiveScenes.firstOrNull()
            val lastIncludedScene = effectiveScenes.lastOrNull()
            val bodyCaptionStyle = TrailerTitleCardRenderer.bodyCaptionOf(effectiveScenes.map { it.caption })

            if (introCardEnabled && introCard != null) {
                val introDur = introCard.clampedDuration()
                val introChosen = introCard.resolveBackgroundSource(cardBackgroundSources)
                val introBgBitmap = if (introChosen != null) {
                    withContext(Dispatchers.IO) {
                        loadFrame(
                            file = introChosen.file,
                            isImage = introChosen.isImage,
                            atSeconds = introCard.clampedBackgroundTimestamp(introChosen),
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else if (introCard.usesFirstOrLastImage && firstIncludedScene != null) {
                    withContext(Dispatchers.IO) {
                        loadFrame(
                            file = firstIncludedScene.source.file,
                            isImage = firstIncludedScene.source.isImage,
                            atSeconds = firstIncludedScene.start,
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else null

                val introBitmap = withContext(Dispatchers.Default) {
                    TrailerTitleCardRenderer.renderCardBitmap(
                        context = context,
                        card = introCard,
                        sourceBitmap = introBgBitmap,
                        width = exportWidth,
                        height = exportHeight,
                        bodyCaption = bodyCaptionStyle,
                    )
                }
                introBgBitmap?.recycle()

                if (introBitmap != null) {
                    val introFile = File(destinationParent, "chunk-intro-${UUID.randomUUID()}.mp4")
                    taskOwnedTempFiles.add(introFile)
                    try {
                        exportTitleCardChunk(
                            context = context,
                            cardBitmap = introBitmap,
                            durationSeconds = introDur,
                            exportWidth = exportWidth,
                            exportHeight = exportHeight,
                            outputFile = introFile,
                            destinationParent = destinationParent,
                            tempFiles = taskOwnedTempFiles,
                            statusText = "시작 화면 생성 중…",
                            safeProgress = ::safeProgress,
                            progressStart = chunkPhaseEnd,
                            progressEnd = chunkPhaseEnd + (progressEndFraction - chunkPhaseEnd) * (if (outroCardEnabled) 0.5f else 1.0f),
                            onTransformerCreated = {
                                currentTransformer = it
                                onTransformerCreated(it)
                            },
                        )
                        introChunkFile = introFile
                    } finally {
                        introBitmap.recycle()
                    }
                }
            }

            if (outroCardEnabled && outroCard != null) {
                val outroDur = outroCard.clampedDuration()
                val outroChosen = outroCard.resolveBackgroundSource(cardBackgroundSources)
                val outroBgBitmap = if (outroChosen != null) {
                    withContext(Dispatchers.IO) {
                        loadFrame(
                            file = outroChosen.file,
                            isImage = outroChosen.isImage,
                            atSeconds = outroCard.clampedBackgroundTimestamp(outroChosen),
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else if (outroCard.usesFirstOrLastImage && lastIncludedScene != null) {
                    withContext(Dispatchers.IO) {
                        val atTime = maxOf(lastIncludedScene.start, lastIncludedScene.start + lastIncludedScene.length - 0.5)
                        loadFrame(
                            file = lastIncludedScene.source.file,
                            isImage = lastIncludedScene.source.isImage,
                            atSeconds = atTime,
                            maxDimension = maxOf(exportWidth, exportHeight),
                            exact = true,
                        )
                    }
                } else null

                val outroBitmap = withContext(Dispatchers.Default) {
                    TrailerTitleCardRenderer.renderCardBitmap(
                        context = context,
                        card = outroCard,
                        sourceBitmap = outroBgBitmap,
                        width = exportWidth,
                        height = exportHeight,
                        bodyCaption = bodyCaptionStyle,
                    )
                }
                outroBgBitmap?.recycle()

                if (outroBitmap != null) {
                    val outroFile = File(destinationParent, "chunk-outro-${UUID.randomUUID()}.mp4")
                    taskOwnedTempFiles.add(outroFile)
                    try {
                        exportTitleCardChunk(
                            context = context,
                            cardBitmap = outroBitmap,
                            durationSeconds = outroDur,
                            exportWidth = exportWidth,
                            exportHeight = exportHeight,
                            outputFile = outroFile,
                            destinationParent = destinationParent,
                            tempFiles = taskOwnedTempFiles,
                            statusText = "마지막 화면 생성 중…",
                            safeProgress = ::safeProgress,
                            progressStart = chunkPhaseEnd + (progressEndFraction - chunkPhaseEnd) * (if (introCardEnabled) 0.5f else 0.0f),
                            progressEnd = progressEndFraction,
                            onTransformerCreated = {
                                currentTransformer = it
                                onTransformerCreated(it)
                            },
                        )
                        outroChunkFile = outroFile
                    } finally {
                        outroBitmap.recycle()
                    }
                }
            }

            val introDuration = if (introChunkFile != null && introCard != null) introCard.clampedDuration() else 0.0
            val outroDuration = if (outroChunkFile != null && outroCard != null) outroCard.clampedDuration() else 0.0
            val allIntermediateFiles = listOfNotNull(introChunkFile) + intermediateFiles + listOfNotNull(outroChunkFile)
            val clipInputs = TrailerAdvancedComposer.buildClipInputs(
                scenes = effectiveScenes,
                introDurationSeconds = introDuration,
                outroDurationSeconds = outroDuration,
            )
            val allKeyframes = buildList<List<TrailerTransformKeyframe>> {
                if (introChunkFile != null && introDuration > 0.0) add(emptyList())
                for (scene in effectiveScenes) {
                    add(scene.adjustments?.keyframes.orEmpty())
                }
                if (outroChunkFile != null && outroDuration > 0.0) add(emptyList())
            }
            check(allIntermediateFiles.size == clipInputs.size && allKeyframes.size == clipInputs.size) {
                "예고편 클립 준비 정보가 맞지 않습니다."
            }
            val directOffset = if (introChunkFile != null) 1 else 0
            val directIndices = directItems.keys.map { it + directOffset }.toSet()
            val preparedClips = clipInputs.indices.map { idx ->
                TrailerAdvancedComposer.PreparedClipSpec(
                    file = allIntermediateFiles[idx],
                    clipInput = clipInputs[idx],
                    keyframes = allKeyframes[idx],
                    // A direct original is never an intermediate (cards and cached chunks are separate files).
                    editedItem = if (idx in directIndices) directItems[idx - directOffset] else null,
                    exactAudio = exactAudioByIndex[idx - directOffset],
                )
            }

            val timelinePlan = TrailerAdvancedComposer.planTimeline(
                clipInputs = clipInputs,
                voiceovers = preflightedVoiceovers.map { it.first },
            )

            val musicSequence = if (copiedMusicFile != null && musicTrack != null) {
                val duckingScenes = (if (introDuration > 0.0) listOf(TrailerScene(source = TrailerSource(id = -999, name = "intro", file = File(""), duration = introDuration, width = 0, height = 0, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = true), start = 0.0, length = introDuration)) else emptyList()) +
                    effectiveScenes +
                    (if (outroDuration > 0.0) listOf(TrailerScene(source = TrailerSource(id = -998, name = "outro", file = File(""), duration = outroDuration, width = 0, height = 0, analysis = AudioAnalysisResult(emptyList(), 0.0), thumbnail = null, isImage = true), start = 0.0, length = outroDuration)) else emptyList())

                buildMusicSequence(
                    context = context,
                    scenes = duckingScenes,
                    copiedMusicFile = copiedMusicFile,
                    musicTrack = musicTrack,
                    musicPolicy = musicPolicy,
                    autoDucking = autoDucking,
                    musicGain = musicGain,
                    totalVideoDurationSeconds = timelinePlan.effectiveTotalDurationSeconds,
                )
            } else null

            val built = TrailerAdvancedComposer.buildTimelineComposition(
                preparedClips = preparedClips,
                exportWidth = exportWidth,
                exportHeight = exportHeight,
                preflightedVoiceovers = preflightedVoiceovers,
                musicSequence = musicSequence,
                ownedTempFiles = taskOwnedTempFiles.toList(),
                recoveredSceneCount = totalRecoveredScenes,
                excludedSceneCount = totalExcludedScenes,
                leasedCacheFiles = leasedCacheFiles.toList(),
            )
            handoverSucceeded = true
            if (useClipCache) PreviewClipCache.trim(PreviewClipCache.dir(destinationParent))
            built
        } finally {
            currentTransformer?.cancel()
            if (!handoverSucceeded) {
                // Only this task's own handoff entries (unique per-task WAV paths); other runs' entries stay.
                val owned = taskOwnedTempFiles.toSet()
                exactAudioByChunk.entries.removeIf { it.value.file in owned }
                taskOwnedTempFiles.forEach { it.delete() }
                PreviewClipCache.release(leasedCacheFiles)
            }
        }
    }

    suspend fun export(
        context: Context,
        scenes: List<TrailerScene>,
        format: TrailerFormat,
        destination: File,
        musicTrack: TrailerMusicTrack? = null,
        musicPolicy: TrailerMusicPolicy = TrailerMusicPolicy.LOOP_MUSIC,
        musicFitCount: Int = 1,
        autoDucking: Boolean = true,
        aspectSources: List<TrailerSource> = scenes.map { it.source },
        musicVolume: Float = 1f,
        introCard: TrailerTitleCard? = null,
        outroCard: TrailerTitleCard? = null,
        // Photos a card's chosen background may come from (same candidates the editor offers); missing = first/last frame.
        cardBackgroundSources: List<TrailerSource> = scenes.map { it.source },
        voiceovers: List<TrailerVoiceoverTake> = emptyList(),
        projectDirectory: File? = null,
        onSuccess: ((recoveredScenes: Int, excludedScenes: Int) -> Unit)? = null,
        // Test host only (the product preview already feeds originals directly): start on the direct-original route.
        preferDirectVideoInputs: Boolean = false,
        progress: (status: String, progress: Float?) -> Unit,
    ): File = withContext(Dispatchers.Main.immediate) {
        val (exportWidth, exportHeight) = format.resolveDimensions(aspectSources)
        val destinationParent = destination.parentFile ?: context.cacheDir

        var lastReportedProgress = 0f
        fun safeProgress(status: String, p: Float?) {
            if (p == null) {
                progress(status, null)
            } else {
                val monotonic = maxOf(lastReportedProgress, p.coerceIn(0f, 1f))
                lastReportedProgress = monotonic
                progress(status, monotonic)
            }
        }

        var currentTransformer: Transformer? = null
        var builtComposition: TrailerAdvancedComposer.BuiltTimelineComposition? = null
        var exportSuccess = false

        try {
            // A codec allocation failure (encoder/decoder init) of the intermediate route is retried ONCE with the healthy originals fed
            // directly (same composition, effects, timing and full-resolution output; fewer concurrent HW decoders). The first attempt's
            // transformer and owned files are released before the retry; a corrupt-media failure is never retried here.
            var direct = preferDirectVideoInputs
            while (true) {
                try {
                    val attempt = prepareCompositionForPreview(
                        context = context,
                        scenes = scenes,
                        format = format,
                        destinationParent = destinationParent,
                        musicTrack = musicTrack,
                        musicPolicy = musicPolicy,
                        musicFitCount = musicFitCount,
                        autoDucking = autoDucking,
                        aspectSources = aspectSources,
                        musicVolume = musicVolume,
                        introCard = introCard,
                        outroCard = outroCard,
                        cardBackgroundSources = cardBackgroundSources,
                        voiceovers = voiceovers,
                        projectDirectory = projectDirectory,
                        progressEndFraction = 0.85f,
                        directVideoInputs = direct,
                        onTransformerCreated = { currentTransformer = it },
                        progress = ::safeProgress,
                    )
                    builtComposition = attempt
                    currentCoroutineContext().ensureActive()
                    val attemptBodyCount = attempt.mappings.count { it.sceneKey != -999L && it.sceneKey != -998L }
                    runTransformerExport(
                        context = context,
                        composition = attempt.composition,
                        outputFile = destination,
                        statusText = "영상으로 저장 중 ($attemptBodyCount/${attemptBodyCount}개 연결 완료)",
                        safeProgress = ::safeProgress,
                        progressStart = 0.85f,
                        progressEnd = 1.0f,
                        onTransformerCreated = { currentTransformer = it },
                        outputPixels = exportWidth.toLong() * exportHeight,
                        outputSeconds = attempt.effectiveTotalDurationSeconds,
                    )
                    break
                } catch (e: Throwable) {
                    currentTransformer?.cancel()
                    currentTransformer = null
                    builtComposition?.releaseOwnedTempFiles()
                    builtComposition = null
                    if (destination.exists()) destination.delete()
                    if (direct || !isCodecResourceFailure(e)) throw e
                    android.util.Log.w("TrailerExporter", "codec resource failure, retrying with direct original inputs", e)
                    direct = true
                    lastReportedProgress = 0f
                }
            }
            val built = checkNotNull(builtComposition) { "예고편 구성을 만들지 못했습니다." }
            val effectiveTotalDuration = built.effectiveTotalDurationSeconds
            val bodySceneCount = built.mappings.count { it.sceneKey != -999L && it.sceneKey != -998L }

            withContext(Dispatchers.IO) {
                check(destination.isFile && destination.length() > 0) { "예고편 파일을 만들지 못했습니다." }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(destination.absolutePath)
                    val containerDuration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0
                    val hasVideo = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                    var videoTrackDuration = -1.0
                    var audioTrackDuration = -1.0
                    runCatching {
                        val extractor = android.media.MediaExtractor()
                        try {
                            extractor.setDataSource(destination.absolutePath)
                            for (index in 0 until extractor.trackCount) {
                                val format = extractor.getTrackFormat(index)
                                val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                                if (!format.containsKey(android.media.MediaFormat.KEY_DURATION)) continue
                                val seconds = format.getLong(android.media.MediaFormat.KEY_DURATION) / 1_000_000.0
                                if (mime.startsWith("video/") && videoTrackDuration < 0) videoTrackDuration = seconds
                                else if (mime.startsWith("audio/") && audioTrackDuration < 0) audioTrackDuration = seconds
                            }
                        } finally { extractor.release() }
                    }
                    val videoDuration = if (videoTrackDuration > 0) videoTrackDuration else containerDuration
                    val clipCount = built.mappings.size
                    val videoTolerance = maxOf(.5, clipCount * .05)
                    // AAC 프레임 패딩이 청크마다 누적되므로 오디오/컨테이너는 비디오보다 넓은 한계를 허용한다.
                    val audioTolerance = maxOf(1.0, clipCount * .1)
                    // 음악 한 번 재생은 음악이 영상보다 짧으면 거기서 끝나는 것이 정상이다. 반복·길이 맞춤은 영상 끝까지 이어져야 한다.
                    val expectedAudioMin = if (musicTrack != null && musicPolicy == TrailerMusicPolicy.SINGLE) {
                        min(musicTrack.durationSeconds, effectiveTotalDuration)
                    } else {
                        effectiveTotalDuration
                    }
                    val videoOk = hasVideo && abs(videoDuration - effectiveTotalDuration) <= videoTolerance
                    val containerOk = abs(containerDuration - effectiveTotalDuration) <= audioTolerance
                    val audioOk = audioTrackDuration < 0 ||
                        (audioTrackDuration >= expectedAudioMin - audioTolerance && audioTrackDuration <= effectiveTotalDuration + audioTolerance)
                    if (!videoOk || !containerOk || !audioOk) {
                        android.util.Log.w(
                            "TrailerExporter",
                            "duration check failed videoOk=$videoOk containerOk=$containerOk audioOk=$audioOk " +
                                "expected=${"%.2f".format(effectiveTotalDuration)} expectedAudioMin=${"%.2f".format(expectedAudioMin)} " +
                                "video=${"%.2f".format(videoDuration)}(tol ${"%.2f".format(videoTolerance)}) " +
                                "audio=${"%.2f".format(audioTrackDuration)} container=${"%.2f".format(containerDuration)}(tol ${"%.2f".format(audioTolerance)}) " +
                                "chunks=$clipCount scenes=$bodySceneCount policy=$musicPolicy",
                        )
                    }
                    check(videoOk && containerOk && audioOk) { "완성 영상의 길이를 확인하지 못했습니다. 다시 만들어 주세요." }
                } finally { reader.release() }
            }

            exportSuccess = true
            onSuccess?.invoke(built.recoveredSceneCount, built.excludedSceneCount)
            destination
        } finally {
            currentTransformer?.cancel()
            if (!exportSuccess && destination.exists()) {
                destination.delete()
            }
            builtComposition?.releaseOwnedTempFiles()
        }
    }
}

/**
 * Per-clip cache of prepared preview intermediates under the app's own cache namespace. A file is keyed by the real source identity
 * (path + size + mtime, hashed) and everything that changes its pixels, so an edit only invalidates the clips it touches.
 * Compositions lease the files they play; trimming only deletes unleased files, so a cancelled/stale prepare or an old player
 * release can never remove a file a live composition still uses. No shared user storage is touched.
 */
internal object PreviewClipCache {
    private const val MAX_BYTES = 768L * 1024 * 1024
    private val leases = HashMap<String, Int>()

    fun dir(parent: File): File = File(parent, "preview-clip-cache").also { it.mkdirs() }

    fun keyOf(scene: TrailerScene, width: Int, height: Int): String {
        val src = scene.source.file
        val raw = listOf(
            "v1", width, height, src.absolutePath, src.length(), src.lastModified(), scene.source.isImage, scene.source.width, scene.source.height,
            scene.start, scene.length, scene.fit, scene.focusX, scene.focusY, scene.sourceVolume,
            scene.caption?.normalizedOrNull(), scene.adjustments?.toJson(),
        ).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** True (and leased) when [file] is a usable cached clip. */
    @Synchronized
    fun lease(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        file.setLastModified(System.currentTimeMillis())
        leases.merge(file.absolutePath, 1, Int::plus)
        return true
    }

    /** Moves a freshly exported [produced] chunk to [target] (no overwrite) and leases it; false leaves [produced] untouched. */
    @Synchronized
    fun adopt(produced: File, target: File): Boolean {
        if (target.exists() || !produced.isFile || produced.length() <= 0L || !produced.renameTo(target)) return false
        leases.merge(target.absolutePath, 1, Int::plus)
        return true
    }

    @Synchronized
    fun release(files: List<File>) {
        for (f in files) {
            val left = (leases[f.absolutePath] ?: 0) - 1
            if (left > 0) leases[f.absolutePath] = left else leases.remove(f.absolutePath)
        }
    }

    /** Oldest-used unleased clips go first until the cache fits its bound. */
    @Synchronized
    fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.extension == "mp4" }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            if (leases.containsKey(f.absolutePath)) continue
            val size = f.length()
            if (f.delete()) total -= size
        }
    }
}
