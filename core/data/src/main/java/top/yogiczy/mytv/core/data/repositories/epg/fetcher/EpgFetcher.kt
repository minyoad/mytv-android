package top.yogiczy.mytv.core.data.repositories.epg.fetcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import top.yogiczy.mytv.core.data.utils.Logger
import java.io.InputStream
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
     * 获取节目单流
     */
    suspend fun fetchStream(response: Response): InputStream

    companion object {
        private val log = Logger.create("EpgFetcher")

        val instances = listOf(
            XmlEpgFetcher(),
            XmlGzEpgFetcher(),
            DefaultEpgFetcher(),
        )

        suspend fun Response.fetchStream(): InputStream = withContext(Dispatchers.IO) {
            val body = body ?: throw Exception("响应体为空")
            val inputStream = body.byteStream()

            // 预读取两个字节来检测是否是 GZIP 格式
            val bufferedInputStream = inputStream.buffered()
            bufferedInputStream.mark(2)
            val header = ByteArray(2)
            val read = bufferedInputStream.read(header)
            bufferedInputStream.reset()

            if (read == 2 && header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte()) {
                log.d("检测到GZIP格式，开始解压流")
                GZIPInputStream(bufferedInputStream)
            } else {
                log.d("检测到明文格式流")
                bufferedInputStream
            }
        }
    }
}
