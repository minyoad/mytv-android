package top.yogiczy.mytv.core.data.repositories.epg.fetcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * 节目单数据获取接口
 */
interface EpgFetcher {
    /**
     * 是否支持该格式
     */
    fun isSupport(url: String): Boolean

    /**
     * 获取节目单
     */
    suspend fun fetch(response: Response): String

    companion object {
        private val log = Logger.create("EpgFetcher")

        val instances = listOf(
            XmlEpgFetcher(),
            XmlGzEpgFetcher(),
            DefaultEpgFetcher(),
        )

        suspend fun Response.fetchText(): String = withContext(Dispatchers.IO) {
            val body = body ?: return@withContext ""
            val bytes = body.bytes()

            log.i("获取节目单数据: size=${bytes.size}, contentType=${body.contentType()}")

            if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
                log.d("检测到GZIP格式，开始解压")
                GZIPInputStream(ByteArrayInputStream(bytes)).use { gzipInputStream ->
                    gzipInputStream.bufferedReader().readText()
                }
            } else {
                val charset = body.contentType()?.charset() ?: Charsets.UTF_8
                log.d("检测到明文格式，编码=${charset.name()}")
                String(bytes, charset)
            }
        }
    }
}