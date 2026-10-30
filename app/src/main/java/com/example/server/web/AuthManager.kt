package com.example.server.web

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

class AuthManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "mc_droid_security_prefs"
        private const val KEY_PASSWORD_HASH = "admin_password_hash"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val activeTokens = ConcurrentHashMap<String, Long>() // token -> expiryMs
    private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>() // ip -> (count, lockoutUntil)

    fun isPasswordSet(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETED, false)

    fun setMasterPassword(password: String): Boolean {
        if (password.length < 4) return false
        val hash = hashString(password)
        prefs.edit()
            .putString(KEY_PASSWORD_HASH, hash)
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .apply()
        return true
    }

    fun verifyPassword(candidate: String, clientIp: String): Pair<Boolean, String?> {
        val now = System.currentTimeMillis()
        val lockoutInfo = failedAttempts[clientIp]
        if (lockoutInfo != null) {
            val (count, lockoutUntil) = lockoutInfo
            if (count >= MAX_FAILED_ATTEMPTS && now < lockoutUntil) {
                val remainingSec = (lockoutUntil - now) / 1000
                return Pair(false, "Account locked due to failed attempts. Try again in ${remainingSec}s")
            }
        }

        val storedHash = prefs.getString(KEY_PASSWORD_HASH, null) ?: return Pair(false, "No password set yet")
        val candidateHash = hashString(candidate)

        if (storedHash == candidateHash) {
            failedAttempts.remove(clientIp)
            val token = generateSecureToken()
            val expiry = now + (24 * 60 * 60 * 1000L) // 24 hours
            activeTokens[token] = expiry
            return Pair(true, token)
        } else {
            val prevCount = failedAttempts[clientIp]?.first ?: 0
            val newCount = prevCount + 1
            val lockoutUntil = if (newCount >= MAX_FAILED_ATTEMPTS) now + LOCKOUT_DURATION_MS else 0L
            failedAttempts[clientIp] = Pair(newCount, lockoutUntil)
            val attemptsLeft = MAX_FAILED_ATTEMPTS - newCount
            return Pair(false, if (attemptsLeft > 0) "Invalid password ($attemptsLeft attempts left)" else "Locked out for 5 minutes")
        }
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val expiry = activeTokens[token] ?: return false
        if (System.currentTimeMillis() > expiry) {
            activeTokens.remove(token)
            return false
        }
        return true
    }

    private fun hashString(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
