package top.yogiczy.mytv.tv.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DisplaySettings
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector

enum class SettingsCategories(
    val icon: ImageVector,
    val title: String
) {
    APP(Icons.Default.Settings, "应用"),
    IPTV(Icons.Default.LiveTv, "直播源"),
    EPG(Icons.Default.Menu, "节目单"),
    EPG_RESERVE(Icons.Default.Bookmark, "预约"),
    FAVORITE(Icons.Default.Star, "收藏"),
    UI(Icons.Default.DisplaySettings, "界面"),
    VIDEO_PLAYER(Icons.Default.SmartDisplay, "播放器"),
    DEBUG(Icons.Default.BugReport, "调试"),
    LOG(Icons.Default.FormatListNumbered, "日志"),
    MORE(Icons.Default.CloudUpload, "推送"),
    ABOUT(Icons.Default.Info, "关于"),
}