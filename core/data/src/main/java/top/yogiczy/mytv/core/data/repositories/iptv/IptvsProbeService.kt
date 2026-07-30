package top.yogiczy.mytv.core.data.repositories.iptv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

@OptIn(InternalSerializationApi::class)
@Serializable
data class SimpleSourceItem(val id: String, val channelId: String, val url: String)

@OptIn(InternalSerializationApi::class)
@Serializable
data class SimpleSourceListResponse(val sources: List<SimpleSourceItem>)

@OptIn(InternalSerializationApi::class)
@Serializable
data class IpDetectResponse(val ip: String, val isp: String, val province: String)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ProbeResult(val sourceId: String, val channelId: String, val status: String, val latency: Long)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ReportPayload(val clientIsp: String, val clientProvince: String, val results: List<ProbeResult>)

@OptIn(InternalSerializationApi::class)
@Serializable
data class ReportResponse(val count: Int)

/**
 * 深度探测函数类型
 */
typealias DeepProbeHandler = suspend (url: String) -> Long?

/**
 * IPTVS 线路探测服务
 */
object IptvsProbeService {
    private val log = Logger.create("IptvsProbeService")
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) IPTVProbe/2.0"
    private const val PHASE_I_TIMEOUT = 2500L // 毫秒

    private val probeClient = OkHttp.client.newBuilder()
        .connectTimeout(PHASE_I_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(PHASE_I_TIMEOUT, TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .build()

    private val apiClient = OkHttp.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 开始探测任务
     */
    fun startProbe(
        serverBaseUrl: String,
        onlyActive: Boolean = true,
        deepProbe: DeepProbeHandler? = null,
        onComplete: (successCount: Int) -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var currentIsp = ""
                var currentProvince = ""

                // 自动检测当前设备网络环境
                log.i("正在自动检测本机 IP 与网络环境归属...")
                val detectUrl = serverBaseUrl.toHttpUrlOrNull()
                    ?.newBuilder()
                    ?.addPathSegments("api/sources/detect-ip")
                    ?.build() ?: throw IOException("无效的 BaseUrl: $serverBaseUrl")

                val request = Request.Builder().url(detectUrl).header("User-Agent", USER_AGENT).get().build()
                apiClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val detectData = json.decodeFromString<IpDetectResponse>(body)
                        currentIsp = detectData.isp
                        currentProvince = detectData.province
                        log.i("网络环境自动识别成功: $currentProvince - $currentIsp (IP: ${detectData.ip})")
                    } else {
                        throw IOException("无法获取网络环境 (HTTP ${response.code})")
                    }
                }

                var page = 1
                val limit = 100
                var totalSuccessCount = 0
                var hasMore = true

                while (hasMore) {
                    val currentPage = page
                    log.i("正在拉取最新的 IPTV 电视频道播放线路: $serverBaseUrl (第 $currentPage 页)")
                    
                    val result = runCatching {
                        val sources = fetchSourcesPage(serverBaseUrl, currentIsp, currentProvince, onlyActive, currentPage, limit)
                        
                        if (sources.isEmpty()) {
                            if (currentPage == 1) log.w("拉取到的可测试频道和线路为空")
                            hasMore = false
                            return@runCatching
                        }

                        val allSourcesToTest = sources.map { Triple(it.id, it.channelId, it.url) }

                        log.i("第 $currentPage 页待测物理流: ${allSourcesToTest.size}，开始并发测速（第一阶段：快速嗅探）...")
                        val fastResults = runConcurrentProbe(allSourcesToTest, maxConcurrency = 10)

                        var finalResults = fastResults

                        // 第二阶段：深度验证
                        if (deepProbe != null) {
                            val activeResults = fastResults.filter { it.status == "active" }
                            log.i("第 $currentPage 页进入第二阶段：深度验证（对 ${activeResults.size} 条线路进行播放测试）...")
                            
                            val deepResults = mutableListOf<ProbeResult>()
                            // 深度测试为了稳定性，采用低并发
                            val semaphore = Semaphore(1) 
                            val deferreds = activeResults.map { res ->
                                async {
                                    semaphore.withPermit {
                                        val url = allSourcesToTest.find { it.first == res.sourceId }?.third ?: ""
                                        val deepLatency = deepProbe(url)
                                        if (deepLatency != null) {
                                            res.copy(latency = deepLatency)
                                        } else {
                                            res.copy(status = "inactive", latency = 9999L)
                                        }
                                    }
                                }
                            }
                            deepResults.addAll(deferreds.awaitAll())
                            
                            // 合并结果：保留没进深度测试的（已标记为 inactive 的）和深度测试后的结果
                            finalResults = fastResults.filter { it.status != "active" } + deepResults
                        }

                        log.i("第 $currentPage 页测速完毕，上报结果: 健康可用 ${finalResults.count { it.status == "active" }} 条")
                        
                        // 稍微延迟一下，避开测速时的网络竞争
                        delay(500)
                        val count = submitReport(serverBaseUrl, currentIsp, currentProvince, finalResults)
                        log.i("第 $currentPage 页数据上报完成，生效 $count 条报告")
                        totalSuccessCount += count

                        if (sources.size < limit) {
                            hasMore = false
                        } else {
                            page++
                        }
                    }

                    result.onFailure { e ->
                        log.e("第 $currentPage 页处理失败", e)
                        // 如果是第一页就完全失败，则不再继续
                        if (currentPage == 1) {
                            hasMore = false
                        } else {
                            // 其他页面失败，尝试继续下一页
                            page++
                        }
                    }
                }

                log.i("所有测速任务完成，共计生效 $totalSuccessCount 条报告")
                withContext(Dispatchers.Main) {
                    onComplete(totalSuccessCount)
                }
            } catch (e: Exception) {
                log.e("测速任务失败", e)
                withContext(Dispatchers.Main) {
                    onComplete(-1)
                }
            }
        }
    }

    private fun fetchSourcesPage(
        baseUrl: String,
        isp: String,
        province: String,
        onlyActive: Boolean,
        page: Int,
        limit: Int
    ): List<SimpleSourceItem> {
        val httpUrl = baseUrl.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addPathSegments("api/sources/client-test-list")
            ?.addQueryParameter("isp", isp)
            ?.addQueryParameter("province", province)
            ?.addQueryParameter("onlyActive", onlyActive.toString())
            ?.addQueryParameter("page", page.toString())
            ?.addQueryParameter("limit", limit.toString())
            ?.build() ?: throw IOException("无效的 BaseUrl: $baseUrl")

        val request = Request.Builder().url(httpUrl).get().build()

        apiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败: ${response.code}")
            val bodyString = response.body?.string() ?: return emptyList()

            return try {
                json.decodeFromString<SimpleSourceListResponse>(bodyString).sources
            } catch (e: Exception) {
                log.e("解析 IPTV 线路列表失败, body: $bodyString", e)
                throw e
            }
        }
    }

    /**
     * 探测单条 URL (支持 HTTP HEAD 与 TCP 端口探测)
     */
    private fun probeUrl(url: String): Pair<String, Long> {
        val startTime = System.currentTimeMillis()
        try {
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                val headRequest = Request.Builder().url(url).header("User-Agent", USER_AGENT).head().build()
                try {
                    probeClient.newCall(headRequest).execute().use { response ->
                        // 2xx 成功 / 3xx 重定向 均视为源 URL 可达
                        if (response.isSuccessful || response.code in 300..399) {
                            return "active" to (System.currentTimeMillis() - startTime)
                        }
                    }
                } catch (e: Exception) {
                    // HEAD 请求超时或异常，尝试 TCP 探测保底
                }
            }

            // 非 HTTP 协议或 HTTP HEAD 失败后，尝试 TCP 端口探测
            val regex = Regex("^(\\w+://)?([^:/\\s?#]+)(:(\\d+))?")
            val match = regex.find(url)
            if (match != null) {
                val scheme = match.groupValues[1].lowercase().removeSuffix("://")
                val host = match.groupValues[2]
                val portStr = match.groupValues[4]

                val port = if (portStr.isNotEmpty()) {
                    portStr.toInt()
                } else {
                    when (scheme) {
                        "rtsp" -> 554
                        "rtmp" -> 1935
                        "https" -> 443
                        else -> 80
                    }
                }

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), PHASE_I_TIMEOUT.toInt())
                    return "active" to (System.currentTimeMillis() - startTime)
                }
            }
        } catch (e: Exception) {
            // 忽略异常，标记为 inactive
        }
        return "inactive" to 9999L
    }

    private suspend fun runConcurrentProbe(
        list: List<Triple<String, String, String>>,
        maxConcurrency: Int
    ): List<ProbeResult> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        val results = mutableListOf<Deferred<ProbeResult>>()

        for (item in list) {
            val task = async(Dispatchers.IO) {
                semaphore.withPermit {
                    val sourceId = item.first
                    val channelId = item.second
                    val url = item.third

                    val (status, latency) = probeUrl(url)
                    ProbeResult(sourceId, channelId, status, latency)
                }
            }
            results.add(task)
        }

        results.awaitAll()
    }

    private fun submitReport(
        baseUrl: String,
        isp: String,
        province: String,
        results: List<ProbeResult>
    ): Int {
        val payload = ReportPayload(isp, province, results)
        val requestBody = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/api/sources/client-test-results")
            .post(requestBody)
            .build()

        apiClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.e("上报结果被拒绝: ${response.code}")
                return 0
            }
            val resBody = response.body?.string() ?: return 0
            return json.decodeFromString<ReportResponse>(resBody).count
        }
    }
}
