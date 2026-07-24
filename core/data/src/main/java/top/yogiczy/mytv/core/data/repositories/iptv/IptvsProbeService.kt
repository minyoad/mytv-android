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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.IOException
import java.util.concurrent.TimeUnit

@OptIn(InternalSerializationApi::class)
@Serializable
data class SimpleChannel(val id: String, val name: String, val sources: List<SimpleSource>)

@OptIn(InternalSerializationApi::class)
@Serializable
data class SimpleSource(val id: String, val url: String)

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
 * IPTVS 线路探测服务
 */
object IptvsProbeService {
    private val log = Logger.create("IptvsProbeService")
    private val client = OkHttp.client.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
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
        isp: String,
        province: String,
        onlyActive: Boolean = true,
        onComplete: (successCount: Int) -> Unit = {}
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                log.i("正在拉取最新的 IPTV 电视频道播放线路: $serverBaseUrl")
                val channels = fetchChannels(serverBaseUrl, onlyActive)
                if (channels.isEmpty()) {
                    log.w("拉取到的可测试频道和线路为空")
                    withContext(Dispatchers.Main) { onComplete(0) }
                    return@launch
                }

                val allSourcesToTest = mutableListOf<Triple<String, String, String>>()
                for (channel in channels) {
                    for (source in channel.sources) {
                        allSourcesToTest.add(Triple(source.id, channel.id, source.url))
                    }
                }

                log.i("待测物理流: ${allSourcesToTest.size}，开始并发测速...")
                val testResults = runConcurrentProbe(allSourcesToTest, maxConcurrency = 8)

                log.i("测速完毕，上报结果: 健康可用 ${testResults.count { it.status == "active" }} 条")
                val count = submitReport(serverBaseUrl, isp, province, testResults)
                log.i("数据上报完成，生效 $count 条报告")

                withContext(Dispatchers.Main) {
                    onComplete(count)
                }
            } catch (e: Exception) {
                log.e("测速任务失败", e)
                withContext(Dispatchers.Main) {
                    onComplete(-1)
                }
            }
        }
    }

    private fun fetchChannels(baseUrl: String, onlyActive: Boolean): List<SimpleChannel> {
        val url = if (onlyActive) "$baseUrl/api/channels?status=testable" else "$baseUrl/api/channels"
        val request = Request.Builder().url(url).get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败: ${response.code}")
            val bodyString = response.body?.string() ?: return emptyList()
            return json.decodeFromString<List<SimpleChannel>>(bodyString)
        }
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

                    val startTime = System.currentTimeMillis()
                    var status = "inactive"
                    var latency = 9999L

                    try {
                        val headRequest = Request.Builder().url(url).head().build()
                        client.newCall(headRequest).execute().use { response ->
                            if (response.isSuccessful || response.code in 300..399) {
                                status = "active"
                                latency = System.currentTimeMillis() - startTime
                            }
                        }
                    } catch (e: Exception) {
                        status = "inactive"
                    }

                    ProbeResult(sourceId, channelId, status, if (status == "active") latency else 9999L)
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

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                log.e("上报结果被拒绝: ${response.code}")
                return 0
            }
            val resBody = response.body?.string() ?: return 0
            return json.decodeFromString<ReportResponse>(resBody).count
        }
    }
}
