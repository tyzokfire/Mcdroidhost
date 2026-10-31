package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.network.PlayitState
import com.example.core.server.ServerState
import com.example.core.server.ServerStatus
import com.example.core.thermal.ThermalLevel
import com.example.core.thermal.ThermalState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonDanger
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

@Composable
fun ServerStatusCard(
    serverState: ServerState,
    onStart: (Int) -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSelectVersion: (String) -> Unit,
    onDeleteServer: () -> Unit,
    onRunDiagnostics: () -> Unit = {}
) {
    var ramAllocation by remember { mutableFloatStateOf(serverState.allocatedRamGb.toFloat()) }
    var showRamWarningDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_status_card"),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row: Title & Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MINECRAFT JAVA DEDICATED",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "PaperMC Engine",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                StatusBadge(status = serverState.status)
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Version Info & Download Progress
            Text(
                text = "Version cible: Paper ${serverState.selectedVersion} (Port ${serverState.port})",
                fontSize = 13.sp,
                color = CyanAccent,
                fontWeight = FontWeight.Medium
            )

            if (serverState.status == ServerStatus.DOWNLOADING_ENGINE) {
                Spacer(modifier = Modifier.height(8.dp))
                if (serverState.engineDownloadProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { serverState.engineDownloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = EmeraldPrimary,
                        trackColor = SlateBorder
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = EmeraldPrimary,
                        trackColor = SlateBorder
                    )
                }
                Text(
                    text = serverState.statusMessage,
                    fontSize = 12.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // RAM Allocation Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "RAM allouée: ${ramAllocation.toInt()} Go",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary
                )
                Text(
                    text = if (ramAllocation > 6f) "Charge élevée" else "Recommandé (4–6 Go)",
                    fontSize = 12.sp,
                    color = if (ramAllocation > 6f) AmberWarning else TextMuted
                )
            }

            Slider(
                value = ramAllocation,
                onValueChange = { newValue ->
                    ramAllocation = newValue
                    if (newValue > 6f && !showRamWarningDialog) {
                        showRamWarningDialog = true
                    }
                },
                valueRange = 2f..8f,
                steps = 5, // 2, 3, 4, 5, 6, 7, 8
                enabled = serverState.status == ServerStatus.STOPPED,
                colors = SliderDefaults.colors(
                    thumbColor = if (ramAllocation > 6f) AmberWarning else EmeraldPrimary,
                    activeTrackColor = if (ramAllocation > 6f) AmberWarning else EmeraldPrimary,
                    inactiveTrackColor = SlateBorder
                ),
                modifier = Modifier.testTag("ram_allocation_slider")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Control Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val isRunning = serverState.status == ServerStatus.RUNNING
                val isStarting = serverState.status == ServerStatus.STARTING

                Button(
                    onClick = { onStart(ramAllocation.toInt()) },
                    enabled = !isRunning && !isStarting,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color(0xFF022C22)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("start_server_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Démarrer", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Start", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = onRestart,
                    enabled = isRunning,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder)),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("restart_server_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Redémarrer", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Restart", fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = onStop,
                    enabled = isRunning || isStarting,
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp)
                        .testTag("stop_server_button")
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Arrêter", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Stop", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Server Location & Status Info
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1017), RoundedCornerShape(8.dp))
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = "Stockage", tint = CyanAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (serverState.serverPath.isNotEmpty()) serverState.serverPath else "Stockage: /sdcard/MinecraftServer",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted,
                            maxLines = 1
                        )
                        Text(
                            text = "Statut: ${serverState.statusMessage}",
                            fontSize = 11.sp,
                            color = if (serverState.status == ServerStatus.CRASHED) CrimsonDanger else TextMuted
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Diagnostics and Delete Server Actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRunDiagnostics,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder)),
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .testTag("run_diagnostics_button")
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = "Diagnostic", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Diagnostic", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = { showDeleteDialog = true },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF451A1A), contentColor = CrimsonDanger),
                    modifier = Modifier
                        .weight(1.3f)
                        .height(40.dp)
                        .testTag("delete_server_button")
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Supprimer le serveur", modifier = Modifier.size(16.dp), tint = CrimsonDanger)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Supprimer le serveur", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CrimsonDanger)
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = "Supprimer", tint = CrimsonDanger, modifier = Modifier.size(32.dp)) },
            title = { Text(text = "Supprimer le serveur ?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Attention : Cette action efface totalement tous les fichiers du serveur (monde 'world', plugins, configurations et paper.jar).\n\nUn serveur vierge et fonctionnel sera regénéré automatiquement au prochain lancement."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        onDeleteServer()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger, contentColor = Color.White),
                    modifier = Modifier.testTag("confirm_delete_server_button")
                ) {
                    Text("Supprimer définitivement", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.testTag("cancel_delete_server_button")
                ) {
                    Text("Annuler", color = TextMuted)
                }
            }
        )
    }

    if (showRamWarningDialog) {
        AlertDialog(
            onDismissRequest = { showRamWarningDialog = false },
            title = { Text(text = "High RAM Allocation Notice") },
            text = {
                Text(
                    text = "Allocating ${ramAllocation.toInt()} GB exceeds the recommended 6 GB threshold on mobile devices. Although the POCO X6 5G has 12 GB RAM, Android OS memory pressure may trigger OOM (Out Of Memory) task termination under heavy load."
                )
            },
            confirmButton = {
                TextButton(onClick = { showRamWarningDialog = false }) {
                    Text("I Understand", color = EmeraldPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun StatusBadge(status: ServerStatus) {
    val (bgColor, textColor, label) = when (status) {
        ServerStatus.RUNNING -> Triple(Color(0x3310B981), EmeraldPrimary, "ONLINE")
        ServerStatus.STARTING -> Triple(Color(0x33F59E0B), AmberWarning, "STARTING")
        ServerStatus.DOWNLOADING_ENGINE -> Triple(Color(0x3338BDF8), CyanAccent, "FETCHING")
        ServerStatus.STOPPING -> Triple(Color(0x33EF4444), CrimsonDanger, "STOPPING")
        ServerStatus.CRASHED -> Triple(Color(0x44EF4444), CrimsonDanger, "CRASHED")
        ServerStatus.STOPPED -> Triple(Color(0x22FFFFFF), TextMuted, "STOPPED")
    }

    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(50))
            .border(1.dp, textColor.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        if (status == ServerStatus.RUNNING || status == ServerStatus.STARTING) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .clip(CircleShape)
                    .background(textColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun NetworkEndpointsCard(
    localIp: String,
    port: Int,
    playitState: PlayitState
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("network_endpoints_card"),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "NETWORK & MULTIPLAYER ENDPOINTS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Local WiFi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Local Wi-Fi (LAN):", fontSize = 12.sp, color = TextMuted)
                    Text(
                        text = "$localIp:$port",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = CyanAccent
                    )
                }
                IconButton(
                    onClick = {
                        copyToClipboard(context, "$localIp:$port", "Local IP copied")
                    },
                    modifier = Modifier.testTag("copy_local_ip_button")
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy Local IP", tint = TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Playit WAN
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Playit.gg WAN Public Address:", fontSize = 12.sp, color = TextMuted)
                    val addr = playitState.publicAddress ?: if (playitState.isRunning) "Resolving domain..." else "Tunnel Offline"
                    Text(
                        text = addr,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (playitState.publicAddress != null) EmeraldPrimary else TextMuted
                    )
                }

                if (playitState.publicAddress != null) {
                    IconButton(
                        onClick = {
                            copyToClipboard(context, playitState.publicAddress, "Public Join Address copied")
                        },
                        modifier = Modifier.testTag("copy_playit_address_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Playit Address", tint = EmeraldPrimary)
                    }
                }
            }

            // Claim URL Button if tunnel is waiting for claim
            if (!playitState.claimUrl.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(playitState.claimUrl))
                        context.startActivity(browserIntent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = Color(0xFF261200)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("claim_playit_tunnel_button")
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Claim Tunnel", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Claim Tunnel on Playit.gg", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun HardwareTelemetryCard(
    thermalState: ThermalState,
    onlinePlayers: Set<String>,
    maxPlayers: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hardware_telemetry_card"),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "HARDWARE & THERMAL TELEMETRY",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Temperature Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(text = "Battery & Core Temp:", fontSize = 12.sp, color = TextMuted)
                    Text(
                        text = "${String.format("%.1f", thermalState.temperatureC)} °C",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (thermalState.level) {
                            ThermalLevel.NORMAL -> EmeraldPrimary
                            ThermalLevel.ELEVATED -> AmberWarning
                            ThermalLevel.CRITICAL, ThermalLevel.EMERGENCY -> CrimsonDanger
                        }
                    )
                }

                // Thermal status badge
                val (tColor, tLabel) = when (thermalState.level) {
                    ThermalLevel.NORMAL -> Pair(EmeraldPrimary, "NORMAL (<38°C)")
                    ThermalLevel.ELEVATED -> Pair(AmberWarning, "ELEVATED (38-42°C)")
                    ThermalLevel.CRITICAL -> Pair(CrimsonDanger, "CRITICAL (>=43°C)")
                    ThermalLevel.EMERGENCY -> Pair(CrimsonDanger, "SHUTDOWN (>45°C)")
                }

                Text(
                    text = tLabel,
                    color = tColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(tColor.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Online players
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Connected Players:",
                    fontSize = 12.sp,
                    color = TextMuted
                )
                Text(
                    text = "${onlinePlayers.size} / $maxPlayers",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            if (onlinePlayers.isEmpty()) {
                Text(
                    text = "No players currently online",
                    fontSize = 12.sp,
                    color = TextMuted,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    onlinePlayers.forEach { player ->
                        Text(
                            text = player,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = CyanAccent,
                            modifier = Modifier
                                .background(CyanAccent.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun WebDashboardCard(
    webAccessUrl: String
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("web_dashboard_card"),
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "EMBEDDED WEB DASHBOARD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextMuted,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Browser Administration",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }

                IconButton(onClick = {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(webAccessUrl))
                    context.startActivity(browserIntent)
                }) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Open Web Dashboard", tint = CyanAccent)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Access live terminal, file manager & backups from PC browser on the same Wi-Fi:",
                fontSize = 12.sp,
                color = TextMuted
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B0F17), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = webAccessUrl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent
                )

                IconButton(
                    onClick = { copyToClipboard(context, webAccessUrl, "Web Dashboard URL copied") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy URL", tint = TextMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

fun copyToClipboard(context: Context, text: String, toastMsg: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("MC-Droid", text))
    Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
}
