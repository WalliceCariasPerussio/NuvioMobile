package com.nuvio.app.features.trakt

import com.nuvio.app.features.addons.RawHttpResponse

/**
 * Fork: a POST sent at most once, for a request that is not idempotent (a Trakt history add adds a
 * play each time). The HTTP client must not resend it on its own: OkHttp retries a request whose
 * connection dropped even after it went out, and Trakt may have recorded it by then.
 */
internal expect suspend fun httpPostOnce(
    url: String,
    headers: Map<String, String>,
    body: String,
): RawHttpResponse
