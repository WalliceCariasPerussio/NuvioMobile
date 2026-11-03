package com.nuvio.app.features.ai

import platform.Foundation.NSUserDefaults

actual object AiAssistantStorage {
    private const val payloadKey = "ai_assistant_settings"

    actual fun loadPayload(): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(payloadKey)

    actual fun savePayload(payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = payloadKey)
    }
}
