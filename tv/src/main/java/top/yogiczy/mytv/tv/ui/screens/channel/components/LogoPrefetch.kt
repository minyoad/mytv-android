package top.yogiczy.mytv.tv.ui.screens.channel.components

import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList

/** Only warm the initial visible window, starting with the current channel. Other groups load on demand. */
internal fun logoPrefetchUrls(groups: ChannelGroupList, current: Channel?, limit: Int = 12): List<String> {
    if (limit <= 0) return emptyList()
    val group = groups.firstOrNull { g -> g.channelList.any { it.id == current?.id } }
        ?: groups.firstOrNull { it.channelList.isNotEmpty() } ?: return emptyList()
    val channels = group.channelList
    val index = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    val start = (index - 2).coerceIn(0, (channels.size - limit).coerceAtLeast(0))
    return (listOf(channels[index]) + channels.drop(start).take(limit))
        .mapNotNull { it.logo?.takeIf(String::isNotBlank) }.distinct().take(limit)
}
