package com.example.server.web

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class AuthManager(context: Context) {
    companion object {
        private const val PREFS_NAME = "mc_droid_security_prefs"
        private const val KEY_PASSWORD_HASH = "admin_password_hash_v2"
        private const val KEY_PASSWORD_SALT = "admin_password_salt_v2"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutes
        private const val PBKDF2_ITERATIONS = 12000
        private const val KEY_LENGTH_BITS = 256
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val activeTokens = ConcurrentHashMap<String, Long>() // token -> expiryMs
    private val failedAttempts = ConcurrentHashMap<String, Pair<Int, Long>>() // ip -> (count, lockoutUntil)

    fun isPasswordSet(): Boolean = prefs.getBoolean(KEY_SETUP_COMPLETED, false)

    fun setMasterPassword(password: String): Boolean {
        if (password.length < 4) return false

        // Generate 16-byte random salt
        val saltBytes = ByteArray(16)
        SecureRandom().nextBytes(saltBytes)
        val saltBase64 = Base64.encodeToString(saltBytes, Base64.NO_WRAP)

        val hashBase64 = hashWithPbkdf2(password, saltBytes)

        prefs.edit()
            .putString(KEY_PASSWORD_SALT, saltBase64)
            .putString(KEY_PASSWORD_HASH, hashBase64)
            .putBoolean(KEY_SETUP_COMPLETED, true)
            .apply()
        return true
    }

    fun verifyPassword(candidate: String, clientIp: String): Pair<Boolean, String?> {
        val now = System.currentTimeMillis()

        // 1. Check IP lockout
        val lockoutInfo = failedAttempts[clientIp]
        if (lockoutInfo != null) {
            val (count, lockoutUntil) = lockoutInfo
            if (count >= MAX_FAILED_ATTEMPTS && now < lockoutUntil) {
                val remainingSec = (lockoutUntil - now) / 1000
                return Pair(false, "Compte temporairement verrouillé pour sécurité. Réessayez dans ${remainingSec}s")
            }
        }

        val storedHash = prefs.getString(KEY_PASSWORD_HASH, null)
        val storedSalt = prefs.getString(KEY_PASSWORD_SALT, null)
        if (storedHash == null || storedSalt == null) {
            return Pair(false, "Aucun mot de passe configuré.")
        }

        val saltBytes = try {
            Base64.decode(storedSalt, Base64.NO_WRAP)
        } catch (_: Exception) {
            return Pair(false, "Erreur de configuration de sécurité.")
        }

        val candidateHash = hashWithPbkdf2(candidate, saltBytes)

        // Constant-time comparison to prevent timing attacks
        val isMatch = MessageDigest.isEqual(
            candidateHash.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )

        if (isMatch) {
            failedAttempts.remove(clientIp)
            val token = generateSecureToken()
            val expiry = now + (24 * 60 * 60 * 1000L) // 24 hours
            activeTokens[token] = expiry
            cleanupExpiredTokens(now)
            return Pair(true, token)
        } else {
            val prevCount = failedAttempts[clientIp]?.first ?: 0
            val newCount = prevCount + 1
            val lockoutUntil = if (newCount >= MAX_FAILED_ATTEMPTS) now + LOCKOUT_DURATION_MS else 0L
            failedAttempts[clientIp] = Pair(newCount, lockoutUntil)
            val attemptsLeft = MAX_FAILED_ATTEMPTS - newCount
            val msg = if (attemptsLeft > 0) {
                "Mot de passe incorrect ($attemptsLeft essais restants)"
            } else {
                "Nombre d'essais dépassé. Verrouillage pour 5 minutes."
            }
            return Pair(false, msg)
        }
    }

    fun validateToken(token: String?): Boolean {
        if (token.isNullOrBlank()) return false
        val expiry = activeTokens[token] ?: return false
        val now = System.currentTimeMillis()
        if (now > expiry) {
            activeTokens.remove(token)
            return false
        }
        return true
    }

    fun invalidateToken(token: String?) {
        if (!token.isNullOrBlank()) {
            activeTokens.remove(token)
        }
    }

    private fun cleanupExpiredTokens(now: Long) {
        val expired = activeTokens.filterValues { it < now }.keys
        expired.forEach { activeTokens.remove(it) }
    }

    private fun hashWithPbkdf2(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = skf.generateSecret(spec).encoded
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
