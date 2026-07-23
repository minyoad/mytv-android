package top.yogiczy.mytv.core.data.repositories.iptv.parser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList

/**
 * m3u直播源解析
 */
class M3uIptvParser : IptvParser {

    override fun isSupport(url: String, data: String): Boolean {
        return data.startsWith("#EXTM3U")
    }

    override suspend fun parse(data: String): ChannelGroupList = withContext(Dispatchers.Default) {
        val lines = data.split("\r\n", "\n")
        val iptvList = mutableListOf<IptvResponseItem>()

        var epgUrl: String? = null
        val header = lines.firstOrNull { it.startsWith("#EXTM3U") }
        if (header != null) {
            epgUrl = (Regex("x-tvg-url=\"(.+?)\"").find(header)?.groupValues?.get(1)
                ?: Regex("url-tvg=\"(.+?)\"").find(header)?.groupValues?.get(1))
                ?.split(",")?.firstOrNull()?.trim()
        }

        lines.forEachIndexed { index, line ->
            if (!line.startsWith("#EXTINF")) return@forEachIndexed

            val name = line.split(",").last().trim()
            val channelName = Regex("tvg-name=\"(.+?)\"").find(line)?.groupValues?.get(1)?.trim()
                ?: name
            val groupName = Regex("group-title=\"(.+?)\"").find(line)?.groupValues?.get(1)?.trim()
                ?: "其他"
            val logo = Regex("tvg-logo=\"(.+?)\"").find(line)?.groupValues?.get(1)?.trim()
                ?: Regex("logo=\"(.+?)\"").find(line)?.groupValues?.get(1)?.trim()
            val url = lines.getOrNull(index + 1)?.trim()

            url?.let {
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

    private data class IptvResponseItem(
        val name: String,
        val channelName: String,
        val groupName: String,
        val url: String,
        val logo: String?,
    )
}