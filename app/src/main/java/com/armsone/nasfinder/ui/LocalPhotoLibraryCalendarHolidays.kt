package com.armsone.nasfinder.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Same public, read-only holiday feed as HanClip. No media or account data leaves the device. */
internal object LocalPhotoLibraryCalendarHolidays {
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun load(context: Context): Map<LocalDate, String> = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences("calendar.holidays.v1", Context.MODE_PRIVATE)
        val cached = preferences.getString("json", null)
        if (cached != null && System.currentTimeMillis() - preferences.getLong("updated", 0L) < TimeUnit.DAYS.toMillis(1)) {
            return@withContext decode(cached)
        }
        try {
            client.newCall(Request.Builder().url("https://holidays.hyunbin.page/basic.json").get().build()).execute().use { response ->
                check(response.isSuccessful)
                val body = response.body ?: error("Missing holiday data")
                check(body.contentLength() <= 2 * 1024 * 1024)
                val raw = body.string()
                check(raw.length <= 2 * 1024 * 1024)
                val names = decode(raw)
                check(names.isNotEmpty())
                preferences.edit().putString("json", raw).putLong("updated", System.currentTimeMillis()).apply()
                names
            }
        } catch (_: Exception) {
            cached?.let { runCatching { decode(it) }.getOrNull() }.orEmpty()
        }
    }

    private fun decode(raw: String): Map<LocalDate, String> {
        val result = mutableMapOf<LocalDate, String>()
        fun visit(value: JSONObject) {
            value.keys().forEach { key ->
                val item = value.opt(key)
                val date = runCatching {
                    val parts = key.split('-').map(String::toInt)
                    if (parts.size == 3) LocalDate.of(parts[0], parts[1], parts[2]) else null
                }.getOrNull()
                if (date != null) {
                    val name = when (item) {
                        is JSONArray -> item.optString(0)
                        is String -> item
                        else -> ""
                    }
                    if (name.isNotBlank()) result[date] = name
                } else if (item is JSONObject) visit(item)
            }
        }
        visit(JSONObject(raw))
        return result
    }
}
