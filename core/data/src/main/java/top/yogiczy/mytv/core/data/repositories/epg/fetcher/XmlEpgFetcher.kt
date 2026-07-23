package top.yogiczy.mytv.core.data.repositories.epg.fetcher

import okhttp3.Response
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher.Companion.fetchText

/**
 * 节目单xml数据获取
 */
class XmlEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return url.endsWith(".xml")
    }

    override suspend fun fetch(response: Response) = response.fetchText()
}
