package com.armsone.nasfinder.platform

import com.armsone.nasfinder.model.ConnectionKind
import com.armsone.nasfinder.model.RemoteFileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafSftpMutationPolicyTest {
    @Test fun `mutations are SFTP only`() {
        assertTrue(SafSftpMutationPolicy.supportsMutations(ConnectionKind.SFTP))
        ConnectionKind.entries.filterNot { it == ConnectionKind.SFTP }.forEach {
            assertFalse(SafSftpMutationPolicy.supportsMutations(it))
        }
        assertEquals(
            SafSftpMutationPolicy.Capabilities(createFolder = true),
            SafSftpMutationPolicy.capabilities(ConnectionKind.SFTP, isRoot = true, isDirectory = true),
        )
        assertEquals(
            SafSftpMutationPolicy.Capabilities(createFolder = true, rename = true, delete = true, move = true),
            SafSftpMutationPolicy.capabilities(ConnectionKind.SFTP, isRoot = false, isDirectory = true),
        )
        assertEquals(
            SafSftpMutationPolicy.Capabilities(rename = true, delete = true, move = true),
            SafSftpMutationPolicy.capabilities(ConnectionKind.SFTP, isRoot = false, isDirectory = false),
        )
        assertEquals(
            SafSftpMutationPolicy.Capabilities(),
            SafSftpMutationPolicy.capabilities(ConnectionKind.SYNOLOGY, isRoot = false, isDirectory = true),
        )
    }

    @Test fun `names cannot traverse inject lines or add path separators`() {
        assertEquals("Folder", SafSftpMutationPolicy.validatedName(" Folder "))
        listOf("", " ", ".", "..", ".hidden", "../escape", "a/b", "a\\b", "a\r\nb", "a\u0000b").forEach { value ->
            assertFails<IllegalArgumentException> { SafSftpMutationPolicy.validatedName(value) }
        }
    }

    @Test fun `path append and immediate parent preserve absolute and SFTP dot roots`() {
        assertEquals("/Folder", SafSftpMutationPolicy.append("/", "Folder"))
        assertEquals("./Folder", SafSftpMutationPolicy.append(".", "Folder"))
        assertEquals("./base/Folder", SafSftpMutationPolicy.append("./base", "Folder"))
        assertTrue(SafSftpMutationPolicy.isImmediateChild("./base/Folder", "./base"))
        assertFalse(SafSftpMutationPolicy.isImmediateChild("./base/nested/Folder", "./base"))
        assertTrue(SafSftpMutationPolicy.isSameOrDescendant("/root/child", "/root"))
        assertFalse(SafSftpMutationPolicy.isSameOrDescendant("/root-private/child", "/root"))
        assertTrue(SafSftpMutationPolicy.isSameOrDescendant("./child", "."))
        assertFalse(SafSftpMutationPolicy.isSameOrDescendant("/absolute", "."))
    }

    @Test fun `post mutation verification requires one exact path and type`() {
        val expected = item("/target/Folder", directory = true)
        assertEquals(expected, SafSftpMutationPolicy.verifiedItem(listOf(expected), expected.path, true))
        assertNull(SafSftpMutationPolicy.verifiedItem(listOf(expected), expected.path, false))
        assertNull(SafSftpMutationPolicy.verifiedItem(listOf(expected, expected.copy(id = "duplicate")), expected.path, true))
        assertNull(SafSftpMutationPolicy.verifiedItem(emptyList(), expected.path, true))
    }

    @Test fun `partial success error explicitly forbids blind retry`() {
        val error = MutationMayHaveSucceededException("이동")
        assertTrue(error.remoteStateMayHaveChanged)
        assertFalse(error.safeToRetryAutomatically)
        assertTrue(error.message.orEmpty().contains("일부 또는 전부 반영"))
        assertTrue(error.message.orEmpty().contains("새로고침"))
    }

    private fun item(path: String, directory: Boolean) = RemoteFileItem(
        id = path,
        name = path.substringAfterLast('/'),
        path = path,
        isDirectory = directory,
    )

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
