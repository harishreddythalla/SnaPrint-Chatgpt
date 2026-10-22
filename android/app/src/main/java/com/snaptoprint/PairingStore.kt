package com.snaptoprint

import android.content.Context
import java.security.SecureRandom

object PairingStore {
    private const val PREFS = "snap_to_print"
    private const val CODE = "pairing_code"
    private const val TOKEN = "auth_token"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun pairingCode(context: Context): String {
        val p = prefs(context)
        val existing = p.getString(CODE, null)
        if (!existing.isNullOrBlank()) return existing
        val code = (100000 + SecureRandom().nextInt(900000)).toString()
        p.edit().putString(CODE, code).apply()
        return code
    }

    @Synchronized
    fun pair(context: Context, suppliedCode: String): String? {
        val p = prefs(context)
        // A pairing code can provision only one laptop. Existing sessions authenticate with the saved random token.
        if (!p.getString(TOKEN, null).isNullOrBlank()) return null
        if (suppliedCode != pairingCode(context)) return null
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        p.edit().putString(TOKEN, token).apply()
        return token
    }

    fun hasPaired(context: Context): Boolean = !prefs(context).getString(TOKEN, null).isNullOrBlank()

    fun resetPairing(context: Context) {
        val p = prefs(context)
        val code = (100000 + SecureRandom().nextInt(900000)).toString()
        p.edit().putString(CODE, code).remove(TOKEN).apply()
    }
}
