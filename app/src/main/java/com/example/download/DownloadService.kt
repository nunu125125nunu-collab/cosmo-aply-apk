package com.example.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    private var currentUrl: String = ""
    private var currentFileName: String = ""
    private var currentAppTitle: String = ""
    private var currentIconUrl: String? = null
    private var currentPackageName: String? = null
    private var appIconBitmap: Bitmap? = null

    private var currentTotalBytes: Long = 0L
    private var currentDownloadedBytes: Long = 0L
    private var isPaused = false

    private lateinit var notificationManager: NotificationManager

    companion object {
        private const val TAG = "DownloadService"
        const val CHANNEL_ID = "channel_cosmo_downloads"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "com.example.download.ACTION_START"
        const val ACTION_PAUSE = "com.example.download.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.download.ACTION_RESUME"
        const val ACTION_CANCEL = "com.example.download.ACTION_CANCEL"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_APP_TITLE = "extra_app_title"
        const val EXTRA_ICON_URL = "extra_icon_url"
        const val EXTRA_PACKAGE_NAME = "extra_package_name"

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        fun startDownload(
            context: Context,
            url: String,
            fileName: String,
            appTitle: String = "",
            iconUrl: String? = null,
            packageName: String? = null
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_APP_TITLE, appTitle)
                putExtra(EXTRA_ICON_URL, iconUrl)
                putExtra(EXTRA_PACKAGE_NAME, packageName)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun pauseDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancelDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "download.apk"
                val title = intent.getStringExtra(EXTRA_APP_TITLE) ?: ""
                val icon = intent.getStringExtra(EXTRA_ICON_URL)
                val pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME)

                currentUrl = url
                currentFileName = fileName
                currentAppTitle = if (title.isNotBlank()) title else fileName.substringBeforeLast(".")
                currentIconUrl = icon
                currentPackageName = pkg
                isPaused = false

                fetchAppIconAsync(icon)
                startForegroundNotification()
                beginDownload(resume = false)
            }

            ACTION_PAUSE -> {
                handlePause()
            }

            ACTION_RESUME -> {
                if (currentUrl.isNotEmpty() && currentFileName.isNotEmpty()) {
                    isPaused = false
                    startForegroundNotification()
                    beginDownload(resume = true)
                }
            }

            ACTION_CANCEL -> {
                handleCancel()
            }
        }
        return START_NOT_STICKY
    }

    private fun fetchAppIconAsync(iconUrl: String?) {
        if (iconUrl.isNullOrBlank()) {
            appIconBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_android_package)
            return
        }

        serviceScope.launch {
            try {
                val url = URL(iconUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.inputStream.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    if (bitmap != null) {
                        appIconBitmap = bitmap
                        updateProgressNotification(
                            progress = calculateProgress(currentDownloadedBytes, currentTotalBytes),
                            downloaded = currentDownloadedBytes,
                            total = currentTotalBytes,
                            isPaused = isPaused
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load rich app icon from: $iconUrl", e)
                appIconBitmap = BitmapFactory.decodeResource(resources, R.drawable.ic_android_package)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Package Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live download and installation progress for games and updates"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = buildDownloadNotification(
            progress = 0,
            downloaded = 0L,
            total = 0L,
            isPaused = false
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginDownload(resume: Boolean) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            val targetFile = File(getDownloadDirectory(), currentFileName)
            var existingLength = 0L

            if (resume && targetFile.exists()) {
                existingLength = targetFile.length()
            } else if (!resume && targetFile.exists()) {
                targetFile.delete()
            }

            currentDownloadedBytes = existingLength

            var connection: HttpURLConnection? = null
            var inputStream: InputStream? = null
            var outputStream: FileOutputStream? = null

            try {
                val urlObj = URL(currentUrl)
                connection = (urlObj.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "CosmoStore/1.0 (Android)")
                    if (existingLength > 0L) {
                        setRequestProperty("Range", "bytes=$existingLength-")
                    }
                }
                connection.connect()

                val responseCode = connection.responseCode
                Log.d(TAG, "Server response: $responseCode for $currentFileName")

                val appendMode: Boolean
                if (responseCode == HttpURLConnection.HTTP_PARTIAL) { // 206
                    appendMode = true
                    val contentRange = connection.getHeaderField("Content-Range")
                    currentTotalBytes = parseTotalFromContentRange(contentRange, existingLength + connection.contentLengthLong)
                } else if (responseCode == HttpURLConnection.HTTP_OK) { // 200
                    appendMode = false
                    currentDownloadedBytes = 0L
                    currentTotalBytes = connection.contentLengthLong
                } else {
                    throw IllegalStateException("Unexpected HTTP response: $responseCode")
                }

                inputStream = connection.inputStream
                outputStream = FileOutputStream(targetFile, appendMode)

                val buffer = ByteArray(1024 * 32)
                var readBytes: Int
                var lastNotificationTime = System.currentTimeMillis()
                var bytesSinceLastSpeedCheck = 0L
                var lastSpeedCheckTime = System.currentTimeMillis()
                var currentSpeed = 0L

                _downloadState.value = DownloadState.Downloading(
                    url = currentUrl,
                    fileName = currentFileName,
                    appTitle = currentAppTitle,
                    iconUrl = currentIconUrl,
                    packageName = currentPackageName,
                    progress = calculateProgress(currentDownloadedBytes, currentTotalBytes),
                    downloadedBytes = currentDownloadedBytes,
                    totalBytes = currentTotalBytes,
                    speedBytesPerSec = 0L
                )

                while (inputStream.read(buffer).also { readBytes = it } != -1) {
                    if (isPaused) {
                        return@launch
                    }

                    outputStream.write(buffer, 0, readBytes)
                    currentDownloadedBytes += readBytes
                    bytesSinceLastSpeedCheck += readBytes

                    val now = System.currentTimeMillis()
                    if (now - lastSpeedCheckTime >= 1000) {
                        currentSpeed = (bytesSinceLastSpeedCheck * 1000) / (now - lastSpeedCheckTime)
                        bytesSinceLastSpeedCheck = 0L
                        lastSpeedCheckTime = now
                    }

                    if (now - lastNotificationTime >= 400) {
                        val progress = calculateProgress(currentDownloadedBytes, currentTotalBytes)
                        _downloadState.value = DownloadState.Downloading(
                            url = currentUrl,
                            fileName = currentFileName,
                            appTitle = currentAppTitle,
                            iconUrl = currentIconUrl,
                            packageName = currentPackageName,
                            progress = progress,
                            downloadedBytes = currentDownloadedBytes,
                            totalBytes = currentTotalBytes,
                            speedBytesPerSec = currentSpeed
                        )

                        updateProgressNotification(
                            progress = progress,
                            downloaded = currentDownloadedBytes,
                            total = currentTotalBytes,
                            isPaused = false
                        )
                        lastNotificationTime = now
                    }
                }

                outputStream.flush()

                _downloadState.value = DownloadState.Completed(
                    url = currentUrl,
                    file = targetFile,
                    appTitle = currentAppTitle,
                    iconUrl = currentIconUrl,
                    packageName = currentPackageName,
                    totalBytes = targetFile.length()
                )

                showCompletedNotification(targetFile)
                stopForeground(false)
                stopSelf()

            } catch (e: Exception) {
                if (!isPaused) {
                    Log.e(TAG, "Download failed for $currentFileName", e)
                    _downloadState.value = DownloadState.Failed(
                        url = currentUrl,
                        fileName = currentFileName,
                        appTitle = currentAppTitle,
                        error = e.localizedMessage ?: "Network error"
                    )
                    showFailedNotification(e.localizedMessage ?: "Download interrupted")
                    stopForeground(false)
                    stopSelf()
                }
            } finally {
                try { outputStream?.close() } catch (ignored: Exception) {}
                try { inputStream?.close() } catch (ignored: Exception) {}
                connection?.disconnect()
            }
        }
    }

    private fun handlePause() {
        isPaused = true
        downloadJob?.cancel()

        val progress = calculateProgress(currentDownloadedBytes, currentTotalBytes)
        _downloadState.value = DownloadState.Paused(
            url = currentUrl,
            fileName = currentFileName,
            appTitle = currentAppTitle,
            iconUrl = currentIconUrl,
            packageName = currentPackageName,
            progress = progress,
            downloadedBytes = currentDownloadedBytes,
            totalBytes = currentTotalBytes
        )

        updateProgressNotification(
            progress = progress,
            downloaded = currentDownloadedBytes,
            total = currentTotalBytes,
            isPaused = true
        )
    }

    private fun handleCancel() {
        isPaused = true
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle

        try {
            val file = File(getDownloadDirectory(), currentFileName)
            if (file.exists()) {
                file.delete()
            }
        } catch (ignored: Exception) {}

        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(true)
        stopSelf()
    }

    private fun calculateProgress(downloaded: Long, total: Long): Int {
        return if (total > 0L) {
            ((downloaded * 100) / total).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    private fun parseTotalFromContentRange(rangeHeader: String?, fallback: Long): Long {
        if (rangeHeader != null && rangeHeader.contains("/")) {
            val totalStr = rangeHeader.substringAfter("/")
            val parsed = totalStr.toLongOrNull()
            if (parsed != null && parsed > 0L) {
                return parsed
            }
        }
        return fallback
    }

    private fun buildDownloadNotification(
        progress: Int,
        downloaded: Long,
        total: Long,
        isPaused: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeAction = if (isPaused) {
            val resumeIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_RESUME }
            val resumePendingIntent = PendingIntent.getService(
                this,
                1,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                R.drawable.ic_play,
                "Resume",
                resumePendingIntent
            ).build()
        } else {
            val pauseIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_PAUSE }
            val pausePendingIntent = PendingIntent.getService(
                this,
                2,
                pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action.Builder(
                R.drawable.ic_pause,
                "Pause",
                pausePendingIntent
            ).build()
        }

        val cancelIntent = Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            3,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelAction = NotificationCompat.Action.Builder(
            R.drawable.ic_close,
            "Cancel",
            cancelPendingIntent
        ).build()

        val displayTitle = if (currentAppTitle.isNotBlank()) currentAppTitle else currentFileName
        val title = if (isPaused) "Paused: $displayTitle" else "Downloading $displayTitle"
        val subtitle = if (total > 0L) {
            "$progress% (${formatFileSize(downloaded)} / ${formatFileSize(total)})"
        } else {
            "${formatFileSize(downloaded)} downloaded"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentPendingIntent)
            .setProgress(100, progress, total <= 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(pauseResumeAction)
            .addAction(cancelAction)

        appIconBitmap?.let {
            builder.setLargeIcon(it)
        }

        return builder.build()
    }

    private fun updateProgressNotification(
        progress: Int,
        downloaded: Long,
        total: Long,
        isPaused: Boolean
    ) {
        val notification = buildDownloadNotification(progress, downloaded, total, isPaused)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletedNotification(file: File) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayTitle = if (currentAppTitle.isNotBlank()) currentAppTitle else file.name
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_check_circle)
            .setContentTitle("Download Complete: $displayTitle")
            .setContentText("Tap to install in Cosmo Game Store")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)

        appIconBitmap?.let {
            builder.setLargeIcon(it)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun showFailedNotification(reason: String) {
        val displayTitle = if (currentAppTitle.isNotBlank()) currentAppTitle else currentFileName
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_off)
            .setContentTitle("Download Failed: $displayTitle")
            .setContentText(reason)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getDownloadDirectory(): File {
        val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val normalized = bytes / Math.pow(1024.0, digitGroups.toDouble())
        return String.format(Locale.getDefault(), "%.1f %s", normalized, units[digitGroups])
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
