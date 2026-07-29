package top.yogiczy.mytv.core.data.repositories.epg.fetcher

import okhttp3.Response
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher.Companion.fetchStream

/**
 * 缺省节目单数据获取
 */
class DefaultEpgFetcher : EpgFetcher {
    override fun isSupport(url: String): Boolean {
        return true
    }

    override suspend fun fetchStream(response: Response) = response.fetchStream()
}
