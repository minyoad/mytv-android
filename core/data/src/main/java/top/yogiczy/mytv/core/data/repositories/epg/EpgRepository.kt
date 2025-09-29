package top.yogiczy.mytv.core.data.repositories.epg

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser

import top.yogiczy.mytv.core.data.entities.epg.Epg
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epg.EpgProgramme
import top.yogiczy.mytv.core.data.entities.epg.EpgProgrammeList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.network.await
import top.yogiczy.mytv.core.data.repositories.FileCacheRepository
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 节目单获取
 */
class EpgRepository(
    source: EpgSource,
) : FileCacheRepository("epg-${source.url.hashCode().toUInt().toString(16)}.json") {
    private val log = Logger.create(javaClass.simpleName)
    private val epgXmlRepository = EpgXmlRepository(source.url)

    /**
     * 解析节目单xml
     */
    private suspend fun parseFromXml(
        xmlString: String,
        filteredChannels: List<String> = emptyList(),
    ) = withContext(Dispatchers.Default) {
        val dateFormat = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.getDefault())
        val lowerFilteredChannels = filteredChannels.map { it.lowercase() }
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xmlString))
        }

        val epgMap = mutableMapOf<String, Epg>()
        var currentChannelId: String? = null

        fun getSafeText(): String {
            return try {
                parser.nextText().trim().replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
            } catch (e: Exception) {
                log.e("解析XML文本失败", e)
                ""
            }
        }

        // 定义时间格式,年份-月份-日期 小时:分钟
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            try {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "channel" -> {
                            currentChannelId = parser.getAttributeValue(null, "id")
                            parser.nextTag()
                            val channelName = getSafeText()

                            if (lowerFilteredChannels.isEmpty() ||
                                channelName.lowercase() in lowerFilteredChannels) {
                                epgMap[currentChannelId] = Epg(channelName, EpgProgrammeList())
                            }
                        }
                        "programme" -> {
                            val channelId = parser.getAttributeValue(null, "channel")
                            val startTime = parser.getAttributeValue(null, "start")
                            val stopTime = parser.getAttributeValue(null, "stop")
                            parser.nextTag()
                            val title = getSafeText()

                            epgMap[channelId]?.let { epg ->
                                val newProgramme = EpgProgramme(
                                    startAt = dateFormat.parse(startTime)?.time ?: 0,
                                    endAt = dateFormat.parse(stopTime)?.time ?: 0,
                                    title = title
                                )

                                // 添加去重检查：开始时间相同的节目不重复添加
                                val isDuplicate = epg.programmeList.any { prog ->
                                    timeFormat.format(prog.startAt) == timeFormat.format(newProgramme.startAt)
//                                    prog.title == newProgramme.title &&
//                                            prog.startAt == newProgramme.startAt &&
//                                            prog.endAt == newProgramme.endAt
                                }

                                if (!isDuplicate) {
                                    epgMap[channelId] = epg.copy(
                                        programmeList = epg.programmeList + newProgramme
                                    )
                                } else {
                                    log.d("发现重复节目: ${newProgramme.title} (${timeFormat.format(newProgramme.startAt)})")
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.e("解析XML标签失败", e)
                continue
            }
        }

        log.i("解析节目单完成，共${epgMap.size}个频道，${epgMap.values.sumOf { it.programmeList.size }}个节目")
        EpgList(epgMap.values.toList())
    }

    // Add this helper function to your project
    fun String.removeBom(): String {
        val bom = "\uFEFF"
        if (this.startsWith(bom)) {
            return this.removePrefix(bom)
        }
        return this
    }

    /**
     * 获取节目单列表
     */
    suspend fun getEpgList(
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ): EpgList = withContext(Dispatchers.Default) {
        try {
            if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < refreshTimeThreshold) {
                log.i("未到时间点，不刷新节目单")
                return@withContext EpgList()
            }

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

            val xmlJson = getOrRefresh({ lastModified, _ ->
                dateFormat.format(System.currentTimeMillis()) != dateFormat.format(lastModified)
            }) {
                val xmlString = epgXmlRepository.getEpgXml().removeBom()
                Json.encodeToString(
                    parseFromXml(
                        xmlString,
                        filteredChannels.map { it.lowercase() },
                    )
                )
            }

            return@withContext Json.decodeFromString(xmlJson)
        } catch (ex: Exception) {
            log.e("获取节目单失败", ex)
            throw Exception(ex)
        }
    }
}

/**
 * 节目单xml获取
 */
private class EpgXmlRepository(
    private val url: String
) : FileCacheRepository("epg-${url.hashCode().toUInt().toString(16)}.xml") {
    private val log = Logger.create(javaClass.simpleName)

    /**
     * 获取远程xml
     */
    private suspend fun fetchXml(): String {
        log.i("获取节目单xml: $url")

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        try {
            val response = client.newCall(request).await()

            if (!response.isSuccessful) throw Exception("${response.code}: ${response.message}")

            val fetcher = EpgFetcher.instances.first { it.isSupport(url) }
            return withContext(Dispatchers.IO) {
                fetcher.fetch(response)
            }
        } catch (ex: Exception) {
            log.e("获取节目单xml失败", ex)
            throw Exception("获取节目单xml失败，请检查网络连接", ex)
        }
    }

    /**
     * 获取xml
     */
    suspend fun getEpgXml(): String {
        return getOrRefresh(0) {
            fetchXml()
        }
    }
}
