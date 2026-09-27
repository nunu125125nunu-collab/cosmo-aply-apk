package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_apps")
data class InstalledAppEntity(
    @PrimaryKey val packageName: String,
    val appTitle: String,
    val iconUrl: String? = null,
    val installDate: Long = System.currentTimeMillis(),
    val versionName: String? = null,
    val isInstalled: Boolean = true,
    val filePath: String? = null
)
