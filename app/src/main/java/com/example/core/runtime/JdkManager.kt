package com.example.core.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class JdkState(
    val isInstalled: Boolean = false,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val statusMessage: String = "Not initialized",
    val javaVersion: String? = null,
    val lastError: String? = null
)

class JdkManager(private val context: Context) {
    companion object {
        private const val TAG = "JdkManager"

        const val DEFAULT_JDK_URL =
            "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre21-20240116/jre21-arm64-20240116-release.tar.xz"
        const val BACKUP_JDK_URL =
            "https://raw.githubusercontent.com/termux/termux-packages/master/packages/openjdk-21/openjdk-21-arm64.tar.gz"
    }

    private val _state = MutableStateFlow(JdkState())
    val state: StateFlow<JdkState> = _state.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    val runtimeDir: File
        get() = File(context.filesDir, "runtime")

    val binDir: File
        get() = File(runtimeDir, "bin")

    val javaExecutable: File
        get() = File(binDir, "java")

    suspend fun checkInstallation(): Boolean = withContext(Dispatchers.IO) {
        normalizeRuntimeStructure(runtimeDir)
        if (!javaExecutable.exists()) {
            _state.value = JdkState(isInstalled = false, statusMessage = "OpenJDK 21 not found")
            return@withContext false
        }

        val result = testJavaVersion()
        if (result.first) {
            _state.value = JdkState(
                isInstalled = true,
                statusMessage = "OpenJDK 21 ready",
                javaVersion = result.second
            )
            true
        } else {
            _state.value = JdkState(
                isInstalled = false,
                statusMessage = "Java binary invalid or failed execution",
                lastError = result.second
            )
            false
        }
    }

    suspend fun downloadAndInstall(
        downloadUrl: String = DEFAULT_JDK_URL,
        expectedSha256: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(
            isDownloading = true,
            statusMessage = "Starting OpenJDK download...",
            downloadProgress = 0f,
            lastError = null
        )

        val ext = if (downloadUrl.contains(".tar.gz")) ".tar.gz" else ".tar.xz"
        val archiveFile = File(context.cacheDir, "openjdk-runtime$ext")

        try {
            downloadFileWithResume(downloadUrl, archiveFile)

            if (!expectedSha256.isNullOrBlank()) {
                _state.value = _state.value.copy(statusMessage = "Verifying archive integrity (SHA-256)...")
                val actualSha256 = computeSha256(archiveFile)
                if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                    throw IllegalStateException("SHA-256 mismatch! Expected $expectedSha256 but got $actualSha256")
                }
            }

            _state.value = _state.value.copy(statusMessage = "Extracting OpenJDK 21...")
            runtimeDir.deleteRecursively()
            runtimeDir.mkdirs()
            extractArchive(archiveFile, runtimeDir)

            normalizeRuntimeStructure(runtimeDir)

            _state.value = _state.value.copy(statusMessage = "Applying execution permissions...")
            applyExecPermissions(runtimeDir)

            archiveFile.delete()

            _state.value = _state.value.copy(statusMessage = "Verifying java -version...")
            val (ok, versionOutput) = testJavaVersion()
            if (ok) {
                _state.value = JdkState(
                    isInstalled = true,
                    isDownloading = false,
                    downloadProgress = 1f,
                    statusMessage = "OpenJDK 21 successfully installed",
                    javaVersion = versionOutput
                )
                true
            } else {
                throw IllegalStateException("Test run java -version failed: $versionOutput")
            }
        } catch (e: Exception) {
            Log.e(TAG, "JDK installation failed", e)
            _state.value = JdkState(
                isInstalled = false,
                isDownloading = false,
                statusMessage = "Installation failed",
                lastError = e.message ?: e.toString()
            )
            false
        }
    }

    private fun downloadFileWithResume(url: String, destFile: File) {
        val existingBytes = if (destFile.exists()) destFile.length() else 0L

        val requestBuilder = Request.Builder().url(url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful && response.code != 416) {
                throw IllegalStateException("Download failed with HTTP ${response.code}: ${response.message}")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val contentLength = body.contentLength()
            val totalBytes = if (response.code == 206) existingBytes + contentLength else contentLength

            val randomAccessFile = RandomAccessFile(destFile, "rw")
            if (response.code == 206) {
                randomAccessFile.seek(existingBytes)
            } else {
                randomAccessFile.setLength(0)
            }

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var currentDownloaded = if (response.code == 206) existingBytes else 0L
            val inputStream = body.byteStream()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                randomAccessFile.write(buffer, 0, bytesRead)
                currentDownloaded += bytesRead

                val progress = if (totalBytes > 0) currentDownloaded.toFloat() / totalBytes else 0f
                val mbDownloaded = currentDownloaded / (1024 * 1024)
                _state.value = _state.value.copy(
                    downloadProgress = progress,
                    downloadedBytes = currentDownloaded,
                    totalBytes = totalBytes,
                    statusMessage = "Downloading OpenJDK: ${(progress * 100).toInt()}% ($mbDownloaded MB)"
                )
            }
            randomAccessFile.close()
        }
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (fis.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun extractArchive(archiveFile: File, targetDir: File) {
        targetDir.mkdirs()
        val name = archiveFile.name.lowercase()

        when {
            name.endsWith(".tar.xz") || name.endsWith(".txz") -> extractTarXz(archiveFile, targetDir)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> extractTarGz(archiveFile, targetDir)
            name.endsWith(".zip") -> extractZip(archiveFile, targetDir)
            else -> {
                try {
                    extractTarXz(archiveFile, targetDir)
                } catch (_: Exception) {
                    try {
                        extractTarGz(archiveFile, targetDir)
                    } catch (_: Exception) {
                        extractZip(archiveFile, targetDir)
                    }
                }
            }
        }
    }

    private fun extractTarXz(archive: File, targetDir: File) {
        FileInputStream(archive).use { fis ->
            XZCompressorInputStream(fis).use { xzIn ->
                extractTarStream(xzIn, targetDir)
            }
        }
    }

    private fun extractTarGz(archive: File, targetDir: File) {
        FileInputStream(archive).use { fis ->
            GzipCompressorInputStream(fis).use { gzIn ->
                extractTarStream(gzIn, targetDir)
            }
        }
    }

    private fun extractTarStream(inputStream: InputStream, targetDir: File) {
        TarArchiveInputStream(inputStream).use { tarIn ->
            var entry = tarIn.nextTarEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Arbitrary file write attempt detected: ${entry.name}")
                }

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        tarIn.copyTo(fos)
                    }
                }
                entry = tarIn.nextTarEntry
            }
        }
    }

    private fun extractZip(archive: File, targetDir: File) {
        ZipInputStream(FileInputStream(archive)).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Zip Slip detected: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        zipIn.copyTo(fos)
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
    }

    private fun normalizeRuntimeStructure(dir: File) {
        if (!dir.exists()) return
        if (File(dir, "bin/java").exists()) return

        val subDirs = dir.listFiles { f -> f.isDirectory } ?: return
        for (sub in subDirs) {
            val nestedBin = File(sub, "bin/java")
            if (nestedBin.exists()) {
                sub.listFiles()?.forEach { item ->
                    val dest = File(dir, item.name)
                    item.renameTo(dest)
                }
                sub.deleteRecursively()
                break
            }
        }
    }

    fun applyExecPermissions(dir: File) {
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                val name = file.name
                if (name == "java" || name.startsWith("playit") || name.endsWith(".so") || file.parentFile?.name == "bin") {
                    file.setReadable(true, false)
                    file.setExecutable(true, false)
                }
            }
        }
    }

    fun testJavaVersion(): Pair<Boolean, String> {
        return try {
            val pb = ProcessBuilder(javaExecutable.absolutePath, "-version")
            val env = pb.environment()
            val libDir = File(runtimeDir, "lib")
            val jvmDir = File(libDir, "server")
            val ldPath = listOf(libDir.absolutePath, jvmDir.absolutePath).joinToString(":")
            env["LD_LIBRARY_PATH"] = ldPath
            env["HOME"] = context.filesDir.absolutePath

            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0 || output.contains("version", ignoreCase = true) || output.contains("openjdk", ignoreCase = true)) {
                Pair(true, output.lines().firstOrNull() ?: output.take(100))
            } else {
                Pair(false, "Exit code $exitCode: $output")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: e.toString())
        }
    }
}
