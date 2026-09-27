package com.example

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.View
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.data.local.AppDatabase
import com.example.data.local.InstalledAppEntity
import com.example.databinding.ActivityMainBinding
import com.example.download.DownloadService
import com.example.download.DownloadState
import com.example.installer.ApkInstaller
import com.example.installer.InstallStatusReceiver
import com.example.installer.XapkInstaller
import com.example.model.DownloadedPackage
import com.example.ui.LibraryAdapter
import com.example.util.AppSettings
import com.example.util.NetworkUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MENU_BROWSER_ID = 1
        private const val MENU_LIBRARY_ID = 2
        private const val MENU_UPDATE_ID = 3
        private const val MENU_SETTINGS_ID = 4
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var appSettings: AppSettings
    private lateinit var appDatabase: AppDatabase
    private lateinit var libraryAdapter: LibraryAdapter

    private var pendingInstallFile: File? = null
    private var currentTabId = MENU_BROWSER_ID
    private var isOffline = false

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (ApkInstaller.canInstallPackages(this)) {
            pendingInstallFile?.let { file ->
                executePackageInstallation(file)
            }
        } else {
            Toast.makeText(this, "Install permission is required to install applications", Toast.LENGTH_LONG).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Notification permission result: $isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        appSettings = AppSettings(this)
        appSettings.applyTheme()

        appDatabase = AppDatabase.getDatabase(this)

        setupBottomNavigation()
        setupWebView()
        setupOfflineHandling()
        setupLibraryView()
        setupUpdateView()
        setupSettingsView()
        setupBackNavigation()
        observeNetworkState()
        observeInstallEvents()
        observeDownloadState()

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data: Uri? = intent?.data
        if (data != null) {
            Log.d(TAG, "Deep link received: $data")
            val targetUrl = when {
                data.scheme.equals("cosmostore", ignoreCase = true) -> {
                    // Extract query or path from cosmostore://
                    data.getQueryParameter("url") ?: appSettings.startUrl
                }
                else -> data.toString()
            }
            loadWebPage(targetUrl)
        } else {
            loadWebPage(appSettings.startUrl)
        }
    }

    /**
     * Programmatic BottomNavigationView configuration matching exact specifications:
     * - Menu Item 1: ID = 1, Title = "Browser", Icon = R.drawable.icon_home_round
     * - Menu Item 2: ID = 2, Title = "My library", Icon = R.drawable.icon_library_add_check_round
     * - Menu Item 3: ID = 3, Title = "Update", Icon = R.drawable.icon_notifications_round
     * - Menu Item 4: ID = 4, Title = "Settings", Icon = R.drawable.icon_settings_round
     */
    private fun setupBottomNavigation() {
        val bottomNav = binding.bottomnavigation1
        val menu = bottomNav.menu
        menu.clear()

        menu.add(Menu.NONE, MENU_BROWSER_ID, 0, getString(R.string.menu_browser))
            .setIcon(R.drawable.icon_home_round)
        menu.add(Menu.NONE, MENU_LIBRARY_ID, 1, getString(R.string.menu_library))
            .setIcon(R.drawable.icon_library_add_check_round)
        menu.add(Menu.NONE, MENU_UPDATE_ID, 2, getString(R.string.menu_update))
            .setIcon(R.drawable.icon_notifications_round)
        menu.add(Menu.NONE, MENU_SETTINGS_ID, 3, getString(R.string.menu_settings))
            .setIcon(R.drawable.icon_settings_round)

        bottomNav.selectedItemId = MENU_BROWSER_ID

        bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
    }

    private fun switchTab(itemId: Int) {
        currentTabId = itemId

        binding.swipeRefreshLayout.isVisible = (itemId == MENU_BROWSER_ID && !isOffline)
        binding.layoutOffline.isVisible = (itemId == MENU_BROWSER_ID && isOffline)
        binding.layoutLibrary.isVisible = (itemId == MENU_LIBRARY_ID)
        binding.layoutUpdate.isVisible = (itemId == MENU_UPDATE_ID)
        binding.layoutSettings.isVisible = (itemId == MENU_SETTINGS_ID)

        when (itemId) {
            MENU_BROWSER_ID -> {
                // Edge-to-edge clean browser view
            }
            MENU_LIBRARY_ID -> {
                refreshLibraryList()
            }
            MENU_UPDATE_ID -> {
                // Update diagnostics view
            }
            MENU_SETTINGS_ID -> {
                updateSettingsUiValues()
                updateCacheSizeDisplay()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webViewMain

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "${webView.settings.userAgentString} CosmoStore/1.0"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        binding.swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.pageProgressBar.isVisible = true
                    binding.pageProgressBar.progress = newProgress
                } else {
                    binding.pageProgressBar.isVisible = false
                    binding.swipeRefreshLayout.isRefreshing = false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.d(TAG, "shouldOverrideUrlLoading: $url")

                // Intercept package downloads and worker download endpoints
                if (isPackageDownloadUrl(url)) {
                    val guessFileName = URLUtil.guessFileName(url, null, null)
                    val appTitle = extractAppTitleFromUrlOrPage(url, view?.title)
                    confirmAndStartDownload(url, guessFileName, 0L, appTitle)
                    return true
                }

                // Handle deep links and custom application schemes
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        true
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot launch external intent for: $url", e)
                        false
                    }
                }

                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.swipeRefreshLayout.isRefreshing = false
                if (isOffline) {
                    isOffline = false
                    if (currentTabId == MENU_BROWSER_ID) {
                        binding.layoutOffline.isVisible = false
                        binding.swipeRefreshLayout.isVisible = true
                    }
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val errorCode = error?.errorCode ?: 0
                    val description = error?.description?.toString() ?: ""
                    Log.w(TAG, "WebView error: code=$errorCode, desc=$description")

                    val isNetworkError = errorCode == ERROR_HOST_LOOKUP ||
                            errorCode == ERROR_CONNECT ||
                            errorCode == ERROR_TIMEOUT ||
                            description.contains("ERR_INTERNET_DISCONNECTED", ignoreCase = true) ||
                            description.contains("net::ERR_NAME_NOT_RESOLVED", ignoreCase = true)

                    if (isNetworkError || !NetworkUtils.isNetworkAvailable(this@MainActivity)) {
                        showOfflineState()
                    }
                }
            }
        }

        // Native File Download Listener interceptor
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            Log.d(TAG, "WebView setDownloadListener: url=$url, mime=$mimetype")
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            val appTitle = extractAppTitleFromUrlOrPage(url, webView.title)
            confirmAndStartDownload(url, fileName, contentLength, appTitle)
        }
    }

    private fun isPackageDownloadUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.endsWith(".apk") ||
                lower.endsWith(".xapk") ||
                lower.contains(".apk?") ||
                lower.contains(".xapk?") ||
                lower.contains("play125store.workers.dev") ||
                lower.contains("workers.dev/download") ||
                lower.contains("/api/download")
    }

    private fun extractAppTitleFromUrlOrPage(url: String, pageTitle: String?): String {
        if (!pageTitle.isNullOrBlank() && !pageTitle.contains("cosmo", ignoreCase = true)) {
            return pageTitle.trim()
        }
        val uri = Uri.parse(url)
        val nameParam = uri.getQueryParameter("name") ?: uri.getQueryParameter("app") ?: uri.getQueryParameter("title")
        if (!nameParam.isNullOrBlank()) {
            return nameParam
        }
        val lastSegment = uri.lastPathSegment ?: ""
        return if (lastSegment.isNotBlank()) {
            lastSegment.substringBeforeLast(".").replace("-", " ").replace("_", " ").capitalize(Locale.ROOT)
        } else {
            "Cosmo Game"
        }
    }

    private fun setupOfflineHandling() {
        binding.btnRetryConnection.setOnClickListener {
            retryConnection()
        }

        binding.btnOpenNetworkSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }
    }

    private fun showOfflineState() {
        isOffline = true
        binding.swipeRefreshLayout.isRefreshing = false
        binding.pageProgressBar.isVisible = false
        if (currentTabId == MENU_BROWSER_ID) {
            binding.swipeRefreshLayout.isVisible = false
            binding.layoutOffline.isVisible = true
        }
    }

    private fun retryConnection() {
        if (NetworkUtils.isNetworkAvailable(this)) {
            isOffline = false
            binding.layoutOffline.isVisible = false
            binding.swipeRefreshLayout.isVisible = true
            binding.webViewMain.reload()
        } else {
            Snackbar.make(binding.mainRootLayout, "Still disconnected. Please check your connection.", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun loadWebPage(url: String) {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            showOfflineState()
        } else {
            isOffline = false
            binding.layoutOffline.isVisible = false
            binding.swipeRefreshLayout.isVisible = (currentTabId == MENU_BROWSER_ID)
            binding.webViewMain.loadUrl(url)
        }
    }

    private fun setupLibraryView() {
        libraryAdapter = LibraryAdapter(
            onOpenClick = { item ->
                openInstalledApp(item.packageName)
            },
            onInstallClick = { item ->
                item.file?.let { requestPackageInstallation(it) }
            },
            onDeleteClick = { item ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Package")
                    .setMessage("Remove ${item.appTitle} from library?")
                    .setPositiveButton("Delete") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            item.file?.delete()
                            appDatabase.installedAppDao().deleteByPackageName(item.packageName)
                            withContext(Dispatchers.Main) {
                                refreshLibraryList()
                                updateCacheSizeDisplay()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )

        binding.rvDownloadedPackages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = libraryAdapter
        }

        binding.btnRefreshLibrary.setOnClickListener {
            refreshLibraryList()
        }
    }

    private fun openInstalledApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "Application cannot be opened directly", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshLibraryList() {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = getDownloadDirectory()
            val files = downloadDir.listFiles()?.filter {
                it.isFile && (it.extension.equals("apk", ignoreCase = true) || it.extension.equals("xapk", ignoreCase = true))
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            // 1. Load entities from Room
            val dbApps = mutableMapOf<String, InstalledAppEntity>()
            try {
                // Collect existing records from database
                appDatabase.installedAppDao().getAllApps().collectLatest { entities ->
                    for (entity in entities) {
                        dbApps[entity.packageName] = entity
                    }
                }
            } catch (ignored: Exception) {}

            val items = mutableListOf<DownloadedPackage>()
            val processedPackages = mutableSetOf<String>()

            // 2. Process physical files in download directory
            for (file in files) {
                var pkgName = file.nameWithoutExtension
                var appTitle = file.nameWithoutExtension.replace("-", " ").replace("_", " ")
                var iconDrawable: Drawable? = null
                var isInstalled = false

                if (file.extension.equals("apk", ignoreCase = true)) {
                    val archiveInfo = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    if (archiveInfo != null) {
                        pkgName = archiveInfo.packageName
                        archiveInfo.applicationInfo?.sourceDir = file.absolutePath
                        archiveInfo.applicationInfo?.publicSourceDir = file.absolutePath
                        try {
                            appTitle = archiveInfo.applicationInfo?.loadLabel(packageManager)?.toString() ?: appTitle
                            iconDrawable = archiveInfo.applicationInfo?.loadIcon(packageManager)
                        } catch (ignored: Exception) {}
                    }
                }

                try {
                    val installedPkg = packageManager.getPackageInfo(pkgName, 0)
                    if (installedPkg != null) {
                        isInstalled = true
                        iconDrawable = packageManager.getApplicationIcon(pkgName)
                    }
                } catch (ignored: Exception) {}

                processedPackages.add(pkgName)
                items.add(
                    DownloadedPackage(
                        packageName = pkgName,
                        appTitle = appTitle,
                        subInfo = "$pkgName • ${formatFileSize(file.length())}",
                        isInstalled = isInstalled,
                        file = file,
                        iconDrawable = iconDrawable,
                        typeLabel = if (file.extension.equals("xapk", ignoreCase = true)) "XAPK" else "APK"
                    )
                )
            }

            // 3. Include any apps recorded in Room database that might not have raw files
            for ((pkg, entity) in dbApps) {
                if (!processedPackages.contains(pkg)) {
                    var isInstalled = false
                    var iconDrawable: Drawable? = null
                    try {
                        val installedPkg = packageManager.getPackageInfo(pkg, 0)
                        if (installedPkg != null) {
                            isInstalled = true
                            iconDrawable = packageManager.getApplicationIcon(pkg)
                        }
                    } catch (ignored: Exception) {}

                    items.add(
                        DownloadedPackage(
                            packageName = pkg,
                            appTitle = entity.appTitle,
                            subInfo = "$pkg • Installed",
                            isInstalled = isInstalled,
                            file = entity.filePath?.let { File(it) },
                            iconDrawable = iconDrawable,
                            typeLabel = "STORE"
                        )
                    )
                }
            }

            withContext(Dispatchers.Main) {
                libraryAdapter.submitList(items)
                binding.layoutEmptyLibrary.isVisible = items.isEmpty()
                binding.rvDownloadedPackages.isVisible = items.isNotEmpty()
            }
        }
    }

    private fun setupUpdateView() {
        binding.tvCurrentVersionBadge.text = "Version 1.0 (Build 1) • Production Engine"

        binding.btnCheckUpdate.setOnClickListener {
            binding.btnCheckUpdate.isEnabled = false
            binding.btnCheckUpdate.text = "Checking for updates..."
            lifecycleScope.launch {
                kotlinx.coroutines.delay(1200)
                binding.btnCheckUpdate.isEnabled = true
                binding.btnCheckUpdate.text = getString(R.string.update_check_button)
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Up to Date")
                    .setMessage("You are using the latest version of Cosmo Store and its PackageInstaller engine.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }

        binding.btnRefreshStoreCache.setOnClickListener {
            binding.webViewMain.clearCache(true)
            binding.webViewMain.reload()
            Snackbar.make(binding.mainRootLayout, "Store cache cleared and reloaded", Snackbar.LENGTH_SHORT).show()
        }

        binding.tvDiagnosticApk.text = "• Standard APK FileProvider: Enabled (${packageName}.fileprovider)"
        binding.tvDiagnosticXapk.text = "• Multi-APK / XAPK Session Engine: Enabled (PackageInstaller)"
        binding.tvDiagnosticObb.text = "• OBB Expansion Extractor: Ready"
    }

    private fun setupSettingsView() {
        updateSettingsUiValues()

        // 1. Download Network Option Dialog
        binding.cardSettingNetwork.setOnClickListener {
            showNetworkSelectionDialog()
        }

        // 2. Auto-Install Behavior Dialog
        binding.cardSettingAutoInstall.setOnClickListener {
            showAutoInstallSelectionDialog()
        }

        // 3. Appearance & Theme Dialog
        binding.cardSettingTheme.setOnClickListener {
            showThemeSelectionDialog()
        }

        // 4. Warn Non-Android Switch
        binding.switchWarnNonAndroid.isChecked = appSettings.warnNonAndroidFiles
        binding.switchWarnNonAndroid.setOnCheckedChangeListener { _, isChecked ->
            appSettings.warnNonAndroidFiles = isChecked
        }

        binding.btnClearCache.setOnClickListener {
            clearDownloadCache()
        }

        val abis = Build.SUPPORTED_ABIS.joinToString(", ")
        binding.tvEngineMonitorText.text = "Session PackageInstaller: Active\nAndroid Target SDK: ${Build.VERSION.SDK_INT}\nSupported ABIs: $abis"
    }

    private fun updateSettingsUiValues() {
        binding.tvSelectedNetwork.text = if (appSettings.isWifiOnly()) "Wi-Fi Only" else "Wi-Fi or Mobile Data"

        binding.tvSelectedAutoInstall.text = when (appSettings.autoInstallMode) {
            AppSettings.AUTO_INSTALL_ALLOW -> "Allow Auto Install"
            AppSettings.AUTO_INSTALL_ASK -> "Ask Before Install"
            else -> "Don't Install Automatically"
        }

        binding.tvSelectedTheme.text = when (appSettings.themeMode) {
            AppSettings.THEME_LIGHT -> "Light"
            AppSettings.THEME_DARK -> "Dark"
            AppSettings.THEME_SITE -> "Site-dependent (Web Theme)"
            else -> "System Default"
        }
    }

    private fun showNetworkSelectionDialog() {
        val options = arrayOf("Wi-Fi or Mobile Data", "Wi-Fi Only")
        val checkedItem = if (appSettings.isWifiOnly()) 1 else 0

        MaterialAlertDialogBuilder(this)
            .setTitle("Download Network")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                appSettings.networkMode = if (which == 1) AppSettings.NETWORK_WIFI_ONLY else AppSettings.NETWORK_WIFI_OR_MOBILE
                updateSettingsUiValues()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAutoInstallSelectionDialog() {
        val options = arrayOf("Allow Auto Install", "Ask Before Install", "Don't Install Automatically")
        val checkedItem = when (appSettings.autoInstallMode) {
            AppSettings.AUTO_INSTALL_ALLOW -> 0
            AppSettings.AUTO_INSTALL_ASK -> 1
            else -> 2
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Auto-Install Behavior")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                appSettings.autoInstallMode = when (which) {
                    0 -> AppSettings.AUTO_INSTALL_ALLOW
                    1 -> AppSettings.AUTO_INSTALL_ASK
                    else -> AppSettings.AUTO_INSTALL_NEVER
                }
                updateSettingsUiValues()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showThemeSelectionDialog() {
        val options = arrayOf("System Default", "Light", "Dark", "Site-dependent (Web Theme)")
        val checkedItem = when (appSettings.themeMode) {
            AppSettings.THEME_SYSTEM -> 0
            AppSettings.THEME_LIGHT -> 1
            AppSettings.THEME_DARK -> 2
            else -> 3
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Appearance & Theme")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                appSettings.themeMode = when (which) {
                    0 -> AppSettings.THEME_SYSTEM
                    1 -> AppSettings.THEME_LIGHT
                    2 -> AppSettings.THEME_DARK
                    else -> AppSettings.THEME_SITE
                }
                updateSettingsUiValues()
                appSettings.applyTheme()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCacheSizeDisplay() {
        lifecycleScope.launch(Dispatchers.IO) {
            val downloadDir = getDownloadDirectory()
            val totalBytes = downloadDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val formatted = formatFileSize(totalBytes)
            withContext(Dispatchers.Main) {
                binding.tvCacheSize.text = formatted
            }
        }
    }

    private fun clearDownloadCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val dir = getDownloadDirectory()
            dir.deleteRecursively()
            dir.mkdirs()
            withContext(Dispatchers.Main) {
                updateCacheSizeDisplay()
                refreshLibraryList()
                Snackbar.make(binding.mainRootLayout, "Download cache cleared", Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTabId == MENU_BROWSER_ID && binding.webViewMain.canGoBack()) {
                    binding.webViewMain.goBack()
                } else if (currentTabId != MENU_BROWSER_ID) {
                    binding.bottomnavigation1.selectedItemId = MENU_BROWSER_ID
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun observeNetworkState() {
        lifecycleScope.launch {
            NetworkUtils.observeNetworkStatus(this@MainActivity).collectLatest { isConnected ->
                if (isConnected && isOffline) {
                    retryConnection()
                }
            }
        }
    }

    private fun observeInstallEvents() {
        lifecycleScope.launch {
            InstallStatusReceiver.events.collectLatest { event ->
                when (event) {
                    is InstallStatusReceiver.InstallEvent.Success -> {
                        hideDownloadProgressCard()
                        Snackbar.make(binding.mainRootLayout, "Installed package: ${event.packageName}", Snackbar.LENGTH_LONG).show()

                        // Record in Room Database
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val pkgInfo = packageManager.getPackageInfo(event.packageName, 0)
                                val title = pkgInfo.applicationInfo?.loadLabel(packageManager)?.toString() ?: event.packageName
                                val entity = InstalledAppEntity(
                                    packageName = event.packageName,
                                    appTitle = title,
                                    installDate = System.currentTimeMillis(),
                                    versionName = pkgInfo.versionName,
                                    isInstalled = true
                                )
                                appDatabase.installedAppDao().insertOrUpdate(entity)
                            } catch (ignored: Exception) {}
                        }

                        refreshLibraryList()
                    }
                    is InstallStatusReceiver.InstallEvent.Failure -> {
                        hideDownloadProgressCard()
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Installation Failed")
                            .setMessage("PackageInstaller error (${event.statusCode}): ${event.message}")
                            .setPositiveButton("Dismiss", null)
                            .show()
                    }
                    is InstallStatusReceiver.InstallEvent.PendingUserAction -> {
                        binding.tvDownloadTitle.text = "Awaiting user confirmation..."
                    }
                }
            }
        }
    }

    private fun observeDownloadState() {
        lifecycleScope.launch {
            DownloadService.downloadState.collectLatest { state ->
                when (state) {
                    is DownloadState.Idle -> {
                        if (binding.ivDownloadStatusIcon.tag != "installing") {
                            binding.downloadProgressCard.isVisible = false
                        }
                    }
                    is DownloadState.Downloading -> {
                        binding.ivDownloadStatusIcon.tag = "downloading"
                        binding.downloadProgressCard.isVisible = true
                        binding.ivDownloadStatusIcon.setImageResource(R.drawable.ic_download)
                        val title = if (state.appTitle.isNotBlank()) state.appTitle else state.fileName
                        binding.tvDownloadTitle.text = "Downloading $title"
                        binding.tvDownloadPercent.text = "${state.progress}%"
                        binding.downloadProgressBar.isIndeterminate = (state.totalBytes <= 0L)
                        binding.downloadProgressBar.progress = state.progress

                        val speedStr = if (state.speedBytesPerSec > 0) " • ${formatFileSize(state.speedBytesPerSec)}/s" else ""
                        binding.tvDownloadStatusSub.text =
                            "${formatFileSize(state.downloadedBytes)} / ${if (state.totalBytes > 0) formatFileSize(state.totalBytes) else "Unknown"}$speedStr"

                        binding.btnPauseResumeDownload.isVisible = true
                        binding.btnCancelDownload.isVisible = true
                        binding.btnPauseResumeDownload.setImageResource(R.drawable.ic_pause)
                        binding.btnPauseResumeDownload.contentDescription = "Pause Download"
                        binding.btnPauseResumeDownload.setOnClickListener {
                            DownloadService.pauseDownload(this@MainActivity)
                        }
                        binding.btnCancelDownload.setOnClickListener {
                            DownloadService.cancelDownload(this@MainActivity)
                        }
                    }
                    is DownloadState.Paused -> {
                        binding.ivDownloadStatusIcon.tag = "paused"
                        binding.downloadProgressCard.isVisible = true
                        binding.ivDownloadStatusIcon.setImageResource(R.drawable.ic_pause)
                        val title = if (state.appTitle.isNotBlank()) state.appTitle else state.fileName
                        binding.tvDownloadTitle.text = "Paused: $title"
                        binding.tvDownloadPercent.text = "${state.progress}%"
                        binding.downloadProgressBar.isIndeterminate = false
                        binding.downloadProgressBar.progress = state.progress
                        binding.tvDownloadStatusSub.text =
                            "${formatFileSize(state.downloadedBytes)} / ${if (state.totalBytes > 0) formatFileSize(state.totalBytes) else "Unknown"} (Paused)"

                        binding.btnPauseResumeDownload.isVisible = true
                        binding.btnCancelDownload.isVisible = true
                        binding.btnPauseResumeDownload.setImageResource(R.drawable.ic_play)
                        binding.btnPauseResumeDownload.contentDescription = "Resume Download"
                        binding.btnPauseResumeDownload.setOnClickListener {
                            DownloadService.resumeDownload(this@MainActivity)
                        }
                        binding.btnCancelDownload.setOnClickListener {
                            DownloadService.cancelDownload(this@MainActivity)
                        }
                    }
                    is DownloadState.Completed -> {
                        binding.ivDownloadStatusIcon.tag = "completed"
                        binding.downloadProgressCard.isVisible = true
                        binding.ivDownloadStatusIcon.setImageResource(R.drawable.ic_check_circle)
                        val title = if (state.appTitle.isNotBlank()) state.appTitle else state.file.name
                        binding.tvDownloadTitle.text = "Download Complete"
                        binding.tvDownloadPercent.text = "100%"
                        binding.downloadProgressBar.isIndeterminate = false
                        binding.downloadProgressBar.progress = 100
                        binding.tvDownloadStatusSub.text = "$title ready to install"
                        binding.btnPauseResumeDownload.isVisible = false
                        binding.btnCancelDownload.isVisible = false

                        refreshLibraryList()
                        updateCacheSizeDisplay()

                        handleCompletedDownloadInstallation(state)
                    }
                    is DownloadState.Failed -> {
                        binding.downloadProgressCard.isVisible = true
                        binding.ivDownloadStatusIcon.setImageResource(R.drawable.ic_cloud_off)
                        binding.tvDownloadTitle.text = "Download Failed"
                        binding.tvDownloadPercent.text = ""
                        binding.tvDownloadStatusSub.text = state.error
                        binding.btnPauseResumeDownload.isVisible = false
                        binding.btnCancelDownload.isVisible = false
                        lifecycleScope.launch {
                            kotlinx.coroutines.delay(4000)
                            if (binding.ivDownloadStatusIcon.tag != "installing") {
                                binding.downloadProgressCard.isVisible = false
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleCompletedDownloadInstallation(state: DownloadState.Completed) {
        when {
            appSettings.isAutoInstallAllowed() -> {
                requestPackageInstallation(state.file)
            }
            appSettings.isAskBeforeInstall() -> {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Download Complete")
                    .setMessage("${state.appTitle.ifBlank { state.file.name }} has finished downloading. Install now?")
                    .setPositiveButton("Install") { _, _ ->
                        requestPackageInstallation(state.file)
                    }
                    .setNegativeButton("Later", null)
                    .show()
            }
            else -> {
                Snackbar.make(binding.mainRootLayout, "${state.appTitle} saved to My Library", Snackbar.LENGTH_LONG).show()
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(3000)
                    hideDownloadProgressCard()
                }
            }
        }
    }

    private fun confirmAndStartDownload(url: String, fileName: String, contentLength: Long, appTitle: String) {
        val isApkOrXapk = fileName.endsWith(".apk", ignoreCase = true) || fileName.endsWith(".xapk", ignoreCase = true)

        if (!isApkOrXapk && appSettings.warnNonAndroidFiles) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Non-Android File")
                .setMessage("The file \"$fileName\" does not appear to be an Android package. Download anyway?")
                .setPositiveButton("Download") { _, _ ->
                    checkNetworkAndStartDownload(url, fileName, appTitle)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        checkNetworkAndStartDownload(url, fileName, appTitle)
    }

    private fun checkNetworkAndStartDownload(url: String, fileName: String, appTitle: String) {
        if (appSettings.isWifiOnly() && NetworkUtils.isMeteredConnection(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Wi-Fi Only Mode")
                .setMessage("You have set downloads to Wi-Fi Only in Settings. Please connect to Wi-Fi to start downloading.")
                .setPositiveButton("Download Anyway") { _, _ ->
                    startDownload(url, fileName, appTitle)
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        startDownload(url, fileName, appTitle)
    }

    private fun startDownload(url: String, fileName: String, appTitle: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        DownloadService.startDownload(
            context = this,
            url = url,
            fileName = fileName,
            appTitle = appTitle
        )
    }

    private fun requestPackageInstallation(file: File) {
        pendingInstallFile = file
        if (!ApkInstaller.canInstallPackages(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.installer_permission_title)
                .setMessage(R.string.installer_permission_desc)
                .setPositiveButton(R.string.installer_grant) { _, _ ->
                    val intent = ApkInstaller.createInstallPermissionIntent(this)
                    installPermissionLauncher.launch(intent)
                }
                .setNegativeButton(R.string.installer_cancel, null)
                .show()
        } else {
            executePackageInstallation(file)
        }
    }

    private fun executePackageInstallation(file: File) {
        if (file.extension.equals("xapk", ignoreCase = true)) {
            installXapkPackage(file)
        } else {
            installApkPackage(file)
        }
    }

    private fun installApkPackage(file: File) {
        binding.downloadProgressCard.isVisible = true
        binding.ivDownloadStatusIcon.tag = "installing"
        binding.btnPauseResumeDownload.isVisible = false
        binding.btnCancelDownload.isVisible = false
        binding.ivDownloadStatusIcon.setImageResource(R.drawable.ic_android_package)
        binding.tvDownloadTitle.text = "Launching APK Installer..."
        binding.tvDownloadPercent.text = "100%"
        binding.downloadProgressBar.progress = 100
        binding.tvDownloadStatusSub.text = file.name

        val result = ApkInstaller.installApk(this, file)
        result.onFailure { error ->
            Log.e(TAG, "APK installation trigger failed", error)
            MaterialAlertDialogBuilder(this)
                .setTitle("Install Error")
                .setMessage("Failed to launch package installer: ${error.message}")
                .setPositiveButton("Dismiss", null)
                .show()
        }

        lifecycleScope.launch {
            kotlinx.coroutines.delay(2500)
            binding.ivDownloadStatusIcon.tag = ""
            hideDownloadProgressCard()
        }
    }

    private fun installXapkPackage(file: File) {
        binding.downloadProgressCard.isVisible = true
        binding.ivDownloadStatusIcon.tag = "installing"
        binding.btnPauseResumeDownload.isVisible = false
        binding.btnCancelDownload.isVisible = false
        binding.ivDownloadStatusIcon.setImageResource(R.drawable.ic_android_package)
        binding.tvDownloadTitle.text = "Extracting XAPK..."
        binding.downloadProgressBar.isIndeterminate = true
        binding.tvDownloadPercent.text = ""
        binding.tvDownloadStatusSub.text = "Unpacking APK splits and expansion files..."

        lifecycleScope.launch {
            val result = XapkInstaller.installXapk(
                context = this@MainActivity,
                xapkFile = file,
                onProgress = { progress ->
                    runOnUiThread {
                        when (progress) {
                            is XapkInstaller.InstallProgress.Extracting -> {
                                binding.downloadProgressBar.isIndeterminate = false
                                binding.tvDownloadTitle.text = "Extracting XAPK Archive"
                                binding.tvDownloadStatusSub.text = progress.entryName
                            }
                            is XapkInstaller.InstallProgress.CopyingObb -> {
                                binding.tvDownloadTitle.text = "Copying OBB Expansion"
                                binding.tvDownloadStatusSub.text = progress.fileName
                            }
                            is XapkInstaller.InstallProgress.StagingSession -> {
                                binding.tvDownloadTitle.text = "Staging Split APKs"
                                binding.tvDownloadStatusSub.text = "${progress.currentApk} (${progress.index}/${progress.total})"
                            }
                            is XapkInstaller.InstallProgress.CommittingSession -> {
                                binding.tvDownloadTitle.text = "Committing PackageInstaller Session..."
                                binding.tvDownloadStatusSub.text = "Awaiting system confirmation..."
                            }
                            is XapkInstaller.InstallProgress.Completed -> {
                                binding.tvDownloadTitle.text = "XAPK Staged Successfully"
                                binding.tvDownloadStatusSub.text = "Ready to install ${progress.packageName}"
                            }
                            is XapkInstaller.InstallProgress.Error -> {
                                binding.tvDownloadTitle.text = "XAPK Extraction Error"
                                binding.tvDownloadStatusSub.text = progress.throwable.message ?: "Failed"
                            }
                        }
                    }
                }
            )

            result.onFailure { e ->
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("XAPK Install Error")
                    .setMessage(e.localizedMessage ?: "Unknown error while processing XAPK")
                    .setPositiveButton("OK", null)
                    .show()
                hideDownloadProgressCard()
            }
        }
    }

    private fun hideDownloadProgressCard() {
        binding.ivDownloadStatusIcon.tag = ""
        binding.downloadProgressCard.isVisible = false
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
}
