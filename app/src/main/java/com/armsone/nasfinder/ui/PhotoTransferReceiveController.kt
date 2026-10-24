package com.armsone.nasfinder.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class PhotoReceiveUi(
    /** The receive sheet (QR / progress / result) is showing. */
    val modalOpen: Boolean = false,
    val starting: Boolean = false,
    val connected: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
    val peerTitle: String? = null,
    val qr: Bitmap? = null,
    val address: String? = null,
    val status: String = "",
    val results: List<SavedPhotoTransferResult> = emptyList(),
    val partialNote: String? = null,
    val legacyExposed: Boolean = false,
)

/**
 * Owns the receive-ready listener and its QR for 사진·영상 전송 > 받기.
 *
 * It is a ViewModel on purpose: the listener socket, the QR token and the "waiting for the sender" state must outlive
 * activity recreation (rotation, window resize, dark-mode switch), which a `remember`ed receiver did not. It is released
 * only when the user closes/cancels the receive sheet, the screen is really left ([release]), or the activity is finished.
 */
internal class PhotoTransferReceiveController(application: Application) : AndroidViewModel(application) {
    private val receiver = PhotoPairingReceiver()
    private val _ui = MutableStateFlow(PhotoReceiveUi())
    val ui: StateFlow<PhotoReceiveUi> = _ui.asStateFlow()
    private var startJob: Job? = null
    private var generation = 0L

    fun start() {
        generation += 1L
        val uiGeneration = generation
        startJob?.cancel()
        receiver.close()
        _ui.value = PhotoReceiveUi(modalOpen = true, starting = true, status = "새 연결 정보를 준비하고 있습니다.")
        val context = getApplication<Application>()
        startJob = viewModelScope.launch {
            val result = receiver.start(
                context = context,
                onConnected = { peer ->
                    viewModelScope.launch {
                        if (generation != uiGeneration) return@launch
                        _ui.update {
                            it.copy(
                                starting = false, connected = true, peerTitle = peer.displayName, qr = null,
                                status = "${peer.displayName}와 연결되었습니다. 파일을 기다리고 있습니다.",
                            )
                        }
                    }
                },
                onProgress = { progress ->
                    viewModelScope.launch { if (generation == uiGeneration) _ui.update { it.copy(status = receiveProgressText(progress)) } }
                },
                onComplete = { results ->
                    viewModelScope.launch {
                        if (generation != uiGeneration) return@launch
                        _ui.update {
                            it.copy(
                                starting = false, connected = false, finished = true, results = results,
                                status = "${results.size}개 파일을 받았습니다." + if (it.legacyExposed) " $LEGACY_RECEIVE_NOTE" else "",
                            )
                        }
                    }
                },
                onLegacyTokenExposed = {
                    viewModelScope.launch { if (generation == uiGeneration) _ui.update { it.copy(legacyExposed = true) } }
                },
                onFailure = { reason ->
                    viewModelScope.launch {
                        if (generation != uiGeneration) return@launch
                        _ui.update { it.copy(starting = false, connected = false, qr = null, error = reason, status = reason) }
                    }
                },
                onPartial = { results, reason ->
                    // Items already saved stay saved (and are journaled); only the rest is reported as missing.
                    viewModelScope.launch {
                        if (generation != uiGeneration) return@launch
                        _ui.update {
                            it.copy(starting = false, connected = false, finished = true, results = results, partialNote = reason, status = reason)
                        }
                    }
                },
            )
            if (!isActive || generation != uiGeneration) return@launch
            when (result) {
                is PhotoPairingStartResult.Ready -> {
                    val readyQr = pairingQrBitmap(result.payload.encode())
                    if (!isActive || generation != uiGeneration || _ui.value.connected) return@launch
                    _ui.update {
                        it.copy(
                            starting = false, qr = readyQr, address = "${result.payload.host}:${result.payload.port}",
                            status = "보내는 기기에서 QR 코드를 스캔하세요.",
                        )
                    }
                }
                PhotoPairingStartResult.NoLocalNetwork -> {
                    val reason = "같은 Wi-Fi에 연결한 뒤 다시 시도하세요."
                    _ui.update { it.copy(starting = false, error = reason, status = reason) }
                }
                is PhotoPairingStartResult.Failed -> _ui.update { it.copy(starting = false, error = result.reason, status = result.reason) }
            }
        }
    }

    /** Stops listening and invalidates late callbacks. [keepSheet] only matters for the visible state. */
    fun stop(keepSheet: Boolean = false) {
        generation += 1L
        startJob?.cancel()
        startJob = null
        receiver.close()
        _ui.value = PhotoReceiveUi(modalOpen = keepSheet)
    }

    /** The screen was really left (not a configuration change): release the socket and reset. */
    fun release() = stop()

    override fun onCleared() {
        generation += 1L
        startJob?.cancel()
        receiver.close()
        super.onCleared()
    }
}
