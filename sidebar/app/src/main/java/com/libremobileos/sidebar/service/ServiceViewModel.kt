package com.libremobileos.sidebar.service

import android.app.Application
import android.app.prediction.AppPredictionContext
import android.app.prediction.AppPredictionManager
import android.app.prediction.AppPredictor
import android.app.prediction.AppTarget
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_PROFILE_AVAILABLE
import android.content.Intent.ACTION_PROFILE_UNAVAILABLE
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerExecutor
import android.os.UserHandle
import android.os.UserManager
import android.view.View
import android.webkit.MimeTypeMap
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.AppInfo
import com.libremobileos.sidebar.room.DatabaseRepository
import com.libremobileos.sidebar.room.SmartClipboardEntity
import com.libremobileos.sidebar.room.SidebarAppsEntity
import com.libremobileos.sidebar.ui.sidebar.SidebarSettingsActivity
import com.libremobileos.sidebar.utils.Logger
import com.libremobileos.sidebar.utils.contains
import com.libremobileos.sidebar.utils.getBadgedIcon
import com.libremobileos.sidebar.utils.getInfo
import com.libremobileos.sidebar.utils.isResizeableActivity
import com.libremobileos.sidebar.utils.isSidebarUserAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * @author KindBrave
 * @since 2023/8/25
 */
class ServiceViewModel(private val application: Application): AndroidViewModel(application) {
    private val logger = Logger("ServiceViewModel")

    private val repository = DatabaseRepository(application)

    val sidebarAppListFlow: StateFlow<List<AppInfo>>
        get() = _sidebarAppList.asStateFlow()
    private val _sidebarAppList = MutableStateFlow<List<AppInfo>>(emptyList())
    val smartClipboardItemsFlow: StateFlow<List<SmartClipboardEntity>>
        get() = _smartClipboardItems.asStateFlow()
    private val _smartClipboardItems = MutableStateFlow<List<SmartClipboardEntity>>(emptyList())
    val smartClipboardEnabledFlow: StateFlow<Boolean>
        get() = _smartClipboardEnabled.asStateFlow()
    private val _smartClipboardEnabled = MutableStateFlow(false)

    private val predictedAppList = MutableStateFlow<List<AppInfo>>(emptyList())

    private val appContext = application.applicationContext
    private val launcherApps = application.getSystemService(Context.LAUNCHER_APPS_SERVICE)!! as LauncherApps
    private val appPredictionManager = application.getSystemService(AppPredictionManager::class.java)
    private val clipboardManager = application.getSystemService(ClipboardManager::class.java)!!
    private val userManager = application.getSystemService(UserManager::class.java)!!
    private val sharedPrefs = appContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
    private val smartClipboardDir by lazy {
        File(appContext.filesDir, SMART_CLIPBOARD_DIRECTORY).apply { mkdirs() }
    }

    private var appPredictor: AppPredictor? = null
    private val handlerExecutor = HandlerExecutor(Handler())
    private var callbacksRegistered = false
    private var primaryClipListenerRegistered = false
    private var ignoreNextPrimaryClipChange = false

    private var lastClipboardEventTime = 0L
    private val CLIPBOARD_DEBOUNCE_MS = 400L
    private val MAX_CLIPBOARD_FILE_SIZE = 100L * 1024 * 1024

    private var showPredictedApps = sharedPrefs.getBoolean(KEY_SHOW_PREDICTED_APPS, true)
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                registerAppPredictionCallback()
            } else {
                unregisterAppPredictionCallback()
                predictedAppList.value = emptyList()
            }
        }
    private var smartClipboardEnabled = sharedPrefs.getBoolean(KEY_SMART_CLIPBOARD, false)
        set(value) {
            if (field == value) return
            field = value
            _smartClipboardEnabled.value = value
            if (!callbacksRegistered) return
            if (value) {
                registerPrimaryClipChangedListener()
            } else {
                unregisterPrimaryClipChangedListener()
            }
        }

    val allAppActivity = AppInfo(
        "",
        AppCompatResources.getDrawable(appContext, R.drawable.ic_all)!!,
        ALL_APP_PACKAGE,
        ALL_APP_ACTIVITY,
        0
    )

    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            logger.d("onPackageRemoved: $packageName")
            _sidebarAppList.value.getInfo(packageName, user)?.let {
                repository.deleteSidebarApp(it.packageName, it.activityName, it.userId)
            }
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {

        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            logger.d("onPackageChanged: $packageName")
            try {
                val appInfo = application.packageManager.getApplicationInfo(packageName, 0)
                if (!appInfo.enabled) {
                    onPackageRemoved(packageName, user)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                onPackageRemoved(packageName, user)
            } catch (e: Exception) {
                logger.e("Error checking package status", e)
            }
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean
        ) {
            logger.d("onPackagesAvailable: ${packageNames.contentToString()}, $user")
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>?,
            user: UserHandle?,
            replacing: Boolean
        ) {
            logger.d("onPackagesUnavailable: ${packageNames.contentToString()}, $user")
        }
    }

    private val appPredictionCallback = object : AppPredictor.Callback {
        override fun onTargetsAvailable(targets: List<AppTarget>) {
            if (!showPredictedApps) return
            logger.d("appPredictionCallback targets: ${targets.size}")
            predictedAppList.value = targets
                .take(MAX_PREDICTED_APPS)
                .mapNotNull { target ->
                    runCatching {
                        val info = application.packageManager.getApplicationInfo(target.packageName, PackageManager.GET_ACTIVITIES)
                        val launchIntent = application.packageManager.getLaunchIntentForPackage(target.packageName)
                        val component = launchIntent!!.component!!
                        val userId = target.user.identifier
                        if (!application.isResizeableActivity(component)) {
                            logger.d("appPredictionCallback: activity is not resizeable, skipped $target")
                            null
                        } else {
                            AppInfo(
                                info.loadLabel(application.packageManager).toString(),
                                application.getBadgedIcon(info, target.user),
                                info.packageName,
                                component.className,
                                userId
                            )
                        }
                    }.onFailure { e ->
                        logger.e("failed to add $target: ", e)
                    }.getOrNull()
                }
        }
    }

    private val userProfileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val user: UserHandle = intent.getParcelableExtra(Intent.EXTRA_USER) ?: return
            val userId = user.identifier
            logger.d("userProfileReceiver received ${intent.action} $user")
            when (intent.action) {
                ACTION_PROFILE_AVAILABLE -> {
                    val sidebarApps = repository.getAllSidebarWithoutLiveData() ?: return
                    val newApps = sidebarApps
                        .filter { it.userId == userId }
                        .mapNotNull { entity ->
                            runCatching { entity.toAppInfo() }
                                .onFailure { e ->
                                    logger.e("failed to add entity $entity: $e" )
                                }
                                .getOrNull()
                        }
                    logger.d("userProfileReceiver added apps: $newApps")
                    // add them at the top
                    _sidebarAppList.value = newApps + _sidebarAppList.value
                }
                ACTION_PROFILE_UNAVAILABLE -> {
                    _sidebarAppList.value = _sidebarAppList.value
                        .filter { it.userId != userId }
                }
            }
        }
    }

    private val primaryClipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!smartClipboardEnabled) return@OnPrimaryClipChangedListener


        val now = System.currentTimeMillis()
        if (now - lastClipboardEventTime < CLIPBOARD_DEBOUNCE_MS) return@OnPrimaryClipChangedListener
        lastClipboardEventTime = now

        if (ignoreNextPrimaryClipChange) {
            ignoreNextPrimaryClipChange = false
            return@OnPrimaryClipChangedListener
        }
        val primaryClip = clipboardManager.primaryClip ?: return@OnPrimaryClipChangedListener
        viewModelScope.launch(Dispatchers.IO) {
            try {
                storePrimaryClip(primaryClip)
            } catch (e: IllegalArgumentException) {
                if (e.message == "FILE_TOO_LARGE") {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(appContext, R.string.smart_clipboard_file_too_large, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private val sharedPrefsListener = object : OnSharedPreferenceChangeListener {
        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            when (key) {
                KEY_SHOW_PREDICTED_APPS -> {
                    showPredictedApps = sharedPrefs.getBoolean(KEY_SHOW_PREDICTED_APPS, true)
                }
                KEY_SMART_CLIPBOARD -> {
                    smartClipboardEnabled = sharedPrefs.getBoolean(KEY_SMART_CLIPBOARD, false)
                    android.provider.Settings.System.putInt(
                        application.contentResolver,
                        "sidebar_smart_clipboard",
                        if (smartClipboardEnabled) 1 else 0
                    )
                }
            }
        }
    }

    companion object {
        private const val ALL_APP_PACKAGE = "com.libremobileos.sidebar"
        private const val ALL_APP_ACTIVITY = "com.libremobileos.sidebar.ui.all_app.AllAppActivity"
        private const val MAX_PREDICTED_APPS = 6
        private const val MAX_SMART_CLIPBOARD_ITEMS = 12
        private const val SMART_CLIPBOARD_DIRECTORY = "smart_clipboard"
        private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val KEY_SHOW_PREDICTED_APPS = "sidebar_show_predicted_apps"
        const val KEY_SMART_CLIPBOARD = "sidebar_smart_clipboard"
        const val KEY_CLIPBOARD_EXPIRATION_HOURS = "sidebar_clipboard_expiration_hours"
    }

    init {
        _smartClipboardEnabled.value = smartClipboardEnabled
        android.provider.Settings.System.putInt(
            application.contentResolver,
            "sidebar_smart_clipboard",
            if (smartClipboardEnabled) 1 else 0
        )
        logger.d("init")
    }

    fun registerCallbacks() {
        if (callbacksRegistered) return
        logger.d("registerCallbacks")
        initSidebarAppList()
        initSmartClipboardHistory()
        launcherApps.registerCallback(launcherAppsCallback)
        if (showPredictedApps) registerAppPredictionCallback()
        if (smartClipboardEnabled) registerPrimaryClipChangedListener()
        sharedPrefs.registerOnSharedPreferenceChangeListener(sharedPrefsListener)
        registerUserProfileReceiver()
        callbacksRegistered = true
    }

    fun unregisterCallbacks() {
        if (!callbacksRegistered) return
        logger.d("unregisterCallbacks")
        launcherApps.unregisterCallback(launcherAppsCallback)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(sharedPrefsListener)
        if (showPredictedApps) unregisterAppPredictionCallback()
        unregisterPrimaryClipChangedListener()
        appContext.unregisterReceiver(userProfileReceiver)
        viewModelScope.coroutineContext.cancelChildren()
        callbacksRegistered = false
    }

    fun destroy() {
        logger.d("destroy")
        runCatching { viewModelScope.cancel() }
    }

    fun openSidebarSettings() {
        appContext.startActivity(
            Intent(appContext, SidebarSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun copySmartClipboardItem(item: SmartClipboardEntity) {
        val clipData = when (item.type) {
            SmartClipboardEntity.TYPE_TEXT -> {
                val text = item.text ?: return
                ClipData.newPlainText(text.take(32), text)
            }
            SmartClipboardEntity.TYPE_IMAGE, SmartClipboardEntity.TYPE_FILE -> {
                val uri = item.getShareableUri() ?: return
                ClipData.newUri(appContext.contentResolver, appContext.getString(R.string.smart_clipboard_label), uri)
            }
            else -> return
        }
        runCatching {
            ignoreNextPrimaryClipChange = true
            clipboardManager.setPrimaryClip(clipData)
        }.onFailure { e ->
            ignoreNextPrimaryClipChange = false
            logger.e("failed to copy smart clipboard item", e)
        }
    }

    fun addClipDataToSmartClipboard(
        clipData: ClipData?,
        enableIfNeeded: Boolean = false,
        showToast: Boolean = false
    ) {
        if (clipData == null) {
            if (showToast) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(appContext, R.string.smart_clipboard_unsupported, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        if (enableIfNeeded && !smartClipboardEnabled) {
            sharedPrefs.edit().putBoolean(KEY_SMART_CLIPBOARD, true).apply()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(appContext, "Smart Clipboard enabled", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        if (!smartClipboardEnabled) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val savedCount = storePrimaryClip(clipData)
                if (showToast) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (savedCount > 0) {
                            val msgBase = appContext.getString(R.string.smart_clipboard_saved)
                            val msg = if (savedCount > 1) "$msgBase ($savedCount)" else msgBase
                            android.widget.Toast.makeText(appContext, msg, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(appContext, R.string.smart_clipboard_unsupported, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                if (e.message == "FILE_TOO_LARGE") {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(appContext, R.string.smart_clipboard_file_too_large, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun supportsSmartClipboardContent(description: ClipDescription?): Boolean {
        if (description == null) return false
        return description.hasMimeType("image/*") ||
            description.hasMimeType("text/*") ||
            description.hasMimeType("application/*") ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) ||
            description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) ||
            description.hasMimeType("*/*")
    }

    fun startSmartClipboardDrag(view: View, item: SmartClipboardEntity): Boolean {
        val clipData = when (item.type) {
            SmartClipboardEntity.TYPE_TEXT -> {
                val text = item.text ?: return false
                ClipData.newPlainText(text.take(32), text)
            }
            SmartClipboardEntity.TYPE_IMAGE, SmartClipboardEntity.TYPE_FILE -> {
                val uri = item.getShareableUri() ?: return false
                ClipData.newUri(
                    appContext.contentResolver,
                    appContext.getString(R.string.smart_clipboard_label),
                    uri
                )
            }
            else -> return false
        }
        val flags = if (item.type == SmartClipboardEntity.TYPE_IMAGE || item.type == SmartClipboardEntity.TYPE_FILE) {
            View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
        } else {
            View.DRAG_FLAG_GLOBAL
        }
        return view.startDragAndDrop(
            clipData,
            View.DragShadowBuilder(view),
            null,
            flags
        )
    }

    fun shareSmartClipboardItem(item: SmartClipboardEntity) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            when (item.type) {
                SmartClipboardEntity.TYPE_TEXT -> {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.text ?: return)
                }
                SmartClipboardEntity.TYPE_IMAGE, SmartClipboardEntity.TYPE_FILE -> {
                    val uri = item.getShareableUri() ?: return
                    type = item.mimeType ?: "image/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(
                        appContext.contentResolver,
                        appContext.getString(R.string.smart_clipboard_label),
                        uri
                    )
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                else -> return
            }
        }
        appContext.startActivity(
            Intent.createChooser(shareIntent, appContext.getString(R.string.smart_clipboard_share)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun deleteSmartClipboardItem(item: SmartClipboardEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSmartClipboardItem(item.id)
            item.imagePath?.let(::deleteCachedImage)
        }
    }

    fun toggleSmartClipboardItemPinned(item: SmartClipboardEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setSmartClipboardItemPinned(item.id, !item.isPinned)
        }
    }

    private fun registerAppPredictionCallback() {
        if (appPredictionManager == null) {
            logger.e("appPredictionManager is null!")
            return
        }
        appPredictor = appPredictionManager.createAppPredictionSession(
            AppPredictionContext.Builder(appContext)
                .setUiSurface("hotseat")
                .setPredictedTargetCount(MAX_PREDICTED_APPS)
                .build()
        ).apply {
            registerPredictionUpdates(handlerExecutor, appPredictionCallback)
            requestPredictionUpdate()
        }
    }

    private fun unregisterAppPredictionCallback() {
        appPredictor?.unregisterPredictionUpdates(appPredictionCallback)
    }

    private fun registerUserProfileReceiver() {
        appContext.registerReceiverAsUser(
            userProfileReceiver,
            UserHandle.CURRENT,
            IntentFilter().apply {
                addAction(ACTION_PROFILE_AVAILABLE)
                addAction(ACTION_PROFILE_UNAVAILABLE)
            },
            null,
            null
        )
    }

    private fun initSidebarAppList() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getAllSidebarAppsByFlow()
                .combine(predictedAppList) { sidebarApps, predictedApps ->
                    mutableListOf<AppInfo>().apply {
                        logger.d("initSidebarAppList: sidebarApps=$sidebarApps predictedApps=$predictedApps")
                        // first add the pinned apps
                        sidebarApps?.forEach { entity ->
                            if (!userManager.isSidebarUserAllowed(entity.userId)) {
                                logger.w("initSidebarAppList: userid not allowed: $entity")
                                return@forEach
                            }
                            runCatching {
                                add(entity.toAppInfo())
                            }.onFailure { e ->
                                logger.w("initSidebarAppList: removing $entity: $e")
                                repository.deleteSidebarApp(entity.packageName, entity.activityName, entity.userId)
                            }
                        }
                        // then the predicted apps
                        addAll(
                            predictedApps.filter { sidebarApps?.contains(it)?.not() ?: true }
                        )
                    }
                }
                .collect { sidebarAppList ->
                    logger.d("initSidebarAppList: combinedList=$sidebarAppList")
                    _sidebarAppList.value = sidebarAppList.toList()
                }
        }
    }

    private fun initSmartClipboardHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val expirationHours = sharedPrefs.getInt(KEY_CLIPBOARD_EXPIRATION_HOURS, 0)
            if (expirationHours > 0) {
                val expirationTimestamp = System.currentTimeMillis() - (expirationHours * 60L * 60L * 1000L)
                repository.deleteExpiredSmartClipboardItems(expirationTimestamp)
            }
            repository.getRecentSmartClipboardItemsByFlow(MAX_SMART_CLIPBOARD_ITEMS)
                .combine(_smartClipboardEnabled) { items, enabled ->
                    if (enabled) items else emptyList()
                }
                .collect { items ->
                    _smartClipboardItems.value = items
                }
        }
    }

    private fun registerPrimaryClipChangedListener() {
        if (primaryClipListenerRegistered) return
        clipboardManager.addPrimaryClipChangedListener(primaryClipChangedListener)
        primaryClipListenerRegistered = true
        clipboardManager.primaryClip?.let { clipData ->
            viewModelScope.launch(Dispatchers.IO) {
                try { storePrimaryClip(clipData) } catch (e: Exception) { /* ignore here */ }
            }
        }
    }

    private fun unregisterPrimaryClipChangedListener() {
        if (!primaryClipListenerRegistered) return
        clipboardManager.removePrimaryClipChangedListener(primaryClipChangedListener)
        primaryClipListenerRegistered = false
    }

    @Throws(IllegalArgumentException::class)
    private fun storePrimaryClip(clipData: ClipData): Int {
        val now = System.currentTimeMillis()
        val contentUris = extractContentUris(clipData)
        var savedCount = 0
        
        if (contentUris.isNotEmpty()) {
            for (uri in contentUris) {
                val persistedContent = persistContent(uri, clipData) ?: continue
                val entity = SmartClipboardEntity(
                    type = persistedContent.type,
                    text = persistedContent.fileName,
                    imagePath = persistedContent.path,
                    mimeType = persistedContent.mimeType,
                    contentHash = persistedContent.contentHash,
                    createdAt = now
                )
                saveSmartClipboardItem(entity) {
                    deleteCachedImage(persistedContent.path)
                }
                savedCount++
            }
            if (savedCount > 0) return savedCount
        }

        val text = buildClipboardText(clipData)?.takeIf { it.isNotBlank() } ?: return 0
        saveSmartClipboardItem(
            SmartClipboardEntity(
                type = SmartClipboardEntity.TYPE_TEXT,
                text = text,
                contentHash = digest(text.toByteArray()),
                createdAt = now
            )
        )
        return 1
    }

    private fun saveSmartClipboardItem(
        item: SmartClipboardEntity,
        onDuplicate: (() -> Unit)? = null
    ) {
        val latest = repository.getLatestSmartClipboardItem()
        val recentHashes = repository.getRecentHashes(5)

        if (recentHashes.contains(item.contentHash)) {
            onDuplicate?.invoke()
            return
        }
        repository.insertSmartClipboardItem(item)
        repository.trimSmartClipboardHistory(MAX_SMART_CLIPBOARD_ITEMS)
        trimUnusedCachedImages()
    }

    private fun buildClipboardText(clipData: ClipData): String? {
        val values = mutableListOf<String>()
        for (index in 0 until clipData.itemCount) {
            val value = clipData.getItemAt(index).coerceToText(appContext)?.toString()
                ?.takeIf { it.isNotBlank() } ?: continue
            values.add(value)
        }
        return values.joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun extractContentUris(clipData: ClipData): List<Uri> {
        val uris = mutableListOf<Uri>()
        for (index in 0 until clipData.itemCount) {
            val uri = clipData.getItemAt(index).uri ?: continue
            if (uri.scheme == "content" || uri.scheme == "file") {
                uris.add(uri)
            }
        }
        return uris
    }

    private fun persistContent(uri: Uri, clipData: ClipData): PersistedContent? {
        var mimeType = appContext.contentResolver.getType(uri)
        if (mimeType == null) {
            clipData.description?.let { desc ->
                if (desc.mimeTypeCount > 0) {
                    mimeType = desc.getMimeType(0)
                }
            }
        }
        mimeType = mimeType ?: "*/*"

        if (mimeType == "*/*") {
            logger.w("unknown mime type, skipping unsafe content")
            return null
        }

        val isAllowedMime =
            mimeType.startsWith("image/") ||
            mimeType.startsWith("text/") ||
            mimeType.startsWith("application/")

        if (!isAllowedMime) {
            logger.w("unsupported mime type: $mimeType")
            return null
        }

        val isImage = mimeType.startsWith("image/")
        val type = if (isImage) SmartClipboardEntity.TYPE_IMAGE else SmartClipboardEntity.TYPE_FILE

        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?.takeIf { it.isNotBlank() } ?: if (isImage) "png" else "bin"

        val uuid = java.util.UUID.randomUUID().toString().take(8)

        var fileName: String? = null
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
            }
        }

        val file = File(smartClipboardDir, "clip_${uuid}_${System.currentTimeMillis()}.$extension")
        val digest = MessageDigest.getInstance("SHA-256")
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break

                        totalBytes += count
                        if (totalBytes > MAX_CLIPBOARD_FILE_SIZE) {
                            logger.w("clipboard too large, skipping")
                            throw IllegalArgumentException("FILE_TOO_LARGE")
                        }

                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            } ?: return null
            return PersistedContent(
                type = type,
                fileName = fileName,
                path = file.absolutePath,
                mimeType = mimeType,
                contentHash = hexDigest(digest.digest())
            )
        }.onFailure { e ->
            logger.e("failed to persist clipboard content", e)
            deleteCachedImage(file.absolutePath)
            if (e is IllegalArgumentException && e.message == "FILE_TOO_LARGE") {
                throw e
            }
        }
        return null
    }

    private fun trimUnusedCachedImages() {
        val activeImagePaths = repository.getRecentSmartClipboardItems(Int.MAX_VALUE)
            .mapNotNull { it.imagePath }
            .toSet()
        smartClipboardDir.listFiles()?.forEach { file ->
            if (file.absolutePath !in activeImagePaths) {
                file.delete()
            }
        }
    }

    private fun deleteCachedImage(path: String) {
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }.onFailure { e ->
            logger.e("failed to delete cached smart clipboard image", e)
        }
    }

    private fun SmartClipboardEntity.getShareableUri(): Uri? {
        val imagePath = imagePath ?: return null
        val file = File(imagePath)
        if (!file.exists()) {
            logger.w("smart clipboard image missing: $imagePath")
            return null
        }
        return FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}$FILE_PROVIDER_SUFFIX",
            file
        )
    }

    private fun digest(bytes: ByteArray): String {
        return hexDigest(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun hexDigest(bytes: ByteArray): String {
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun SidebarAppsEntity.toAppInfo(): AppInfo {
        if (!application.isResizeableActivity(packageName, activityName)) {
            throw Exception("activity is not resizeable")
        }
        val info = application.packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_ACTIVITIES
        )
        if (!info.enabled) {
            throw Exception("package is disabled.")
        }
        return AppInfo(
            info.loadLabel(application.packageManager).toString(),
            application.getBadgedIcon(info, userId),
            packageName,
            activityName,
            userId
        )
    }

    private data class PersistedContent(
        val type: String,
        val fileName: String?,
        val path: String,
        val mimeType: String,
        val contentHash: String
    )
}

