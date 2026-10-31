package com.example.server.web

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.example.core.backup.BackupManager
import com.example.core.network.PlayitTunnelManager
import com.example.core.server.MinecraftServerManager
import com.example.core.thermal.ThermalMonitor
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

data class WebServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val localIp: String = "127.0.0.1",
    val accessUrl: String = "http://localhost:8080"
)

class KtorWebServer(
    private val context: Context,
    private val authManager: AuthManager,
    private val serverManager: MinecraftServerManager,
    private val playitManager: PlayitTunnelManager,
    private val thermalMonitor: ThermalMonitor,
    private val backupManager: BackupManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "KtorWebServer"
        const val PORT = 8080
    }

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    private var engine: ApplicationEngine? = null

    fun start() {
        if (engine != null) return

        val localIp = getDeviceWifiIp()
        _state.value = WebServerState(
            isRunning = true,
            port = PORT,
            localIp = localIp,
            accessUrl = "http://$localIp:$PORT"
        )

        scope.launch(Dispatchers.IO) {
            try {
                val server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                    install(WebSockets)
                    install(CORS) {
                        allowMethod(HttpMethod.Options)
                        allowMethod(HttpMethod.Put)
                        allowMethod(HttpMethod.Delete)
                        allowMethod(HttpMethod.Patch)
                        allowHeader(HttpHeaders.Authorization)
                        allowHeader(HttpHeaders.ContentType)
                        anyHost()
                    }

                    routing {
                        // Serve Web Dashboard Single Page Application
                        get("/") {
                            val html = loadAsset("web/index.html")
                            call.respondText(html, ContentType.Text.Html)
                        }

                        get("/index.html") {
                            val html = loadAsset("web/index.html")
                            call.respondText(html, ContentType.Text.Html)
                        }

                        // Auth: Login
                        post("/api/auth/login") {
                            val clientIp = call.request.header("X-Forwarded-For") ?: "local"
                            val bodyText = call.receiveText()
                            val json = try { JSONObject(bodyText) } catch (_: Exception) { JSONObject() }
                            val password = json.optString("password", "")

                            val (ok, result) = authManager.verifyPassword(password, clientIp)
                            if (ok) {
                                val resp = JSONObject().put("token", result).put("status", "ok")
                                call.respondText(resp.toString(), ContentType.Application.Json)
                            } else {
                                val resp = JSONObject().put("error", result).put("status", "unauthorized")
                                call.respondText(resp.toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                            }
                        }

                        // Status endpoint
                        get("/api/status") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respondText(JSONObject().put("error", "Unauthorized").toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@get
                            }

                            val serverState = serverManager.state.value
                            val playitState = playitManager.state.value
                            val thermalState = thermalMonitor.thermalState.value

                            val memInfo = ActivityManager.MemoryInfo()
                            val am = this@KtorWebServer.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                            am?.getMemoryInfo(memInfo)

                            val runtime = Runtime.getRuntime()
                            val jvmUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                            val jvmMax = runtime.maxMemory() / 1024 / 1024

                            val resp = JSONObject()
                                .put("serverStatus", serverState.status.name)
                                .put("selectedVersion", serverState.selectedVersion)
                                .put("allocatedRamGb", serverState.allocatedRamGb)
                                .put("port", serverState.port)
                                .put("onlinePlayers", JSONArray(serverState.onlinePlayers))
                                .put("maxPlayers", serverState.maxPlayers)
                                .put("statusMessage", serverState.statusMessage)
                                .put("playitRunning", playitState.isRunning)
                                .put("playitClaimUrl", playitState.claimUrl ?: "")
                                .put("playitAddress", playitState.publicAddress ?: "")
                                .put("temperatureC", thermalState.temperatureC)
                                .put("thermalLevel", thermalState.level.name)
                                .put("jvmUsedMb", jvmUsed)
                                .put("jvmMaxMb", jvmMax)
                                .put("systemFreeMemMb", memInfo.availMem / 1024 / 1024)
                                .put("systemTotalMemMb", memInfo.totalMem / 1024 / 1024)

                            call.respondText(resp.toString(), ContentType.Application.Json)
                        }

                        // Server Controls
                        post("/api/control/{action}") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respondText(JSONObject().put("error", "Unauthorized").toString(), ContentType.Application.Json, HttpStatusCode.Unauthorized)
                                return@post
                            }
                            val action = call.parameters["action"]
                            when (action) {
                                "start" -> {
                                    val body = call.receiveText()
                                    val ram = try { JSONObject(body).optInt("ramGb", 4) } catch (_: Exception) { 4 }
                                    serverManager.startServer(ram)
                                    call.respondText(JSONObject().put("result", "starting").toString(), ContentType.Application.Json)
                                }
                                "stop" -> {
                                    serverManager.stopServer()
                                    call.respondText(JSONObject().put("result", "stopping").toString(), ContentType.Application.Json)
                                }
                                "restart" -> {
                                    serverManager.restartServer()
                                    call.respondText(JSONObject().put("result", "restarting").toString(), ContentType.Application.Json)
                                }
                                "delete" -> {
                                    val ok = serverManager.deleteServerFiles()
                                    call.respondText(JSONObject().put("result", if (ok) "deleted" else "failed").toString(), ContentType.Application.Json)
                                }
                                else -> call.respond(HttpStatusCode.BadRequest, "Invalid action")
                            }
                        }

                        // Console command
                        post("/api/console/command") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val body = call.receiveText()
                            val json = JSONObject(body)
                            val cmd = json.optString("command", "")
                            if (cmd.isNotEmpty()) {
                                serverManager.sendCommand(cmd)
                            }
                            call.respondText(JSONObject().put("status", "sent").toString(), ContentType.Application.Json)
                        }

                        // File explorer - List files with Path Traversal Protection
                        get("/api/files") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@get
                            }
                            val subPath = call.request.queryParameters["path"] ?: ""
                            val baseDir = serverManager.serverDirectory.canonicalFile
                            val targetFile = File(baseDir, subPath).canonicalFile

                            if (!targetFile.path.startsWith(baseDir.path)) {
                                call.respond(HttpStatusCode.Forbidden, "Access Denied: Path Traversal Detected")
                                return@get
                            }

                            if (!targetFile.exists()) {
                                call.respond(HttpStatusCode.NotFound, "Directory not found")
                                return@get
                            }

                            val files = targetFile.listFiles()?.map { f ->
                                JSONObject()
                                    .put("name", f.name)
                                    .put("isDirectory", f.isDirectory)
                                    .put("size", f.length())
                                    .put("modified", f.lastModified())
                            } ?: emptyList()

                            val resp = JSONObject()
                                .put("currentPath", targetFile.relativeTo(baseDir).path)
                                .put("files", JSONArray(files))
                            call.respondText(resp.toString(), ContentType.Application.Json)
                        }

                        // File explorer - Read file
                        get("/api/files/read") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@get
                            }
                            val subPath = call.request.queryParameters["path"] ?: ""
                            val baseDir = serverManager.serverDirectory.canonicalFile
                            val targetFile = File(baseDir, subPath).canonicalFile

                            if (!targetFile.path.startsWith(baseDir.path)) {
                                call.respond(HttpStatusCode.Forbidden, "Access Denied: Path Traversal Detected")
                                return@get
                            }

                            if (!targetFile.exists() || targetFile.isDirectory) {
                                call.respond(HttpStatusCode.NotFound, "File not found")
                                return@get
                            }

                            if (targetFile.length() > 5 * 1024 * 1024) {
                                call.respond(HttpStatusCode.PayloadTooLarge, "File exceeds 5MB")
                                return@get
                            }

                            call.respondText(targetFile.readText(), ContentType.Text.Plain)
                        }

                        // File explorer - Write file
                        post("/api/files/write") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val subPath = call.request.queryParameters["path"] ?: ""
                            val baseDir = serverManager.serverDirectory.canonicalFile
                            val targetFile = File(baseDir, subPath).canonicalFile

                            if (!targetFile.path.startsWith(baseDir.path)) {
                                call.respond(HttpStatusCode.Forbidden, "Access Denied: Path Traversal Detected")
                                return@post
                            }

                            val content = call.receiveText()
                            targetFile.parentFile?.mkdirs()
                            targetFile.writeText(content)
                            call.respondText(JSONObject().put("status", "saved").toString(), ContentType.Application.Json)
                        }

                        // File explorer - Delete file/directory with Path Traversal Protection
                        post("/api/files/delete") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val subPath = call.request.queryParameters["path"] ?: ""
                            val baseDir = serverManager.serverDirectory.canonicalFile
                            val targetFile = File(baseDir, subPath).canonicalFile

                            if (!targetFile.path.startsWith(baseDir.path) || targetFile == baseDir) {
                                call.respond(HttpStatusCode.Forbidden, "Access Denied: Path Traversal or Root Deletion Detected")
                                return@post
                            }

                            val ok = targetFile.deleteRecursively()
                            call.respondText(JSONObject().put("success", ok).toString(), ContentType.Application.Json)
                        }

                        // Plugins Management
                        get("/api/plugins") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@get
                            }
                            val plugins = serverManager.listPlugins().map { f ->
                                JSONObject()
                                    .put("name", f.name)
                                    .put("sizeBytes", f.length())
                                    .put("modified", f.lastModified())
                            }
                            call.respondText(JSONObject().put("plugins", JSONArray(plugins)).toString(), ContentType.Application.Json)
                        }

                        post("/api/plugins/delete") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val json = JSONObject(call.receiveText())
                            val name = json.optString("name", "")
                            val ok = serverManager.deletePlugin(name)
                            call.respondText(JSONObject().put("success", ok).toString(), ContentType.Application.Json)
                        }

                        post("/api/plugins/upload") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val pluginName = call.request.header("X-Plugin-Name") ?: "plugin_${System.currentTimeMillis()}.jar"
                            val bytes = call.receive<ByteArray>()
                            if (bytes.isEmpty()) {
                                call.respond(HttpStatusCode.BadRequest, "Empty file")
                                return@post
                            }
                            val ok = serverManager.installPlugin(bytes.inputStream(), pluginName)
                            call.respondText(JSONObject().put("success", ok).toString(), ContentType.Application.Json)
                        }

                        // Backups endpoints
                        get("/api/backups") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@get
                            }
                            backupManager.refreshBackupList()
                            val state = backupManager.state.value
                            val array = JSONArray()
                            state.backupList.forEach { b ->
                                array.put(
                                    JSONObject()
                                        .put("fileName", b.fileName)
                                        .put("sizeBytes", b.sizeBytes)
                                        .put("formattedDate", b.formattedDate)
                                        .put("isAuto", b.isAutoBackup)
                                )
                            }
                            val resp = JSONObject()
                                .put("backups", array)
                                .put("usedQuotaBytes", state.usedQuotaBytes)
                                .put("maxQuotaBytes", state.maxQuotaBytes)
                                .put("isBackingUp", state.isBackingUp)
                            call.respondText(resp.toString(), ContentType.Application.Json)
                        }

                        post("/api/backups/create") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val ok = backupManager.createBackup(isAuto = false, label = "manual_dashboard")
                            call.respondText(JSONObject().put("success", ok).toString(), ContentType.Application.Json)
                        }

                        post("/api/backups/restore") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val json = JSONObject(call.receiveText())
                            val fileName = json.optString("fileName", "")
                            val (ok, msg) = backupManager.restoreBackup(fileName)
                            call.respondText(JSONObject().put("success", ok).put("message", msg).toString(), ContentType.Application.Json)
                        }

                        post("/api/backups/delete") {
                            if (!isAuthorized(call.request.header("Authorization"))) {
                                call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
                                return@post
                            }
                            val json = JSONObject(call.receiveText())
                            val fileName = json.optString("fileName", "")
                            val ok = backupManager.deleteBackup(fileName)
                            call.respondText(JSONObject().put("success", ok).toString(), ContentType.Application.Json)
                        }

                        // WebSocket interactive streaming console
                        webSocket("/ws/console") {
                            val token = call.request.queryParameters["token"]
                            if (!authManager.validateToken(token)) {
                                close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                                return@webSocket
                            }

                            // Send buffered logs first
                            serverManager.state.value.recentLogs.forEach { logLine ->
                                send(Frame.Text(logLine))
                            }

                            // Launch broadcast listener coroutine
                            val broadcastJob = launch {
                                serverManager.logBroadcast.collect { line ->
                                    send(Frame.Text(line))
                                }
                            }

                            // Read incoming commands
                            try {
                                for (frame in incoming) {
                                    if (frame is Frame.Text) {
                                        val cmd = frame.readText().trim()
                                        if (cmd.isNotEmpty()) {
                                            serverManager.sendCommand(cmd)
                                        }
                                    }
                                }
                            } finally {
                                broadcastJob.cancel()
                            }
                        }
                    }
                }
                engine = server
                server.start(wait = true)
            } catch (e: Exception) {
                Log.e(TAG, "Web server failed", e)
                _state.value = _state.value.copy(isRunning = false)
            }
        }
    }

    fun stop() {
        engine?.stop(1000, 2000)
        engine = null
        _state.value = _state.value.copy(isRunning = false)
    }

    private fun isAuthorized(authHeader: String?): Boolean {
        if (authHeader == null) return false
        val token = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.substring(7).trim()
        } else {
            authHeader.trim()
        }
        return authManager.validateToken(token)
    }

    fun getDeviceWifiIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun loadAsset(path: String): String {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "<html><body><h2>MC-Droid Host</h2><p>Dashboard asset loading: ${e.message}</p></body></html>"
        }
    }
}
