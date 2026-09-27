package com.libremobileos.sidebar.ui.sidebar

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_PROFILE_AVAILABLE
import android.content.Intent.ACTION_PROFILE_UNAVAILABLE
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.libremobileos.sidebar.app.SidebarApplication
import com.libremobileos.sidebar.bean.SidebarAppInfo
import com.libremobileos.sidebar.room.DatabaseRepository
import com.libremobileos.sidebar.service.ServiceViewModel.Companion.KEY_SMART_CLIPBOARD
import com.libremobileos.sidebar.service.ServiceViewModel.Companion.KEY_SHOW_PREDICTED_APPS
import com.libremobileos.sidebar.service.ServiceViewModel.Companion.KEY_CLIPBOARD_EXPIRATION_HOURS
import com.libremobileos.sidebar.service.SidebarService
import com.libremobileos.sidebar.utils.Logger
import com.libremobileos.sidebar.utils.contains
import com.libremobileos.sidebar.utils.getSidebarFilteredUsers
import com.libremobileos.sidebar.utils.isResizeableActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Collections

class SidebarSettingsViewModel(private val application: Application) : AndroidViewModel(application) {
    private val logger = Logger("SidebarSettingsViewModel")
    private val repository = DatabaseRepository(application)
    private val allAppList = ArrayList<SidebarAppInfo>()
    val appListFlow: StateFlow<List<SidebarAppInfo>>
        get() = _appList.asStateFlow()
    private val _appList = MutableStateFlow<List<SidebarAppInfo>>(emptyList())
    private val appComparator = AppComparator()

    val sidebarEnabledFlow: StateFlow<Boolean>
        get() = _sidebarEnabled.asStateFlow()
    private val _sidebarEnabled = MutableStateFlow(false)
    val predictedAppsEnabledFlow: StateFlow<Boolean>
        get() = _predictedAppsEnabled.asStateFlow()
    private val _predictedAppsEnabled = MutableStateFlow(true)
    val smartClipboardEnabledFlow: StateFlow<Boolean>
        get() = _smartClipboardEnabled.asStateFlow()
    private val _smartClipboardEnabled = MutableStateFlow(false)
    val expirationMinutesFlow: StateFlow<Int>
        get() = _expirationMinutes.asStateFlow()
    private val _expirationMinutes = MutableStateFlow(0)

    val isEnabled = UserHandle.myUserId() == 0
    private val appContext = application.applicationContext
    private lateinit var launcherApps: LauncherApps
    private lateinit var userManager: UserManager
    private lateinit var sp: SharedPreferences

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        when (key) {
            SidebarService.SIDELINE -> _sidebarEnabled.value = getSidebarEnabled()
            KEY_SHOW_PREDICTED_APPS -> _predictedAppsEnabled.value = getPredictedAppsEnabled()
            KEY_SMART_CLIPBOARD -> _smartClipboardEnabled.value = getSmartClipboardEnabled()
            KEY_CLIPBOARD_EXPIRATION_HOURS -> _expirationMinutes.value = getClipboardExpirationHours()
        }
    }

    private val userProfileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            logger.d("userProfileReceiver received ${intent.action}")
            allAppList.clear()
            initAllAppList()
        }
    }

    init {
        if (isEnabled) {
            logger.d("init")
            launcherApps = application.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            userManager = application.getSystemService(Context.USER_SERVICE) as UserManager
            sp = appContext.getSharedPreferences(SidebarApplication.CONFIG, Context.MODE_PRIVATE)
            _sidebarEnabled.value = getSidebarEnabled()
            _predictedAppsEnabled.value = getPredictedAppsEnabled()
            _smartClipboardEnabled.value = getSmartClipboardEnabled()
            _expirationMinutes.value = getClipboardExpirationHours()
            sp.registerOnSharedPreferenceChangeListener(prefsListener)

            initAllAppList()
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
    }

    override fun onCleared() {
        logger.d("onCleared")
        if (!isEnabled) return
        runCatching { sp.unregisterOnSharedPreferenceChangeListener(prefsListener) }
        appContext.unregisterReceiver(userProfileReceiver)
    }

    fun getSidebarEnabled(): Boolean =
        isEnabled && sp.getBoolean(SidebarService.SIDELINE, false)

    fun setSidebarEnabled(enabled: Boolean) {
        sp.edit()
            .putBoolean(SidebarService.SIDELINE, enabled)
            .apply()
    }

    fun addSidebarApp(appInfo: SidebarAppInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insertSidebarApp(appInfo.packageName, appInfo.activityName, appInfo.userId)
        }
    }

    fun deleteSidebarApp(appInfo: SidebarAppInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteSidebarApp(appInfo.packageName, appInfo.activityName, appInfo.userId)
        }
    }

    fun getPredictedAppsEnabled(): Boolean =
        sp.getBoolean(KEY_SHOW_PREDICTED_APPS, true)

    fun setPredictedAppsEnabled(enabled: Boolean) {
        sp.edit()
            .putBoolean(KEY_SHOW_PREDICTED_APPS, enabled)
            .apply()
    }

    fun getSmartClipboardEnabled(): Boolean =
        sp.getBoolean(KEY_SMART_CLIPBOARD, false)

    fun setSmartClipboardEnabled(enabled: Boolean) {
        sp.edit()
            .putBoolean(KEY_SMART_CLIPBOARD, enabled)
            .apply()
    }

    fun getClipboardExpirationHours(): Int {
        return when (val v = sp.getInt(KEY_CLIPBOARD_EXPIRATION_HOURS, 0)) {
            1 -> 60
            24 -> 1440
            168 -> 10080
            else -> v
        }
    }

    fun setClipboardExpirationHours(minutes: Int) {
        sp.edit()
            .putInt(KEY_CLIPBOARD_EXPIRATION_HOURS, minutes)
            .apply()
    }

    fun clearClipboardHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.clearUnpinnedReturningPaths().forEach { path ->
                    runCatching { java.io.File(path).delete() }
                }
            }
        }
    }

    private fun initAllAppList() {
        viewModelScope.launch(Dispatchers.IO) {
            userManager.getSidebarFilteredUsers().forEach { userInfo ->
                logger.d("initAllAppList for user $userInfo")
                val list = launcherApps.getActivityList(null, userInfo.userHandle)
                val sidebarAppList = repository.getAllSidebarWithoutLiveData()

                list.forEach { info ->
                    val component = info.componentName
                    if (!application.isResizeableActivity(component)) {
                        logger.d("activity not resizeable, skipped $component")
                    } else {
                        allAppList.add(
                            SidebarAppInfo(
                                "${info.label}${userInfo.suffix}",
                                info.getBadgedIcon(0),
                                component.packageName,
                                component.className,
                                userInfo.userId,
                                sidebarAppList?.contains(
                                    info.componentName.packageName,
                                    info.componentName.className,
                                    userInfo.userId
                                ) ?: false
                            )
                        )
                    }
                }
            }

            Collections.sort(allAppList, appComparator)
            _appList.value = allAppList
            logger.d("emitted allAppList: $allAppList")
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                SidebarSettingsViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                )
            }
        }
    }

    private inner class AppComparator : Comparator<SidebarAppInfo> {
        override fun compare(p0: SidebarAppInfo, p1: SidebarAppInfo): Int {
            return when {
                p0.isSidebarApp && !p1.isSidebarApp -> -1
                p1.isSidebarApp && !p0.isSidebarApp -> 1
                else -> Collator.getInstance().compare(p0.label, p1.label)
            }
        }
    }
}
