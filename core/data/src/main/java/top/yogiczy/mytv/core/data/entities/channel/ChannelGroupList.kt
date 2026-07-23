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
            this.flatMap { it.channelList }.indexOfFirst { it == channel }

        val ChannelGroupList.channelList: ChannelList
            get() = ChannelList(this.asSequence().flatMap { it.channelList.asSequence() }.toList())
    }
}