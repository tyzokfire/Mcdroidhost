package com.example.core.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

data class PlayitState(
    val isRunning: Boolean = false,
    val isInstalled: Boolean = false,
    val claimUrl: String? = null,
    val publicAddress: String? = null,
    val isTunnelOnline: Boolean = false,
    val latestLog: String = "Ready"
)

class PlayitTunnelManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "PlayitTunnelManager"
        const val PLAYIT_DOWNLOAD_URL =
            "https://github.com/playit-cloud/playit-agent/releases/download/v0.15.26/playit-linux-aarch64"

        private val CLAIM_REGEX = Pattern.compile("https://playit\\.gg/claim/[a-zA-Z0-9_-]+")
        private val PUBLIC_ADDR_REGEX = Pattern.compile("([a-zA-Z0-9_-]+\\.gl\\.joinmc\\.link(:\\d+)?|[a-zA-Z0-9_-]+\\.playit\\.gg(:\\d+)?)")
    }

    private val _state = MutableStateFlow(PlayitState())
    val state: StateFlow<PlayitState> = _state.asStateFlow()

    private val httpClient = OkHttpClient.Builder().build()
    private var process: Process? = null
    private var monitorJob: Job? = null
    private var shouldAutoRestart = true

    val playitExecutable: File
        get() = File(context.filesDir, "runtime/bin/playit")

    fun isInstalled(): Boolean = playitExecutable.exists() && playitExecutable.canExecute()

    suspend fun downloadBinary(): Boolean = withContext(Dispatchers.IO) {
        try {
            playitExecutable.parentFile?.mkdirs()
            _state.value = _state.value.copy(latestLog = "Downloading Playit.gg ARM64 client...")

            val request = Request.Builder().url(PLAYIT_DOWNLOAD_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                val body = response.body ?: throw IllegalStateException("Empty body")

                FileOutputStream(playitExecutable).use { fos ->
                    body.byteStream().copyTo(fos)
                }
            }

            playitExecutable.setReadable(true, false)
            playitExecutable.setExecutable(true, false)

            _state.value = _state.value.copy(
                isInstalled = true,
                latestLog = "Playit binary installed"
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed downloading playit", e)
            _state.value = _state.value.copy(latestLog = "Download error: ${e.message}")
            false
        }
    }

    fun startTunnel() {
        if (process != null) return
        shouldAutoRestart = true

        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive && shouldAutoRestart) {
                runTunnelProcess()
                if (shouldAutoRestart && isActive) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        isTunnelOnline = false,
                        latestLog = "Tunnel disconnected. Reconnecting in 5s..."
                    )
                    delay(5000)
                }
            }
        }
    }

    private suspend fun runTunnelProcess() = withContext(Dispatchers.IO) {
        if (!isInstalled()) {
            val ok = downloadBinary()
            if (!ok) return@withContext
        }

        try {
            val pb = ProcessBuilder(playitExecutable.absolutePath)
            val workDir = File(context.filesDir, "playit_data")
            workDir.mkdirs()
            pb.directory(workDir)
            pb.redirectErrorStream(true)

            val p = pb.start()
            process = p

            _state.value = _state.value.copy(
                isRunning = true,
                latestLog = "Playit process active"
            )

            val reader = BufferedReader(InputStreamReader(p.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { parseLine(it) }
            }

            p.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "Playit error", e)
            _state.value = _state.value.copy(latestLog = "Playit error: ${e.message}")
        } finally {
            process = null
            _state.value = _state.value.copy(isRunning = false, isTunnelOnline = false)
        }
    }

    private fun parseLine(line: String) {
        val trimmed = line.trim()
        Log.d(TAG, "Playit output: $trimmed")

        var newClaim = _state.value.claimUrl
        val claimMatcher = CLAIM_REGEX.matcher(trimmed)
        if (claimMatcher.find()) {
            newClaim = claimMatcher.group()
        }

        var newAddr = _state.value.publicAddress
        val addrMatcher = PUBLIC_ADDR_REGEX.matcher(trimmed)
        if (addrMatcher.find()) {
            newAddr = addrMatcher.group()
        }

        val online = trimmed.contains("tunnel running", ignoreCase = true) ||
                trimmed.contains("registered secret", ignoreCase = true) ||
                newAddr != null

        _state.value = _state.value.copy(
            claimUrl = newClaim,
            publicAddress = newAddr,
            isTunnelOnline = online,
            latestLog = trimmed.take(120)
        )
    }

    fun stopTunnel() {
        shouldAutoRestart = false
        monitorJob?.cancel()
        monitorJob = null
        try {
            process?.destroy()
        } catch (_: Exception) {}
        process = null
        _state.value = _state.value.copy(
            isRunning = false,
            isTunnelOnline = false,
            latestLog = "Tunnel stopped"
        )
    }
}
