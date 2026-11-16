package top.yogiczy.mytv.tv.ui.screens.channel.components

import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.data.entities.channel.Channel
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroup
import top.yogiczy.mytv.core.data.entities.channel.ChannelGroupList
import top.yogiczy.mytv.core.data.entities.channel.ChannelList

class LogoPrefetchTest {
    private val channels = (0..999).map { Channel(id = "$it", name = "Channel $it", logo = "https://logo/$it.png") }
    private fun groups(list: List<Channel>) = ChannelGroupList(listOf(ChannelGroup("Group", ChannelList(list))))

    @Test fun lastViewedChannelIsFirstWithoutEnqueuingHundredsAheadOfIt() {
        val urls = logoPrefetchUrls(groups(channels), channels[900])
        assertEquals(channels[900].logo, urls.first())
        assertEquals(12, urls.size)
        assertTrue(urls.all { url -> channels.subList(898, 910).any { it.logo == url } })
    }

    @Test fun onlyInitialGroupIsWarmedAndDuplicateUrlsAreRemoved() {
        val group = groups(channels.take(5).map { it.copy(logo = "https://same-logo") })
        val result = logoPrefetchUrls(ChannelGroupList(group + groups(channels.drop(5))), channels[0])
        assertEquals(listOf("https://same-logo"), result)
    }

    @Test fun hiddenCurrentChannelFallsBackToVisibleGroupAndSkipsMissingLogos() {
        val visible = channels.subList(10, 15).mapIndexed { index, channel -> if (index == 0) channel.copy(logo = null) else channel }
        val urls = logoPrefetchUrls(groups(visible), channels[900])
        assertEquals(visible.drop(1).map { it.logo }, urls)
    }

    @Test fun emptyAndEndOfListAreSafeAndBounded() {
        assertTrue(logoPrefetchUrls(ChannelGroupList(), null).isEmpty())
        val urls = logoPrefetchUrls(groups(channels), channels.last())
        assertEquals(channels.last().logo, urls.first())
        assertEquals(12, urls.size)
        assertTrue(logoPrefetchUrls(groups(channels), channels.first(), 0).isEmpty())
    }
}
