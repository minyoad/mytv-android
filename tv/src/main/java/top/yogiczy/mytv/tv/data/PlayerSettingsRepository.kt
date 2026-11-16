package top.yogiczy.mytv.tv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yogiczy.mytv.tv.ui.screens.videoplayer.VideoPlayerDisplayMode
import top.yogiczy.mytv.tv.ui.utils.Configs

data class PlayerSettings(
    val type: Configs.VideoPlayerType,
    val userAgent: String,
    val loadTimeout: Long,
    val displayMode: VideoPlayerDisplayMode,
    val forceSoftDecode: Boolean,
    val renderMode: Configs.VideoPlayerRenderMode,
    val stopPreviousMediaItem: Boolean,
    val skipMultipleFramesOnSameVSync: Boolean,
    val autoFillForSD: Boolean,
) {
    companion object {
        fun normalizeTimeout(value: Long) = value.coerceIn(1000L, 120000L)
    }
}

/** Configs remains the persistence adapter for existing UI and HTTP callers. StateFlow retains the latest value. */
object PlayerSettingsRepository {
    private fun read() = PlayerSettings(
        Configs.videoPlayerType, Configs.videoPlayerUserAgent, Configs.videoPlayerLoadTimeout,
        Configs.videoPlayerDisplayMode, Configs.videoPlayerForceSoftDecode, Configs.videoPlayerRenderMode,
        Configs.videoPlayerStopPreviousMediaItem, Configs.videoPlayerSkipMultipleFramesOnSameVSync,
        Configs.videoPlayerAutoFillForSD,
    )
    private val _settings = MutableStateFlow(read())
    val settings = _settings.asStateFlow()

    @Synchronized
    fun refresh() { _settings.value = read() }
}
