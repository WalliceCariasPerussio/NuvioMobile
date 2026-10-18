package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.AddonHttpClientProvider
import com.nuvio.app.features.addons.RawHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal actual suspend fun httpPostOnce(
    url: String,
    headers: Map<String, String>,
    body: String,
): RawHttpResponse = withContext(Dispatchers.IO) {
    val contentType = headers.entries.firstOrNull { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
        ?.value ?: "application/json"
    val request = Request.Builder()
        .url(url)
        .apply { headers.forEach { (name, value) -> header(name, value) } }
        .post(body.toRequestBody(contentType.toMediaType()))
        .build()
    // The app's client (same connections), minus OkHttp's resend of a request whose connection dropped.
    val client = AddonHttpClientProvider.get().newBuilder().retryOnConnectionFailure(false).build()
    client.newCall(request).execute().use { response ->
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
