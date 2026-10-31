package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.core.backup.BackupManager
import com.example.core.network.PlayitTunnelManager
import com.example.core.runtime.JdkManager
import com.example.core.server.MinecraftServerManager
import com.example.core.server.ServerStatus
import com.example.core.thermal.ThermalLevel
import com.example.core.thermal.ThermalMonitor
import com.example.server.web.AuthManager
import com.example.server.web.KtorWebServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MinecraftHostService : Service() {
    companion object {
        private const val TAG = "MinecraftHostService"
        private const val CHANNEL_ID = "mc_host_service_channel"
        private const val NOTIFICATION_ID = 25565

        const val ACTION_START_SERVER = "com.example.action.START_SERVER"
        const val ACTION_STOP_SERVER = "com.example.action.STOP_SERVER"
        const val ACTION_RESTART_SERVER = "com.example.action.RESTART_SERVER"

        @Volatile
        var instance: MinecraftHostService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        val service: MinecraftHostService get() = this@MinecraftHostService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    lateinit var jdkManager: JdkManager
        private set
    lateinit var serverManager: MinecraftServerManager
        private set
    lateinit var playitManager: PlayitTunnelManager
        private set
    lateinit var backupManager: BackupManager
        private set
    lateinit var thermalMonitor: ThermalMonitor
        private set
    lateinit var authManager: AuthManager
        private set
    lateinit var webServer: KtorWebServer
        private set

    private var consecutiveCrashCount = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "Creating MinecraftHostService...")

        // 1. Initialize Managers
        authManager = AuthManager(applicationContext)
        jdkManager = JdkManager(applicationContext)

        serverManager = MinecraftServerManager(
            context = applicationContext,
            jdkManager = jdkManager,
            scope = serviceScope,
            onPlayerLeft = { playerName ->
                backupManager.onPlayerDisconnected(playerName)
            }
        )

        playitManager = PlayitTunnelManager(applicationContext, serviceScope)
        backupManager = BackupManager(serverManager, serviceScope)

        thermalMonitor = ThermalMonitor(
            context = applicationContext,
            scope = serviceScope,
            onEmergencyShutdownRequired = {
                handleEmergencyThermalShutdown()
            }
        )

        webServer = KtorWebServer(
            context = applicationContext,
            authManager = authManager,
            serverManager = serverManager,
            playitManager = playitManager,
            thermalMonitor = thermalMonitor,
            backupManager = backupManager,
            scope = serviceScope
        )

        // 2. Hardware Locks
        acquireWakeAndWifiLocks()

        // 3. Setup Notification Channel & Start Foreground
        createNotificationChannel()
        val initialNotification = buildNotification("Initializing MC-Droid Host...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // 4. Start Background Monitors
        thermalMonitor.start()
        backupManager.startScheduledBackups()
        webServer.start()

        observeServerState()
        observeThermalAlerts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVER -> {
                serverManager.stopServer()
                playitManager.stopTunnel()
            }
            ACTION_RESTART_SERVER -> {
                serverManager.restartServer()
            }
            ACTION_START_SERVER -> {
                val ram = intent.getIntExtra("ramGb", 4)
                startServerAndTunnel(ram)
            }
        }
        return START_STICKY
    }

    fun startServerAndTunnel(ramGb: Int = 4) {
        serverManager.startServer(ramGb)
        playitManager.startTunnel()
    }

    fun installJdk() {
        serviceScope.launch(Dispatchers.IO) {
            jdkManager.downloadAndInstall()
        }
    }

    fun deleteServer(): Boolean {
        return serverManager.deleteServerFiles()
    }

    fun runDiagnostics() {
        serverManager.runSystemDiagnostics()
    }

    private fun acquireWakeAndWifiLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MC-Droid:HostWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MC-Droid:HostWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "Acquired WakeLock and WifiLock for background operation")
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring locks", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing locks", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Minecraft Dedicated Server Host",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status of running Minecraft server and Playit tunnel"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pLaunch = PendingIntent.getActivity(this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, MinecraftHostService::class.java).apply { action = ACTION_STOP_SERVER }
        val pStop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val restartIntent = Intent(this, MinecraftHostService::class.java).apply { action = ACTION_RESTART_SERVER }
        val pRestart = PendingIntent.getService(this, 2, restartIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MC-Droid Host | Server Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pLaunch)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pStop)
            .addAction(android.R.drawable.ic_menu_rotate, "Restart", pRestart)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun observeServerState() {
        serviceScope.launch {
            serverManager.state.collectLatest { s ->
                val localIp = webServer.getDeviceWifiIp()
                val playitAddr = playitManager.state.value.publicAddress ?: "Tunnel active"
                val ram = "${s.allocatedRamGb}GB"
                val text = when (s.status) {
                    ServerStatus.RUNNING -> "Online • $localIp:25565 • $playitAddr • RAM: $ram"
                    ServerStatus.STARTING -> "Starting Minecraft Server (${s.selectedVersion})..."
                    ServerStatus.STOPPING -> "Stopping server..."
                    ServerStatus.CRASHED -> "Server crashed! Attempting recovery..."
                    else -> "Server Stopped • Web Dashboard: http://$localIp:8080"
                }
                updateNotification(text)

                // Crash recovery logic
                if (s.status == ServerStatus.CRASHED) {
                    handleCrashRecovery()
                } else if (s.status == ServerStatus.RUNNING) {
                    consecutiveCrashCount = 0
                }
            }
        }
    }

    private fun handleCrashRecovery() {
        if (consecutiveCrashCount < 3) {
            consecutiveCrashCount++
            serviceScope.launch {
                serverManager.appendLog("[CRASH-RECOVERY] Crash detected (#$consecutiveCrashCount). Waiting 30s before auto-restarting...")
                delay(30_000L)
                serverManager.startServer()
            }
        } else {
            serverManager.appendLog("[CRASH-RECOVERY] Exceeded 3 crash restarts. Halting auto-restart to prevent boot loop.")
        }
    }

    private fun observeThermalAlerts() {
        serviceScope.launch {
            thermalMonitor.thermalState.collectLatest { t ->
                if (t.level == ThermalLevel.CRITICAL) {
                    val alertText = "WARNING: Phone temperature at ${t.temperatureC.toInt()}°C! Cooldown recommended."
                    serverManager.appendLog("[THERMAL-ALERT] $alertText")
                }
            }
        }
    }

    private fun handleEmergencyThermalShutdown() {
        serviceScope.launch {
            serverManager.appendLog("[EMERGENCY] Thermal threshold 45°C exceeded for > 3 minutes! Triggering emergency safe flush and shutdown...")
            backupManager.createBackup(isAuto = true, label = "thermal_emergency")
            delay(1000)
            serverManager.stopServer()
            playitManager.stopTunnel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying MinecraftHostService...")
        instance = null
        thermalMonitor.stop()
        backupManager.stopScheduledBackups()
        webServer.stop()
        serverManager.stopServer()
        playitManager.stopTunnel()
        releaseLocks()
        serviceScope.cancel()
    }
}
