package com.vigyan.scanner

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * App lock: a 4–6 digit PIN (only a salted hash is stored, on this phone), optional fingerprint,
 * and how long the app may stay in the background before it locks again.
 */
class AppLock(context: Context) {

    private val prefs = context.getSharedPreferences("app_lock", Context.MODE_PRIVATE)

    val enabled get() = prefs.getString(KEY_HASH, null) != null

    var fingerprint: Boolean
        get() = prefs.getBoolean(KEY_FINGER, false)
        set(v) = prefs.edit().putBoolean(KEY_FINGER, v).apply()

    /** Seconds in the background before locking again (0 = every time). */
    var timeoutSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT, 60)
        set(v) = prefs.edit().putInt(KEY_TIMEOUT, v).apply()

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, salt).putString(KEY_HASH, PinHash.hash(salt, pin)).apply()
    }

    fun check(pin: String): Boolean {
        val salt = prefs.getString(KEY_SALT, null) ?: return false
        return PinHash.hash(salt, pin) == prefs.getString(KEY_HASH, null)
    }

    fun disable() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_HASH = "pin_hash"
        const val KEY_SALT = "pin_salt"
        const val KEY_FINGER = "fingerprint"
        const val KEY_TIMEOUT = "timeout"
    }
}

/** Salted, repeated SHA-256 of the PIN. Pure Kotlin, unit-tested. */
object PinHash {
    fun hash(salt: String, pin: String): String {
        var h = (salt + pin).toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        repeat(10_000) { h = md.digest(h) }
        return h.joinToString("") { "%02x".format(it) }
    }

    fun valid(pin: String) = pin.length in 4..6 && pin.all(Char::isDigit)
}
