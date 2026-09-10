package top.yogiczy.mytv.tv.ui.screens.settings.components

import android.content.Context
import android.content.pm.PackageInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.tv.ui.material.LocalPopupManager
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.material.SnackbarType
import top.yogiczy.mytv.tv.ui.screens.components.Qrcode
import top.yogiczy.mytv.tv.ui.screens.guide.GuideScreen
import top.yogiczy.mytv.tv.ui.screens.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screens.update.UpdateViewModel

@Composable
fun SettingsCategoryAbout(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = viewModel(),
    updateViewModel: UpdateViewModel = viewModel(),
    packageInfo: PackageInfo = rememberPackageInfo(),
) {
    val coroutineScope = rememberCoroutineScope()

    SettingsContentList(modifier) {
        item {
            SettingsListItem(
                modifier = Modifier.focusRequester(it),
                headlineContent = "应用名称",
                trailingContent = Constants.APP_TITLE,
            )
        }

        item {
            SettingsListItem(
                headlineContent = "应用版本",
                trailingContent = packageInfo.versionName ?: "",
            )
        }

        item {
            val popupManager = LocalPopupManager.current
            val focusRequester = remember { FocusRequester() }

            SettingsListItem(
                modifier = Modifier.focusRequester(focusRequester),
                headlineContent = "应用更新",
                supportingContent = "最新版本：v${updateViewModel.latestRelease.version}",
                trailingContent = if (updateViewModel.isUpdateAvailable) "发现新版本" else "无更新",
                onSelected = {
                    popupManager.push(focusRequester, true)
                    updateViewModel.visible = true
                },
            )
        }

        item {
            val list = mapOf(
                "stable" to "稳定版",
                "beta" to "测试版",
            )

            SettingsListItem(
                headlineContent = "更新通道",
                supportingContent = "切换后立即检查远端版本",
                trailingContent = list[settingsViewModel.updateChannel] ?: "",
                onSelected = {
                    val newChannel = list.keys.first { it != settingsViewModel.updateChannel }
                    settingsViewModel.updateChannel = newChannel

                    coroutineScope.launch {
                        Snackbar.show(
                            "正在检查${list[newChannel]}版本...",
                            leadingLoading = true,
                            duration = 5_000,
                            id = "checkUpdate",
                        )

                        val success = updateViewModel.checkUpdate(
                            currentVersion = packageInfo.versionName ?: "0.0.0",
                            channel = newChannel,
                            force = true,
                        )

                        when {
                            !success -> Snackbar.show(
                                "检查更新失败，请稍后重试",
                                type = SnackbarType.ERROR,
                                id = "checkUpdate",
                            )

                            updateViewModel.isUpdateAvailable -> Snackbar.show(
                                "发现新版本：v${updateViewModel.latestRelease.version}",
                                id = "checkUpdate",
                            )

                            else -> Snackbar.show(
                                "当前已是最新版本",
                                id = "checkUpdate",
                            )
                        }
                    }
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "更新强提醒",
                supportingContent = if (settingsViewModel.updateForceRemind) "检测到新版本时会全屏提醒"
                else "检测到新版本时仅消息提示",
                trailingContent = {
                    Switch(settingsViewModel.updateForceRemind, null)
                },
                onSelected = {
                    settingsViewModel.updateForceRemind = !settingsViewModel.updateForceRemind
                },
            )
        }

        item {
            val popupManager = LocalPopupManager.current
            val focusRequester = remember { FocusRequester() }
            var showDialog by remember { mutableStateOf(false) }

            SettingsListItem(
                modifier = Modifier.focusRequester(focusRequester),
                headlineContent = "代码仓库",
                trailingContent = Constants.APP_REPO,
                trailingIcon = Icons.AutoMirrored.Default.OpenInNew,
                onSelected = {
                    popupManager.push(focusRequester, true)
                    showDialog = true
                },
            )

            SimplePopup(
                visibleProvider = { showDialog },
                onDismissRequest = { showDialog = false },
            ) {
                Box(modifier = modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Qrcode(
                            modifier = Modifier
                                .padding(bottom = 10.dp)
                                .width(200.dp)
                                .height(200.dp),
                            textProvider = { Constants.APP_REPO },
                        )

                        Text("扫码前往代码仓库")
                    }
                }
            }
        }

        item {
            val popupManager = LocalPopupManager.current
            val focusRequester = remember { FocusRequester() }
            var isGuideScreenVisible by remember { mutableStateOf(false) }

            SettingsListItem(
                modifier = Modifier.focusRequester(focusRequester),
                headlineContent = "使用说明",
                trailingIcon = Icons.AutoMirrored.Filled.OpenInNew,
                onSelected = {
                    popupManager.push(focusRequester, true)
                    isGuideScreenVisible = true
                },
            )

            SimplePopup(
                visibleProvider = { isGuideScreenVisible },
                onDismissRequest = { isGuideScreenVisible = false },
            ) {
                GuideScreen(onClose = { isGuideScreenVisible = false })
            }
        }
    }
}

@Composable
private fun rememberPackageInfo(context: Context = LocalContext.current): PackageInfo =
    context.packageManager.getPackageInfo(context.packageName, 0)
