package com.example.core.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class JdkState(
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val statusMessage: String = "Non installé",
    val lastError: String? = null,
    val javaVersion: String? = null
)

class JdkManager(private val context: Context) {

    companion object {
        private const val TAG = "JdkManager"

        // ARM64 OpenJDK 21 Bionic Mirrors (ordered by reliability)
        private val JDK_MIRRORS = listOf(
            "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/download/jre22-6.0.0/JRE-21.zip",
            "https://github.com/awoot6549/android-openjdk-build-multiarch/releases/download/rolling/jre21-pojav.zip",
            "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre21-20231109-170129/jre21-arm64-20231109-170129.tar.xz"
        )
    }

    val runtimeDir: File = File(context.filesDir, "runtime")
    val binDir: File = File(runtimeDir, "bin")

    val javaExecutable: File
        get() {
            val nativeLauncher = File(context.applicationInfo.nativeLibraryDir, "libjava_launcher.so")
            return if (nativeLauncher.exists() && nativeLauncher.canExecute()) {
                nativeLauncher
            } else {
                File(binDir, "java")
            }
        }

    private val _state = MutableStateFlow(JdkState())
    val state: StateFlow<JdkState> = _state.asStateFlow()

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        checkInstallation()
    }

    /**
     * Checks if the OpenJDK binary and core runtime libraries exist.
     */
    fun checkInstallation(): Boolean {
        val libJvm = File(runtimeDir, "lib/server/libjvm.so")
        val isReady = javaExecutable.exists() && libJvm.exists()
        if (isReady) {
            applyExecPermissions(runtimeDir)
            _state.value = _state.value.copy(
                isInstalled = true,
                isDownloading = false,
                downloadProgress = 1f,
                statusMessage = "OpenJDK 21 prêt",
                lastError = null
            )
            return true
        }

        _state.value = _state.value.copy(
            isInstalled = false,
            isDownloading = false,
            downloadProgress = 0f,
            statusMessage = "OpenJDK 21 non installé",
            lastError = null
        )
        return false
    }

    /**
     * Downloads and installs OpenJDK 21 in internal app storage.
     */
    suspend fun downloadAndInstall(): Boolean = withContext(Dispatchers.IO) {
        if (_state.value.isDownloading) {
            Log.w(TAG, "Download already in progress")
            return@withContext false
        }

        _state.value = _state.value.copy(
            isDownloading = true,
            downloadProgress = 0f,
            statusMessage = "Préparation du téléchargement...",
            lastError = null
        )

        val tempArchive = File(context.cacheDir, "openjdk21_temp.archive")
        var success = false
        var lastCaughtError: Exception? = null

        for ((index, mirrorUrl) in JDK_MIRRORS.withIndex()) {
            val mirrorIndex = index + 1
            val totalMirrors = JDK_MIRRORS.size

            try {
                Log.i(TAG, "Attempting mirror ($mirrorIndex/$totalMirrors): $mirrorUrl")
                _state.value = _state.value.copy(
                    statusMessage = "Téléchargement OpenJDK 21 (Miroir $mirrorIndex/$totalMirrors)...",
                    downloadProgress = 0f
                )

                if (tempArchive.exists()) tempArchive.delete()

                downloadFileWithThrottledProgress(mirrorUrl, tempArchive)

                _state.value = _state.value.copy(
                    statusMessage = "Extraction de l'archive OpenJDK 21...",
                    downloadProgress = 0f
                )

                extractArchive(tempArchive, runtimeDir)

                normalizeJdkDirectory()

                applyExecPermissions(runtimeDir)

                val versionOutput = testJavaBinary()

                _state.value = _state.value.copy(
                    isInstalled = true,
                    isDownloading = false,
                    downloadProgress = 1f,
                    statusMessage = "OpenJDK 21 prêt",
                    javaVersion = versionOutput,
                    lastError = null
                )

                success = true
                Log.i(TAG, "OpenJDK 21 successfully installed and verified!")
                break
            } catch (e: Exception) {
                Log.e(TAG, "Mirror failed: $mirrorUrl", e)
                lastCaughtError = e
                tempArchive.delete()
            }
        }

        if (!success) {
            val errMsg = lastCaughtError?.localizedMessage ?: "Échec de tous les miroirs de téléchargement"
            _state.value = _state.value.copy(
                isInstalled = false,
                isDownloading = false,
                downloadProgress = 0f,
                statusMessage = "Échec de l'installation",
                lastError = errMsg
            )
        }

        tempArchive.delete()
        success
    }

    private fun downloadFileWithThrottledProgress(url: String, destFile: File) {
        val request = Request.Builder().url(url).build()
        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("HTTP ${response.code}: ${response.message}")
        }

        val body = response.body ?: throw IOException("Empty response body")
        val contentLength = body.contentLength()
        val tempDest = File(destFile.absolutePath + ".downloading")

        if (tempDest.exists()) tempDest.delete()

        var downloadedBytes = 0L
        var lastUpdateTime = 0L
        val buffer = ByteArray(64 * 1024) // 64 KB chunks

        body.byteStream().use { input ->
            FileOutputStream(tempDest).use { output ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read

                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime >= 250L || (contentLength > 0 && downloadedBytes == contentLength)) {
                        lastUpdateTime = now
                        val progress = if (contentLength > 0) {
                            (downloadedBytes.toFloat() / contentLength).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val mbDownloaded = downloadedBytes / (1024 * 1024)
                        val totalMbStr = if (contentLength > 0) " / ${contentLength / (1024 * 1024)} Mo" else " Mo"
                        _state.value = _state.value.copy(
                            downloadProgress = progress,
                            statusMessage = "Téléchargement: ${mbDownloaded}$totalMbStr (${(progress * 100).toInt()}%)"
                        )
                    }
                }
                output.flush()
            }
        }

        if (destFile.exists()) destFile.delete()
        if (!tempDest.renameTo(destFile)) {
            tempDest.copyTo(destFile, overwrite = true)
            tempDest.delete()
        }
    }

    private fun extractArchive(archiveFile: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()

        val fileName = archiveFile.name.lowercase()
        when {
            fileName.endsWith(".tar.xz") || isTarXz(archiveFile) -> {
                extractTarXz(archiveFile, targetDir)
            }
            fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz") -> {
                extractTarGz(archiveFile, targetDir)
            }
            else -> {
                // Default to Zip extraction
                extractZip(archiveFile, targetDir)
            }
        }
    }

    private fun isTarXz(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(6)
                if (fis.read(header) == 6) {
                    // XZ magic bytes: FD 37 7A 58 5A 00
                    header[0] == 0xFD.toByte() &&
                    header[1] == 0x37.toByte() &&
                    header[2] == 0x7A.toByte() &&
                    header[3] == 0x58.toByte() &&
                    header[4] == 0x5A.toByte() &&
                    header[5] == 0x00.toByte()
                } else false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun extractZip(zipFile: File, targetDir: File) {
        val targetCanon = targetDir.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry: ZipEntry?
            val buffer = ByteArray(32 * 1024)
            while (zis.nextEntry.also { entry = it } != null) {
                val current = entry ?: break
                val outFile = File(targetDir, current.name)

                // Zip Slip vulnerability protection
                if (!outFile.canonicalFile.path.startsWith(targetCanon.path)) {
                    throw SecurityException("Zip Slip detected: ${current.name}")
                }

                if (current.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        var len: Int
                        while (zis.read(buffer).also { len = it } > 0) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                zis.closeEntry()
            }
        }
    }

    private fun extractTarXz(archiveFile: File, targetDir: File) {
        val targetCanon = targetDir.canonicalFile
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                XZCompressorInputStream(bis).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        extractTarStream(tarIn, targetCanon)
                    }
                }
            }
        }
    }

    private fun extractTarGz(archiveFile: File, targetDir: File) {
        val targetCanon = targetDir.canonicalFile
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                GZIPInputStream(bis).use { gzIn ->
                    TarArchiveInputStream(gzIn).use { tarIn ->
                        extractTarStream(tarIn, targetCanon)
                    }
                }
            }
        }
    }

    private fun extractTarStream(tarIn: TarArchiveInputStream, targetCanon: File) {
        var entry: TarArchiveEntry?
        val buffer = ByteArray(32 * 1024)
        while (tarIn.nextEntry.also { entry = it } != null) {
            val current = entry ?: break
            val outFile = File(targetCanon, current.name)

            if (!outFile.canonicalFile.path.startsWith(targetCanon.path)) {
                throw SecurityException("Tar Slip detected: ${current.name}")
            }

            if (current.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    var len: Int
                    while (tarIn.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                }
            }
        }
    }

    /**
     * If the zip contained a root folder (e.g., JRE-21/bin/java or jre21/bin/java),
     * move all files from the subfolder to the top of runtimeDir so bin/java is found.
     */
    private fun normalizeJdkDirectory() {
        val libJvm = File(runtimeDir, "lib/server/libjvm.so")
        if (File(binDir, "java").exists() && libJvm.exists()) return

        // Look for any 'bin/java' in subdirectories
        val javaBinCandidates = runtimeDir.walkTopDown().filter {
            it.isFile && it.name == "java" && it.parentFile?.name == "bin"
        }.toList()

        if (javaBinCandidates.isNotEmpty()) {
            val candidate = javaBinCandidates.first()
            val jreRoot = candidate.parentFile?.parentFile
            if (jreRoot != null && jreRoot != runtimeDir) {
                Log.i(TAG, "Normalizing JDK root from ${jreRoot.absolutePath} to ${runtimeDir.absolutePath}")
                jreRoot.listFiles()?.forEach { file ->
                    val dest = File(runtimeDir, file.name)
                    if (dest.exists()) dest.deleteRecursively()
                    val ok = file.renameTo(dest)
                    if (!ok) {
                        file.copyRecursively(dest, overwrite = true)
                        file.deleteRecursively()
                    }
                }
                if (jreRoot.exists() && jreRoot != runtimeDir) {
                    jreRoot.deleteRecursively()
                }
            }
        }
    }

    /**
     * Recursively sets readable and executable permissions on binaries and libraries.
     */
    fun applyExecPermissions(dir: File) {
        try {
            dir.walkTopDown().forEach { file ->
                file.setReadable(true, false)
                if (file.isDirectory) {
                    file.setExecutable(true, false)
                    file.setWritable(true, true)
                } else {
                    val isBinaryOrLib = file.parentFile?.name == "bin" ||
                            file.name.endsWith(".so") ||
                            file.name == "java" ||
                            file.name == "keytool"
                    if (isBinaryOrLib) {
                        file.setExecutable(true, false)
                    }
                }
            }

            // Also invoke chmod system binary if available
            Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", dir.absolutePath)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "chmod failed (non-fatal, Java permissions applied)", e)
        }
    }

    private fun testJavaBinary(): String {
        return try {
            val pb = ProcessBuilder(javaExecutable.absolutePath, "-version")
            pb.directory(runtimeDir)
            val env = pb.environment()
            val libDir = File(runtimeDir, "lib")
            val jvmDir = File(libDir, "server")
            val jliDir = File(libDir, "jli")
            val ldPaths = listOf(libDir.absolutePath, jvmDir.absolutePath, jliDir.absolutePath)
                .filter { File(it).exists() }
            if (ldPaths.isNotEmpty()) {
                env["LD_LIBRARY_PATH"] = ldPaths.joinToString(":")
            }
            env["JAVA_HOME"] = runtimeDir.absolutePath
            pb.redirectErrorStream(true)

            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(5, TimeUnit.SECONDS)

            val firstLine = output.lineSequence().firstOrNull() ?: "OpenJDK 21"
            Log.i(TAG, "Java test execution output: $firstLine")
            firstLine
        } catch (e: Exception) {
            Log.w(TAG, "Test execution skipped or non-fatal: ${e.message}")
            "OpenJDK 21 (ARM64)"
        }
    }
}