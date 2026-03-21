package com.libremobileos.sidebar.ui.sidebar

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.rememberNavController
import com.android.settingslib.spa.framework.compose.localNavController
import com.android.settingslib.spa.framework.compose.rememberDrawablePainter
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.widget.preference.ListPreference
import com.android.settingslib.spa.widget.preference.ListPreferenceModel
import com.android.settingslib.spa.widget.preference.ListPreferenceOption
import com.android.settingslib.spa.widget.preference.MainSwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreference
import com.android.settingslib.spa.widget.preference.SwitchPreferenceModel
import com.android.settingslib.spa.widget.scaffold.SettingsScaffold
import com.android.settingslib.spa.widget.ui.Category
import com.libremobileos.sidebar.R
import com.libremobileos.sidebar.bean.SidebarAppInfo

@Composable
fun SidebarSettingsPage(
    viewModel: SidebarSettingsViewModel
) {
    val navController = rememberNavController()
    var mainChecked = rememberSaveable { mutableStateOf(viewModel.getSidebarEnabled()) }

    CompositionLocalProvider(navController.localNavController()) {
        SettingsScaffold(
            title = stringResource(R.string.sidebar_label)
        ) { paddingValues ->
            Column(
                modifier = Modifier.padding(paddingValues)
            ) {
                MainSwitchPreference(object : SwitchPreferenceModel {
                    override val title = stringResource(R.string.enable_sideline)
                    override val checked = { mainChecked.value }
                    override val changeable = { viewModel.isEnabled }
                    override val onCheckedChange: (Boolean) -> Unit = {
                        mainChecked.value = it
                        viewModel.setSidebarEnabled(it)
                    }
                })
                if (mainChecked.value) {
                    SidebarSettingSwitch(
                        title = stringResource(R.string.sidebar_predicted_apps),
                        summary = stringResource(R.string.sidebar_predicted_apps_summary),
                        isChecked = viewModel.getPredictedAppsEnabled(),
                        onCheckedChange = { viewModel.setPredictedAppsEnabled(it) }
                    )
                    SidebarSettingSwitch(
                        title = stringResource(R.string.smart_clipboard_label),
                        summary = stringResource(R.string.smart_clipboard_summary),
                        isChecked = viewModel.getSmartClipboardEnabled(),
                        onCheckedChange = { viewModel.setSmartClipboardEnabled(it) }
                    )
                    if (viewModel.getSmartClipboardEnabled()) {
                        SidebarExpirationPreference(viewModel)
                    }
                    SidebarAppList(viewModel)
                }
            }
        }
    }
}

@Composable
fun SidebarAppList(
    viewModel: SidebarSettingsViewModel
) {
    val sidebarApps by viewModel.appListFlow.collectAsState()
    Category(
        title = stringResource(R.string.sidebar_app_setting_label)
    ) {
        LazyColumn {
            items(sidebarApps) { appInfo ->
                SidebarAppListItem(
                    appInfo = appInfo,
                    onCheckedChange = { isChecked ->
                        if (isChecked) {
                            viewModel.addSidebarApp(appInfo)
                        } else {
                            viewModel.deleteSidebarApp(appInfo)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SidebarAppListItem(
    appInfo: SidebarAppInfo,
    onCheckedChange: (Boolean) -> Unit
) {
    var appChecked = rememberSaveable { mutableStateOf(appInfo.isSidebarApp) }
    SwitchPreference(
        model = object : SwitchPreferenceModel {
            override val title = appInfo.label
            override val icon = @Composable {
                Image(
                    painter = rememberDrawablePainter(appInfo.icon),
                    contentDescription = appInfo.label,
                    modifier = Modifier.size(SettingsDimension.appIconItemSize)
                )
            }
            override val checked = { appChecked.value }
            override val onCheckedChange: (Boolean) -> Unit = {
                appChecked.value = it
                onCheckedChange(it)
            }
        },
    )
}

@Composable
fun SidebarSettingSwitch(
    title: String,
    summary: String?,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    var myChecked = rememberSaveable { mutableStateOf(isChecked) }
    SwitchPreference(
        model = object : SwitchPreferenceModel {
            override val title = title
            override val summary = { summary ?: "" }
            override val checked = { myChecked.value }
            override val onCheckedChange: (Boolean) -> Unit = {
                myChecked.value = it
                onCheckedChange(it)
            }
        },
    )
}

@Composable
fun SidebarExpirationPreference(viewModel: SidebarSettingsViewModel) {
    val expiration = rememberSaveable { mutableIntStateOf(viewModel.getClipboardExpirationHours()) }
    val titleStr = stringResource(R.string.smart_clipboard_expiration_label)
    val opt1 = stringResource(R.string.smart_clipboard_expiration_1_hour)
    val opt24 = stringResource(R.string.smart_clipboard_expiration_1_day)
    val opt168 = stringResource(R.string.smart_clipboard_expiration_1_week)
    val opt0 = stringResource(R.string.smart_clipboard_expiration_forever)

    ListPreference(remember(titleStr, opt1, opt24, opt168, opt0) {
        object : ListPreferenceModel {
            override val title = titleStr
            override val options = listOf(
                ListPreferenceOption(id = 1, text = opt1),
                ListPreferenceOption(id = 24, text = opt24),
                ListPreferenceOption(id = 168, text = opt168),
                ListPreferenceOption(id = 0, text = opt0)
            )
            override val selectedId = expiration
            override val onIdSelected: (Int) -> Unit = {
                expiration.intValue = it
                viewModel.setClipboardExpirationHours(it)
            }
        }
    })
}
