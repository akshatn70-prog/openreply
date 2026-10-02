package com.thehomeodoc.app

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context, "thehomeodoc_session",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    var token: String?
        get() = prefs.getString("token", null)
        set(value) { if (value == null) prefs.edit().remove("token").apply() else prefs.edit().putString("token", value).apply() }
}
