package com.armsone.nasfinder.data

import com.armsone.nasfinder.model.RemoteFileItem
import com.armsone.nasfinder.network.RemoteFileService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class SuperThumbnailVaultAndSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun vaultFilenameIsStableAndIncludesSourceMetadata() {
        val item = media("/photos/a.jpg", size = 12, modifiedAt = Instant.ofEpochMilli(20))
        assertEquals(SuperThumbnailVault.filename(item), SuperThumbnailVault.filename(item.copy()))
        assertNotEquals(SuperThumbnailVault.filename(item), SuperThumbnailVault.filename(item.copy(size = 13)))
        assertTrue(SuperThumbnailVault.filename(item).matches(Regex("v1-[0-9a-f]{64}\\.jpg")))
    }

    @Test fun vaultFilenameUsesIosUnknownSizeSentinelAndKeepsLegacyAndroidLookup() {
        val unknown = media("/photos/photo.jpg", size = 0, modifiedAt = Instant.ofEpochMilli(500))
        val canonical = "v1-a08c1b186cb93cafccda2ba5658212d90b5b013d8398eb25b45058931fc6af19.jpg"
        val legacy = "v1-aafd20184b6067ea8b6da81ac96207d83b6022131580a12506906dd6be4cbfcc.jpg"

        assertEquals(canonical, SuperThumbnailVault.filename(unknown))
        assertEquals(listOf(canonical, legacy), SuperThumbnailVault.compatibleFilenames(unknown))
        assertEquals(
            listOf(SuperThumbnailVault.filename(unknown.copy(size = 1))),
            SuperThumbnailVault.compatibleFilenames(unknown.copy(size = 1)),
        )
    }

    @Test fun vaultReadsLegacyUnknownSizeEntryWithoutDuplicatingIt() = runBlocking {
        val unknown = media("/photos/photo.jpg", size = 0, modifiedAt = Instant.ofEpochMilli(500))
        val names = SuperThumbnailVault.compatibleFilenames(unknown)
        val validJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        val service = FakeVaultService().apply {
            directories += "/photos"
            directories += "/photos/${SuperThumbnailVault.DIRECTORY_NAME}"
            files["/photos/${SuperThumbnailVault.DIRECTORY_NAME}/${names[1]}"] = validJpeg
        }
        val vault = SuperThumbnailVault(temporary.newFolder("legacy-restore-work"))

        org.junit.Assert.assertArrayEquals(validJpeg, vault.restoredData(unknown, "/photos", service))
        val result = vault.storeFolder(listOf(unknown), "/photos", service) { validJpeg }

        assertEquals(setOf(unknown.id), result.storedItemIds)
        assertFalse(service.files.containsKey("/photos/${SuperThumbnailVault.DIRECTORY_NAME}/${names[0]}"))
        assertTrue(service.files.containsKey("/photos/${SuperThumbnailVault.DIRECTORY_NAME}/${names[1]}"))
    }

    @Test fun cooperativeNowUploadPublishesImmediatelyAndNeverJoinsFolderBatch() {
        assertEquals(
            SuperThumbnailVaultPublishMode.IMMEDIATE,
            SuperThumbnailVaultPublishPolicy.mode(
                hasCooperativeLease = true,
            ),
        )
        assertEquals(
            SuperThumbnailVaultPublishMode.FOLDER_BATCH,
            SuperThumbnailVaultPublishPolicy.mode(
                hasCooperativeLease = false,
            ),
        )
    }

    @Test fun sessionPersistsRootReportVaultFailureAndResumeItems() {
        val file = temporary.newFile("session.json")
        val key = superThumbnailSessionKey("connection", "/photos")
        val first = media("/photos/a.jpg")
        val second = media("/photos/b.mp4")
        SuperThumbnailSessionStore(file).apply {
            prepare(key, "/photos", listOf(first, second), vaultEnabled = true)
            recordSuccess(key, first)
            markVaultPending(key, first)
            recordFailure(key, second, "frame failed")
            recordVaultResult(
                key,
                SuperThumbnailVaultStoreResult(emptySet(), setOf(first.id), "upload failed", true),
            )
        }

        val restored = SuperThumbnailSessionStore(file)
        val report = restored.report(key)!!
        assertEquals("/photos", restored.rootPath(key))
        assertEquals(1, report.photoSuccessCount)
        assertEquals(1, report.failures.size)
        assertEquals(1, report.vaultFailedCount)
        assertTrue(report.hasWorkToResume)
        assertEquals(setOf(first.id, second.id), restored.resumeItems(key).mapTo(hashSetOf()) { it.id })

        restored.recordVaultVerification(key, setOf(first.id), Instant.ofEpochMilli(1_000))
        assertEquals(1, restored.report(key)!!.vaultUploadedCount)
        restored.markVaultRemoved(key, Instant.ofEpochMilli(2_000))
        assertEquals(1, restored.report(key)!!.vaultPendingCount)
        assertTrue(restored.report(key)!!.hasWorkToResume)
        restored.recordVaultVerification(key, setOf(first.id), Instant.ofEpochMilli(3_000))
        restored.reconcileObserved(key, listOf(first))
        assertTrue(restored.report(key)!!.failures.isEmpty())
        assertTrue(restored.resumeItems(key).isEmpty())
        val otherKey = superThumbnailSessionKey("other", "/photos")
        restored.prepare(otherKey, "/photos", listOf(first), vaultEnabled = false)
        restored.clearConnection("connection")
        assertNull(restored.report(key))
        assertNotNull(restored.report(otherKey))
    }

    @Test fun vaultUsesHiddenStagingVerifiesRenameAndDeletesOnlyVaultTree() = runBlocking {
        val service = FakeVaultService().apply {
            directories += "/photos"
            directories += "/photos/visible"
            files["/photos/visible/keep.txt"] = byteArrayOf(9)
        }
        val items = listOf(media("/photos/a.jpg"), media("/photos/b.mp4"))
        val vault = SuperThumbnailVault(temporary.newFolder("vault-work"))
        val result = vault.storeFolderWithRetry(items, "/photos", service) { byteArrayOf(1, 2, 3) }

        assertEquals(items.mapTo(hashSetOf()) { it.id }, result.storedItemIds)
        assertTrue(result.errorDescription == null)
        assertTrue(service.directories.contains("/photos/${SuperThumbnailVault.DIRECTORY_NAME}"))
        assertFalse(service.files.keys.any { it.substringAfterLast('/').startsWith(".upload-") })
        assertEquals(result.storedItemIds, vault.verifyStoredItemIds(items, "/photos", service))

        val removed = vault.removeVaults("/photos", service)
        assertEquals(2, removed.removedFiles)
        assertEquals(1, removed.removedFolders)
        assertTrue(removed.failures.isEmpty())
        assertTrue(service.files.containsKey("/photos/visible/keep.txt"))
    }

    @Test fun vaultRejectsItemOutsideSelectedRootWithoutRemoteMutation() = runBlocking {
        val service = FakeVaultService().apply { directories += "/photos" }
        val result = SuperThumbnailVault(temporary.newFolder("outside-work"))
            .storeFolder(listOf(media("/outside/a.jpg")), "/photos", service) { byteArrayOf(1) }
        assertTrue(result.errorDescription != null)
        assertEquals(setOf("/photos"), service.directories)
        assertTrue(service.files.isEmpty())
    }

    @Test fun cooperativeWorkerRecordUsesIosNameAndExpiresAfterNinetySeconds() = runBlocking {
        val service = FakeVaultService().apply { directories += "/photos" }
        val vault = SuperThumbnailVault(temporary.newFolder("worker-record-work"))
        val now = Instant.parse("2026-08-15T00:00:00Z")

        vault.registerWorker("worker-a", "/photos", service, now)

        val workerPath = "/photos/${SuperThumbnailVault.DIRECTORY_NAME}/" +
            "${SuperThumbnailVault.WORKERS_DIRECTORY_NAME}/worker-worker-a.json"
        val record = JSONObject(String(service.files.getValue(workerPath), Charsets.UTF_8))
        assertEquals("worker-a", record.getString("workerID"))
        assertTrue(record.getDouble("expiresAt") > 0.0)
        assertEquals(setOf("worker-a"), vault.activeWorkerIds("/photos", service, now.plusSeconds(89)))
        assertTrue(vault.activeWorkerIds("/photos", service, now.plusSeconds(91)).isEmpty())
        assertFalse(service.files.containsKey(workerPath))
    }

    @Test fun cooperativeClaimDefersPeersAndOnlyMatchingTokenCanRelease() = runBlocking {
        val service = FakeVaultService().apply { directories += "/photos" }
        val vault = SuperThumbnailVault(temporary.newFolder("claim-work"))
        val item = media("/photos/a.jpg")
        val now = Instant.parse("2026-08-15T00:00:00Z")

        val first = vault.claim(item, "worker-a", "/photos", service, now)
        assertEquals(SuperThumbnailCooperativeClaimType.ACQUIRED, first.type)
        val lease = first.lease!!
        assertEquals(SuperThumbnailVault.leaseDirectoryName(item), lease.directoryName)
        assertTrue(lease.directoryName.matches(Regex("\\.claim-v1-[0-9a-f]{64}")))
        assertTrue(service.files.containsKey("${lease.vaultPath}/${lease.directoryName}/${SuperThumbnailVault.LEASE_RECORD_NAME}"))
        assertEquals(
            SuperThumbnailCooperativeClaimType.DEFERRED,
            vault.claim(item, "worker-b", "/photos", service, now.plusSeconds(1)).type,
        )

        vault.release(lease.copy(token = "wrong-token"), service)
        assertTrue(service.directories.contains("${lease.vaultPath}/${lease.directoryName}"))
        vault.release(lease, service)
        assertFalse(service.directories.contains("${lease.vaultPath}/${lease.directoryName}"))
        assertEquals(
            SuperThumbnailCooperativeClaimType.ACQUIRED,
            vault.claim(item, "worker-b", "/photos", service, now.plusSeconds(2)).type,
        )
    }

    @Test fun cooperativeClaimReclaimsAnExpiredLease() = runBlocking {
        val service = FakeVaultService().apply { directories += "/photos" }
        val vault = SuperThumbnailVault(temporary.newFolder("expired-claim-work"))
        val item = media("/photos/a.mov")
        val now = Instant.parse("2026-08-15T00:00:00Z")

        val first = vault.claim(item, "worker-a", "/photos", service, now)
        val reclaimed = vault.claim(
            item,
            "worker-b",
            "/photos",
            service,
            now.plusSeconds(SuperThumbnailVault.LEASE_LIFETIME_SECONDS + 1),
        )

        assertEquals(SuperThumbnailCooperativeClaimType.ACQUIRED, reclaimed.type)
        assertNotEquals(first.lease!!.token, reclaimed.lease!!.token)
    }

    @Test fun vaultRestoredDataReturnsJpegOnHitAndSafelyFallsBackOnMissOrCorrupt() = runBlocking {
        val service = FakeVaultService().apply { directories += "/photos" }
        val vault = SuperThumbnailVault(temporary.newFolder("restore-work"))
        val item = media("/photos/photo.jpg", size = 100, modifiedAt = Instant.ofEpochMilli(500))

        assertNull(vault.restoredData(item, "/photos", service))

        val validJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())
        val storeResult = vault.storeFolder(listOf(item), "/photos", service) { validJpeg }
        assertEquals(setOf(item.id), storeResult.storedItemIds)

        val restored = vault.restoredData(item, "/photos", service)
        assertNotNull(restored)
        org.junit.Assert.assertArrayEquals(validJpeg, restored)

        val changedItem = item.copy(size = 200)
        assertNull(vault.restoredData(changedItem, "/photos", service))
    }

    @Test fun vaultFilenameSupportsFolderIdentityAndNfcPrecomposition() {
        val folder = RemoteFileItem("f1", "photos", "/photos", true)
        val nfcName = java.text.Normalizer.normalize("한글폴더", java.text.Normalizer.Form.NFC)
        val nfdName = java.text.Normalizer.normalize("한글폴더", java.text.Normalizer.Form.NFD)

        val folderNfc = RemoteFileItem("f2", nfcName, "/$nfcName", true)
        val folderNfd = RemoteFileItem("f3", nfdName, "/$nfdName", true)

        assertEquals(SuperThumbnailVault.filename(folderNfc), SuperThumbnailVault.filename(folderNfd))
        assertTrue(SuperThumbnailVault.filename(folder).startsWith("v1-folder-"))
        assertTrue(SuperThumbnailVault.filename(folder).endsWith(".jpg"))
        assertTrue(SuperThumbnailVault.folderEmptyMarkerFilename("photos").startsWith("v1-folder-"))
        assertTrue(SuperThumbnailVault.folderEmptyMarkerFilename("photos").endsWith(".empty"))
        assertTrue(SuperThumbnailVault.leaseDirectoryName(folder).startsWith(".claim-v1-folder-"))

        val fileItem = media("/photos/photos.jpg")
        assertTrue(SuperThumbnailVault.filename(fileItem).startsWith("v1-"))
        assertFalse(SuperThumbnailVault.filename(fileItem).startsWith("v1-folder-"))
    }

    @Test fun vaultStoresAndRestoresFolderSuperThumbnailAndEmptyMarker() = runBlocking {
        val folder = RemoteFileItem("/photos/subfolder", "subfolder", "/photos/subfolder", true)
        val emptyFolder = RemoteFileItem("/photos/empty", "empty", "/photos/empty", true)
        val validJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 2, 3, 0xff.toByte(), 0xd9.toByte())

        val service = FakeVaultService().apply {
            directories += "/photos"
            directories += "/photos/subfolder"
            directories += "/photos/empty"
        }
        val vault = SuperThumbnailVault(temporary.newFolder("folder-vault-work"))

        // Store folder thumbnail
        val stored = vault.storeFolderThumbnail(folder, "/photos", service, validJpeg)
        assertTrue(stored)
        val expectedJpegName = SuperThumbnailVault.folderFilename(folder.name)
        assertTrue(service.files.containsKey("/photos/${SuperThumbnailVault.DIRECTORY_NAME}/$expectedJpegName"))

        // Restore folder thumbnail
        val restored = vault.restoredData(folder, "/photos", service)
        assertNotNull(restored)
        org.junit.Assert.assertArrayEquals(validJpeg, restored)

        // A failed replacement restores the prior sheet and cleans its backup.
        service.failNextRenameTo = expectedJpegName
        val failedReplacement = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 4, 0xff.toByte(), 0xd9.toByte())
        assertFalse(vault.storeFolderThumbnail(folder, "/photos", service, failedReplacement))
        org.junit.Assert.assertArrayEquals(validJpeg, vault.restoredData(folder, "/photos", service))
        assertFalse(service.files.keys.any { it.contains(".backup-") })

        // A normal producer pass replaces a legacy folder sheet at the same
        // compatible filename instead of treating old baked blur as current.
        val regeneratedJpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 9, 8, 7, 0xff.toByte(), 0xd9.toByte())
        assertTrue(vault.storeFolderThumbnail(folder, "/photos", service, regeneratedJpeg))
        org.junit.Assert.assertArrayEquals(
            regeneratedJpeg,
            vault.restoredData(folder, "/photos", service),
        )
        assertFalse(service.files.keys.any { it.contains(".backup-") })

        // Store folder empty marker
        val emptyStored = vault.storeFolderEmptyMarker(emptyFolder, "/photos", service)
        assertTrue(emptyStored)
        val expectedEmptyName = SuperThumbnailVault.folderEmptyMarkerFilename(emptyFolder.name)
        assertTrue(service.files.containsKey("/photos/${SuperThumbnailVault.DIRECTORY_NAME}/$expectedEmptyName"))

        // Verify stored items
        val verified = vault.verifyStoredItemIds(listOf(folder, emptyFolder), "/photos", service)
        assertEquals(setOf(folder.id, emptyFolder.id), verified)
    }

    private fun media(path: String, size: Long = 10, modifiedAt: Instant = Instant.ofEpochMilli(10)) = RemoteFileItem(
        id = path,
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = false,
        size = size,
        modifiedAt = modifiedAt,
    )

    private class FakeVaultService : RemoteFileService {
        val directories = linkedSetOf<String>()
        val files = linkedMapOf<String, ByteArray>()
        var failNextRenameTo: String? = null

        override suspend fun testConnection() = Unit
        override suspend fun list(path: String): List<RemoteFileItem> {
            val prefix = if (path == "/") "/" else "${path.trimEnd('/')}/"
            val directoryItems = directories.asSequence().filter { it != path && it.startsWith(prefix) }
                .filter { !it.removePrefix(prefix).contains('/') }
                .map { item(it, true, 0) }
            val fileItems = files.asSequence().filter { it.key.startsWith(prefix) }
                .filter { !it.key.removePrefix(prefix).contains('/') }
                .map { item(it.key, false, it.value.size.toLong()) }
            return (directoryItems + fileItems).toList()
        }
        override suspend fun download(item: RemoteFileItem, destination: File, progress: (Long, Long) -> Unit) {
            val data = files[item.path] ?: error("missing")
            destination.writeBytes(data)
            progress(data.size.toLong(), data.size.toLong())
        }
        override suspend fun createFolder(parent: String, name: String) {
            check(directories.add(join(parent, name))) { "directory already exists" }
        }
        override suspend fun upload(parent: String, source: File) {
            files[join(parent, source.name)] = source.readBytes()
        }
        override suspend fun rename(item: RemoteFileItem, newName: String) {
            if (failNextRenameTo == newName) {
                failNextRenameTo = null
                error("forced rename failure")
            }
            val destination = join(item.path.substringBeforeLast('/').ifBlank { "/" }, newName)
            files.remove(item.path)?.let { files[destination] = it }
                ?: error("missing staging")
        }
        override suspend fun delete(items: List<RemoteFileItem>) {
            items.forEach { item ->
                if (item.isDirectory) {
                    val prefix = "${item.path.trimEnd('/')}/"
                    files.keys.filter { it.startsWith(prefix) }.forEach(files::remove)
                    directories.removeAll { it == item.path || it.startsWith(prefix) }
                } else files.remove(item.path)
            }
        }
        override fun close() = Unit

        private fun item(path: String, directory: Boolean, size: Long) = RemoteFileItem(
            id = path, name = path.substringAfterLast('/'), path = path, isDirectory = directory, size = size,
        )
        private fun join(parent: String, name: String) = if (parent == "/") "/$name" else "${parent.trimEnd('/')}/$name"
    }
}
