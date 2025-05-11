package top.yogiczy.mytv.tv.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Switch
import top.yogiczy.mytv.core.util.utils.humanizeMs
import top.yogiczy.mytv.tv.ui.material.LocalPopupManager
import top.yogiczy.mytv.tv.ui.material.SimplePopup
import top.yogiczy.mytv.tv.ui.screens.components.SelectDialog
import top.yogiczy.mytv.tv.ui.screens.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screens.videoplayerdiaplaymode.VideoPlayerDisplayModeScreen
import top.yogiczy.mytv.tv.ui.utils.Configs

@Composable
fun SettingsCategoryVideoPlayer(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = viewModel(),
) {
    SettingsContentList(modifier) {
        item {
            SettingsListItem(
                modifier = Modifier.focusRequester(it),
                headlineContent = "渲染方式",
                trailingContent = settingsViewModel.videoPlayerRenderMode.label,
                onSelected = {
                    if (settingsViewModel.videoPlayerRenderMode == Configs.VideoPlayerRenderMode.SURFACE_VIEW)
                        settingsViewModel.videoPlayerRenderMode =
                            Configs.VideoPlayerRenderMode.TEXTURE_VIEW
                    else
                        settingsViewModel.videoPlayerRenderMode =
                            Configs.VideoPlayerRenderMode.SURFACE_VIEW
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "强制音频软解",
                trailingContent = {
                    Switch(settingsViewModel.videoPlayerForceAudioSoftDecode, null)
                },
                onSelected = {
                    settingsViewModel.videoPlayerForceAudioSoftDecode =
                        !settingsViewModel.videoPlayerForceAudioSoftDecode
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "停止上一媒体项",
                trailingContent = {
                    Switch(settingsViewModel.videoPlayerStopPreviousMediaItem, null)
                },
                onSelected = {
                    settingsViewModel.videoPlayerStopPreviousMediaItem =
                        !settingsViewModel.videoPlayerStopPreviousMediaItem
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "跳过多帧渲染",
                trailingContent = {
                    Switch(settingsViewModel.videoPlayerSkipMultipleFramesOnSameVSync, null)
                },
                onSelected = {
                    settingsViewModel.videoPlayerSkipMultipleFramesOnSameVSync =
                        !settingsViewModel.videoPlayerSkipMultipleFramesOnSameVSync
                },
            )
        }

        item {
            val popupManager = LocalPopupManager.current
            var visible by remember { mutableStateOf(false) }

            SettingsListItem(
                headlineContent = "全局显示模式",
                trailingContent = settingsViewModel.videoPlayerDisplayMode.label,
                onSelected = {
                    popupManager.push(it, true)
                    visible = true
                },
                remoteConfig = true,
            )

            SimplePopup(
                visibleProvider = { visible },
                onDismissRequest = { visible = false },
            ) {
                VideoPlayerDisplayModeScreen(
                    currentDisplayModeProvider = { settingsViewModel.videoPlayerDisplayMode },
                    onDisplayModeChanged = {
                        settingsViewModel.videoPlayerDisplayMode = it
                        visible = false
                    },
                )
            }
        }

        item {
            val popupManager = LocalPopupManager.current
            val focusRequester = remember { FocusRequester() }
            var visible by remember { mutableStateOf(false) }

            SettingsListItem(
                modifier = Modifier.focusRequester(focusRequester),
                headlineContent = "播放器加载超时",
                supportingContent = "影响超时换源、断线重连",
                trailingContent = settingsViewModel.videoPlayerLoadTimeout.humanizeMs(),
                onSelected = {
                    popupManager.push(focusRequester, true)
                    visible = true
                },
            )

            SelectDialog(
                visibleProvider = { visible },
                onDismissRequest = { visible = false },
                title = "播放器加载超时",
                currentDataProvider = { settingsViewModel.videoPlayerLoadTimeout },
                dataListProvider = { listOf(3, 5, 10, 15, 20, 25, 30).map { it.toLong() * 1000 } },
                dataText = { it.humanizeMs() },
                onDataSelected = {
                    settingsViewModel.videoPlayerLoadTimeout = it
                    visible = false
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "播放器类型",
                trailingContent = settingsViewModel.getVideoPlayerTypeLabel(settingsViewModel.videoPlayerType),
                onSelected = {
                    settingsViewModel.videoPlayerType = if (settingsViewModel.videoPlayerType == Configs.VideoPlayerType.IJK) {
                        Configs.VideoPlayerType.MEDIA3
                    } else {
                        Configs.VideoPlayerType.IJK
                    }
                },
            )
        }

        item {
            SettingsListItem(
                headlineContent = "播放器自定义UA",
                supportingContent = settingsViewModel.videoPlayerUserAgent,
                remoteConfig = true,
            )
        }
    }
}