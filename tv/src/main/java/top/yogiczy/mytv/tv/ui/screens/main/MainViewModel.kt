package top.yogiczy.mytv.tv.ui.screens.main

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList.Companion.channelList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.repositories.epg.EpgRepository
import top.yogiczy.mytv.core.data.repositories.iptv.IptvRepository
import top.yogiczy.mytv.core.data.repositories.iptv.IptvsProbeService
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.data.utils.Constants
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.tv.ui.material.Snackbar
import top.yogiczy.mytv.tv.ui.screens.channel.components.ChannelLogoRequest
import top.yogiczy.mytv.tv.ui.screens.channel.components.logoPrefetchUrls
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

    private val channelRefreshMutex = Mutex()
    private val epgTask = LatestTask<EpgRequest>(viewModelScope)
    private var initializationJob: Job? = null
    private var generation = 0L
    private var channelListSource: IptvSource? = null

    // 最近一次成功加载的频道列表缓存，用于 EPG 刷新兜底
    private var lastKnownChannelGroupList: ChannelGroupList? = null

    init {
        // 订阅跨 ViewModel 的 EPG 缓存失效信号（如清缓存后通知重新拉取）
        viewModelScope.launch {
            EPG_CACHE_INVALIDATE_SIGNAL.collect { invalidateEpg() }
        }
        startChannelPeriodicCheck()
        init()
    }

    fun init() {
        generation++
        val requestGeneration = generation
        val source = Configs.iptvSourceCurrent
        initializationJob?.cancel()
        epgTask.cancel()
        lastKnownChannelGroupList = null
        channelListSource = null
        initializationJob = viewModelScope.launch {
            _uiState.value = MainUiState.Loading()

            // 1. 尝试并行加载混合模式配置
            val hybridJob = launch {
                if (Configs.iptvHybridMode != Configs.IptvHybridMode.DISABLE) {
                    ChannelUtil.loadHybridWebViewUrlFromRemote(Constants.WEBVIEW_CHANNELS_URL)
                }
            }

            // 2. 尝试从缓存快速恢复频道列表
            val cachedChannelGroupList = IptvRepository(source).getCachedChannelGroupList()
            if (generation != requestGeneration || Configs.iptvSourceCurrent != source) return@launch
            if (cachedChannelGroupList != null) {
                log.i("从缓存中快速恢复频道列表")
                val hydrated = hybridChannel(cachedChannelGroupList)
                if (generation != requestGeneration || Configs.iptvSourceCurrent != source) return@launch
                channelListSource = source
                lastKnownChannelGroupList = hydrated  // 立即缓存，供后续 EPG 兜底使用
                _uiState.value = MainUiState.Ready(channelGroupList = hydrated)

                // Local EPG is independent of hybrid config and channel-network refresh.
                requestEpgRefresh()
                launch {
                    val changed = refreshChannel(showLoading = false)
                    if (changed) requestEpgRefresh()
                }
            } else {
                // Fetch channels immediately; hybrid configuration must not delay the first list.
                refreshChannel(showLoading = true)
                requestEpgRefresh()
            }

            launch {
                hybridJob.join()
                if (generation == requestGeneration && Configs.iptvSourceCurrent == source &&
                    Configs.iptvHybridMode != Configs.IptvHybridMode.DISABLE) {
                    val changed = refreshChannel(showLoading = false)
                    if (changed) requestEpgRefresh()
                }
            }

            probeIptvs()
        }
    }

    suspend fun preloadLogos(context: Context, groups: ChannelGroupList, current: Channel?) {
        if (!Configs.uiShowChannelLogo) return
        val loader = context.applicationContext.imageLoader
        val permits = Semaphore(2)
        coroutineScope {
            logoPrefetchUrls(groups, current).map { url ->
                async {
                    permits.withPermit { loader.execute(ChannelLogoRequest.create(context.applicationContext, url)) }
                }
            }.awaitAll()
        }
    }

    /** Repository policy decides whether the daily refresh boundary has been reached. */
    fun onAppResume() {
        requestEpgRefresh()
    }

    private fun invalidateEpg() {
        requestEpgRefresh()
    }

    /**
     * 周期检查直播源是否有更新。
     *
     * 电视端 App 通常连续在前台运行数小时甚至数天，Activity 不重建、onAppResume
     * 也不会触发，导致 init() 中的过期检查只有冷启动时才执行一次，服务器更新无法感知。
     * 这里定时唤起 refreshChannel；是否真正发起网络请求由 IptvRepository 侧的
     * cacheTime 窗口节流（未过期直接读缓存，过期才走 ETag 协商），
     * 因此定时唤起本身不会产生额外请求压力。
     */
    private fun startChannelPeriodicCheck() {
        viewModelScope.launch {
            while (true) {
                delay(Constants.IPTV_SOURCE_CHECK_INTERVAL)
                refreshChannel(showLoading = false)
                // Also refresh after the daily EPG boundary while the TV stays in the foreground.
                requestEpgRefresh()
            }
        }
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

    /**
     * 拉取直播源。是否真正发起网络请求由 IptvRepository 的 cacheTime 窗口决定。
     *
     * @return 频道列表是否发生变化（false 表示与上次一致、列表为空或拉取失败）
     */
    private suspend fun refreshChannel(showLoading: Boolean = true): Boolean = channelRefreshMutex.withLock {
        val source = Configs.iptvSourceCurrent
        val requestGeneration = generation
        fun isCurrent() = source == Configs.iptvSourceCurrent && requestGeneration == generation
        var changed = false

        flow {
            val iptvRepository = IptvRepository(source)
            emit(iptvRepository.getChannelGroupList(cacheTime = Configs.iptvSourceCacheTime))
        }
            .retryWhen { _, attempt ->
                if (!isCurrent() || attempt >= Constants.HTTP_RETRY_COUNT) return@retryWhen false

                // 只在首次重试时切换 UI 状态，避免反复重组整个主屏幕导致按键排队卡顿
                if (showLoading && attempt == 0L) {
                    _uiState.value =
                        MainUiState.Loading("获取远程直播源(${attempt + 1}/${Constants.HTTP_RETRY_COUNT})...")
                }
                delay(Constants.HTTP_RETRY_INTERVAL)
                true
            }
            .catch {
                if (!isCurrent()) return@catch
                if (showLoading || _uiState.value !is MainUiState.Ready) {
                    _uiState.value = MainUiState.Error(it.message)
                } else {
                    log.e("后台刷新直播源失败", it)
                }
            }
            .map { hybridChannel(it) }
            .map {
                if (!isCurrent()) return@map it
                // 只有当数据真的变化时才更新 UI，避免冗余刷新
                val currentState = _uiState.value
                val isPopulated = it.channelList.isNotEmpty()

                if (isPopulated) {
                    // 缓存最近一次成功的频道列表，用于 refreshEpg 兜底（避免 UI 进 Error 后 EPG 永久不刷新）
                    lastKnownChannelGroupList = it
                    channelListSource = source
                }

                if (currentState !is MainUiState.Ready || (isPopulated && currentState.channelGroupList != it)) {
                    _uiState.value = MainUiState.Ready(
                        channelGroupList = it,
                        epgList = (currentState as? MainUiState.Ready)?.epgList ?: EpgList()
                    )
                    changed = true
                    // 直播源已更新，假定失效线路已从新列表中移除，旧的线路索引记忆不再有效，
                    // 全部复位到 0；后续某条线路播放成功时再由 setIptvChannelUrlIdx 重新记录。
                    Configs.iptvChannelUrlIdx = emptyMap()
                    // 频道 id 每次解析都会重新编号，通知播放层把当前频道重新映射到新列表
                    CHANNEL_LIST_UPDATED_SIGNAL.tryEmit(it)
                } else if (!isPopulated && !showLoading) {
                    log.w("后台刷新直播源获取到空列表，已忽略以防止 UI 变空")
                }
                it
            }
            .collect()

        changed
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

    private data class EpgRequest(
        val generation: Long,
        val iptvSource: IptvSource,
        val source: EpgSource,
        val channelNames: Set<String>,
        val threshold: Int,
    )

    private fun currentEpgRequest(): EpgRequest? {
        if (!Configs.epgEnable) return null
        val source = Configs.iptvSourceCurrent
        if (channelListSource != source) return null
        val groups = lastKnownChannelGroupList ?: return null
        if (groups.channelList.isEmpty()) return null
        val epg = groups.epgUrl?.takeIf { it.isNotBlank() }?.let { EpgSource("直播源自带", it) }
            ?: Configs.epgSourceCurrent
        return EpgRequest(generation, source, epg, groups.channelList.map { it.epgName }.toSet(),
            Configs.epgRefreshTimeThreshold)
    }

    private fun requestEpgRefresh() {
        val request = currentEpgRequest() ?: run { epgTask.cancel(); return }
        epgTask.start(request) { refreshEpg(request) }
    }

    private suspend fun refreshEpg(request: EpgRequest) {
        fun isCurrent() = request == currentEpgRequest()

        EpgRepository(request.source).observeEpgList(
            filteredChannels = request.channelNames.toList(),
            refreshTimeThreshold = request.threshold,
        )
            .retryWhen { _, attempt ->
                if (!isCurrent() || attempt >= Constants.HTTP_RETRY_COUNT) false
                else { delay(Constants.HTTP_RETRY_INTERVAL); true }
            }
            .catch {
                if (isCurrent()) {
                    // Keep the displayed EPG on failure. Never turn an exception into empty success.
                    log.e("节目单刷新失败，保留当前节目单", it)
                    Snackbar.show("节目单刷新失败，已保留原有数据", type = SnackbarType.ERROR)
                }
            }
            .collect { result ->
                if (!isCurrent()) return@collect
                val current = _uiState.value as? MainUiState.Ready ?: return@collect
                result.refreshError?.let { log.w("节目单刷新失败，使用已验证的过期缓存", it) }
                if (result.refreshError != null && current.epgList.isNotEmpty()) return@collect
                EpgList.clearCache()
                _uiState.value = current.copy(epgList = result.value)
            }
    }

    companion object {
        // 进程级 SharedFlow：用于跨 ViewModel 通信。
        // 当前用途：SettingsViewModel.clearCache() 后通知 MainViewModel EPG 缓存失效。
        // Retain the invalidation until a ViewModel subscribes.
        private val EPG_CACHE_INVALIDATE_SIGNAL = MutableSharedFlow<Unit>(replay = 1)

        /**
         * 通知 EPG 缓存已失效。供外部模块（如清缓存）调用，
         * MainViewModel 监听后会重新读取缓存并按需拉取 EPG。
         */
        fun notifyEpgCacheInvalidated() {
            EPG_CACHE_INVALIDATE_SIGNAL.tryEmit(Unit)
        }

        /**
         * 直播源刷新后频道列表发生变化的信号，携带最新的完整频道列表。
         *
         * 播放层（MainContent）监听后调用 MainContentState.relocateCurrentChannel()，
         * 按频道名把当前播放频道重新绑定到新列表中的对应条目。
         * 之所以不直接监听 uiState 的列表变化，是因为 UI 上的列表还叠加了
         * 分组隐藏/省份过滤，切换这些设置也会导致列表变化，但并不应该触发重定位。
         */
        private val CHANNEL_LIST_UPDATED_SIGNAL = MutableSharedFlow<ChannelGroupList>(extraBufferCapacity = 1)
        val channelListUpdated: SharedFlow<ChannelGroupList> = CHANNEL_LIST_UPDATED_SIGNAL.asSharedFlow()
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
