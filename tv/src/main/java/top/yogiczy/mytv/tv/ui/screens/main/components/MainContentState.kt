package top.yogiczy.mytv.tv.ui.screens.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine   // 1. 新增
import androidx.compose.ui.platform.LocalContext          // 2. 新增
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import androidx.core.content.ContextCompat
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelIdx
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserve
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeReserveList
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.core.data.utils.Loggable
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.screens.settings.SettingsViewModel
import top.yogiczy.mytv.tv.ui.screens.videoplayer.VideoPlayerState
import top.yogiczy.mytv.tv.ui.screens.videoplayer.rememberVideoPlayerState
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Stable
class MainContentState(
    private val coroutineScope: CoroutineScope,
    private val videoPlayerState: VideoPlayerState,
    private val channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    private val settingsViewModel: SettingsViewModel,
    private val context: Context,
) : Loggable() {
    private var _currentChannel by mutableStateOf(Channel())
    val currentChannel get() = _currentChannel

    private var _currentChannelUrlIdx by mutableIntStateOf(0)
    val currentChannelUrlIdx get() = _currentChannelUrlIdx

    private var _currentPlaybackEpgProgramme by mutableStateOf<EpgProgramme?>(null)
    val currentPlaybackEpgProgramme get() = _currentPlaybackEpgProgramme

    private var _isTempChannelScreenVisible by mutableStateOf(false)
    var isTempChannelScreenVisible
        get() = _isTempChannelScreenVisible
        set(value) {
            _isTempChannelScreenVisible = value
        }

    private var _isChannelScreenVisible by mutableStateOf(false)
    var isChannelScreenVisible
        get() = _isChannelScreenVisible
        set(value) {
            _isChannelScreenVisible = value
        }

    private var _isSettingsScreenVisible by mutableStateOf(false)
    var isSettingsScreenVisible
        get() = _isSettingsScreenVisible
        set(value) {
            _isSettingsScreenVisible = value
        }

    private var _isVideoPlayerControllerScreenVisible by mutableStateOf(false)
    var isVideoPlayerControllerScreenVisible
        get() = _isVideoPlayerControllerScreenVisible
        set(value) {
            _isVideoPlayerControllerScreenVisible = value
        }

    private var _isQuickOpScreenVisible by mutableStateOf(false)
    var isQuickOpScreenVisible
        get() = _isQuickOpScreenVisible
        set(value) {
            _isQuickOpScreenVisible = value
        }

    private var _isEpgScreenVisible by mutableStateOf(false)
    var isEpgScreenVisible
        get() = _isEpgScreenVisible
        set(value) {
            _isEpgScreenVisible = value
        }

    private var _isChannelUrlScreenVisible by mutableStateOf(false)
    var isChannelUrlScreenVisible
        get() = _isChannelUrlScreenVisible
        set(value) {
            _isChannelUrlScreenVisible = value
        }

    private var _isVideoPlayerDisplayModeScreenVisible by mutableStateOf(false)
    var isVideoPlayerDisplayModeScreenVisible
        get() = _isVideoPlayerDisplayModeScreenVisible
        set(value) {
            _isVideoPlayerDisplayModeScreenVisible = value
        }

    init {
        val channelGroupList = channelGroupListProvider()

        changeCurrentChannel(channelGroupList.channelList.getOrElse(settingsViewModel.iptvLastChannelIdx) {
            channelGroupList.channelList.firstOrNull() ?: Channel()
        })

        /* 新增：监听 RESTART_PLAY 广播，收到后重新 prepare 当前频道 */
        coroutineScope.launch {
            suspendCancellableCoroutine<Unit> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        if (intent.action == "top.yogiczy.mytv.tv.RESTART_PLAY") {
                            restartCurrentChannel()
                        }
                    }
                }
                val filter = IntentFilter("top.yogiczy.mytv.tv.RESTART_PLAY")
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                cont.invokeOnCancellation { context.unregisterReceiver(receiver) }
                // 永久挂起，直到作用域被取消
            }
        }

        videoPlayerState.onReady {
            settingsViewModel.iptvPlayableHostList += getUrlHost(_currentChannel.urlList[_currentChannelUrlIdx])
            coroutineScope.launch {
                val name = _currentChannel.name
                val urlIdx = _currentChannelUrlIdx
                delay(Constants.UI_TEMP_CHANNEL_SCREEN_SHOW_DURATION)
                if (name == _currentChannel.name && urlIdx == _currentChannelUrlIdx) {
                    _isTempChannelScreenVisible = false
                    settingsViewModel.setIptvChannelUrlIdx(_currentChannel.name, _currentChannelUrlIdx)
//                    settingsViewModel.iptvChannelUrlIdx[_currentChannel.name]= _currentChannelUrlIdx
                }
            }
        }

        videoPlayerState.onError {
            if (_currentPlaybackEpgProgramme != null) {
                // 回放播放错误时，切换到直播
                if (_currentChannelUrlIdx < _currentChannel.urlList.size - 1) {
                    changeCurrentChannel(_currentChannel, _currentChannelUrlIdx)
                }
                return@onError
            }

            settingsViewModel.iptvPlayableHostList -= getUrlHost(_currentChannel.urlList[_currentChannelUrlIdx])

            if (_currentChannelUrlIdx < _currentChannel.urlList.size - 1) {
                changeCurrentChannel(_currentChannel, _currentChannelUrlIdx + 1)
            }
        }

        videoPlayerState.onInterrupt {
            // 播放位置长时间不推进视为卡住，需强制重新拉流恢复
            restartCurrentChannel()
        }
    }

    private fun getPrevFavoriteChannel(): Channel? {
        if (!settingsViewModel.iptvChannelFavoriteListVisible) return null

        val channelGroupList = channelGroupListProvider()

        val favoriteChannelNameList = settingsViewModel.iptvChannelFavoriteList
        val favoriteChannelList =
            channelGroupList.channelList.filter { it.name in favoriteChannelNameList }

        return if (_currentChannel in favoriteChannelList && _currentChannel != favoriteChannelList.first()) {
            val currentIdx = favoriteChannelList.indexOf(_currentChannel)
            favoriteChannelList[currentIdx - 1]
        } else if (settingsViewModel.iptvChannelFavoriteChangeBoundaryJumpOut) {
            settingsViewModel.iptvChannelFavoriteListVisible = false
            channelGroupList.channelList.lastOrNull()
        } else {
            favoriteChannelList.lastOrNull()
        }

    }

    private fun getNextFavoriteChannel(): Channel? {
        if (!settingsViewModel.iptvChannelFavoriteListVisible) return null

        val channelGroupList = channelGroupListProvider()

        val favoriteChannelNameList = settingsViewModel.iptvChannelFavoriteList
        val favoriteChannelList =
            channelGroupList.channelList.filter { it.name in favoriteChannelNameList }

        return if (_currentChannel in favoriteChannelList && _currentChannel != favoriteChannelList.last()) {
            val currentIdx = favoriteChannelList.indexOf(_currentChannel)
            favoriteChannelList[currentIdx + 1]
        } else if (settingsViewModel.iptvChannelFavoriteChangeBoundaryJumpOut) {
            settingsViewModel.iptvChannelFavoriteListVisible = false
            channelGroupList.channelList.firstOrNull()
        } else {
            favoriteChannelList.firstOrNull()
        }
    }

    private fun getPrevChannel(): Channel {
        return getPrevFavoriteChannel() ?: run {
            val channelGroupList = channelGroupListProvider()
            val channelList = channelGroupList.channelList
            if (channelList.isEmpty()) return Channel()

            val currentIdx = channelGroupList.channelIdx(_currentChannel)
            // currentIdx < 0 表示当前频道已不在列表中（如直播源刷新后残留旧对象）。
            // 显式处理，不再依赖 getOrElse 的越界兜底（原写法会传入 -1 之类的非法下标）。
            return if (currentIdx <= 0) channelList.last() else channelList[currentIdx - 1]
        }
    }

    private fun getNextChannel(): Channel {
        return getNextFavoriteChannel() ?: run {
            val channelGroupList = channelGroupListProvider()
            val channelList = channelGroupList.channelList
            if (channelList.isEmpty()) return Channel()

            val currentIdx = channelGroupList.channelIdx(_currentChannel)
            return if (currentIdx < 0 || currentIdx >= channelList.lastIndex) {
                channelList.first()
            } else {
                channelList[currentIdx + 1]
            }
        }
    }

    private fun getUrlIdx(urlList: List<String>, urlIdx: Int? = null): Int {
        val idx = if (urlIdx == null) settingsViewModel.getIptvChannelUrlIdx(_currentChannel.name)
//            urlList.indexOfFirst {
//            settingsViewModel.iptvPlayableHostList.contains(getUrlHost(it))
//        }
        else (urlIdx + urlList.size) % urlList.size

        return max(0, min(idx, urlList.size - 1))
    }

    /**
     * @param force 强制重新播放。为 true 时跳过幂等守卫，
     * 即使频道、线路、回看节目都与当前完全一致也重新 prepare。
     */
    fun changeCurrentChannel(
        channel: Channel,
        urlIdx: Int? = null,
        playbackEpgProgramme: EpgProgramme? = null,
        force: Boolean = false,
    ) {
        if (!force && channel == _currentChannel && urlIdx == _currentChannelUrlIdx && playbackEpgProgramme == _currentPlaybackEpgProgramme) return

        if (channel == _currentChannel && urlIdx != _currentChannelUrlIdx) {
            settingsViewModel.iptvPlayableHostList -= getUrlHost(_currentChannel.urlList[_currentChannelUrlIdx])
        }

        _isTempChannelScreenVisible = true

        _currentChannel = channel
        settingsViewModel.iptvLastChannelIdx =
            channelGroupListProvider().channelIdx(_currentChannel)

        _currentChannelUrlIdx = getUrlIdx(_currentChannel.urlList, urlIdx)

        _currentPlaybackEpgProgramme = playbackEpgProgramme

        var url = _currentChannel.urlList[_currentChannelUrlIdx]
        if (_currentPlaybackEpgProgramme != null) {
            val timeFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.getDefault())
            val query = listOf(
                "playseek=",
                timeFormat.format(_currentPlaybackEpgProgramme!!.startAt),
                "-",
                timeFormat.format(_currentPlaybackEpgProgramme!!.endAt),
            ).joinToString("")
            url = if (URI(url).query.isNullOrBlank()) "$url?$query" else "$url&$query"
//            url = ChannelUtil.urlToCanPlayback(url) //电信RTSP不需要替换
        }

        log.d("播放${_currentChannel.name}（${_currentChannelUrlIdx + 1}/${_currentChannel.urlList.size}）: $url")

        if (ChannelUtil.isHybridWebViewUrl(url)) {
            videoPlayerState.stop()
        } else {
            videoPlayerState.prepare(url)
        }
    }

    /**
     * 直播源刷新后，将当前播放频道重新绑定到新列表中的对应条目（仅替换引用）。
     *
     * Channel 的相等性（data class equals）包含自增 id，而每次解析直播源都会
     * 先 IdGenerator.reset() 再从 1 重新编号。若继续沿用旧 Channel 对象，
     * channelIdx() 按 id 匹配会命中错误条目（例如服务端在头部插入频道后，
     * 原 id=1 的位置已被新频道占用），换台就会跳错。这里改用跨批次稳定的
     * name/epgName 定位。
     *
     * 正在播放的内容不做任何改动：本方法不会重新 prepare，播放器继续消费当前流，
     * 即使该线路在新列表中已被移除也一样（播不动时由 onError 自行换源）。
     *
     * 新的线路要等下次切台时才生效——届时 iptvChannelUrlIdx 已被清空，
     * getUrlIdx() 会重新从线路 0 开始挑选。这里只更新内部引用，
     * 目的是让换台时的 channelIdx() 能正确定位到相邻频道。
     *
     * @param newChannelGroupList 刷新后的完整频道列表（未经分组隐藏/省份过滤）
     */
    fun relocateCurrentChannel(newChannelGroupList: ChannelGroupList) {
        val newList = newChannelGroupList.channelList
        if (newList.isEmpty()) return

        val oldChannel = _currentChannel
        if (oldChannel.name.isBlank()) return

        val target = newList.firstOrNull { it.name == oldChannel.name }
            ?: newList.firstOrNull { it.epgName == oldChannel.epgName }
            ?: run {
                log.w("直播源已更新，但当前频道已不存在：${oldChannel.name}")
                return
            }

        if (target == oldChannel || target.urlList.isEmpty()) return

        // 线路数变少导致旧索引越界时回落到 0，而不是压到末位：
        // 后者会让 onError 因「idx < size-1」不成立而放弃换源重试。
        // 只改索引不影响正在播放的流，实际生效要等下次切台或换源。
        _currentChannelUrlIdx = _currentChannelUrlIdx.takeIf { it <= target.urlList.lastIndex } ?: 0
        _currentChannel = target
        settingsViewModel.iptvLastChannelIdx = channelGroupListProvider().channelIdx(target)
            .takeIf { it >= 0 }
            ?: newChannelGroupList.channelIdx(target).coerceAtLeast(0)
        log.d("直播源已更新，重新绑定当前频道：${target.name}")
    }

    /**
     * 强制重新播放当前频道。
     *
     * 用于「频道/线路/回看节目都没变、但仍需重新拉流」的场景：
     * - 应用从后台回到前台：直播流在后台仍在缓冲，回到前台后播放器继续消费
     *   后台期间累积的缓存，画面停留在离开时的旧内容，必须重新 prepare 才能拉到最新画面。
     * - 播放卡住（onInterrupt）：需要重新建流恢复。
     * - RESTART_PLAY 广播。
     *
     * 直接调用 changeCurrentChannel 会被其幂等守卫拦截（三者都未变化），故需 force。
     */
    fun restartCurrentChannel() {
        log.d("强制重新播放当前频道：${_currentChannel.name}")
        changeCurrentChannel(
            _currentChannel,
            _currentChannelUrlIdx,
            _currentPlaybackEpgProgramme,
            force = true,
        )
    }

    fun changeCurrentChannelToPrev() {
        changeCurrentChannel(getPrevChannel())
    }

    fun changeCurrentChannelToNext() {
        changeCurrentChannel(getNextChannel())
    }

    fun favoriteChannelOrNot(channel: Channel) {
        if (!settingsViewModel.iptvChannelFavoriteEnable) return

        if (settingsViewModel.iptvChannelFavoriteList.contains(channel.name)) {
            settingsViewModel.iptvChannelFavoriteList -= channel.name
            Snackbar.show("取消收藏：${channel.name}")
        } else {
            settingsViewModel.iptvChannelFavoriteList += channel.name
            Snackbar.show("已收藏：${channel.name}")
        }
    }

    fun reverseEpgProgrammeOrNot(channel: Channel, programme: EpgProgramme) {
        val reverse = settingsViewModel.epgChannelReserveList.firstOrNull {
            it.test(channel, programme)
        }

        if (reverse != null) {
            settingsViewModel.epgChannelReserveList =
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList - reverse)
            Snackbar.show("取消预约：${reverse.channel} - ${reverse.programme}")
        } else {
            val newReserve = EpgProgrammeReserve(
                channel = channel.name,
                programme = programme.title,
                startAt = programme.startAt,
                endAt = programme.endAt,
            )

            settingsViewModel.epgChannelReserveList =
                EpgProgrammeReserveList(settingsViewModel.epgChannelReserveList + newReserve)
            Snackbar.show("已预约：${channel.name} - ${programme.title}")
        }
    }

    fun supportPlayback(
        channel: Channel = _currentChannel,
        urlIdx: Int? = _currentChannelUrlIdx,
    ): Boolean {
        val currentUrlIdx = getUrlIdx(channel.urlList, urlIdx)
        return ChannelUtil.urlSupportPlayback(channel.urlList[currentUrlIdx])
    }
}

@Composable
fun rememberMainContentState(
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    videoPlayerState: VideoPlayerState = rememberVideoPlayerState(),
    channelGroupListProvider: () -> ChannelGroupList = { ChannelGroupList() },
    settingsViewModel: SettingsViewModel = viewModel(),
):MainContentState {
    val context = LocalContext.current.applicationContext  // 先在Composable体内获取context
    return remember {
        MainContentState(
            coroutineScope = coroutineScope,
            videoPlayerState = videoPlayerState,
            channelGroupListProvider = channelGroupListProvider,
            settingsViewModel = settingsViewModel,
            context = context,  // 传递context
        )
    }
}

private fun getUrlHost(url: String): String {
    return url.split("://").getOrElse(1) { "" }.split("/").firstOrNull() ?: url
}