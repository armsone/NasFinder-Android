package com.armsone.nasfinder.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTransferQrRefreshContractTest {
    @Test
    fun receiverRestartInvalidatesOldGenerationAndSockets() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferPairing.kt",
        ).readText()

        assertTrue(source.contains("sessionGeneration.incrementAndGet()"))
        assertTrue(source.contains("while (isCurrentGeneration(generation))"))
        assertTrue(source.contains("if (!isCurrentGeneration(generation)) return@onFailure"))
        assertTrue(source.contains("serverSocket?.close()"))
        assertTrue(source.contains("activeSocket.getAndSet(null)?.close()"))
        assertTrue(source.contains("photo-pairing-receiver-\$generation"))
        assertTrue(source.contains("const val MAX_HANDSHAKE_WORKERS = 4"))
        assertTrue(source.contains("const val MAX_PENDING_HANDSHAKES = 4"))
        assertTrue(source.contains("SynchronousQueue()"))
        assertTrue(source.contains("activeSocket.compareAndSet(null, socket)"))
        assertTrue(source.indexOf("val generation = synchronized(lifecycleLock)") < source.indexOf("return withContext(Dispatchers.IO)"))
    }

    @Test
    fun qrRefreshOnlyPublishesReadySessionAndClearsPreviousResult() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferScreen.kt",
        ).readText()

        assertTrue(source.contains("\"QR 다시 만들기\""))
        assertTrue(source.contains("receiverStartJob.value?.cancel()"))
        assertTrue(source.contains("receiver.close()"))
        assertTrue(source.contains("receivedResults = emptyList()"))
        assertTrue(source.contains("if (!isActive || receiverUiGeneration != uiGeneration) return@launch"))
        assertTrue(source.contains("is PhotoPairingStartResult.Ready ->"))
        assertTrue(source.contains("val readyQr = pairingQrBitmap(result.payload.encode())"))
        assertTrue(source.contains("receiverConnected) {"))
        assertTrue(source.contains("pairingQr = readyQr"))
        assertTrue(source.contains("canRegenerate = pairingQr != null && !receiverStarting && !receiverConnected"))
        assertTrue(source.contains("OutlinedButton(onClick = onRegenerate, enabled = canRegenerate)"))
    }

    @Test
    fun selectionAndSenderGuardsPreventStaleOrConcurrentSend() {
        val screen = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferScreen.kt",
        ).readText()
        val pairing = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferPairing.kt",
        ).readText()

        assertTrue(screen.contains("if (uris.isNotEmpty())"))
        assertTrue(screen.contains("selectionLoading = true"))
        assertTrue(screen.contains("val semaphore = Semaphore(4)"))
        assertTrue(screen.contains("semaphore.withPermit"))
        assertTrue(screen.contains("if (transferInProgress || selectionLoading) return@sendSelected"))
        assertTrue(screen.contains("canSend = senderSession != null && selectedMedia.isNotEmpty() && !selectionLoading"))
        assertTrue(screen.contains("senderUiGeneration != scanGeneration"))
        assertTrue(pairing.contains("sendInProgress.compareAndSet(false, true)"))
        assertTrue(pairing.contains("sendInProgress.set(false)"))
        assertTrue(pairing.contains("rollbackSavedResults(context, savedResults)"))
    }

    @Test
    fun pairingStatesUseOwnedFullScreenModalWithDismissConfirmation() {
        val source = sourceFile(
            "app/src/main/java/com/armsone/nasfinder/ui/PhotoTransferScreen.kt",
        ).readText()

        assertTrue(source.contains("DialogProperties(usePlatformDefaultWidth = false"))
        assertTrue(source.contains("\"QR 표시해서 받기\" else \"QR 스캔해서 보내기\""))
        assertTrue(source.contains("TextButton(onClick = onDismissRequest) { Text(\"닫기\""))
        assertTrue(source.contains("pairingDismissNeedsConfirmation"))
        assertTrue(source.contains("\"중단하고 닫기\""))
        assertTrue(source.contains("headerIcon = Icons.Default.Wifi"))
        assertTrue(source.contains("headerIcon = Icons.Default.Collections"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }
}
