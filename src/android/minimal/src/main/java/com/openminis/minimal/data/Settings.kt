package com.openminis.minimal.data

// [T-minimal] The three things this app remembers about its owner: a key, a
// model, and whether the welcome screen has been seen.
//
// SharedPreferences rather than EncryptedSharedPreferences. That deserves an
// argument rather than a shrug: app-private preferences are readable only by
// this uid on a non-rooted device, and EncryptedSharedPreferences brings
// androidx.security plus a keystore that has historically failed to open on some
// OEMs — a failure mode where the key is unreadable and the app must therefore
// re-ask. If this app ever syncs, backs up or exports anything containing the
// key, that trade changes and this comment is the record of why.

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("minimal", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString(KEY_API, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_API, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, Models.FLASH) ?: Models.FLASH
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    /**
     * Versioned. The welcome screen is replaced, not adjusted, when its design
     * changes — and a device that answered the previous one answered a different
     * question.
     */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED_V1, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED_V1, value).apply()

    val hasKey: Boolean get() = apiKey.isNotBlank()

    private companion object {
        const val KEY_API = "api_key"
        const val KEY_MODEL = "model"
        const val KEY_ONBOARDED_V1 = "onboarded_v1"
    }
}
