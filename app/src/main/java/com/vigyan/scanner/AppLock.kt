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
        prefs.edit().putString(KEY_SALT, salt).putString(KEY_HASH, PinHash.hash(salt, pin))
            .putInt(KEY_LEN, pin.length).putInt(KEY_WRONG, 0).putLong(KEY_UNTIL, 0L).apply()
    }

    /** How many digits the PIN has (0 = not known: set before this was saved, so OK must be pressed). */
    val pinLength get() = prefs.getInt(KEY_LEN, 0)

    /** Wrong tries and the end of the wait are kept on the phone, so closing the app does not reset them. */
    val waitUntil get() = prefs.getLong(KEY_UNTIL, 0L)

    /** Counts a wrong PIN; every 5th starts a wait (30 s, then longer each time, up to 15 minutes). Returns the wait's end, or 0. */
    fun noteWrong(now: Long = System.currentTimeMillis()): Long {
        val n = prefs.getInt(KEY_WRONG, 0) + 1
        val until = if (n % 5 == 0) now + LockRules.waitMillis(n) else 0L
        prefs.edit().putInt(KEY_WRONG, n).apply { if (until > 0) putLong(KEY_UNTIL, until) }.apply()
        return until
    }

    fun noteRight() { prefs.edit().putInt(KEY_WRONG, 0).putLong(KEY_UNTIL, 0L).apply() }

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
        const val KEY_LEN = "pin_len"
        const val KEY_WRONG = "wrong_tries"
        const val KEY_UNTIL = "wait_until"
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

/** The wait after wrong PINs. Pure Kotlin, unit-tested. */
object LockRules {
    /** 5 wrong → 30 s, 10 → 1 min, 15 → 2 min … up to 15 minutes. */
    fun waitMillis(wrongTries: Int): Long {
        val step = (wrongTries / 5 - 1).coerceIn(0, 10)
        return minOf(30_000L shl step, 15 * 60_000L)
    }
}
