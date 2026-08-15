package com.armsone.nasfinder.platform

import com.armsone.nasfinder.model.ConnectionKind
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentsProviderConnectionParityTest {
    @Test
    fun `system Files exposes the same verified backends as iOS File Provider`() {
        assertEquals(
            setOf(ConnectionKind.SYNOLOGY, ConnectionKind.SFTP),
            DOCUMENTS_PROVIDER_CONNECTION_KINDS,
        )
    }
}
