package top.yogiczy.mytv.tv.ui.screens.settings.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Switch
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.utils.SP
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.material.SnackbarType
import top.yogiczy.mytv.tv.ui.screens.main.MainViewModel
import top.yogiczy.mytv.tv.ui.screens.settings.SettingsViewModel

/**
 * Android 10 (API 29) 起后台启动 Activity 受限，开机自启需悬浮窗权限才能豁免
 */
private val needOverlayPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

private fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

private fun overlayPermissionIntent(context: Context): Intent =
    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
    }

@Composable
fun SettingsCategoryApp(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = viewModel(),
    mainViewModel: MainViewModel = viewModel(),
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var overlayPermissionGranted by remember { mutableStateOf(hasOverlayPermission(context)) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionGranted = hasOverlayPermission(context)
        if (overlayPermissionGranted) {
            settingsViewModel.appBootLaunch = true
            Snackbar.show("已开启开机自启")
        } else {
            Snackbar.show(
                "未授予悬浮窗权限，开机自启可能无法生效",
                type = SnackbarType.ERROR,
            )
        }
    }

    SettingsContentList(modifier) {
        item {
            SettingsListItem(
                modifier = Modifier.focusRequester(it),
                headlineContent = "开机自启",
                supportingContent = when {
                    !needOverlayPermission -> "请确保当前设备支持该功能"
                    overlayPermissionGranted -> "已授予悬浮窗权限"
                    else -> "需授予悬浮窗权限，否则部分设备无法生效"
                },
                trailingContent = {
                    Switch(settingsViewModel.appBootLaunch, null)
                },
                onSelected = {
                    if (settingsViewModel.appBootLaunch) {
                        settingsViewModel.appBootLaunch = false
                    } else if (!needOverlayPermission || hasOverlayPermission(context)) {
                        overlayPermissionGranted = true
                        settingsViewModel.appBootLaunch = true
                    } else {
                        try {
                            overlayPermissionLauncher.launch(overlayPermissionIntent(context))
                        } catch (ex: Exception) {
                            Snackbar.show(
                                "无法打开悬浮窗权限设置，请在系统设置中手动开启自启动",
                                type = SnackbarType.ERROR,
                            )
                        }
                    }
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "清除缓存",
                supportingContent = "包括图片、节目单、直播源等缓存",
                onSelected = {
                    settingsViewModel.clearCache(context) {
                        mainViewModel.init()
                    }
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "恢复初始化",
                onSelected = {
                    coroutineScope.launch {
                        SP.clear()
                        Snackbar.show("已恢复初始化")
                    }
                },
            )
        }
    }
}
