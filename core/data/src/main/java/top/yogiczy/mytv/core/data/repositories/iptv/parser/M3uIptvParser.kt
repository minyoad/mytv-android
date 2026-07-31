package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.util.utils.removeBom

/**
 * m3u直播源解析
 */
class M3uIptvParser : IptvParser {

    override fun isSupport(url: String, data: String): Boolean {
        return data.removeBom().startsWith("#EXTM3U")
    }

    override suspend fun parse(data: String): ChannelGroupList = withContext(Dispatchers.Default) {
        val cleanData = data.removeBom()
        val lines = cleanData.split("\r\n", "\n")
        val iptvList = mutableListOf<IptvResponseItem>()

        var epgUrl: String? = null
        val header = lines.firstOrNull { it.startsWith("#EXTM3U") }
        if (header != null) {
            epgUrl = extractAttribute(header, "x-tvg-url")
                ?: extractAttribute(header, "url-tvg")
                ?: extractAttribute(header, "tvg-url")
        }

        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXTINF")) return@forEachIndexed

            val name = line.substringAfterLast(",").trim()
            val channelName = ChannelUtil.standardChannelName(extractAttribute(line, "tvg-name") ?: name)
            val groupName = extractAttribute(line, "group-title") ?: "其他"
            val logo = extractAttribute(line, "tvg-logo") ?: extractAttribute(line, "logo")
            
            // 💡 优化：向后查找真正的 URL，跳过空行和其他标签行
            var url: String? = null
            for (i in (index + 1)..<lines.size) {
                val nextLine = lines[i].trim()
                if (nextLine.isBlank()) continue
                if (nextLine.startsWith("#")) {
                    if (nextLine.startsWith("#EXTINF")) break // 遇到下一个频道标签，说明当前频道无 URL
                    continue
                }
                url = nextLine
                break
            }

            if (!url.isNullOrBlank()) {
                iptvList.add(
                    IptvResponseItem(
                        name = name,
                        channelName = channelName,
                        groupName = groupName,
                        url = url,
                        logo = logo,
                    )
                )
            }
        }

        return@withContext ChannelGroupList(
            value = iptvList.groupBy { it.groupName }.map { groupEntry ->
                ChannelGroup(
                    name = groupEntry.key,
                    channelList = ChannelList(groupEntry.value.groupBy { it.name }.map { nameEntry ->
                        Channel(
                            name = nameEntry.key,
                            epgName = nameEntry.value.first().channelName,
                            urlList = nameEntry.value.map { it.url }.distinct(),
                            logo = nameEntry.value.first().logo
                        )
                    })
                )
            },
            epgUrl = epgUrl,
        )
    }

    /**
     * 高性能属性提取（避免使用正则）
     */
    private fun extractAttribute(line: String, attributeName: String): String? {
        val key = "$attributeName=\""
        val startIdx = line.indexOf(key)
        if (startIdx != -1) {
            val valueStart = startIdx + key.length
            val endIdx = line.indexOf("\"", valueStart)
            if (endIdx != -1) {
                return line.substring(valueStart, endIdx).trim()
            }
        }
        
        // 尝试不带引号的形式，例如 tvg-logo=http://...
        val keyNoQuote = "$attributeName="
        val startIdxNoQuote = line.indexOf(keyNoQuote)
        if (startIdxNoQuote != -1) {
            val valueStart = startIdxNoQuote + keyNoQuote.length
            var endIdx = line.indexOf(" ", valueStart)
            if (endIdx == -1) endIdx = line.indexOf(",", valueStart)
            if (endIdx == -1) endIdx = line.length
            
            val value = line.substring(valueStart, endIdx).trim()
            return value.ifBlank { null }
        }
        
        return null
    }

    private data class IptvResponseItem(
        val name: String,
        val channelName: String,
        val groupName: String,
        val url: String,
        val logo: String?,
    )
}