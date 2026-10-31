package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.runtime.JdkManager
import com.example.core.server.MinecraftServerManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinecraftServerManagerTest {

    private lateinit var context: Context
    private lateinit var jdkManager: JdkManager
    private lateinit var manager: MinecraftServerManager
    private val testScope = TestScope()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        jdkManager = JdkManager(context)
        manager = MinecraftServerManager(context, jdkManager, testScope, onPlayerLeft = {})
    }

    @Test
    fun testPluginInstallationAndDeletion() {
        val pluginContent = "MOCK_JAR_BYTES".toByteArray()
        val stream = ByteArrayInputStream(pluginContent)
        val pluginName = "EssentialsX.jar"

        val installed = manager.installPlugin(stream, pluginName)
        assertTrue("Plugin should install successfully", installed)

        val plugins = manager.listPlugins()
        assertTrue("Plugin list should contain EssentialsX.jar", plugins.any { it.name == pluginName })

        val deleted = manager.deletePlugin(pluginName)
        assertTrue("Plugin should delete successfully", deleted)

        val pluginsAfter = manager.listPlugins()
        assertFalse("Plugin list should not contain EssentialsX.jar after deletion", pluginsAfter.any { it.name == pluginName })
    }

    @Test
    fun testPathTraversalRejectionInPluginInstall() {
        val stream = ByteArrayInputStream("TEST".toByteArray())
        val installed = manager.installPlugin(stream, "../../evil.jar")
        assertTrue(installed)
        val plugins = manager.listPlugins()
        assertFalse(plugins.any { it.name.contains("..") })
    }

    @Test
    fun testInitServerFiles() {
        manager.initServerFiles()
        val eulaFile = File(manager.serverDirectory, "eula.txt")
        assertTrue("eula.txt should exist", eulaFile.exists())
        assertTrue("eula should be agreed", eulaFile.readText().contains("eula=true"))

        val propsFile = File(manager.serverDirectory, "server.properties")
        assertTrue("server.properties should exist", propsFile.exists())
    }
}
