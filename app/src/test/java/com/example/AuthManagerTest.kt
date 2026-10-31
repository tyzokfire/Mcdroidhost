package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.server.web.AuthManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthManagerTest {

    private lateinit var authManager: AuthManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("mc_droid_security_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        authManager = AuthManager(context)
    }

    @Test
    fun testPasswordSetupAndVerification() {
        val testPassword = "SuperSecurePassword123!"
        val setResult = authManager.setMasterPassword(testPassword)
        assertTrue(setResult)
        assertTrue(authManager.isPasswordSet())

        // Valid verification
        val (valid, token) = authManager.verifyPassword(testPassword, "192.168.1.50")
        assertTrue(valid)
        assertNotNull(token)
        assertTrue(token!!.isNotEmpty())
        assertTrue(authManager.validateToken(token))

        // Invalid verification
        val (invalid, _) = authManager.verifyPassword("WrongPassword", "192.168.1.50")
        assertFalse(invalid)
    }

    @Test
    fun testBruteForceLockout() {
        authManager.setMasterPassword("adminPass")
        val attackerIp = "192.168.1.99"

        for (i in 1..5) {
            val (ok, _) = authManager.verifyPassword("guess$i", attackerIp)
            assertFalse(ok)
        }

        // 6th attempt should be blocked by lockout even with right password
        val (ok, msg) = authManager.verifyPassword("adminPass", attackerIp)
        assertFalse(ok)
        assertNotNull(msg)
        assertTrue(msg!!.contains("verrouillé", ignoreCase = true) || msg.contains("sécurité", ignoreCase = true))

        // Another IP should still be able to authenticate
        val (otherOk, _) = authManager.verifyPassword("adminPass", "192.168.1.100")
        assertTrue(otherOk)
    }
}
