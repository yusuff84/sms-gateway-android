package com.smsgateway.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "wss://sms.loca-li.com") ?: "wss://sms.loca-li.com"
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value.trim()).apply()

    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "sms_dev_android_gateway_token_9999") ?: ""
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value.trim()).apply()

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE_ENABLED, value).apply()

    var simMode: Int
        get() = prefs.getInt(KEY_SIM_MODE, 0) // 0: Auto-Rotate, 1: SIM 1, 2: SIM 2
        set(value) = prefs.edit().putInt(KEY_SIM_MODE, value).apply()

    companion object {
        private const val PREF_NAME = "sms_gateway_preferences"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_TOKEN = "device_token"
        private const val KEY_SERVICE_ENABLED = "service_enabled"
        private const val KEY_SIM_MODE = "sim_mode"
    }
}
