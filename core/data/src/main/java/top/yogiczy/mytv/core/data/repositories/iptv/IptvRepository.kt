package top.yogiczy.mytv.core.data.repositories.iptv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.entities.iptvsource.IptvSource
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.network.await
import top.yogiczy.mytv.core.data.repositories.ValidatedFileCache
import top.yogiczy.mytv.core.data.repositories.iptv.parser.DefaultIptvParser
import top.yogiczy.mytv.core.data.repositories.iptv.parser.IptvParser
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.core.data.utils.Globals
import top.yogiczy.mytv.core.util.utils.removeBom
import java.io.File
import java.io.IOException


class IdGenerator {
    private var currentId = 0

    fun nextId(): Int {
        return ++currentId
    }

    fun reset() {
        currentId = 0
    }
}

/**
 * 直播源数据获取
 */
class IptvRepository(
    private val source: IptvSource,
) {
    private val log = Logger.create(IptvRepository::class.java.simpleName)

    private val file = if (source.isLocal) File(source.url)
        else File(Globals.cacheDir, "iptv-${source.url.hashCode().toUInt().toString(16)}.txt")
    private val cache = ValidatedFileCache(file)

    /**
     * 获取直播源数据
     */
    private suspend fun fetchSource(temporary: File, etag: String?): ValidatedFileCache.Download {
        log.d("获取远程直播源: $source")

        val client = OkHttp.client
        val request = Request.Builder().url(source.url).apply {
            if (!etag.isNullOrEmpty()) {
                header("If-None-Match", etag)
            }
        }.build()

        try {
            client.newCall(request).await().use { response ->
                if (response.code == 304) {
                    log.i("直播源内容未变动 (304 Not Modified): $source")
                    return ValidatedFileCache.Download.NotModified
                }

                if (!response.isSuccessful) throw Exception("${response.code}: ${response.message}")

                return withContext(Dispatchers.IO) {
                    temporary.writeText(response.body?.string() ?: throw IOException("直播源响应体为空"))
                    ValidatedFileCache.Download.Modified(response.header("ETag"))
                }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.e("获取直播源失败", ex)
            throw Exception("获取直播源失败，请检查网络连接", ex)
        }
    }

    /**
     * 获取直播源分组列表
     */
    suspend fun getChannelGroupList(cacheTime: Long): ChannelGroupList {
        try {
            if (source.isLocal) return cache.read { parseData(it.readText()) }
                ?: throw IOException("本地直播源不存在")
            val result = cache.load(
                isExpired = { System.currentTimeMillis() - it >= cacheTime },
                validate = { parseData(it.readText()) },
                download = ::fetchSource,
            )
            result.refreshError?.let { log.w("刷新失败，保留直播源缓存", it) }
            return result.value
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            log.e("获取直播源失败", ex)
            throw Exception(ex)
        }
    }

    /**
     * 获取缓存的直播源分组列表
     */
    suspend fun getCachedChannelGroupList(): ChannelGroupList? {
        return try {
            withContext(Dispatchers.IO) {
                cache.read { parseData(it.readText()) }
            }
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            null
        }
    }

    private suspend fun parseData(rawData: String): ChannelGroupList {
        return withContext(Dispatchers.IO) {
            val sourceData = rawData.removeBom()

            val parser = IptvParser.instances.firstOrNull {
                it !is DefaultIptvParser && it.isSupport(source.url, sourceData)
            } ?: throw IOException("不支持当前直播源格式")
            val startTime = System.currentTimeMillis()
            val groupList = parser.parse(sourceData)
            if (groupList.none { group -> group.channelList.any { channel -> channel.urlList.any(String::isNotBlank) } }) {
                throw IOException("直播源没有可用频道")
            }

            // 在获取到频道列表后，统一生成数字序号 ID
            val idGenerator = IdGenerator()
            val groupListWithIds = ChannelGroupList(
                value = groupList.map { group ->
                    group.copy(channelList = ChannelList(group.channelList.map { channel ->
                        channel.copy(id = idGenerator.nextId().toString())
                    }))
                },
                epgUrl = groupList.epgUrl,
            )

            log.i(
                listOf(
                    "解析直播源（${source.name}）完成：${groupList.size}个分组",
                    "${groupList.sumOf { it.channelList.size }}个频道",
                    "${groupList.sumOf { it.channelList.sumOf { channel -> channel.urlList.size } }}条线路",
                    "耗时：${System.currentTimeMillis() - startTime}ms",
                ).joinToString()
            )

            groupListWithIds
        }
    }

    suspend fun clearCache() {
        if (source.isLocal) return
        cache.clear()
    }
}
