package com.example.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cosmo_store_prefs", Context.MODE_PRIVATE)

    companion object {
        const val DEFAULT_START_URL = "https://cosmo-game.pages.dev/"

        // Network preference values
        const val NETWORK_WIFI_OR_MOBILE = "wifi_or_mobile"
        const val NETWORK_WIFI_ONLY = "wifi_only"

        // Auto install preference values
        const val AUTO_INSTALL_ALLOW = "auto_install_allow"
        const val AUTO_INSTALL_ASK = "auto_install_ask"
        const val AUTO_INSTALL_NEVER = "auto_install_never"

        // Theme preference values
        const val THEME_SYSTEM = "theme_system"
        const val THEME_LIGHT = "theme_light"
        const val THEME_DARK = "theme_dark"
        const val THEME_SITE = "theme_site"

        private const val KEY_START_URL = "key_start_url"
        private const val KEY_NETWORK_MODE = "key_network_mode"
        private const val KEY_AUTO_INSTALL_MODE = "key_auto_install_mode"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_WARN_NON_ANDROID = "key_warn_non_android"
    }

    val startUrl: String
        get() = prefs.getString(KEY_START_URL, DEFAULT_START_URL) ?: DEFAULT_START_URL

    var networkMode: String
        get() = prefs.getString(KEY_NETWORK_MODE, NETWORK_WIFI_OR_MOBILE) ?: NETWORK_WIFI_OR_MOBILE
        set(value) = prefs.edit().putString(KEY_NETWORK_MODE, value).apply()

    var autoInstallMode: String
        get() = prefs.getString(KEY_AUTO_INSTALL_MODE, AUTO_INSTALL_ALLOW) ?: AUTO_INSTALL_ALLOW
        set(value) = prefs.edit().putString(KEY_AUTO_INSTALL_MODE, value).apply()

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, THEME_SYSTEM) ?: THEME_SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value).apply()

    var warnNonAndroidFiles: Boolean
        get() = prefs.getBoolean(KEY_WARN_NON_ANDROID, true)
        set(value) = prefs.edit().putBoolean(KEY_WARN_NON_ANDROID, value).apply()

    fun isWifiOnly(): Boolean = networkMode == NETWORK_WIFI_ONLY

    fun isAutoInstallAllowed(): Boolean = autoInstallMode == AUTO_INSTALL_ALLOW
    fun isAskBeforeInstall(): Boolean = autoInstallMode == AUTO_INSTALL_ASK
    fun isNeverAutoInstall(): Boolean = autoInstallMode == AUTO_INSTALL_NEVER

    fun applyTheme() {
        when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            THEME_DARK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            THEME_SITE, THEME_SYSTEM -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
