package top.yogiczy.mytv.core.data.repositories.epg.fetcher

import okhttp3.Response
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher.Companion.fetchText

/**
 * 节目单xml.gz数据获取
 */
class XmlGzEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return url.endsWith(".gz")
    }

    override suspend fun fetch(response: Response) = response.fetchText()
}
