package com.example.ui.components

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.backup.BackupState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonDanger
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary

@Composable
fun ConsoleView(
    logs: List<String>,
    onSendCommand: (String) -> Unit,
    onClearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var commandText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new logs arrive
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07090E))
            .padding(12.dp)
            .testTag("console_view")
    ) {
        // Console Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "INTERACTIVE SERVER TERMINAL",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                letterSpacing = 1.sp
            )

            IconButton(onClick = onClearLogs, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Clear, contentDescription = "Clear Logs", tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Logs Output
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF0A0C10), RoundedCornerShape(8.dp))
                .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            items(logs) { line ->
                val color = when {
                    line.contains("[ERROR]", ignoreCase = true) || line.contains("Exception") -> CrimsonDanger
                    line.contains("[WARN]", ignoreCase = true) -> AmberWarning
                    line.startsWith("> ") -> CyanAccent
                    line.contains("joined the game") || line.contains("Done (") -> EmeraldPrimary
                    else -> Color(0xFFA7F3D0)
                }

                Text(
                    text = line,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Command Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val quickCmds = listOf("help", "list", "save-all", "stop")
            quickCmds.forEach { cmd ->
                OutlinedButton(
                    onClick = { onSendCommand(cmd) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanAccent),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder)),
                    modifier = Modifier.height(30.dp)
                ) {
                    Text(cmd, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Command Input Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                placeholder = { Text("Command (e.g. op, say, whitelist)...", fontSize = 13.sp, color = TextMuted) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EmeraldPrimary,
                    unfocusedBorderColor = SlateBorder,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = SlateSurface,
                    unfocusedContainerColor = SlateSurface
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .testTag("console_command_input")
            )

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = {
                    if (commandText.isNotBlank()) {
                        onSendCommand(commandText)
                        commandText = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color(0xFF022C22)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .height(52.dp)
                    .testTag("send_command_button")
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send Command")
            }
        }
    }
}

@Composable
fun BackupsView(
    backupState: BackupState,
    isServerRunning: Boolean,
    onCreateBackup: () -> Unit,
    onRestoreBackup: (String) -> Unit
) {
    var selectedRestoreFile by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
            .padding(16.dp)
            .testTag("backups_view")
    ) {
        // Quota & Action Card
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                            text = "WORLD BACKUPS & INTEGRITY",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Snapshots & Rollbacks",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Button(
                        onClick = onCreateBackup,
                        enabled = !backupState.isBackingUp,
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color(0xFF022C22)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.testTag("create_backup_button")
                    ) {
                        Icon(Icons.Default.Archive, contentDescription = "Backup", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (backupState.isBackingUp) "Backing up..." else "Create Backup", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val usedMb = backupState.usedQuotaBytes / 1024 / 1024
                val maxMb = backupState.maxQuotaBytes / 1024 / 1024
                val pct = if (maxMb > 0) (usedMb.toFloat() / maxMb) else 0f

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Storage Quota: $usedMb MB / $maxMb MB", fontSize = 12.sp, color = TextMuted)
                    Text(text = "${(pct * 100).toInt()}%", fontSize = 12.sp, color = if (pct > 0.8f) AmberWarning else CyanAccent)
                }

                LinearProgressIndicator(
                    progress = { pct.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    color = if (pct > 0.8f) AmberWarning else EmeraldPrimary,
                    trackColor = SlateBorder
                )

                Text(
                    text = backupState.lastBackupStatus,
                    fontSize = 12.sp,
                    color = CyanAccent,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "AVAILABLE ARCHIVES (${backupState.backupList.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (backupState.backupList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No backup archives created yet.", color = TextMuted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(backupState.backupList) { file ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        shape = RoundedCornerShape(12.dp),
                        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(SlateBorder))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.fileName,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${file.formattedDate} • ${file.sizeBytes / 1024 / 1024} MB" + if (file.isAutoBackup) " • Auto-Save" else "",
                                    fontSize = 12.sp,
                                    color = if (file.isAutoBackup) CyanAccent else TextMuted
                                )
                            }

                            Button(
                                onClick = { selectedRestoreFile = file.fileName },
                                enabled = !isServerRunning && !backupState.isRestoring,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B), contentColor = CyanAccent),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.Restore, contentDescription = "Restore", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Restore", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Safety Restore Confirmation Dialog
    selectedRestoreFile?.let { fileName ->
        AlertDialog(
            onDismissRequest = { selectedRestoreFile = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = "Warning", tint = AmberWarning) },
            title = { Text("Confirm World Rollback") },
            text = {
                Text("Are you sure you want to restore from '$fileName'? Current world dimensions will be overwritten. A safety pre-restore backup will be automatically saved.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onRestoreBackup(fileName)
                        selectedRestoreFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger)
                ) {
                    Text("Restore Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedRestoreFile = null }) {
                    Text("Cancel", color = TextMuted)
                }
            }
        )
    }
}

@Composable
fun StoragePermissionBanner(onRequestPermission: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("storage_permission_banner"),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1E08)),
        shape = RoundedCornerShape(12.dp),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AmberWarning))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Storage Permission Needed",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AmberWarning
                )
                Text(
                    text = "Grant All Files Access so /sdcard/MinecraftServer is visible via USB on your PC.",
                    fontSize = 12.sp,
                    color = TextPrimary
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = AmberWarning, contentColor = Color(0xFF261200)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Grant", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
