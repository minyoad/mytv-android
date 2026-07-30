package top.yogiczy.mytv.tv.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSourceList
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSourceList
import top.yogiczy.mytv.core.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.screens.videoplayer.VideoPlayerDisplayMode
import top.yogiczy.mytv.tv.ui.utils.Configs

class SettingsViewModel : ViewModel() {
    var onVideoPlayerTypeChanged: ((Configs.VideoPlayerType) -> Unit)? = null
    private var _appBootLaunch by mutableStateOf(Configs.appBootLaunch)
    var appBootLaunch: Boolean
        get() = _appBootLaunch
        set(value) {
            Configs.appBootLaunch = value
        }

    private var _appLastLatestVersion by mutableStateOf(Configs.appLastLatestVersion)
    var appLastLatestVersion: String
        get() = _appLastLatestVersion
        set(value) {
            Configs.appLastLatestVersion = value
        }

    private var _appAgreementAgreed by mutableStateOf(Configs.appAgreementAgreed)
    var appAgreementAgreed: Boolean
        get() = _appAgreementAgreed
        set(value) {
            Configs.appAgreementAgreed = value
        }

    private var _debugShowFps by mutableStateOf(Configs.debugShowFps)
    var debugShowFps: Boolean
        get() = _debugShowFps
        set(value) {
            Configs.debugShowFps = value
        }

    private var _debugShowVideoPlayerMetadata by mutableStateOf(Configs.debugShowVideoPlayerMetadata)
    var debugShowVideoPlayerMetadata: Boolean
        get() = _debugShowVideoPlayerMetadata
        set(value) {
            Configs.debugShowVideoPlayerMetadata = value
        }

    private var _debugShowLayoutGrids by mutableStateOf(Configs.debugShowLayoutGrids)
    var debugShowLayoutGrids: Boolean
        get() = _debugShowLayoutGrids
        set(value) {
            Configs.debugShowLayoutGrids = value
        }

    private var _iptvLastChannelIdx by mutableIntStateOf(Configs.iptvLastChannelIdx)
    var iptvLastChannelIdx: Int
        get() = _iptvLastChannelIdx
        set(value) {
            Configs.iptvLastChannelIdx = value
        }

    private var _iptvChannelUrlIdxMap= mutableStateOf(mutableMapOf<String, Int>().apply {
        putAll(Configs.iptvChannelUrlIdx)
    })
    fun getIptvChannelUrlIdx(channel_name:String):Int{
        return _iptvChannelUrlIdxMap.value.getOrDefault(channel_name,0)
    }
    fun setIptvChannelUrlIdx(channel_name:String,value:Int){
        val newMap = _iptvChannelUrlIdxMap.value.toMutableMap()
        newMap[channel_name] = value
        Configs.iptvChannelUrlIdx = newMap
    }

    private var _iptvChannelChangeFlip by mutableStateOf(Configs.iptvChannelChangeFlip)
    var iptvChannelChangeFlip: Boolean
        get() = _iptvChannelChangeFlip
        set(value) {
            Configs.iptvChannelChangeFlip = value
        }

    private var _iptvSourceCacheTime by mutableLongStateOf(Configs.iptvSourceCacheTime)
    var iptvSourceCacheTime: Long
        get() = _iptvSourceCacheTime
        set(value) {
            Configs.iptvSourceCacheTime = value
        }

    private var _iptvSourceCurrent by mutableStateOf(Configs.iptvSourceCurrent)
    var iptvSourceCurrent: IptvSource
        get() = _iptvSourceCurrent
        set(value) {
            Configs.iptvSourceCurrent = value
        }

    private var _iptvSourceList by mutableStateOf(Configs.iptvSourceList)
    var iptvSourceList: IptvSourceList
        get() = _iptvSourceList
        set(value) {
            Configs.iptvSourceList = value
        }

    private var _iptvPlayableHostList by mutableStateOf(Configs.iptvPlayableHostList)
    var iptvPlayableHostList: Set<String>
        get() = _iptvPlayableHostList
        set(value) {
            Configs.iptvPlayableHostList = value
        }

    private var _iptvChannelNoSelectEnable by mutableStateOf(Configs.iptvChannelNoSelectEnable)
    var iptvChannelNoSelectEnable: Boolean
        get() = _iptvChannelNoSelectEnable
        set(value) {
            Configs.iptvChannelNoSelectEnable = value
        }

    private var _iptvChannelFavoriteEnable by mutableStateOf(Configs.iptvChannelFavoriteEnable)
    var iptvChannelFavoriteEnable: Boolean
        get() = _iptvChannelFavoriteEnable
        set(value) {
            Configs.iptvChannelFavoriteEnable = value
        }

    private var _iptvChannelFavoriteListVisible by mutableStateOf(Configs.iptvChannelFavoriteListVisible)
    var iptvChannelFavoriteListVisible: Boolean
        get() = _iptvChannelFavoriteListVisible
        set(value) {
            Configs.iptvChannelFavoriteListVisible = value
        }

    private var _iptvChannelFavoriteList by mutableStateOf(Configs.iptvChannelFavoriteList)
    var iptvChannelFavoriteList: Set<String>
        get() = _iptvChannelFavoriteList
        set(value) {
            Configs.iptvChannelFavoriteList = value
        }

    private var _iptvChannelFavoriteChangeBoundaryJumpOut by mutableStateOf(Configs.iptvChannelFavoriteChangeBoundaryJumpOut)
    var iptvChannelFavoriteChangeBoundaryJumpOut: Boolean
        get() = _iptvChannelFavoriteChangeBoundaryJumpOut
        set(value) {
            Configs.iptvChannelFavoriteChangeBoundaryJumpOut = value
        }

    private var _iptvChannelGroupHiddenList by mutableStateOf(Configs.iptvChannelGroupHiddenList)
    var iptvChannelGroupHiddenList: Set<String>
        get() = _iptvChannelGroupHiddenList
        set(value) {
            Configs.iptvChannelGroupHiddenList = value
        }

    private var _iptvHybridMode by mutableStateOf(Configs.iptvHybridMode)
    var iptvHybridMode: Configs.IptvHybridMode
        get() = _iptvHybridMode
        set(value) {
            Configs.iptvHybridMode = value
        }

    private var _iptvAutoProbe by mutableStateOf(Configs.iptvAutoProbe)
    var iptvAutoProbe: Boolean
        get() = _iptvAutoProbe
        set(value) {
            Configs.iptvAutoProbe = value
        }
        
    private var _videoPlayerType by mutableStateOf(Configs.videoPlayerType)
    var videoPlayerType: Configs.VideoPlayerType
        get() = _videoPlayerType
        set(value) {
            Configs.videoPlayerType = value
        }

    var videoPlayerTypeValue: Configs.VideoPlayerType = Configs.VideoPlayerType.MEDIA3
        get() = videoPlayerType
    
    fun getVideoPlayerTypeLabel(type: Configs.VideoPlayerType): String = when (type) {
        Configs.VideoPlayerType.IJK -> "IJK播放器"
        Configs.VideoPlayerType.MEDIA3 -> "Media3播放器"
    }

    private var _epgEnable by mutableStateOf(Configs.epgEnable)
    var epgEnable: Boolean
        get() = _epgEnable
        set(value) {
            Configs.epgEnable = value
        }

    private var _epgSourceCurrent by mutableStateOf(Configs.epgSourceCurrent)
    var epgSourceCurrent: EpgSource
        get() = _epgSourceCurrent
        set(value) {
            Configs.epgSourceCurrent = value
        }

    private var _epgSourceList by mutableStateOf(Configs.epgSourceList)
    var epgSourceList: EpgSourceList
        get() = _epgSourceList
        set(value) {
            Configs.epgSourceList = value
        }

    private var _epgRefreshTimeThreshold by mutableIntStateOf(Configs.epgRefreshTimeThreshold)
    var epgRefreshTimeThreshold: Int
        get() = _epgRefreshTimeThreshold
        set(value) {
            Configs.epgRefreshTimeThreshold = value
        }

    private var _epgChannelReserveList by mutableStateOf(Configs.epgChannelReserveList)
    var epgChannelReserveList: EpgProgrammeReserveList
        get() = _epgChannelReserveList
        set(value) {
            Configs.epgChannelReserveList = value
        }

    private var _epgRefreshIdleEnable by mutableStateOf(Configs.epgRefreshIdleEnable)
    var epgRefreshIdleEnable: Boolean
        get() = _epgRefreshIdleEnable
        set(value) {
            Configs.epgRefreshIdleEnable = value
        }

    private var _epgRefreshIdleDelay by mutableLongStateOf(Configs.epgRefreshIdleDelay)
    var epgRefreshIdleDelay: Long
        get() = _epgRefreshIdleDelay
        set(value) {
            Configs.epgRefreshIdleDelay = value
        }

    private var _uiShowEpgProgrammeProgress by mutableStateOf(Configs.uiShowEpgProgrammeProgress)
    var uiShowEpgProgrammeProgress: Boolean
        get() = _uiShowEpgProgrammeProgress
        set(value) {
            Configs.uiShowEpgProgrammeProgress = value
        }

    private var _uiShowEpgProgrammePermanentProgress by mutableStateOf(Configs.uiShowEpgProgrammePermanentProgress)
    var uiShowEpgProgrammePermanentProgress: Boolean
        get() = _uiShowEpgProgrammePermanentProgress
        set(value) {
            Configs.uiShowEpgProgrammePermanentProgress = value
        }

    private var _uiShowChannelLogo by mutableStateOf(Configs.uiShowChannelLogo)
    var uiShowChannelLogo: Boolean
        get() = _uiShowChannelLogo
        set(value) {
            Configs.uiShowChannelLogo = value
        }

    private var _uiUseClassicPanelScreen by mutableStateOf(Configs.uiUseClassicPanelScreen)
    var uiUseClassicPanelScreen: Boolean
        get() = _uiUseClassicPanelScreen
        set(value) {
            Configs.uiUseClassicPanelScreen = value
        }

    private var _uiDensityScaleRatio by mutableFloatStateOf(Configs.uiDensityScaleRatio)
    var uiDensityScaleRatio: Float
        get() = _uiDensityScaleRatio
        set(value) {
            Configs.uiDensityScaleRatio = value
        }

    private var _uiFontScaleRatio by mutableFloatStateOf(Configs.uiFontScaleRatio)
    var uiFontScaleRatio: Float
        get() = _uiFontScaleRatio
        set(value) {
            Configs.uiFontScaleRatio = value
        }

    private var _uiTimeShowMode by mutableStateOf(Configs.uiTimeShowMode)
    var uiTimeShowMode: Configs.UiTimeShowMode
        get() = _uiTimeShowMode
        set(value) {
            Configs.uiTimeShowMode = value
        }

    private var _uiFocusOptimize by mutableStateOf(Configs.uiFocusOptimize)
    var uiFocusOptimize: Boolean
        get() = _uiFocusOptimize
        set(value) {
            Configs.uiFocusOptimize = value
        }

    private var _uiScreenAutoCloseDelay by mutableLongStateOf(Configs.uiScreenAutoCloseDelay)
    var uiScreenAutoCloseDelay: Long
        get() = _uiScreenAutoCloseDelay
        set(value) {
            Configs.uiScreenAutoCloseDelay = value
        }

    private var _updateForceRemind by mutableStateOf(Configs.updateForceRemind)
    var updateForceRemind: Boolean
        get() = _updateForceRemind
        set(value) {
            Configs.updateForceRemind = value
        }

    private var _updateChannel by mutableStateOf(Configs.updateChannel)
    var updateChannel: String
        get() = _updateChannel
        set(value) {
            Configs.updateChannel = value
        }

    private var _videoPlayerUserAgent by mutableStateOf(Configs.videoPlayerUserAgent)
    var videoPlayerUserAgent: String
        get() = _videoPlayerUserAgent
        set(value) {
            Configs.videoPlayerUserAgent = value
        }

    private var _videoPlayerLoadTimeout by mutableLongStateOf(Configs.videoPlayerLoadTimeout)
    var videoPlayerLoadTimeout: Long
        get() = _videoPlayerLoadTimeout
        set(value) {
            Configs.videoPlayerLoadTimeout = value
        }

    private var _videoPlayerAspectRatio by mutableStateOf(Configs.videoPlayerDisplayMode)
    var videoPlayerDisplayMode: VideoPlayerDisplayMode
        get() = _videoPlayerAspectRatio
        set(value) {
            Configs.videoPlayerDisplayMode = value
        }

    private var _videoPlayerForceAudioSoftDecode by mutableStateOf(Configs.videoPlayerForceSoftDecode)
    var videoPlayerForceSoftDecode: Boolean
        get() = _videoPlayerForceAudioSoftDecode
        set(value) {
            Configs.videoPlayerForceSoftDecode = value
        }

    private var _videoPlayerRenderMode by mutableStateOf(Configs.videoPlayerRenderMode)
    var videoPlayerRenderMode: Configs.VideoPlayerRenderMode
        get() = _videoPlayerRenderMode
        set(value) {
            Configs.videoPlayerRenderMode = value
        }

    private var _videoPlayerStopPreviousMediaItem by mutableStateOf(Configs.videoPlayerStopPreviousMediaItem)
    var videoPlayerStopPreviousMediaItem: Boolean
        get() = _videoPlayerStopPreviousMediaItem
        set(value) {
            Configs.videoPlayerStopPreviousMediaItem = value
        }

    private var _videoPlayerSkipMultipleFramesOnSameVSync by mutableStateOf(Configs.videoPlayerSkipMultipleFramesOnSameVSync)
    var videoPlayerSkipMultipleFramesOnSameVSync: Boolean
        get() = _videoPlayerSkipMultipleFramesOnSameVSync
        set(value) {
            Configs.videoPlayerSkipMultipleFramesOnSameVSync = value
        }

    private var _videoPlayerAutoFillForSD by mutableStateOf(Configs.videoPlayerAutoFillForSD)
    var videoPlayerAutoFillForSD: Boolean
        get() = _videoPlayerAutoFillForSD
        set(value) {
            Configs.videoPlayerAutoFillForSD = value
        }

    init {
        // 删除过期的预约
        _epgChannelReserveList = EpgProgrammeReserveList(
            _epgChannelReserveList.filter {
                System.currentTimeMillis() < it.startAt + 60 * 1000
            }
        )

        viewModelScope.launch {
            Configs.onKeyChanged.collect { key ->
                when (key) {
                    Configs.KEY.APP_BOOT_LAUNCH -> _appBootLaunch = Configs.appBootLaunch
                    Configs.KEY.APP_LAST_LATEST_VERSION -> _appLastLatestVersion = Configs.appLastLatestVersion
                    Configs.KEY.APP_AGREEMENT_AGREED -> _appAgreementAgreed = Configs.appAgreementAgreed
                    Configs.KEY.DEBUG_SHOW_FPS -> _debugShowFps = Configs.debugShowFps
                    Configs.KEY.DEBUG_SHOW_VIDEO_PLAYER_METADATA -> _debugShowVideoPlayerMetadata = Configs.debugShowVideoPlayerMetadata
                    Configs.KEY.DEBUG_SHOW_LAYOUT_GRIDS -> _debugShowLayoutGrids = Configs.debugShowLayoutGrids
                    Configs.KEY.IPTV_LAST_CHANNEL_IDX -> _iptvLastChannelIdx = Configs.iptvLastChannelIdx
                    Configs.KEY.IPTV_CHANNEL_CHANGE_FLIP -> _iptvChannelChangeFlip = Configs.iptvChannelChangeFlip
                    Configs.KEY.IPTV_SOURCE_CURRENT -> _iptvSourceCurrent = Configs.iptvSourceCurrent
                    Configs.KEY.IPTV_SOURCE_LIST -> _iptvSourceList = Configs.iptvSourceList
                    Configs.KEY.IPTV_SOURCE_CACHE_TIME -> _iptvSourceCacheTime = Configs.iptvSourceCacheTime
                    Configs.KEY.IPTV_PLAYABLE_HOST_LIST -> _iptvPlayableHostList = Configs.iptvPlayableHostList
                    Configs.KEY.IPTV_CHANNEL_NO_SELECT_ENABLE -> _iptvChannelNoSelectEnable = Configs.iptvChannelNoSelectEnable
                    Configs.KEY.IPTV_CHANNEL_FAVORITE_ENABLE -> _iptvChannelFavoriteEnable = Configs.iptvChannelFavoriteEnable
                    Configs.KEY.IPTV_CHANNEL_FAVORITE_LIST_VISIBLE -> _iptvChannelFavoriteListVisible = Configs.iptvChannelFavoriteListVisible
                    Configs.KEY.IPTV_CHANNEL_FAVORITE_LIST -> _iptvChannelFavoriteList = Configs.iptvChannelFavoriteList
                    Configs.KEY.IPTV_CHANNEL_FAVORITE_CHANGE_BOUNDARY_JUMP_OUT -> _iptvChannelFavoriteChangeBoundaryJumpOut = Configs.iptvChannelFavoriteChangeBoundaryJumpOut
                    Configs.KEY.IPTV_CHANNEL_GROUP_HIDDEN_LIST -> _iptvChannelGroupHiddenList = Configs.iptvChannelGroupHiddenList
                    Configs.KEY.IPTV_HYBRID_MODE -> _iptvHybridMode = Configs.iptvHybridMode
                    Configs.KEY.IPTV_AUTO_PROBE -> _iptvAutoProbe = Configs.iptvAutoProbe
                    Configs.KEY.EPG_ENABLE -> _epgEnable = Configs.epgEnable
                    Configs.KEY.EPG_SOURCE_CURRENT -> _epgSourceCurrent = Configs.epgSourceCurrent
                    Configs.KEY.EPG_SOURCE_LIST -> _epgSourceList = Configs.epgSourceList
                    Configs.KEY.EPG_REFRESH_TIME_THRESHOLD -> _epgRefreshTimeThreshold = Configs.epgRefreshTimeThreshold
                    Configs.KEY.EPG_CHANNEL_RESERVE_LIST -> _epgChannelReserveList = Configs.epgChannelReserveList
                    Configs.KEY.EPG_REFRESH_IDLE_ENABLE -> _epgRefreshIdleEnable = Configs.epgRefreshIdleEnable
                    Configs.KEY.EPG_REFRESH_IDLE_DELAY -> _epgRefreshIdleDelay = Configs.epgRefreshIdleDelay
                    Configs.KEY.UI_SHOW_EPG_PROGRAMME_PROGRESS -> _uiShowEpgProgrammeProgress = Configs.uiShowEpgProgrammeProgress
                    Configs.KEY.UI_SHOW_EPG_PROGRAMME_PERMANENT_PROGRESS -> _uiShowEpgProgrammePermanentProgress = Configs.uiShowEpgProgrammePermanentProgress
                    Configs.KEY.UI_SHOW_CHANNEL_LOGO -> _uiShowChannelLogo = Configs.uiShowChannelLogo
                    Configs.KEY.UI_USE_CLASSIC_PANEL_SCREEN -> _uiUseClassicPanelScreen = Configs.uiUseClassicPanelScreen
                    Configs.KEY.UI_DENSITY_SCALE_RATIO -> _uiDensityScaleRatio = Configs.uiDensityScaleRatio
                    Configs.KEY.UI_FONT_SCALE_RATIO -> _uiFontScaleRatio = Configs.uiFontScaleRatio
                    Configs.KEY.UI_TIME_SHOW_MODE -> _uiTimeShowMode = Configs.uiTimeShowMode
                    Configs.KEY.UI_FOCUS_OPTIMIZE -> _uiFocusOptimize = Configs.uiFocusOptimize
                    Configs.KEY.UI_SCREEN_AUTO_CLOSE_DELAY -> _uiScreenAutoCloseDelay = Configs.uiScreenAutoCloseDelay
                    Configs.KEY.UPDATE_FORCE_REMIND -> _updateForceRemind = Configs.updateForceRemind
                    Configs.KEY.UPDATE_CHANNEL -> _updateChannel = Configs.updateChannel
                    Configs.KEY.VIDEO_PLAYER_USER_AGENT -> _videoPlayerUserAgent = Configs.videoPlayerUserAgent
                    Configs.KEY.VIDEO_PLAYER_LOAD_TIMEOUT -> _videoPlayerLoadTimeout = Configs.videoPlayerLoadTimeout
                    Configs.KEY.VIDEO_PLAYER_DISPLAY_MODE -> _videoPlayerAspectRatio = Configs.videoPlayerDisplayMode
                    Configs.KEY.VIDEO_PLAYER_FORCE_AUDIO_SOFT_DECODE -> _videoPlayerForceAudioSoftDecode = Configs.videoPlayerForceSoftDecode
                    Configs.KEY.VIDEO_PLAYER_RENDER_MODE -> _videoPlayerRenderMode = Configs.videoPlayerRenderMode
                    Configs.KEY.VIDEO_PLAYER_TYPE -> {
                        _videoPlayerType = Configs.videoPlayerType
                        onVideoPlayerTypeChanged?.invoke(_videoPlayerType)
                    }
                    Configs.KEY.VIDEO_PLAYER_STOP_PREVIOUS_MEDIA_ITEM -> _videoPlayerStopPreviousMediaItem = Configs.videoPlayerStopPreviousMediaItem
                    Configs.KEY.VIDEO_PLAYER_SKIP_MULTIPLE_FRAMES_ON_SAME_VSYNC -> _videoPlayerSkipMultipleFramesOnSameVSync = Configs.videoPlayerSkipMultipleFramesOnSameVSync
                    Configs.KEY.VIDEO_PLAYER_AUTO_FILL_FOR_SD -> _videoPlayerAutoFillForSD = Configs.videoPlayerAutoFillForSD
                    else -> {}
                }
            }
        }
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearCache(context: Context, onComplete: () -> Unit = {}) {
        iptvPlayableHostList = emptySet()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 清理所有直播源和节目单文件缓存
                Globals.cacheDir.listFiles()?.forEach { file ->
                    if (file.name.startsWith("iptv-") || file.name.startsWith("epg-")) {
                        file.delete()
                    }
                }
                
                // 清理图片磁盘缓存
                context.imageLoader.diskCache?.clear()
            }

            // 清理内存缓存
            EpgList.clearCache()
            context.imageLoader.memoryCache?.clear()

            Snackbar.show("已清除所有缓存")
            onComplete()
        }
    }
}
