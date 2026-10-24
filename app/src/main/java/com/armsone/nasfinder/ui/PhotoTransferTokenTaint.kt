package com.armsone.nasfinder.ui

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sender-side memory of QR tokens that left this device in plaintext (legacy v3 fallback line).
 * Once the raw token was written to a socket, the QR and its secret are tainted for good: a later v4 attempt with the
 * same QR can never qualify for original deletion, and the sender refuses to reuse it at all. Only SHA-256
 * fingerprints are stored (never the token). A fresh QR (new receiver-side secret) is the only way forward.
 * Fingerprints are never evicted or capped: an exposed secret must not come back to life. An unreadable store fails
 * closed (every token counts as tainted, nothing is overwritten). Wire contract rev 4 §1.2.
 */
internal object PhotoTransferTokenTaint {
    private val lock = Any()

    fun fingerprint(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun isTainted(context: Context, token: String): Boolean = synchronized(lock) {
        val entries = read(context) ?: return@synchronized true
        fingerprint(token) in entries.keys
    }

    /** Persists before the plaintext line is written; a failure to persist aborts the legacy attempt. */
    fun markTainted(context: Context, token: String) = synchronized(lock) {
        val entries = (read(context) ?: error("노출 기록을 읽지 못해 이전 방식 연결을 중단했습니다.")).toMutableMap()
        entries.putIfAbsent(fingerprint(token), System.currentTimeMillis())
        val array = JSONArray()
        entries.forEach { (print, at) -> array.put(JSONObject().put("fp", print).put("at", at)) }
        val atomic = AtomicFile(file(context))
        val stream = atomic.startWrite()
        try {
            stream.write(JSONObject().put("tainted", array).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Throwable) {
            atomic.failWrite(stream)
            throw error
        }
    }

    private fun file(context: Context): File {
        val directory = File(context.applicationContext.noBackupFilesDir, "photo-transfer-journal").also { it.mkdirs() }
        return File(directory, "tainted-tokens.json")
    }

    /** Null means the store exists but cannot be read; callers must fail closed. */
    private fun read(context: Context): Map<String, Long>? {
        val target = file(context)
        if (!target.exists()) return emptyMap()
        return runCatching {
            val array = JSONObject(String(AtomicFile(target).readFully(), Charsets.UTF_8)).getJSONArray("tainted")
            buildMap {
                for (i in 0 until array.length()) {
                    val entry = array.getJSONObject(i)
                    put(entry.getString("fp"), entry.optLong("at", 0L))
                }
            }
        }.getOrNull()
    }
}
