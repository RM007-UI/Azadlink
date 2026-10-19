package com.azadlink.vpn.runtime

import android.content.Context

/**
 * UI-preview placeholder only. Actual Psiphon runtime is not wired into this build.
 */
object PsiphonTunnelRuntime {
    fun validateConfigFile(context: Context, json: String) {
        require(json.trim().startsWith("{") && json.trim().endsWith("}")) { "JSON نامعتبر است" }
        context.getSharedPreferences("azadlink_psiphon", Context.MODE_PRIVATE)
            .edit().putString("config_preview", json).apply()
    }

    fun canLoad(raw: String, context: Context): Boolean = false
}
