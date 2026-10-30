package com.example

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.core.backup.BackupState
import com.example.core.network.PlayitState
import com.example.core.runtime.JdkState
import com.example.core.server.ServerState
import com.example.core.server.ServerStatus
import com.example.core.thermal.ThermalState
import com.example.service.MinecraftHostService
import com.example.ui.components.BackupsView
import com.example.ui.components.ConsoleView
import com.example.ui.components.HardwareTelemetryCard
import com.example.ui.components.NetworkEndpointsCard
import com.example.ui.components.ServerStatusCard
import com.example.ui.components.StoragePermissionBanner
import com.example.ui.components.WebDashboardCard
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var hostService by mutableStateOf<MinecraftHostService?>(null)
    private var isServiceBound by mutableStateOf(false)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? MinecraftHostService.LocalBinder
            hostService = localBinder?.service
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            hostService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start Foreground Service
        val serviceIntent = Intent(this, MinecraftHostService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            MyApplicationTheme {
                val notificationLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { _ -> }

                val storageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                MainAppScreen(
                    hostService = hostService,
                    onRequestManageStorage = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                storageLauncher.launch(intent)
                            } catch (_: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                storageLauncher.launch(intent)
                            }
                        }
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    hostService: MinecraftHostService?,
    onRequestManageStorage: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showPasswordSetupDialog by remember { mutableStateOf(false) }

    // Observe Service State
    val serverState by hostService?.serverManager?.state?.collectAsState() ?: remember { mutableStateOf(ServerState()) }
    val playitState by hostService?.playitManager?.state?.collectAsState() ?: remember { mutableStateOf(PlayitState()) }
    val thermalState by hostService?.thermalMonitor?.thermalState?.collectAsState() ?: remember { mutableStateOf(ThermalState()) }
    val backupState by hostService?.backupManager?.state?.collectAsState() ?: remember { mutableStateOf(BackupState()) }
    val jdkState by hostService?.jdkManager?.state?.collectAsState() ?: remember { mutableStateOf(JdkState()) }
    val webState by hostService?.webServer?.state?.collectAsState() ?: remember { mutableStateOf(com.example.server.web.WebServerState()) }

    // Check if initial master password needs setup
    LaunchedEffect(hostService) {
        hostService?.let { service ->
            service.jdkManager.checkInstallation()
            if (!service.authManager.isPasswordSet()) {
                showPasswordSetupDialog = true
            }
        }
    }

    val hasStoragePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(EmeraldPrimary, RoundedCornerShape(6.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("MC", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF022C22))
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("MC-Droid Host", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Dedicated Java Server • POCO X6", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = SlateSurface,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = EmeraldPrimary,
                        selectedTextColor = EmeraldPrimary,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = Color(0xFF16252F)
                    ),
                    modifier = Modifier.testTag("nav_tab_dashboard")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal") },
                    label = { Text("Console") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = EmeraldPrimary,
                        selectedTextColor = EmeraldPrimary,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = Color(0xFF16252F)
                    ),
                    modifier = Modifier.testTag("nav_tab_console")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Archive, contentDescription = "Backups") },
                    label = { Text("Backups") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = EmeraldPrimary,
                        selectedTextColor = EmeraldPrimary,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = Color(0xFF16252F)
                    ),
                    modifier = Modifier.testTag("nav_tab_backups")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianBg)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> {
                    // Dashboard View
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Storage permission banner if not granted
                        if (!hasStoragePermission) {
                            StoragePermissionBanner(onRequestPermission = onRequestManageStorage)
                        }

                        // JDK Status & 1-tap download card if not installed
                        if (!jdkState.isInstalled) {
                            JdkSetupCard(
                                jdkState = jdkState,
                                onInstallJdk = {
                                    coroutineScope.launch {
                                        hostService?.jdkManager?.downloadAndInstall()
                                    }
                                }
                            )
                        }

                        // Server Status Card
                        ServerStatusCard(
                            serverState = serverState,
                            onStart = { ramGb ->
                                hostService?.startServerAndTunnel(ramGb)
                            },
                            onStop = {
                                hostService?.serverManager?.stopServer()
                                hostService?.playitManager?.stopTunnel()
                            },
                            onRestart = {
                                hostService?.serverManager?.restartServer()
                            },
                            onSelectVersion = { version ->
                                coroutineScope.launch {
                                    hostService?.serverManager?.downloadPaperJar(version)
                                }
                            }
                        )

                        // Network Endpoints Card
                        NetworkEndpointsCard(
                            localIp = webState.localIp,
                            port = serverState.port,
                            playitState = playitState
                        )

                        // Hardware & Thermal Card
                        HardwareTelemetryCard(
                            thermalState = thermalState,
                            onlinePlayers = serverState.onlinePlayers,
                            maxPlayers = serverState.maxPlayers
                        )

                        // Web Dashboard Card
                        WebDashboardCard(
                            webAccessUrl = webState.accessUrl
                        )
                    }
                }

                1 -> {
                    // Interactive Console View
                    ConsoleView(
                        logs = serverState.recentLogs,
                        onSendCommand = { cmd ->
                            hostService?.serverManager?.sendCommand(cmd)
                        },
                        onClearLogs = {
                            hostService?.serverManager?.clearLogs()
                        }
                    )
                }

                2 -> {
                    // Backups View
                    BackupsView(
                        backupState = backupState,
                        isServerRunning = serverState.status == ServerStatus.RUNNING,
                        onCreateBackup = {
                            coroutineScope.launch {
                                hostService?.backupManager?.createBackup(isAuto = false, label = "manual_app")
                            }
                        },
                        onRestoreBackup = { fileName ->
                            coroutineScope.launch {
                                hostService?.backupManager?.restoreBackup(fileName)
                            }
                        }
                    )
                }
            }
        }
    }

    // Initial Master Password Dialog
    if (showPasswordSetupDialog) {
        var masterPass by remember { mutableStateOf("") }
        var confirmPass by remember { mutableStateOf("") }
        var errorMsg by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { /* Force setup */ },
            icon = { Icon(Icons.Default.Lock, contentDescription = "Security", tint = EmeraldPrimary) },
            title = { Text("Set Web Dashboard Password") },
            text = {
                Column {
                    Text(
                        text = "Define an administrator password for the embedded Web Dashboard (LAN port 8080).",
                        fontSize = 13.sp,
                        color = TextMuted
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = masterPass,
                        onValueChange = { masterPass = it },
                        label = { Text("Admin Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldPrimary,
                            unfocusedBorderColor = SlateBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = confirmPass,
                        onValueChange = { confirmPass = it },
                        label = { Text("Confirm Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldPrimary,
                            unfocusedBorderColor = SlateBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    errorMsg?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (masterPass.length < 4) {
                            errorMsg = "Password must be at least 4 characters"
                        } else if (masterPass != confirmPass) {
                            errorMsg = "Passwords do not match"
                        } else {
                            hostService?.authManager?.setMasterPassword(masterPass)
                            showPasswordSetupDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color(0xFF022C22))
                ) {
                    Text("Save Password", fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun JdkSetupCard(
    jdkState: JdkState,
    onInstallJdk: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("jdk_setup_card"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14243A)),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CyanAccent))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RUNTIME PREREQUISITE",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanAccent,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "OpenJDK 21 ARM64 Bionic",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                Button(
                    onClick = onInstallJdk,
                    enabled = !jdkState.isDownloading,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanAccent, contentColor = Color(0xFF082F49)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = "Download", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (jdkState.isDownloading) "Installing..." else "Install JDK", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = jdkState.statusMessage,
                fontSize = 12.sp,
                color = TextMuted
            )

            if (jdkState.isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { jdkState.downloadProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanAccent,
                    trackColor = SlateBorder
                )
            }
        }
    }
}
