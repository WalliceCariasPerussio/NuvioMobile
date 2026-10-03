package com.nuvio.app.features.streams

enum class StreamAutoPlayMode {
    MANUAL,
    FIRST_STREAM,
    REGEX_MATCH,
    /** Fork: best quality (up to the cap) in the preferred audio language, then the secondary one. */
    BEST_PREFERRED_AUDIO,
}

/** Fork: highest quality an automatic source choice may pick (Netflix's "data usage per screen"). */
enum class StreamAutoPlayMaxQuality {
    NO_LIMIT,
    UHD_4K,
    FHD_1080,
    HD_720,
}

enum class StreamAutoPlaySource {
    ALL_SOURCES,
    INSTALLED_ADDONS_ONLY,
    ENABLED_PLUGINS_ONLY,
}
