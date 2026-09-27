package com.example.download

import java.io.File

sealed class DownloadState {
    object Idle : DownloadState()

    data class Downloading(
        val url: String,
        val fileName: String,
        val appTitle: String = "",
        val iconUrl: String? = null,
        val packageName: String? = null,
        val progress: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L
    ) : DownloadState()

    data class Paused(
        val url: String,
        val fileName: String,
        val appTitle: String = "",
        val iconUrl: String? = null,
        val packageName: String? = null,
        val progress: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : DownloadState()

    data class Completed(
        val url: String,
        val file: File,
        val appTitle: String = "",
        val iconUrl: String? = null,
        val packageName: String? = null,
        val totalBytes: Long
    ) : DownloadState()

    data class Failed(
        val url: String,
        val fileName: String,
        val appTitle: String = "",
        val error: String
    ) : DownloadState()
}
