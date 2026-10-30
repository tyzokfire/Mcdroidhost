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
    val statusMessage: String = "Non installé",
    val javaVersion: String? = null,
    val lastError: String? = null
)

class JdkManager(private val context: Context) {
    companion object {
        private const val TAG = "JdkManager"

        // High reliability, direct ARM64 Android OpenJDK builds
        val MIRRORS = listOf(
            "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/download/jre22-6.0.0/JRE-21.zip",
            "https://github.com/awoot6549/android-openjdk-build-multiarch/releases/download/rolling/jre21-pojav.zip",
            "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ca01427/jre17-arm64-20220817-release.tar.xz"
        )

        const val DEFAULT_JDK_URL =
            "https://github.com/QuestCraftPlusPlus/android-openjdk-build-multiarch/releases/download/jre22-6.0.0/JRE-21.zip"
        const val BACKUP_JDK_URL =
            "https://github.com/awoot6549/android-openjdk-build-multiarch/releases/download/rolling/jre21-pojav.zip"
    }

    private val _state = MutableStateFlow(JdkState())
    val state: StateFlow<JdkState> = _state.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    val runtimeDir: File
        get() = File(context.filesDir, "runtime")

    val binDir: File
        get() = File(runtimeDir, "bin")

    val javaExecutable: File
        get() = File(binDir, "java")

    suspend fun checkInstallation(): Boolean = withContext(Dispatchers.IO) {
        if (!javaExecutable.exists()) {
            _state.value = JdkState(isInstalled = false, statusMessage = "OpenJDK 21 non installé")
            return@withContext false
        }

        // Validate execution with java -version
        val result = testJavaVersion()
        if (result.first) {
            _state.value = JdkState(
                isInstalled = true,
                statusMessage = "OpenJDK 21 prêt (${result.second})",
                javaVersion = result.second
            )
            true
        } else {
            _state.value = JdkState(
                isInstalled = false,
                statusMessage = "Binaire Java non exécutable",
                lastError = result.second
            )
            false
        }
    }

    suspend fun downloadAndInstall(
        downloadUrl: String? = null,
        expectedSha256: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(
            isDownloading = true,
            statusMessage = "Initialisation du téléchargement...",
            downloadProgress = 0f,
            lastError = null
        )

        val candidateUrls = if (!downloadUrl.isNullOrBlank()) {
            listOf(downloadUrl) + MIRRORS.filter { it != downloadUrl }
        } else {
            MIRRORS
        }

        var lastException: Exception? = null

        for ((index, url) in candidateUrls.withIndex()) {
            val mirrorLabel = if (index == 0) "principal" else "miroir #$index"
            Log.i(TAG, "Tentative d'installation OpenJDK depuis le miroir $mirrorLabel: $url")
            _state.value = _state.value.copy(
                statusMessage = "Téléchargement OpenJDK ($mirrorLabel)...",
                downloadProgress = 0f
            )

            val archiveFile = File(context.cacheDir, "openjdk-runtime-${System.currentTimeMillis()}.tmp")

            try {
                // 1. Download
                downloadFileWithResume(url, archiveFile)

                // 2. Validate SHA-256 if requested and for primary url
                if (index == 0 && !expectedSha256.isNullOrBlank()) {
                    _state.value = _state.value.copy(statusMessage = "Vérification SHA-256 de l'archive...")
                    val actualSha256 = computeSha256(archiveFile)
                    if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                        throw IllegalStateException("SHA-256 invalide: attendu $expectedSha256 mais reçu $actualSha256")
                    }
                }

                // 3. Extract into runtime directory
                _state.value = _state.value.copy(statusMessage = "Extraction de l'environnement OpenJDK...")
                if (runtimeDir.exists()) {
                    runtimeDir.deleteRecursively()
                }
                runtimeDir.mkdirs()
                extractArchive(archiveFile, runtimeDir)

                // 4. Normalize directory layout if nested
                normalizeRuntimeDir(runtimeDir)

                // 5. Apply executable permissions (chmod 755 & Java attributes)
                _state.value = _state.value.copy(statusMessage = "Application des permissions d'exécution...")
                applyExecPermissions(runtimeDir)

                // 6. Delete temp archive
                try { archiveFile.delete() } catch (_: Exception) {}

                // 7. Verify installation
                _state.value = _state.value.copy(statusMessage = "Vérification du binaire java -version...")
                val (ok, versionOutput) = testJavaVersion()
                if (ok) {
                    _state.value = JdkState(
                        isInstalled = true,
                        isDownloading = false,
                        downloadProgress = 1f,
                        statusMessage = "OpenJDK installé avec succès ($versionOutput)",
                        javaVersion = versionOutput
                    )
                    Log.i(TAG, "OpenJDK installé et vérifié: $versionOutput")
                    return@withContext true
                } else {
                    throw IllegalStateException("Test java -version a échoué: $versionOutput")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Échec avec le miroir $url", e)
                lastException = e
                try { archiveFile.delete() } catch (_: Exception) {}
            }
        }

        // All mirrors failed
        val errorMsg = lastException?.message ?: "Tous les miroirs OpenJDK ont échoué"
        Log.e(TAG, "Installation finale échouée: $errorMsg", lastException)
        _state.value = JdkState(
            isInstalled = false,
            isDownloading = false,
            statusMessage = "Échec de l'installation OpenJDK",
            lastError = errorMsg
        )
        false
    }

    private fun downloadFileWithResume(url: String, destFile: File) {
        val existingBytes = if (destFile.exists()) destFile.length() else 0L

        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; arm64-v8a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MC-Droid-Host")
            .header("Accept", "application/octet-stream, application/zip, */*")

        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val request = requestBuilder.build()
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: Exception) {
            // If range request failed, reset and try full request once
            if (existingBytes > 0) {
                destFile.delete()
                val retryReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; arm64-v8a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MC-Droid-Host")
                    .build()
                httpClient.newCall(retryReq).execute()
            } else {
                throw e
            }
        }

        response.use { resp ->
            if (!resp.isSuccessful && resp.code != 416) {
                throw IllegalStateException("HTTP ${resp.code}: ${resp.message}")
            }

            // If range is invalid (416), delete cache and redownload cleanly
            if (resp.code == 416) {
                destFile.delete()
                val freshReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; arm64-v8a) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MC-Droid-Host")
                    .build()
                httpClient.newCall(freshReq).execute().use { freshResp ->
                    if (!freshResp.isSuccessful) throw IllegalStateException("HTTP ${freshResp.code}: ${freshResp.message}")
                    writeResponseBody(freshResp, destFile, 0L)
                }
                return
            }

            val isPartial = (resp.code == 206)
            writeResponseBody(resp, destFile, if (isPartial) existingBytes else 0L)
        }
    }

    private fun writeResponseBody(response: okhttp3.Response, destFile: File, offset: Long) {
        val body = response.body ?: throw IllegalStateException("Corps de réponse HTTP vide")
        val contentLength = body.contentLength()
        val totalBytes = if (offset > 0) offset + contentLength else contentLength

        val randomAccessFile = RandomAccessFile(destFile, "rw")
        if (offset > 0) {
            randomAccessFile.seek(offset)
        } else {
            randomAccessFile.setLength(0)
        }

        val buffer = ByteArray(64 * 1024)
        var bytesRead: Int
        var currentDownloaded = offset
        val inputStream = body.byteStream()

        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            randomAccessFile.write(buffer, 0, bytesRead)
            currentDownloaded += bytesRead

            val progress = if (totalBytes > 0) currentDownloaded.toFloat() / totalBytes else 0f
            _state.value = _state.value.copy(
                downloadProgress = progress,
                downloadedBytes = currentDownloaded,
                totalBytes = totalBytes,
                statusMessage = "Téléchargement OpenJDK: ${(progress * 100).toInt()}% (${currentDownloaded / 1024 / 1024} Mo)"
            )
        }
        randomAccessFile.close()
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

        // Detect archive type by magic bytes first
        val magic = ByteArray(6)
        FileInputStream(archiveFile).use { it.read(magic) }

        val isZip = magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
        val isXz = magic[0] == 0xFD.toByte() && magic[1] == 0x37.toByte() && magic[2] == 0x7A.toByte()
        val isGz = magic[0] == 0x1F.toByte() && magic[1] == 0x8B.toByte()

        when {
            isZip -> extractZip(archiveFile, targetDir)
            isXz -> extractTarXz(archiveFile, targetDir)
            isGz -> extractTarGz(archiveFile, targetDir)
            else -> {
                // Try zip first, then tar.xz, then tar.gz
                try {
                    extractZip(archiveFile, targetDir)
                } catch (_: Exception) {
                    try {
                        extractTarXz(archiveFile, targetDir)
                    } catch (_: Exception) {
                        extractTarGz(archiveFile, targetDir)
                    }
                }
            }
        }
    }

    private fun normalizeRuntimeDir(targetDir: File) {
        val directJava = File(File(targetDir, "bin"), "java")
        if (directJava.exists()) return

        // Search recursively for bin/java (e.g. if root folder was JRE-21/)
        val foundJava = targetDir.walkTopDown().firstOrNull {
            it.isFile && it.name == "java" && it.parentFile?.name == "bin"
        }

        if (foundJava != null) {
            val nestedRootDir = foundJava.parentFile?.parentFile
            if (nestedRootDir != null && nestedRootDir.canonicalPath != targetDir.canonicalPath) {
                Log.i(TAG, "Normalisation de l'arborescence JDK depuis ${nestedRootDir.name}")
                nestedRootDir.listFiles()?.forEach { child ->
                    val dest = File(targetDir, child.name)
                    if (child.isDirectory) {
                        child.copyRecursively(dest, overwrite = true)
                    } else {
                        child.copyTo(dest, overwrite = true)
                    }
                }
                try { nestedRootDir.deleteRecursively() } catch (_: Exception) {}
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
                // Zip-slip / Tar-slip protection
                if (!outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw SecurityException("Tentative d'écriture hors du répertoire cible: ${entry.name}")
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
                    throw SecurityException("Zip Slip détecté: ${entry.name}")
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

    fun applyExecPermissions(dir: File) {
        try {
            Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", dir.absolutePath)).waitFor()
        } catch (e: Exception) {
            Log.w(TAG, "chmod -R 755 non supporté ou ignoré: ${e.message}")
        }

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
            if (!javaExecutable.exists()) {
                return Pair(false, "Fichier binaire java introuvable (${javaExecutable.absolutePath})")
            }
            val pb = ProcessBuilder(javaExecutable.absolutePath, "-version")
            val env = pb.environment()
            val libDir = File(runtimeDir, "lib")
            val jvmDir = File(libDir, "server")
            val jliDir = File(libDir, "jli")
            val ldPaths = listOf(libDir.absolutePath, jvmDir.absolutePath, jliDir.absolutePath)
                .filter { File(it).exists() }
            env["LD_LIBRARY_PATH"] = ldPaths.joinToString(":")
            env["JAVA_HOME"] = runtimeDir.absolutePath
            env["PATH"] = "${binDir.absolutePath}:${System.getenv("PATH") ?: "/system/bin"}"
            env["HOME"] = context.filesDir.absolutePath

            pb.redirectErrorStream(true)
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0 || output.contains("version", ignoreCase = true) || output.contains("openjdk", ignoreCase = true)) {
                Pair(true, output.lines().firstOrNull { it.isNotBlank() } ?: output.take(100))
            } else {
                Pair(false, "Code de sortie $exitCode: $output")
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: e.toString())
        }
    }
}
