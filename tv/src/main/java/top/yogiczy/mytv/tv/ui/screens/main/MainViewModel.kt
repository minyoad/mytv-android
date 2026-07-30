package top.yogiczy.mytv.tv.ui.screens.main

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
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
    private val log = Logger.create(javaClass.simpleName)
    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Loading())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val userInteractionFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val idleSettingsFlow = MutableStateFlow(
        Pair(Configs.epgRefreshIdleEnable, Configs.epgRefreshIdleDelay)
    )

    // Add property to store last EPG update timestamp
    private var lastEpgUpdateTimestamp: Long = 0

    init {
        init()
        initIdleRefresh()
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
                _uiState.value = MainUiState.Ready(channelGroupList = hybridChannel(cachedChannelGroupList))
                
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

    @OptIn(FlowPreview::class)
    private fun initIdleRefresh() {
        viewModelScope.launch {
            userInteractionFlow.emit(Unit)
            
            // 使用 combine 监听交互和设置变化
            kotlinx.coroutines.flow.combine(userInteractionFlow, idleSettingsFlow) { _, settings ->
                settings
            }
                .debounce { (enable, delay) -> if (enable) delay else Long.MAX_VALUE }
                .collect { (enable, _) ->
                    if (enable) {
                        refreshEpg()
                    }
                }
        }
    }

    fun setIdleSettings(enable: Boolean, delay: Long) {
        idleSettingsFlow.value = Pair(enable, delay)
    }

    fun onUserInteraction() {
        viewModelScope.launch {
            userInteractionFlow.emit(Unit)
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

    // Add method to handle app resume
    fun onAppResume() {
        viewModelScope.launch {
            val currentDate = LocalDate.now()
            val lastUpdateDate = java.time.Instant.ofEpochMilli(lastEpgUpdateTimestamp)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate()

            if (lastEpgUpdateTimestamp != 0L && lastUpdateDate != currentDate) {
                // Date changed, refresh EPG
                refreshEpg()
            } else if (Configs.epgRefreshIdleEnable &&
                lastEpgUpdateTimestamp != 0L &&
                System.currentTimeMillis() - lastEpgUpdateTimestamp > Configs.epgRefreshIdleDelay
            ) {
                // Idle time exceeded while in background, refresh EPG
                refreshEpg()
            }
        }
    }
    private fun onChannelChanged() {
        viewModelScope.launch {
            Configs.iptvChannelUrlIdx= emptyMap()
        }
    }

    private fun probeIptvs() {
        if (!Configs.iptvAutoProbe) return

        val currentSource = Configs.iptvSourceCurrent
        if (currentSource.url.contains("iptvs.mybacc.com")) {
            viewModelScope.launch {
                delay(10000) // 延迟10秒，等播放稳定后再开始探测
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
            iptvRepository.setDataChanged({ onChannelChanged() })
            emit(
                iptvRepository.getChannelGroupList(cacheTime = Configs.iptvSourceCacheTime)
            )
        }
            .retryWhen { _, attempt ->
                if (attempt >= Constants.HTTP_RETRY_COUNT) return@retryWhen false

                if (showLoading) {
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
                if (currentState !is MainUiState.Ready || currentState.channelGroupList != it) {
                    _uiState.value = MainUiState.Ready(
                        channelGroupList = it,
                        epgList = (currentState as? MainUiState.Ready)?.epgList ?: EpgList()
                    )
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

        if (_uiState.value is MainUiState.Ready) {
            EpgList.clearCache()
            val channelGroupList = (_uiState.value as MainUiState.Ready).channelGroupList

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
                    _uiState.value = (_uiState.value as MainUiState.Ready).copy(epgList = epgList)
                }
                .collect()
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