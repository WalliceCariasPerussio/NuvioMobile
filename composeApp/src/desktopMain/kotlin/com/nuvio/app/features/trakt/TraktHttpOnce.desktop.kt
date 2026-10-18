package com.nuvio.app.features.trakt

import com.nuvio.app.core.network.DesktopIPv4FirstDns
import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val sendOnceClient = OkHttpClient.Builder()
    .dns(DesktopIPv4FirstDns())
    .connectTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .build()

internal actual suspend fun httpPostOnce(
    url: String,
    headers: Map<String, String>,
    body: String,
): RawHttpResponse = withContext(Dispatchers.IO) {
    sendOnceClient.newCall(buildPostOnceRequest(url, headers, body)).execute().use { response ->
        RawHttpResponse(
            status = response.code,
            statusText = response.message,
            url = response.request.url.toString(),
            body = response.body?.string().orEmpty(),
            headers = response.headers.toMultimap()
                .mapValues { (_, values) -> values.joinToString(",") }
                .mapKeys { (name, _) -> name.lowercase() },
        )
    }
}

private fun buildPostOnceRequest(url: String, headers: Map<String, String>, body: String): Request {
    val contentType = headers.entries.firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
        ?.value ?: "application/json"
    return Request.Builder()
        .url(url)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .post(body.toRequestBody(contentType.toMediaType()))
        .build()
}
