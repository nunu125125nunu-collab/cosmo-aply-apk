package com.example.installer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

object XapkInstaller {

    private const val TAG = "XapkInstaller"

    data class XapkManifest(
        val packageName: String,
        val versionCode: String,
        val versionName: String,
        val splitApks: List<String>,
        val obbFiles: List<ObbEntry>
    )

    data class ObbEntry(
        val fileName: String,
        val installPath: String
    )

    sealed class InstallProgress {
        data class Extracting(val current: Int, val total: Int, val entryName: String) : InstallProgress()
        data class CopyingObb(val fileName: String) : InstallProgress()
        data class StagingSession(val currentApk: String, val index: Int, val total: Int) : InstallProgress()
        object CommittingSession : InstallProgress()
        data class Completed(val packageName: String) : InstallProgress()
        data class Error(val throwable: Throwable) : InstallProgress()
    }

    /**
     * Extracts and installs a .xapk package with multi-apk / split-apk PackageInstaller session,
     * parsing manifest.json and handling OBB expansions.
     */
    suspend fun installXapk(
        context: Context,
        xapkFile: File,
        onProgress: (InstallProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!xapkFile.exists()) {
            val err = IllegalArgumentException("XAPK file not found: ${xapkFile.absolutePath}")
            onProgress(InstallProgress.Error(err))
            return@withContext Result.failure(err)
        }

        val workDir = File(context.cacheDir, "xapk_extracted_${System.currentTimeMillis()}").apply {
            mkdirs()
        }

        try {
            // Step 1: Extract XAPK archive
            Log.d(TAG, "Extracting XAPK archive to: ${workDir.absolutePath}")
            val extractedFiles = extractZip(xapkFile, workDir, onProgress)

            // Step 2: Parse manifest.json
            val manifestFile = File(workDir, "manifest.json")
            val manifest = if (manifestFile.exists()) {
                parseManifest(manifestFile)
            } else {
                Log.w(TAG, "manifest.json missing in XAPK, building fallback manifest")
                createFallbackManifest(workDir)
            }

            Log.d(TAG, "Parsed XAPK manifest: package=${manifest.packageName}, apks=${manifest.splitApks.size}")

            // Step 3: Handle OBB files
            handleObbFiles(context, workDir, manifest, onProgress)

            // Step 4: Locate all APK files to stage
            val apksToInstall = mutableListOf<File>()
            if (manifest.splitApks.isNotEmpty()) {
                for (apkName in manifest.splitApks) {
                    val apkFile = File(workDir, apkName)
                    if (apkFile.exists()) {
                        apksToInstall.add(apkFile)
                    }
                }
            }

            // Fallback: collect all .apk files in working folder if manifest did not list them
            if (apksToInstall.isEmpty()) {
                workDir.walkTopDown().filter { it.extension.equals("apk", ignoreCase = true) }.forEach {
                    apksToInstall.add(it)
                }
            }

            if (apksToInstall.isEmpty()) {
                val err = IllegalStateException("No APK files found inside XAPK archive")
                onProgress(InstallProgress.Error(err))
                return@withContext Result.failure(err)
            }

            // Step 5: Install using Android PackageInstaller session
            installApksViaSession(context, manifest.packageName, apksToInstall, onProgress)

            onProgress(InstallProgress.Completed(manifest.packageName))
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to install XAPK", e)
            onProgress(InstallProgress.Error(e))
            Result.failure(e)
        } finally {
            // Clean up temporary extracted folder after staging
            try {
                workDir.deleteRecursively()
            } catch (ignored: Exception) {}
        }
    }

    private fun extractZip(
        zipFile: File,
        targetDir: File,
        onProgress: (InstallProgress) -> Unit
    ): List<File> {
        val extractedFiles = mutableListOf<File>()
        val buffer = ByteArray(1024 * 32)
        var count = 0

        FileInputStream(zipFile).use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    // Guard against Zip Slip vulnerability
                    val targetFile = File(targetDir, entryName)
                    if (!targetFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        throw SecurityException("Zip entry is attempting to climb outside destination directory: $entryName")
                    }

                    if (entry.isDirectory) {
                        targetFile.mkdirs()
                    } else {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            var read: Int
                            while (zis.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                            }
                        }
                        extractedFiles.add(targetFile)
                        count++
                        onProgress(InstallProgress.Extracting(count, count + 5, entryName))
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        return extractedFiles
    }

    private fun parseManifest(manifestFile: File): XapkManifest {
        val jsonString = manifestFile.readText(Charsets.UTF_8)
        val json = JSONObject(jsonString)

        val packageName = json.optString("package_name", "")
        val versionCode = json.optString("version_code", "")
        val versionName = json.optString("version_name", "")

        val splitApks = mutableListOf<String>()
        if (json.has("split_apks")) {
            val splits = json.getJSONArray("split_apks")
            for (i in 0 until splits.length()) {
                val item = splits.getJSONObject(i)
                val fileName = item.optString("file", "")
                if (fileName.isNotEmpty()) {
                    splitApks.add(fileName)
                }
            }
        }

        val obbList = mutableListOf<ObbEntry>()
        if (json.has("expansions")) {
            val expansions = json.getJSONArray("expansions")
            for (i in 0 until expansions.length()) {
                val exp = expansions.getJSONObject(i)
                val fileName = exp.optString("file", "")
                val path = exp.optString("install_path", "Android/obb/$packageName/$fileName")
                if (fileName.isNotEmpty()) {
                    obbList.add(ObbEntry(fileName, path))
                }
            }
        }

        return XapkManifest(
            packageName = packageName,
            versionCode = versionCode,
            versionName = versionName,
            splitApks = splitApks,
            obbFiles = obbList
        )
    }

    private fun createFallbackManifest(workDir: File): XapkManifest {
        val apks = workDir.walkTopDown()
            .filter { it.extension.equals("apk", ignoreCase = true) }
            .map { it.name }
            .toList()

        return XapkManifest(
            packageName = "",
            versionCode = "1",
            versionName = "1.0",
            splitApks = apks,
            obbFiles = emptyList()
        )
    }

    private fun handleObbFiles(
        context: Context,
        workDir: File,
        manifest: XapkManifest,
        onProgress: (InstallProgress) -> Unit
    ) {
        if (manifest.packageName.isEmpty()) return

        // 1. Check for OBBs declared in manifest
        for (obb in manifest.obbFiles) {
            val sourceFile = File(workDir, obb.fileName)
            if (sourceFile.exists()) {
                copyObbToStorage(context, sourceFile, manifest.packageName, onProgress)
            }
        }

        // 2. Also check if Android/obb directory exists in extracted content
        val extractedObbDir = File(workDir, "Android/obb")
        if (extractedObbDir.exists() && extractedObbDir.isDirectory) {
            extractedObbDir.walkTopDown()
                .filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
                .forEach { obbFile ->
                    copyObbToStorage(context, obbFile, manifest.packageName, onProgress)
                }
        }
    }

    private fun copyObbToStorage(
        context: Context,
        source: File,
        packageName: String,
        onProgress: (InstallProgress) -> Unit
    ) {
        try {
            onProgress(InstallProgress.CopyingObb(source.name))
            Log.d(TAG, "Copying OBB file: ${source.name} for package $packageName")

            // Destination directory: /sdcard/Android/obb/<package_name>/
            val externalStorage = Environment.getExternalStorageDirectory()
            val targetDir = File(externalStorage, "Android/obb/$packageName")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val targetFile = File(targetDir, source.name)
            source.copyTo(targetFile, overwrite = true)
            Log.i(TAG, "OBB successfully copied to: ${targetFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Unable to copy OBB directly to system OBB directory (may require Scoped Storage workaround): ${e.message}")
            try {
                // Fallback destination in app's external files directory
                val fallbackDir = File(context.getExternalFilesDir(null), "obb/$packageName").apply { mkdirs() }
                source.copyTo(File(fallbackDir, source.name), overwrite = true)
            } catch (ignored: Exception) {}
        }
    }

    private fun installApksViaSession(
        context: Context,
        packageName: String,
        apkFiles: List<File>,
        onProgress: (InstallProgress) -> Unit
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (packageName.isNotEmpty()) {
            params.setAppPackageName(packageName)
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        try {
            val buffer = ByteArray(1024 * 64)
            for ((index, apkFile) in apkFiles.withIndex()) {
                onProgress(InstallProgress.StagingSession(apkFile.name, index + 1, apkFiles.size))
                Log.d(TAG, "Staging split APK into PackageInstaller session: ${apkFile.name} (${apkFile.length()} bytes)")

                val sessionStream = session.openWrite(apkFile.name, 0, apkFile.length())
                FileInputStream(apkFile).use { fis ->
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        sessionStream.write(buffer, 0, bytesRead)
                    }
                }
                session.fsync(sessionStream)
                sessionStream.close()
            }

            onProgress(InstallProgress.CommittingSession)

            // Commit session with PendingIntent targeting InstallStatusReceiver
            val intent = Intent(context, InstallStatusReceiver::class.java).apply {
                action = InstallStatusReceiver.ACTION_INSTALL_STATUS
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pendingIntent.intentSender)
            Log.i(TAG, "PackageInstaller session $sessionId committed successfully")
        } finally {
            session.close()
        }
    }
}
