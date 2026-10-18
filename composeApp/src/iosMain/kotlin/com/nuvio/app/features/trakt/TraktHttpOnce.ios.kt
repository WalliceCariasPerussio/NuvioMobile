package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse
import com.nuvio.app.features.addons.httpRequestRaw

// NSURLSession does not resend a POST on its own, so the usual request already goes out at most once.
internal actual suspend fun httpPostOnce(
    url: String,
    headers: Map<String, String>,
    body: String,
): RawHttpResponse = httpRequestRaw(method = "POST", url = url, headers = headers, body = body)
