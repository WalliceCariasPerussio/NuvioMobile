package com.nuvio.app.features.details

// Some addons answer a throttled request with HTTP 200 and a fake meta whose name is the error
// ("⚠️ Rate limit exceeded — please wait a few minutes before trying again."). Taking it as the
// title shows the error on the details screen and saves it as the title of the watch progress.
private val addonErrorNamePatterns = listOf(
    Regex("""\brate[ -]?limit""", RegexOption.IGNORE_CASE),
    Regex("""\btoo many requests\b""", RegexOption.IGNORE_CASE),
    Regex("""\bplease (wait|try again)\b""", RegexOption.IGNORE_CASE),
)

internal fun String.looksLikeAddonError(): Boolean =
    addonErrorNamePatterns.any { it.containsMatchIn(this) }
