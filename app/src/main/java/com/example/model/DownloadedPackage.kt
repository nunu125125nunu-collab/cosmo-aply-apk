package com.example.model

import android.graphics.drawable.Drawable
import java.io.File

data class DownloadedPackage(
    val packageName: String,
    val appTitle: String,
    val subInfo: String,
    val isInstalled: Boolean,
    val file: File? = null,
    val iconDrawable: Drawable? = null,
    val typeLabel: String = "APP"
)
