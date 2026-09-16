package top.yogiczy.mytv.tv.ui.screens.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import top.yogiczy.mytv.core.data.entities.git.GitRelease
import top.yogiczy.mytv.core.data.repositories.git.GitRepository
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.core.util.utils.Downloader
import top.yogiczy.mytv.core.util.utils.compareVersion
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.material.SnackbarType
import java.io.File

class UpdateViewModel : ViewModel() {
    private val log = Logger.create(UpdateViewModel::class.java.simpleName)

    private var _isChecking = false
    private var _isUpdating = false

    private var _isUpdateAvailable by mutableStateOf(false)
    val isUpdateAvailable get() = _isUpdateAvailable

    private var _updateDownloaded by mutableStateOf(false)
    val updateDownloaded get() = _updateDownloaded

    private var _latestRelease by mutableStateOf(GitRelease())
    val latestRelease get() = _latestRelease

    /**
     * 每次 downloadAndUpdate 完成（下载成功或复用已下载）后自增，
     * 用于让 UpdateScreen 的 LaunchedEffect 必然重启，从而触发安装流程。
     *
     * 之所以不直接用 updateDownloaded 作 key：用户首次点击下载成功后会设为 true，
     * 再次点击"立即更新"（实际是已下载）时若不改变该值，LaunchedEffect 不会重启，
     * 导致"已下载但没弹出安装界面"。
     */
    private var _installRequestToken by mutableStateOf(0)
    val installRequestToken get() = _installRequestToken


    var visible by mutableStateOf(false)

    /**
     * 检查更新
     * @param force 为 true 时忽略已发现的更新并重新拉取（用于切换更新通道后立即刷新）
     * @return 是否成功获取到远端版本信息
     */
    suspend fun checkUpdate(currentVersion: String, channel: String, force: Boolean = false): Boolean {
        if (_isChecking) return false
        if (_isUpdateAvailable && !force) return false

        try {
            val releaseUrl = Constants.GIT_RELEASE_LATEST_URL[channel] ?: return false

            _isChecking = true

            if (force) {
                _isUpdateAvailable = false
                _updateDownloaded = false
                _latestRelease = GitRelease()
            }

            _latestRelease = GitRepository().latestRelease(releaseUrl)
            log.d("线上版本: ${_latestRelease.version}")
            _isUpdateAvailable = _latestRelease.version.compareVersion(currentVersion) > 0

            return true
        } catch (ex: Exception) {
            log.e("检查更新失败", ex)
            _latestRelease = _latestRelease.copy(description = ex.message ?: "检查更新失败")

            return false
        } finally {
            _isChecking = false
        }
    }

    /**
     * 确保 latestFile 已下载属于当前 _latestRelease 的 APK。已下载且匹配则复用跳过下载。
     * 完成后会自增 installRequestToken，触发 UpdateScreen 弹出安装界面。
     */
    suspend fun downloadAndUpdate(latestFile: File) {
        if (!_isUpdateAvailable) return
        if (_isUpdating) return

        val metaFile = File(latestFile.parentFile, "${latestFile.name}.meta")
        val expectedUrl = _latestRelease.downloadUrl

        // 修复：复用已下载文件。
        // 通过 .meta 文件保存当前 release 的 downloadUrl，避免跨版本误用旧 APK。
        // 原方案：每次都重新下载，即使上次已成功下载完也要重新拉一遍。
        if (
            latestFile.exists() &&
            latestFile.length() > 0 &&
            metaFile.exists() &&
            runCatching { metaFile.readText() }.getOrNull() == expectedUrl
        ) {
            _updateDownloaded = true
            _installRequestToken++
            Snackbar.show("更新已下载", leadingLoading = false, duration = 2300)
            return
        }

        _isUpdating = true
        _updateDownloaded = false
        Snackbar.show(
            "开始下载更新",
            leadingLoading = true,
            duration = 10_000,
            id = "downloadProcess"
        )

        try {
            Downloader.downloadTo(expectedUrl, latestFile.path) {
                Snackbar.show(
                    "正在下载更新: $it%",
                    leadingLoading = true,
                    duration = 10_000,
                    id = "downloadProcess"
                )
            }

            // Downloader 已做完整性校验（Content-Length 对比）。这里写入 meta 供下次复用校验。
            metaFile.writeText(expectedUrl)
            _updateDownloaded = true
            _installRequestToken++
            Snackbar.show("下载更新成功")
        } catch (ex: Exception) {
            log.e("下载更新失败", ex)
            // 失败时清理可能存在的半截文件和 meta，避免下次复用时误判
            runCatching {
                if (latestFile.exists()) latestFile.delete()
                if (metaFile.exists()) metaFile.delete()
            }
            Snackbar.show("下载更新失败：${ex.message}", type = SnackbarType.ERROR)
        } finally {
            _isUpdating = false
        }
    }
}