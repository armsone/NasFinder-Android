package com.armsone.nasfinder.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class SftpAndSmbPolicyTest {
    @Test
    fun `SFTP credential keeps ordinary values as passwords`() {
        val credential = SftpCredential.parse("  ordinary password  ") as SftpCredential.Password

        assertEquals("  ordinary password  ", credential.value)
    }

    @Test
    fun `SFTP credential recognizes unencrypted PEM and OpenSSH keys`() {
        val pem = "-----BEGIN PRIVATE KEY-----\nYWJj\n-----END PRIVATE KEY-----\n"
        val pemCredential = SftpCredential.parse(pem) as SftpCredential.PrivateKey
        assertArrayEquals(pem.toByteArray(), pemCredential.encoded)

        val openssh = opensshKey("none")
        val opensshCredential = SftpCredential.parse(openssh) as SftpCredential.PrivateKey
        assertArrayEquals(openssh.toByteArray(), opensshCredential.encoded)
    }

    @Test
    fun `SFTP credential rejects encrypted keys without exposing key material`() {
        val marker = "private-key-secret-marker"
        val encryptedPem = "-----BEGIN ENCRYPTED PRIVATE KEY-----\n$marker\n-----END ENCRYPTED PRIVATE KEY-----"
        val pemError = assertThrows(RemoteServiceException.Unsupported::class.java) {
            SftpCredential.parse(encryptedPem)
        }
        val opensshError = assertThrows(RemoteServiceException.Unsupported::class.java) {
            SftpCredential.parse(opensshKey("aes256-ctr"))
        }

        assertFalse(pemError.message.orEmpty().contains(marker))
        assertFalse(opensshError.message.orEmpty().contains("aes256-ctr"))
    }

    @Test
    fun `SMB transfer policy confines operations to one share and blocks descendants`() {
        assertTrue(SmbTransferPolicy.sameShare("Media", "media"))
        assertFalse(SmbTransferPolicy.sameShare("Media", "Backup"))
        assertTrue(SmbTransferPolicy.isSameOrDescendant("/Media/Folder", "/media/folder"))
        assertTrue(SmbTransferPolicy.isSameOrDescendant("/Media/Folder/Child", "/media/folder"))
        assertFalse(SmbTransferPolicy.isSameOrDescendant("/Media/Folder-copy", "/media/folder"))
    }

    private fun opensshKey(cipher: String): String {
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        val cipherBytes = cipher.toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(magic.size + 4 + cipherBytes.size)
            .put(magic)
            .putInt(cipherBytes.size)
            .put(cipherBytes)
            .array()
        return "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(payload) +
            "\n-----END OPENSSH PRIVATE KEY-----\n"
    }
}
