package com.example.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.server.MinecraftServerManager
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonDanger
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun PluginsView(
    serverManager: MinecraftServerManager?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var plugins by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedDeletePlugin by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    fun refresh() {
        plugins = serverManager?.listPlugins() ?: emptyList()
    }

    LaunchedEffect(serverManager) {
        refresh()
    }

    val pluginPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        coroutineScope.launch {
            isImporting = true
            val success = withContext(Dispatchers.IO) {
                try {
                    var fileName = "plugin_${System.currentTimeMillis()}.jar"
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1 && cursor.moveToFirst()) {
                            fileName = cursor.getString(nameIndex)
                        }
                    }
                    if (!fileName.endsWith(".jar", ignoreCase = true)) {
                        fileName = "$fileName.jar"
                    }

                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        serverManager?.installPlugin(stream, fileName) ?: false
                    } ?: false
                } catch (e: Exception) {
                    false
                }
            }
            isImporting = false
            if (success) {
                Toast.makeText(context, "Plugin installé avec succès !", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(context, "Erreur lors de l'import du plugin.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
            .padding(16.dp)
            .testTag("plugins_view")
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
                            text = "EXTENSIONS & MODS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextMuted,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Gestionnaire de Plugins",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rafraîchir", tint = TextMuted)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Button(
                            onClick = { pluginPickerLauncher.launch("application/java-archive") },
                            enabled = !isImporting,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldPrimary, contentColor = Color(0xFF022C22)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("import_plugin_button")
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Importer", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isImporting) "Import..." else "Importer (.jar)", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Dossier : /sdcard/MinecraftServer/plugins/",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = CyanAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "PLUGINS INSTALLÉS (${plugins.size})",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = TextMuted,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (plugins.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aucun plugin installé.\nUtilisez 'Importer (.jar)' ou déposez vos .jar dans /sdcard/MinecraftServer/plugins/.",
                    color = TextMuted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plugins) { pluginFile ->
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
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(Color(0xFF16252F), RoundedCornerShape(8.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Extension,
                                        contentDescription = "Plugin",
                                        tint = CyanAccent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = pluginFile.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "${pluginFile.length() / 1024} Ko",
                                        fontSize = 12.sp,
                                        color = TextMuted
                                    )
                                }
                            }

                            IconButton(
                                onClick = { selectedDeletePlugin = pluginFile.name }
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Supprimer le plugin",
                                    tint = CrimsonDanger,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog for plugin deletion
    selectedDeletePlugin?.let { name ->
        AlertDialog(
            onDismissRequest = { selectedDeletePlugin = null },
            icon = { Icon(Icons.Default.Warning, contentDescription = "Attention", tint = AmberWarning) },
            title = { Text("Supprimer le plugin") },
            text = {
                Text("Voulez-vous vraiment supprimer le fichier '$name' ? Le serveur devra être redémarré pour appliquer ce changement.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ok = serverManager?.deletePlugin(name) ?: false
                        if (ok) {
                            Toast.makeText(context, "Plugin $name supprimé.", Toast.LENGTH_SHORT).show()
                            refresh()
                        } else {
                            Toast.makeText(context, "Erreur lors de la suppression.", Toast.LENGTH_SHORT).show()
                        }
                        selectedDeletePlugin = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonDanger)
                ) {
                    Text("Supprimer", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedDeletePlugin = null }) {
                    Text("Annuler", color = TextMuted)
                }
            }
        )
    }
}
