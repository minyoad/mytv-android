package top.yogiczy.mytv.core.data.entities.channel

import androidx.compose.runtime.Immutable

/**
 * 频道分组列表
 */
@Immutable
data class ChannelGroupList(
    val value: List<ChannelGroup> = emptyList(),
    val epgUrl: String? = null,
) : List<ChannelGroup> by value {
    // 扁平频道列表缓存：同一实例只构建一次。
    // 大直播源（数千频道）在换台按键路径上会多次访问该列表，
    // 每次都 flatMap 全量重建是主线程卡顿的直接原因之一。
    private val flatChannelListCache: ChannelList by lazy(LazyThreadSafetyMode.NONE) {
        ChannelList(value.asSequence().flatMap { it.channelList.asSequence() }.toList())
    }

    // 频道 -> 全局下标映射缓存，channelIdx() 由 O(N) 线性查找降为 O(1)。
    private val channelIdxMapCache: Map<Channel, Int> by lazy(LazyThreadSafetyMode.NONE) {
        val map = HashMap<Channel, Int>(flatChannelListCache.size * 2)
        flatChannelListCache.forEachIndexed { idx, channel ->
            if (!map.containsKey(channel)) map[channel] = idx
        }
        map
    }

    companion object {
        val EXAMPLE = ChannelGroupList(
            value = List(20) { groupIdx ->
                ChannelGroup(
                    name = "频道分组${groupIdx + 1}",
                    channelList = ChannelList(
                        List(20) { idx ->
                            Channel.EXAMPLE.copy(
                                name = "频道${groupIdx + 1}-${idx + 1}",
                                epgName = "频道${groupIdx + 1}-${idx + 1}",
                            )
                        },
                    )
                )
            },
            epgUrl = "http://epg.51zmt.top:12450/cc.xml",
        )

        fun ChannelGroupList.channelGroupIdx(channel: Channel) =
            this.indexOfFirst { group -> group.channelList.any { it == channel } }

        fun ChannelGroupList.channelIdx(channel: Channel) =
            channelIdxMapCache[channel] ?: -1

        val ChannelGroupList.channelList: ChannelList
            get() = flatChannelListCache
    }
}