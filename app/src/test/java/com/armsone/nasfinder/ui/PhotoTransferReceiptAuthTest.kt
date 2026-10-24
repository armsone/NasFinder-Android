package com.armsone.nasfinder.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoTransferReceiptAuthTest {
    private val token = "abcdefghijklmnopqrstuvwxyz012345"
    private val transferId = "0123456789abcdef0123456789abcdef"
    private val receiverId = "fedcba9876543210fedcba9876543210"
    private val sha = "a".repeat(64)

    @Test
    fun v4HelloIsExactAndCarriesNoToken() {
        val nonce = PhotoTransferAuth.newNonce()
        val line = "NASFINDER_PHOTO/4 hello ios grouped-v1 receipt-v1 $nonce"
        val hello = parseV4Hello(line)
        assertEquals(PhotoPeerPlatform.IOS, hello?.peer)
        assertEquals(nonce, hello?.senderNonce)
        assertNull(parseV4Hello("NASFINDER_PHOTO/4 hello ios grouped-v1 $nonce"))
        assertNull(parseV4Hello("NASFINDER_PHOTO/4 hello ios grouped-v1 receipt-v1 extra $nonce"))
        assertNull(parseV4Hello("NASFINDER_PHOTO/4 $token ios grouped-v1 receipt-v1 $nonce"))
        assertNull(parseV4Hello("NASFINDER_PHOTO/3 expected-token ios grouped-v1"))
    }

    @Test
    fun proofsDependOnTokenRoleAndBothNonces() {
        val key = PhotoTransferAuth.deriveKey(token)
        val other = PhotoTransferAuth.deriveKey(token + "x")
        val sender = PhotoTransferAuth.proof(key, "S", "android", "nonceSender0000000000000", "nonceReceiver00000000000")
        assertEquals(64, sender.length)
        assertTrue(sender != PhotoTransferAuth.proof(other, "S", "android", "nonceSender0000000000000", "nonceReceiver00000000000"))
        assertTrue(sender != PhotoTransferAuth.proof(key, "R", "android", "nonceSender0000000000000", "nonceReceiver00000000000"))
        assertTrue(sender != PhotoTransferAuth.proof(key, "S", "android", "nonceSender0000000000000", "nonceReceiver00000000001"))
    }

    @Test
    fun receiptRoundTripsWithMacAndRejectsTamperingReplayAndWrongSequence() {
        val sessionKey = PhotoTransferAuth.sessionKey(PhotoTransferAuth.deriveKey(token), "senderNonce000000000000", "receiverNonce0000000000")
        val receipt = PhotoTransferReceipt(
            stage = "final", transferId = transferId, receiverId = receiverId, status = "complete", more = false, seq = 2,
            items = listOf(PhotoTransferReceiptItem(UUID.randomUUID().toString(), true, mapOf(0 to sha, 1 to sha))),
        )
        val bytes = ByteArrayOutputStream().also { PhotoTransferWireCodec.writeReceipt(it, receipt, sessionKey) }.toByteArray()
        assertEquals(receipt, PhotoTransferWireCodec.readReceipt(ByteArrayInputStream(bytes), sessionKey, expectedSeq = 2))
        // Another session key (forged or replayed into a different session) is rejected.
        val otherKey = PhotoTransferAuth.sessionKey(PhotoTransferAuth.deriveKey(token), "senderNonce000000000000", "receiverNonce0000000001")
        assertTrue(runCatching { PhotoTransferWireCodec.readReceipt(ByteArrayInputStream(bytes), otherKey, 2) }.isFailure)
        // Dropped or reordered frames change the expected sequence.
        assertTrue(runCatching { PhotoTransferWireCodec.readReceipt(ByteArrayInputStream(bytes), sessionKey, 1) }.isFailure)
        // Flipping one hash digit in the payload breaks the MAC.
        val tampered = String(bytes, Charsets.UTF_8).replaceFirst("aaaa", "aaab").toByteArray(Charsets.UTF_8)
        assertTrue(runCatching { PhotoTransferWireCodec.readReceipt(ByteArrayInputStream(tampered), sessionKey, 2) }.isFailure)
    }

    @Test
    fun receiptCanonicalBytesAreExactlyTheDocumentedLines() {
        val receipt = PhotoTransferReceipt(
            stage = "final", transferId = transferId, receiverId = receiverId, status = "partial", more = false, seq = 3,
            items = listOf(
                PhotoTransferReceiptItem("b-item", false, emptyMap(), "storage"),
                PhotoTransferReceiptItem("a-item", true, mapOf(1 to "b".repeat(64), 0 to sha)),
            ),
        )
        // Items are sorted by itemId; inputs by index; no trailing newline; absent status/reason are "-".
        assertEquals(
            "receipt\nfinal\n$transferId\n$receiverId\npartial\nfalse\n3" +
                "\na-item|committed|0:$sha,1:${"b".repeat(64)}|-" +
                "\nb-item|failed||storage",
            PhotoTransferAuth.receiptCanonical(receipt),
        )
    }

    @Test
    fun tokenFingerprintIsStableAndNeverTheToken() {
        val fingerprint = PhotoTransferTokenTaint.fingerprint(token)
        assertEquals(fingerprint, PhotoTransferTokenTaint.fingerprint(token))
        assertEquals(64, fingerprint.length)
        assertTrue(!fingerprint.contains(token))
        assertTrue(fingerprint != PhotoTransferTokenTaint.fingerprint(token + "x"))
    }

    @Test
    fun beginFrameIsStrictAndOnlyReadWhenAllowed() {
        val out = ByteArrayOutputStream().also { PhotoTransferWireCodec.writeBegin(it, transferId, 3) }.toByteArray()
        assertEquals(
            PhotoTransferWireMessage.Begin(transferId, 3),
            PhotoTransferWireCodec.readMessage(ByteArrayInputStream(out), allowBegin = true),
        )
        assertTrue(runCatching { PhotoTransferWireCodec.readMessage(ByteArrayInputStream(out)) }.isFailure)
    }

    private val outputSha = "c".repeat(64)
    private fun committed(hash: String? = outputSha, bytes: Long = 100L) =
        JournalItem("item", JournalItemState.COMMITTED, mapOf(0 to sha), "content://media/external/images/media/1", bytes, outputSha256 = hash)
    private fun seen(pending: Boolean = false, trashed: Boolean = false, size: Long = 100L, inFolder: Boolean = true) =
        OutputObservation(pending, trashed, size, inFolder)

    @Test
    fun receiptNamesOnlyAssetsThatStillMatchTheRecordedOutputHash() {
        val verify = { item: JournalItem, observation: OutputObservation?, failed: Boolean, hash: String? ->
            ReceivedOutputCheck.classify(item, observation, failed) { hash }
        }
        assertEquals(OutputVerdict.VERIFIED, verify(committed(), seen(), false, outputSha))
        // Same size, different bytes (edited in place): size alone must not verify.
        assertEquals(OutputVerdict.CHANGED, verify(committed(), seen(), false, "d".repeat(64)))
        // Deleted, trashed, moved out of the NasFinder folder, size changed.
        assertEquals(OutputVerdict.ABSENT, verify(committed(), null, false, outputSha))
        assertEquals(OutputVerdict.CHANGED, verify(committed(), seen(trashed = true), false, outputSha))
        assertEquals(OutputVerdict.CHANGED, verify(committed(), seen(inFolder = false), false, outputSha))
        assertEquals(OutputVerdict.CHANGED, verify(committed(), seen(size = 99L), false, outputSha))
        // A legacy record without an output hash is never promoted by size alone.
        assertEquals(OutputVerdict.LEGACY_UNVERIFIABLE, verify(committed(hash = null), seen(), false, outputSha))
        // Unreadable evidence is unknown, not deletable and not "gone".
        assertEquals(OutputVerdict.UNKNOWN, verify(committed(), seen(), true, null))
        assertEquals(OutputVerdict.UNKNOWN, verify(committed(), seen(), false, null))
        // A still-pending row is reported as pending (recovery may remove only that), never as verified.
        assertEquals(OutputVerdict.PENDING, verify(committed(), seen(pending = true), false, outputSha))
    }

    @Test
    fun differingStoredCopyIsCountedSeparatelyFromConfirmed() {
        assertEquals("3개 보내기 완료", sendCompletionText(true, 3, 3, 0, 3))
        val text = sendCompletionText(true, 3, 2, 1, 3)
        assertTrue(text.startsWith("2/3개만 저장이 확인되었습니다."))
        assertTrue(text.contains("보낸 내용과 다릅니다"))
    }

    private fun shareItem(order: Int, uri: String, hash: String?) =
        PendingShareItem(java.io.File("/x/$order.jpg"), "n$order", "image/jpeg", false, uri, order, hash)
    private fun share(id: String, vararg items: PendingShareItem, failedUri: String? = null) = PendingMediaShare(
        id, 0L, items.toList(), emptyList(), listOfNotNull(failedUri?.let { PendingShareFailure("f", it, "x") }),
    )

    @Test
    fun sameUriWithNewContentIsAnotherBatchButIdenticalRevisionIsRedelivery() {
        val existing = share("a", shareItem(0, "content://p/1", "1".repeat(64)))
        assertTrue(isSameShareRevision(existing, share("b", shareItem(0, "content://p/1", "1".repeat(64)))))
        // Provider reused the URI for new content: must be kept.
        assertTrue(!isSameShareRevision(existing, share("b", shareItem(0, "content://p/1", "2".repeat(64)))))
        // Different URI with identical bytes is not folded away either (no cross-source duplicate guess).
        assertTrue(!isSameShareRevision(existing, share("b", shareItem(0, "content://p/2", "1".repeat(64)))))
        // Legacy manifest without hashes never matches.
        assertTrue(!isSameShareRevision(share("a", shareItem(0, "content://p/1", null)), share("b", shareItem(0, "content://p/1", null))))
        // Nothing could be read now: the same URI proves nothing about what it holds, so the error batch is never folded.
        val unreadable = PendingMediaShare("c", 0L, emptyList(), emptyList(), listOf(PendingShareFailure("f", "content://p/1", "x")))
        assertTrue(!isSameShareRevision(existing, unreadable))
        assertTrue(!isSameShareRevision(share("a", shareItem(0, "content://p/1", "1".repeat(64)), failedUri = "content://p/1"), unreadable))
    }

    @Test
    fun unconfirmableEarlierOutputIsReportedFailedAndNeverResentOnTheSameTransfer() {
        val item = JournalItem("i1", JournalItemState.COMMITTED, mapOf(0 to sha), "content://m/1", 10L, outputSha256 = sha)
        assertEquals("outmissing", ReceivedOutputCheck.failureReason(OutputVerdict.ABSENT))
        assertEquals("outmodified", ReceivedOutputCheck.failureReason(OutputVerdict.CHANGED))
        // Unknown, legacy and pending stay "unverified"; they are not promoted to missing/modified.
        listOf(OutputVerdict.UNKNOWN, OutputVerdict.LEGACY_UNVERIFIABLE, OutputVerdict.PENDING).forEach {
            assertEquals("outunverified", ReceivedOutputCheck.failureReason(it))
        }
        val review = CommittedReview(
            listOf(item.copy(itemId = "ok")),
            linkedMapOf("gone" to OutputVerdict.ABSENT, "edited" to OutputVerdict.CHANGED, "unk" to OutputVerdict.UNKNOWN),
        )
        val items = committedReceiptItems(review)
        // Verified stays committed; every other item is kept in the receipt as failed with its reason (not dropped).
        assertEquals(listOf("ok" to true), items.filter { it.committed }.map { it.itemId to it.committed })
        assertEquals(
            mapOf("gone" to "outmissing", "edited" to "outmodified", "unk" to "outunverified"),
            items.filter { !it.committed }.associate { it.itemId to it.reason },
        )
        // The sender does not resend any of them on a reconnect of the same transferId (they were not "removed to be resent").
        val plans = mapOf("ok" to mapOf(0 to sha), "gone" to mapOf(0 to sha), "edited" to mapOf(0 to sha), "unk" to mapOf(0 to sha), "new" to mapOf(0 to sha))
        assertEquals(setOf("ok", "gone", "edited", "unk"), itemsNotToResend(items) { plans[it] })
        // Only an explicit new-send choice lists these as the failed set; a plain unconfirmed item is not one of them.
        val outcomes = items.associate { it.itemId to PhotoTransferItemOutcome(it.itemId, it.committed, it.committed, it.reason) } +
            ("never" to PhotoTransferItemOutcome("never", false, false, "unconfirmed"))
        val report = PhotoTransferSendReport(transferId, true, receiverId, 0, outcomes, emptyMap(), "final")
        assertEquals(setOf("gone", "edited", "unk"), failedOutputItemIds(report))
        assertTrue(outputFailureNote(3).contains("새로 보내기"))
    }

    @Test
    fun failedOutputReasonCodesPassMacAndFraming() {
        val sessionKey = PhotoTransferAuth.sessionKey(PhotoTransferAuth.deriveKey(token), "senderNonce000000000000", "receiverNonce0000000000")
        val items = listOf("outmissing", "outmodified", "outunverified").mapIndexed { index, reason ->
            PhotoTransferReceiptItem("item$index", false, reason = reason)
        }
        listOf("ready" to null, "final" to "partial", "error" to "failed").forEachIndexed { index, (stage, status) ->
            val receipt = PhotoTransferReceipt(stage, transferId, receiverId, status, false, index + 1, items)
            val bytes = ByteArrayOutputStream().also { PhotoTransferWireCodec.writeReceipt(it, receipt, sessionKey) }.toByteArray()
            assertEquals(receipt, PhotoTransferWireCodec.readReceipt(ByteArrayInputStream(bytes), sessionKey, index + 1))
            // Changing the reason after the MAC was computed is rejected.
            val forged = String(bytes, Charsets.UTF_8).replace("outmissing", "outmodified").toByteArray(Charsets.UTF_8)
            assertTrue(runCatching { PhotoTransferWireCodec.readReceipt(ByteArrayInputStream(forged), sessionKey, index + 1) }.isFailure)
        }
        // Canonical line keeps the documented shape for a failed item with a reason.
        val canonical = PhotoTransferAuth.receiptCanonical(PhotoTransferReceipt("final", transferId, receiverId, "partial", false, 1, items.take(1)))
        assertTrue(canonical.endsWith("\nitem0|failed||outmissing"))
    }
}
