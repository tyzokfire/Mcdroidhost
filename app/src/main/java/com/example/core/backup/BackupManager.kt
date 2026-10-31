package com.example.core.backup

import android.util.Log
import com.example.core.server.MinecraftServerManager
import com.example.core.server.ServerStatus
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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupFileInfo(
    val fileName: String,
    val sizeBytes: Long,
    val timestampMs: Long,
    val formattedDate: String,
    val isAutoBackup: Boolean
)

data class BackupState(
    val isBackingUp: Boolean = false,
    val isRestoring: Boolean = false,
    val backupList: List<BackupFileInfo> = emptyList(),
    val maxQuotaBytes: Long = 25L * 1024 * 1024 * 1024, // 25 GB default
    val usedQuotaBytes: Long = 0L,
    val lastBackupStatus: String = "Aucune sauvegarde effectuée pour le moment"
)

class BackupManager(
    private val serverManager: MinecraftServerManager,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "BackupManager"
        private const val DEBOUNCE_DISCONNECT_MS = 10 * 60 * 1000L // 10 min
    }

    private val _state = MutableStateFlow(BackupState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private var scheduledJob: Job? = null
    private var lastPlayerLeaveBackupMs = 0L

    val backupsDirectory: File
        get() {
            val dir = File(serverManager.serverDirectory, "backups")
            dir.mkdirs()
            return dir
        }

    fun startScheduledBackups() {
        refreshBackupList()

        // 60-minute recurring backup timer
        scheduledJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(60 * 60 * 1000L)
                if (serverManager.state.value.status == ServerStatus.RUNNING) {
                    createBackup(isAuto = true, label = "scheduled_hourly")
                }
            }
        }
    }

    fun stopScheduledBackups() {
        scheduledJob?.cancel()
        scheduledJob = null
    }

    fun onPlayerDisconnected(playerName: String) {
        val now = System.currentTimeMillis()
        if (now - lastPlayerLeaveBackupMs > DEBOUNCE_DISCONNECT_MS) {
            lastPlayerLeaveBackupMs = now
            scope.launch {
                createBackup(isAuto = true, label = "leave_${playerName}")
            }
        }
    }

    /**
     * Creates a full world backup with safe disk write pause (save-off/save-all).
     * Guaranteed to resume world writes (save-on) in finally block under all circumstances.
     */
    suspend fun createBackup(isAuto: Boolean = false, label: String = "manual"): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.isBackingUp) return@withContext false

        _state.value = _state.value.copy(
            isBackingUp = true,
            lastBackupStatus = "Initiation de la sauvegarde du monde..."
        )

        var isServerRunning = false
        try {
            isServerRunning = serverManager.state.value.status == ServerStatus.RUNNING

            // 1. Safe flush if server is running
            if (isServerRunning) {
                serverManager.appendLog("[BACKUP] Suspension temporaire des écritures disque (save-off & save-all)...")
                serverManager.sendCommand("save-off")
                serverManager.sendCommand("save-all flush")
                delay(2000) // Allow Anvil region cache flush to settle
            }

            // 2. Identify folders to compress
            val targetFolders = listOf("world", "world_nether", "world_the_end", "plugins")
            val serverDir = serverManager.serverDirectory
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val archiveName = "backup_${label}_${timestamp}.zip"
            val archiveFile = File(backupsDirectory, archiveName)

            _state.value = _state.value.copy(lastBackupStatus = "Compression dans $archiveName...")

            ZipOutputStream(FileOutputStream(archiveFile)).use { zos ->
                // Add server.properties
                val props = File(serverDir, "server.properties")
                if (props.exists()) {
                    addFileToZip(props, "server.properties", zos)
                }

                // Add dimension folders and plugins
                for (folderName in targetFolders) {
                    val folder = File(serverDir, folderName)
                    if (folder.exists() && folder.isDirectory) {
                        addFolderToZip(folder, folderName, zos)
                    }
                }
            }

            // 3. Enforce quota (FIFO cleanup)
            enforceStorageQuota()
            refreshBackupList()

            val sizeMb = archiveFile.length() / (1024 * 1024)
            val msg = "Sauvegarde réussie : $archiveName (${sizeMb} Mo)"
            _state.value = _state.value.copy(
                isBackingUp = false,
                lastBackupStatus = msg
            )
            serverManager.appendLog("[BACKUP] $msg")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            val errMsg = "Échec de la sauvegarde : ${e.message}"
            _state.value = _state.value.copy(
                isBackingUp = false,
                lastBackupStatus = errMsg
            )
            serverManager.appendLog("[BACKUP-ERREUR] $errMsg")
            false
        } finally {
            // CRITICAL: Always resume server disk writes even on error or cancellation
            if (isServerRunning) {
                serverManager.sendCommand("save-on")
                serverManager.appendLog("[BACKUP] Écritures disque reprises (save-on).")
            }
        }
    }

    /**
     * Restores a world backup cleanly. Server must be stopped first.
     */
    suspend fun restoreBackup(fileName: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (serverManager.state.value.status != ServerStatus.STOPPED) {
            return@withContext Pair(false, "Impossible de restaurer pendant que le serveur est actif ! Arrêtez le serveur d'abord.")
        }
        if (_state.value.isRestoring) {
            return@withContext Pair(false, "Une restauration est déjà en cours.")
        }

        val archive = File(backupsDirectory, fileName)
        if (!archive.exists()) {
            return@withContext Pair(false, "L'archive $fileName est introuvable.")
        }

        _state.value = _state.value.copy(isRestoring = true, lastBackupStatus = "Restauration de $fileName...")

        try {
            val serverDir = serverManager.serverDirectory

            // Clean existing world folders prior to restore to avoid mixing corrupted region files
            val worldFolders = listOf("world", "world_nether", "world_the_end")
            for (w in worldFolders) {
                val f = File(serverDir, w)
                if (f.exists()) {
                    f.deleteRecursively()
                }
            }

            // Extract with Zip Slip check
            ZipInputStream(FileInputStream(archive)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val destFile = File(serverDir, entry.name)
                    // Security: Verify destination file is strictly inside serverDirectory
                    if (!destFile.canonicalPath.startsWith(serverDir.canonicalPath)) {
                        throw SecurityException("Tentative de Zip Slip détectée dans l'archive : ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        FileOutputStream(destFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            refreshBackupList()
            val msg = "Restauration réussie depuis $fileName"
            _state.value = _state.value.copy(
                isRestoring = false,
                lastBackupStatus = msg
            )
            serverManager.appendLog("[BACKUP-SUCCÈS] $msg")
            Pair(true, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Restore error", e)
            val errMsg = "Échec de la restauration : ${e.message}"
            _state.value = _state.value.copy(
                isRestoring = false,
                lastBackupStatus = errMsg
            )
            serverManager.appendLog("[BACKUP-ERREUR] $errMsg")
            Pair(false, e.message ?: e.toString())
        }
    }

    fun deleteBackup(fileName: String): Boolean {
        return try {
            val file = File(backupsDirectory, fileName)
            if (file.exists() && file.delete()) {
                refreshBackupList()
                serverManager.appendLog("[BACKUP] Archive supprimée : $fileName")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete backup $fileName", e)
            false
        }
    }

    fun refreshBackupList() {
        val files = backupsDirectory.listFiles { f -> f.extension.equals("zip", ignoreCase = true) } ?: emptyArray()
        val sorted = files.sortedByDescending { it.lastModified() }
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        var totalBytes = 0L
        val list = sorted.map { f ->
            totalBytes += f.length()
            BackupFileInfo(
                fileName = f.name,
                sizeBytes = f.length(),
                timestampMs = f.lastModified(),
                formattedDate = formatter.format(Date(f.lastModified())),
                isAutoBackup = f.name.contains("_scheduled_") || f.name.contains("_leave_") || f.name.contains("_pre_restore_")
            )
        }

        _state.value = _state.value.copy(
            backupList = list,
            usedQuotaBytes = totalBytes
        )
    }

    private fun enforceStorageQuota() {
        val files = backupsDirectory.listFiles { f -> f.extension.equals("zip", ignoreCase = true) }?.toMutableList() ?: return
        files.sortBy { it.lastModified() } // Oldest first

        var currentTotal = files.sumOf { it.length() }
        val quota = _state.value.maxQuotaBytes

        while (currentTotal > quota && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            val size = oldest.length()
            if (oldest.delete()) {
                currentTotal -= size
                Log.i(TAG, "FIFO quota cleanup: deleted oldest archive ${oldest.name}")
            }
        }
    }

    private fun addFolderToZip(folder: File, parentPath: String, zos: ZipOutputStream) {
        val files = folder.listFiles() ?: return
        for (file in files) {
            val entryPath = "$parentPath/${file.name}"
            if (file.isDirectory) {
                addFolderToZip(file, entryPath, zos)
            } else {
                addFileToZip(file, entryPath, zos)
            }
        }
    }

    private fun addFileToZip(file: File, entryPath: String, zos: ZipOutputStream) {
        val entry = ZipEntry(entryPath)
        entry.time = file.lastModified()
        zos.putNextEntry(entry)
        FileInputStream(file).use { fis ->
            fis.copyTo(zos)
        }
        zos.closeEntry()
    }
}
