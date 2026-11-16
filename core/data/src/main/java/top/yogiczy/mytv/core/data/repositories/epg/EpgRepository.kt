package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.network.OkHttp
import top.yogiczy.mytv.core.data.network.await
import top.yogiczy.mytv.core.data.repositories.ValidatedFileCache
import top.yogiczy.mytv.core.data.repositories.epg.fetcher.EpgFetcher.Companion.fetchStream
import top.yogiczy.mytv.core.data.utils.ChannelUtil
import top.yogiczy.mytv.core.data.utils.Globals
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class EpgRepository(private val source: EpgSource) {
    private val cache = ValidatedFileCache(File(Globals.cacheDir, "epg-${source.url.hashCode().toUInt().toString(16)}.xml"))
    private val client = OkHttp.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** Refresh failure with usable cache is explicit; failure without usable cache is thrown. */
    suspend fun getEpgList(
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ): ValidatedFileCache.Result<EpgList> = cache.load(
        isExpired = { EpgRefreshPolicy.isExpired(it, System.currentTimeMillis(), refreshTimeThreshold) },
        validate = { file ->
            withContext(Dispatchers.Default) {
                file.inputStream().use { EpgXmlParser.parse(it, filteredChannels.toSet(), ChannelUtil::standardChannelName) }
            }
        },
        download = { temporary, etag ->
            val request = Request.Builder().url(source.url).apply {
                if (!etag.isNullOrBlank()) header("If-None-Match", etag)
            }.build()
            client.newCall(request).await().use { response ->
                if (response.code == 304) {
                    ValidatedFileCache.Download.NotModified
                } else {
                    if (!response.isSuccessful) throw IOException("${response.code}: ${response.message}")
                    response.fetchStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count == -1) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    ValidatedFileCache.Download.Modified(response.header("ETag"))
                }
            }
        },
    )

    suspend fun clearCache() = cache.clear()
}
