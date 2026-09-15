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

    suspend fun downloadAndUpdate(latestFile: File) {
        if (!_isUpdateAvailable) return
        if (_isUpdating) return

        _isUpdating = true
        _updateDownloaded = false
        Snackbar.show(
            "开始下载更新",
            leadingLoading = true,
            duration = 10_000,
            id = "downloadProcess"
        )

        try {
            Downloader.downloadTo(_latestRelease.downloadUrl, latestFile.path) {
                Snackbar.show(
                    "正在下载更新: $it%",
                    leadingLoading = true,
                    duration = 10_000,
                    id = "downloadProcess"
                )
            }

            _updateDownloaded = true
            Snackbar.show("下载更新成功")
        } catch (ex: Exception) {
            Snackbar.show("下载更新失败", type = SnackbarType.ERROR)
        } finally {
            _isUpdating = false
        }
    }
}