package com.armsone.nasfinder.ui

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

// HanClip's composition, crop presentation and cancellation/export validation path.
@androidx.annotation.OptIn(UnstableApi::class)
internal object TrailerExporter {
    suspend fun export(context: Context, scenes: List<TrailerScene>, format: TrailerFormat, destination: File, progress: (Float) -> Unit): File = withContext(Dispatchers.Main.immediate) {
        require(scenes.isNotEmpty()) { "이 장면 길이로 만들 수 있는 영상이 없습니다. 장면 길이를 줄여 주세요." }
        val edited = scenes.map { scene ->
            val input = MediaItem.Builder().setUri(Uri.fromFile(scene.source.file))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((scene.start * 1000).toLong())
                    .setEndPositionMs(((scene.start + scene.length) * 1000).toLong()).build()).build()
            EditedMediaItem.Builder(input).setFrameRate(30)
                .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(format.width, format.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))))
                .build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(edited))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
        val scope = CoroutineScope(currentCoroutineContext())
        var poll: Job? = null
        var transformer: Transformer? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val instance = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
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
                transformer = instance
                continuation.invokeOnCancellation {
                    poll?.cancel()
                    if (Looper.myLooper() == Looper.getMainLooper()) instance.cancel()
                    else Handler(Looper.getMainLooper()).post { instance.cancel() }
                }
                instance.start(composition, destination.absolutePath)
                poll = scope.launch {
                    val holder = ProgressHolder()
                    while (isActive) {
                        if (instance.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
                        delay(250)
                    }
                }
            }
            withContext(Dispatchers.IO) {
                check(destination.isFile && destination.length() > 0) { "예고편 파일을 만들지 못했습니다." }
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(destination.absolutePath)
                    val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: 0.0
                    check(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" && abs(duration - scenes.sumOf { it.length }) <= maxOf(.5, scenes.size * .05)) { "완성 영상의 길이를 확인하지 못했습니다. 다시 만들어 주세요." }
                } finally { reader.release() }
            }
            destination
        } finally {
            poll?.cancel()
            transformer?.cancel()
        }
    }
}
