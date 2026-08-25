package com.armsone.nasfinder.googlephotos

import java.io.File
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class GooglePhotosHttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null) {
    override fun toString(): String = "GooglePhotosHttpRequest(method=$method, url=<redacted>, headerNames=${headers.keys}, body=${if (body == null) "null" else "<redacted>"})"
}
data class GooglePhotosHttpResponse(val status: Int, val body: String = "")
fun interface GooglePhotosHttpTransport { suspend fun execute(request: GooglePhotosHttpRequest): GooglePhotosHttpResponse }

class OkHttpGooglePhotosTransport(private val client: OkHttpClient = OkHttpClient()) : GooglePhotosHttpTransport {
    override suspend fun execute(request: GooglePhotosHttpRequest): GooglePhotosHttpResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url)
        request.headers.forEach(builder::header)
        val body = request.body?.toRequestBody(request.headers["Content-Type"]?.toMediaType())
        builder.method(request.method, body)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            GooglePhotosHttpResponse(response.code, if (text.length <= 1_048_576) text else "")
        }
    }
}

class GooglePhotosTokenClient(
    private val configuration: GooglePhotosOAuthConfiguration,
    private val transport: GooglePhotosHttpTransport,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun exchange(code: String, verifier: String) = token(mapOf("client_id" to configuration.clientId, "code" to code,
        "code_verifier" to verifier, "grant_type" to "authorization_code", "redirect_uri" to configuration.redirectUri), null)
    suspend fun refresh(credential: GooglePhotosCredential): GooglePhotosCredential {
        val refresh = credential.refreshToken?.takeIf(String::isNotBlank) ?: throw GooglePhotosOAuthException.ReauthorizationRequired
        return token(mapOf("client_id" to configuration.clientId, "grant_type" to "refresh_token", "refresh_token" to refresh), refresh)
    }
    suspend fun revoke(credential: GooglePhotosCredential) {
        val response = transport.execute(formRequest(configuration.revocationEndpoint, mapOf("token" to (credential.refreshToken ?: credential.accessToken))))
        if (response.status !in 200..299) throw GooglePhotosOAuthException.RequestFailed(response.status)
    }
    private suspend fun token(values: Map<String, String>, previousRefresh: String?): GooglePhotosCredential {
        val response = transport.execute(formRequest(configuration.tokenEndpoint, values))
        if (response.status !in 200..299) {
            if (runCatching { JSONObject(response.body).optString("error") }.getOrNull() == "invalid_grant") throw GooglePhotosOAuthException.ReauthorizationRequired
            throw GooglePhotosOAuthException.RequestFailed(response.status)
        }
        val json = runCatching { JSONObject(response.body) }.getOrElse { throw GooglePhotosOAuthException.RequestFailed(response.status) }
        val access = json.optString("access_token").takeIf(String::isNotBlank) ?: throw GooglePhotosOAuthException.RequestFailed(response.status)
        return GooglePhotosCredential(access, json.optString("refresh_token").takeIf(String::isNotBlank) ?: previousRefresh,
            json.optLong("expires_in").takeIf { it > 0 }?.let { now().epochSecond + it },
            json.optString("scope", configuration.scope).split(' ').filter(String::isNotBlank).toSet())
    }
    private fun formRequest(url: String, values: Map<String, String>) = GooglePhotosHttpRequest("POST", url,
        mapOf("Content-Type" to "application/x-www-form-urlencoded"), values.entries.joinToString("&") { "${GooglePhotosOAuthPolicy.encode(it.key)}=${GooglePhotosOAuthPolicy.encode(it.value)}" })
}

enum class GooglePhotosMediaType { PHOTO, VIDEO, UNSPECIFIED }
enum class GooglePhotosVideoStatus { PROCESSING, READY, FAILED, UNSPECIFIED }
data class GooglePhotosMediaFile(val baseUrl: String, val mimeType: String?, val filename: String?, val videoStatus: GooglePhotosVideoStatus = GooglePhotosVideoStatus.UNSPECIFIED)
data class GooglePhotosMediaItem(val id: String, val type: GooglePhotosMediaType, val mediaFile: GooglePhotosMediaFile?)
data class GooglePhotosPickingSession(val id: String, val pickerUri: String?, val pollIntervalSeconds: Double?, val timeoutSeconds: Double?, val mediaItemsSet: Boolean)
data class GooglePhotosMediaPage(val items: List<GooglePhotosMediaItem>, val nextPageToken: String?)

sealed class GooglePhotosPickerException(message: String) : Exception(message) {
    data object InvalidResponse : GooglePhotosPickerException("Google Photos 응답을 해석하지 못했습니다.")
    data object InvalidContentUrl : GooglePhotosPickerException("Google Photos 콘텐츠 주소가 올바르지 않습니다.")
    data class Http(val statusCode: Int) : GooglePhotosPickerException("Google Photos 요청이 실패했습니다. (HTTP $statusCode)")
}

class GooglePhotosPickerClient(
    private val transport: GooglePhotosHttpTransport, private val token: suspend () -> String,
    private val baseUrl: String = "https://photospicker.googleapis.com",
) {
    suspend fun createSession(maxItems: Int = 50): GooglePhotosPickingSession = session(call("POST", "/v1/sessions", JSONObject().put("pickingConfig", JSONObject().put("maxItemCount", maxItems.toString())).toString()))
    suspend fun fetchSession(id: String): GooglePhotosPickingSession = session(call("GET", "/v1/sessions/${path(id)}"))
    suspend fun deleteSession(id: String) { call("DELETE", "/v1/sessions/${path(id)}") }
    suspend fun allMediaItems(sessionId: String, pageSize: Int? = null): List<GooglePhotosMediaItem> {
        val result = mutableListOf<GooglePhotosMediaItem>(); var next: String? = null; val seen = mutableSetOf<String>()
        do {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val query = buildList { add("sessionId=${GooglePhotosOAuthPolicy.encode(sessionId)}"); pageSize?.let { add("pageSize=$it") }; next?.let { add("pageToken=${GooglePhotosOAuthPolicy.encode(it)}") } }.joinToString("&")
            val page = mediaPage(call("GET", "/v1/mediaItems?$query")); result += page.items
            next = page.nextPageToken?.takeIf(String::isNotBlank)?.takeIf(seen::add)
        } while (next != null)
        return result
    }
    suspend fun contentRequest(item: GooglePhotosMediaItem): GooglePhotosHttpRequest {
        val raw = item.mediaFile?.baseUrl?.trim()?.removeSuffix("=dv")?.removeSuffix("=d") ?: throw GooglePhotosPickerException.InvalidContentUrl
        val uri = runCatching { URI(raw) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) throw GooglePhotosPickerException.InvalidContentUrl
        return GooglePhotosHttpRequest("GET", raw + if (item.type == GooglePhotosMediaType.VIDEO) "=dv" else "=d", mapOf("Authorization" to "Bearer ${token()}"))
    }
    private suspend fun call(method: String, path: String, body: String? = null): String {
        val response = transport.execute(GooglePhotosHttpRequest(method, "$baseUrl$path", mapOf("Accept" to "application/json", "Authorization" to "Bearer ${token()}") + if (body != null) mapOf("Content-Type" to "application/json") else emptyMap(), body))
        if (response.status !in 200..299) throw GooglePhotosPickerException.Http(response.status)
        return response.body
    }
    private fun session(raw: String): GooglePhotosPickingSession = runCatching {
        val j = JSONObject(raw); val polling = j.optJSONObject("pollingConfig")
        GooglePhotosPickingSession(j.getString("id"), j.optString("pickerUri").takeIf(String::isNotBlank), duration(polling?.optString("pollInterval")), duration(polling?.optString("timeoutIn")), j.optBoolean("mediaItemsSet"))
    }.getOrElse { throw GooglePhotosPickerException.InvalidResponse }
    private fun mediaPage(raw: String): GooglePhotosMediaPage = runCatching {
        val j = JSONObject(raw); val array = j.optJSONArray("mediaItems"); val items = buildList {
            for (i in 0 until (array?.length() ?: 0)) { val x = array!!.getJSONObject(i); val f = x.optJSONObject("mediaFile"); val metadata = f?.optJSONObject("mediaFileMetadata")?.optJSONObject("videoMetadata")
                add(GooglePhotosMediaItem(x.getString("id"), runCatching { GooglePhotosMediaType.valueOf(x.optString("type")) }.getOrDefault(GooglePhotosMediaType.UNSPECIFIED),
                    f?.let { GooglePhotosMediaFile(it.optString("baseUrl"), it.optString("mimeType").takeIf(String::isNotBlank), it.optString("filename").takeIf(String::isNotBlank), runCatching { GooglePhotosVideoStatus.valueOf(metadata?.optString("processingStatus").orEmpty()) }.getOrDefault(GooglePhotosVideoStatus.UNSPECIFIED)) })) }
        }; GooglePhotosMediaPage(items, j.optString("nextPageToken").takeIf(String::isNotBlank))
    }.getOrElse { throw GooglePhotosPickerException.InvalidResponse }
    private fun duration(raw: String?): Double? = raw?.removeSuffix("s")?.toDoubleOrNull()
    private fun path(value: String) = GooglePhotosOAuthPolicy.encode(value)
}

class GooglePhotosContentDownloader(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun download(request: GooglePhotosHttpRequest, destination: File): File = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(request.url); request.headers.forEach(builder::header)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw GooglePhotosPickerException.Http(response.code)
            destination.parentFile?.mkdirs(); val partial = File(destination.parentFile, destination.name + ".partial")
            try { response.body.byteStream().use { input -> partial.outputStream().use(input::copyTo) }; if (!partial.renameTo(destination)) throw java.io.IOException("Unable to finalize Google Photos download") }
            catch (error: Throwable) { partial.delete(); throw error }
        }; destination
    }
}
