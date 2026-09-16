package top.yogiczy.mytv.tv.ui.screens.main

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.data.repositories.iptv.IptvsProbeService
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.material.SnackbarType
import top.yogiczy.mytv.tv.ui.utils.Configs
import top.yogiczy.mytv.tv.ui.utils.IJKProbe
import java.time.LocalDate

class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val log = Logger.create(MainViewModel::class.java.simpleName)
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // 最近一次成功拉取 EPG 的时间戳（毫秒）
    // 用于 onAppResume 判断是否跨日，以及 invalidateEpg 后重置
    private var lastEpgUpdateTimestamp: Long = 0

    // 最近一次成功加载的频道列表缓存，用于 EPG 刷新兜底
    private var lastKnownChannelGroupList: ChannelGroupList? = null

    init {
        // 订阅跨 ViewModel 的 EPG 缓存失效信号（如清缓存后通知重新拉取）
        viewModelScope.launch {
            EPG_CACHE_INVALIDATE_SIGNAL.collect { invalidateEpg() }
        }
        init()
    }

    fun init() {
        viewModelScope.launch {
            _uiState.value = MainUiState.Loading()

            // 1. 尝试并行加载混合模式配置
            val hybridJob = launch {
                if (Configs.iptvHybridMode != Configs.IptvHybridMode.DISABLE) {
                    ChannelUtil.loadHybridWebViewUrlFromRemote(Constants.WEBVIEW_CHANNELS_URL)
                }
            }

            // 2. 尝试从缓存快速恢复频道列表
            val cachedChannelGroupList = IptvRepository(Configs.iptvSourceCurrent).getCachedChannelGroupList()
            if (cachedChannelGroupList != null) {
                log.i("从缓存中快速恢复频道列表")
                val hydrated = hybridChannel(cachedChannelGroupList)
                lastKnownChannelGroupList = hydrated  // 立即缓存，供后续 EPG 兜底使用
                _uiState.value = MainUiState.Ready(channelGroupList = hydrated)

                // 缓存恢复后并行拉取最新数据
                launch {
                    hybridJob.join() // 确保混合模式配置加载完再更新频道
                    refreshChannel(showLoading = false)
                    refreshEpg()
                }
            } else {
                // 无缓存时，顺序执行以保证首次加载体验
                hybridJob.join()
                refreshChannel(showLoading = true)
                refreshEpg()
            }

            probeIptvs()
        }
    }

    fun preloadLogos(context: Context, channelGroupList: ChannelGroupList) {
        if (!Configs.uiShowChannelLogo) return

        viewModelScope.launch(Dispatchers.IO) {
            channelGroupList.channelList.forEach { channel ->
                if (!channel.logo.isNullOrBlank()) {
                    val request = ImageRequest.Builder(context)
                        .data(channel.logo)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                    context.imageLoader.enqueue(request)
                }
            }
        }
    }

    /**
     * 应用从后台回到前台时，按需刷新 EPG。
     *
     * 触发条件（任一满足即刷新）：
     * 1. **跨日**：本地 EPG 是前一天的数据（EPG 服务器通常一天更新一次，无意义高频刷新）
     * 2. **本地没有 EPG**：从未成功拉取过，或当前 epgList 为空（用户清缓存、缓存被系统清理等场景）
     *
     * 这两个条件之外（本地有 EPG 且仍在同一天），不触发刷新。
     */
    fun onAppResume() {
        viewModelScope.launch {
            val currentDate = LocalDate.now()
            val lastUpdateDate = lastEpgUpdateTimestamp.takeIf { it != 0L }
                ?.let {
                    java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                }

            // 条件 1: 跨日 → 刷新
            val isDateChanged = lastUpdateDate != null && lastUpdateDate != currentDate

            // 条件 2: 本地没 EPG → 刷新
            // - 从未成功拉过（lastEpgUpdateTimestamp == 0L）
            // - 或当前 uiState.epgList 为空（拉到但无数据，或被清空）
            val hasNoEpg = lastEpgUpdateTimestamp == 0L ||
                (_uiState.value as? MainUiState.Ready)?.epgList?.isEmpty() == true

            if (isDateChanged || hasNoEpg) {
                refreshEpg()
            }
        }
    }

    /**
     * 标记 EPG 缓存已失效并立即重新拉取。
     * 供外部模块（如 SettingsViewModel.clearCache）通过 notifyEpgCacheInvalidated() 触发。
     */
    private fun invalidateEpg() {
        lastEpgUpdateTimestamp = 0L
        viewModelScope.launch { refreshEpg() }
    }

    private fun probeIptvs() {
        if (!Configs.iptvAutoProbe) return

        val today = LocalDate.now().toString()
        if (Configs.iptvAutoProbeLastDate != today) {
            Configs.iptvAutoProbeLastDate = today
            Configs.iptvAutoProbeDailyCount = 0
        }

        if (Configs.iptvAutoProbeDailyCount >= Configs.iptvAutoProbeDailyLimit) {
            log.i("今日自动线路探测次数已达限额 (${Configs.iptvAutoProbeDailyLimit})，跳过")
            return
        }

        val currentSource = Configs.iptvSourceCurrent
        if (currentSource.url.contains("iptvs.mybacc.com")) {
            viewModelScope.launch {
                delay(10000) // 延迟10秒，等播放稳定后再开始探测
                
                Configs.iptvAutoProbeDailyCount++
                val baseUrl = currentSource.url.split("/api/").first()
                IptvsProbeService.startProbe(
                    serverBaseUrl = baseUrl,
                    deepProbe = { url -> IJKProbe.probe(getApplication(), url) }
                )
            }
        }
    }

    private suspend fun refreshChannel(showLoading: Boolean = true) {
        flow {
            val iptvRepository = IptvRepository(Configs.iptvSourceCurrent)
            emit(iptvRepository.getChannelGroupList(cacheTime = Configs.iptvSourceCacheTime))
        }
            .retryWhen { _, attempt ->
                if (attempt >= Constants.HTTP_RETRY_COUNT) return@retryWhen false

                // 只在首次重试时切换 UI 状态，避免反复重组整个主屏幕导致按键排队卡顿
                if (showLoading && attempt == 0L) {
                    _uiState.value =
                        MainUiState.Loading("获取远程直播源(${attempt + 1}/${Constants.HTTP_RETRY_COUNT})...")
                }
                delay(Constants.HTTP_RETRY_INTERVAL)
                true
            }
            .catch {
                if (showLoading || _uiState.value !is MainUiState.Ready) {
                    _uiState.value = MainUiState.Error(it.message)
                } else {
                    log.e("后台刷新直播源失败", it)
                }
            }
            .map { hybridChannel(it) }
            .map {
                // 只有当数据真的变化时才更新 UI，避免冗余刷新
                val currentState = _uiState.value
                val isPopulated = it.channelList.isNotEmpty()

                if (isPopulated) {
                    // 缓存最近一次成功的频道列表，用于 refreshEpg 兜底（避免 UI 进 Error 后 EPG 永久不刷新）
                    lastKnownChannelGroupList = it
                }

                if (currentState !is MainUiState.Ready || (isPopulated && currentState.channelGroupList != it)) {
                    _uiState.value = MainUiState.Ready(
                        channelGroupList = it,
                        epgList = (currentState as? MainUiState.Ready)?.epgList ?: EpgList()
                    )
                } else if (!isPopulated && !showLoading) {
                    log.w("后台刷新直播源获取到空列表，已忽略以防止 UI 变空")
                }
                it
            }
            .collect()
    }

    private suspend fun hybridChannel(channelGroupList: ChannelGroupList) =
        withContext(Dispatchers.Default) {
            val hybridMode = Configs.iptvHybridMode
            return@withContext when (hybridMode) {
                Configs.IptvHybridMode.DISABLE -> channelGroupList
                Configs.IptvHybridMode.IPTV_FIRST -> {
                    ChannelGroupList(
                        value = channelGroupList.map { group ->
                            group.copy(channelList = ChannelList(group.channelList.map { channel ->
                                channel.copy(
                                    urlList = channel.urlList.plus(
                                        ChannelUtil.getHybridWebViewUrl(channel.name) ?: emptyList()
                                    )
                                )
                            }))
                        },
                        epgUrl = channelGroupList.epgUrl,
                    )
                }

                Configs.IptvHybridMode.HYBRID_FIRST -> {
                    ChannelGroupList(
                        value = channelGroupList.map { group ->
                            group.copy(channelList = ChannelList(group.channelList.map { channel ->
                                channel.copy(
                                    urlList = (ChannelUtil.getHybridWebViewUrl(channel.name)
                                        ?: emptyList())
                                        .plus(channel.urlList)
                                )
                            }))
                        },
                        epgUrl = channelGroupList.epgUrl,
                    )
                }
            }
        }

    private suspend fun refreshEpg() {
        if (!Configs.epgEnable) return

        // 优先使用入参 channelGroupList（来自 init），其次用最近一次成功的列表，最后才从 uiState 取
        val channelGroupList = when {
            _uiState.value is MainUiState.Ready -> (_uiState.value as MainUiState.Ready).channelGroupList
            else -> lastKnownChannelGroupList
        } ?: return

        if (channelGroupList.channelList.isEmpty()) return

        EpgList.clearCache()

        flow {
            val epgUrl = channelGroupList.epgUrl
            val epgSource = if (!epgUrl.isNullOrBlank()) {
                log.i("优先使用直播源自带节目单: $epgUrl")
                EpgSource(name = "直播源自带", url = epgUrl)
            } else {
                log.i("使用系统设置节目单: ${Configs.epgSourceCurrent.url}")
                Configs.epgSourceCurrent
            }

            emit(
                EpgRepository(epgSource).getEpgList(
                    filteredChannels = channelGroupList.channelList.map { it.epgName },
                    refreshTimeThreshold = Configs.epgRefreshTimeThreshold,
                )
            )
        }
            .retry(Constants.HTTP_RETRY_COUNT) { delay(Constants.HTTP_RETRY_INTERVAL); true }
            .catch {
                emit(EpgList())
                Snackbar.show("节目单获取失败，请检查网络连接", type = SnackbarType.ERROR)
            }
            .map { epgList ->
                // Record current timestamp when EPG is successfully updated
                lastEpgUpdateTimestamp = System.currentTimeMillis()
                // 即使 uiState 不是 Ready（如 Error），也用最新的 channelGroupList 升级到 Ready
                val current = _uiState.value
                _uiState.value = if (current is MainUiState.Ready) {
                    current.copy(epgList = epgList)
                } else {
                    MainUiState.Ready(channelGroupList = channelGroupList, epgList = epgList)
                }
            }
            .collect()
    }

    companion object {
        // 进程级 SharedFlow：用于跨 ViewModel 通信。
        // 当前用途：SettingsViewModel.clearCache() 后通知 MainViewModel EPG 缓存失效。
        // extraBufferCapacity = 1 保证 emit 不会因暂无订阅者而丢失（最多缓存一个事件）。
        private val EPG_CACHE_INVALIDATE_SIGNAL = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

        /**
         * 通知 EPG 缓存已失效。供外部模块（如清缓存）调用，
         * MainViewModel 监听后会重置时间戳并立即重新拉取 EPG。
         */
        fun notifyEpgCacheInvalidated() {
            EPG_CACHE_INVALIDATE_SIGNAL.tryEmit(Unit)
        }
    }
}

sealed interface MainUiState {
    data class Loading(val message: String? = null) : MainUiState
    data class Error(val message: String? = null) : MainUiState
    data class Ready(
        val channelGroupList: ChannelGroupList = ChannelGroupList(),
        val epgList: EpgList = EpgList(),
    ) : MainUiState
}