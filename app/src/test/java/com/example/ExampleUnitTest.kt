package com.example

import com.example.core.thermal.ThermalLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.regex.Pattern

class ExampleUnitTest {

    @Test
    fun testThermalLevelEvaluation() {
        fun evaluateLevel(tempC: Float): ThermalLevel {
            return when {
                tempC >= 43.0f -> ThermalLevel.CRITICAL
                tempC >= 38.0f -> ThermalLevel.ELEVATED
                else -> ThermalLevel.NORMAL
            }
        }

        assertEquals(ThermalLevel.NORMAL, evaluateLevel(35.5f))
        assertEquals(ThermalLevel.NORMAL, evaluateLevel(37.9f))
        assertEquals(ThermalLevel.ELEVATED, evaluateLevel(38.0f))
        assertEquals(ThermalLevel.ELEVATED, evaluateLevel(41.2f))
        assertEquals(ThermalLevel.CRITICAL, evaluateLevel(43.0f))
        assertEquals(ThermalLevel.CRITICAL, evaluateLevel(46.2f))
    }

    @Test
    fun testPlayitRegexParsing() {
        val claimPattern = Pattern.compile("https://playit\\.gg/claim/[a-zA-Z0-9_-]+")
        val addrPattern = Pattern.compile("([a-zA-Z0-9_-]+\\.gl\\.joinmc\\.link(:\\d+)?|[a-zA-Z0-9_-]+\\.playit\\.gg(:\\d+)?)")

        val logClaim = "visit https://playit.gg/claim/abc-123_xyz to claim your tunnel"
        val claimMatcher = claimPattern.matcher(logClaim)
        assertTrue(claimMatcher.find())
        assertEquals("https://playit.gg/claim/abc-123_xyz", claimMatcher.group())

        val logAddr = "allocated address: server-host-99.gl.joinmc.link:25565"
        val addrMatcher = addrPattern.matcher(logAddr)
        assertTrue(addrMatcher.find())
        assertEquals("server-host-99.gl.joinmc.link:25565", addrMatcher.group())
    }

    @Test
    fun testPlayerJoinLeaveRegex() {
        val joinLine = "[12:34:56 INFO]: Steve joined the game"
        val leaveLine = "[12:35:10 INFO]: Steve left the game"

        fun extractPlayer(line: String, token: String): String? {
            val idx = line.indexOf(token)
            if (idx == -1) return null
            val before = line.substring(0, idx).trim()
            val parts = before.split(" ", ":")
            return parts.lastOrNull()?.trim()?.replace("[", "")?.replace("]", "")
        }

        assertEquals("Steve", extractPlayer(joinLine, "joined the game"))
        assertEquals("Steve", extractPlayer(leaveLine, "left the game"))
    }
}
