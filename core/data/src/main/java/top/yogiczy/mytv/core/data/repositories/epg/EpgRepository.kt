package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.Request
import okhttp3.OkHttpClient
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

class EpgRepository(
    private val source: EpgSource,
    private val cache: ValidatedFileCache = ValidatedFileCache(File(Globals.cacheDir, "epg-${source.url.hashCode().toUInt().toString(16)}.xml")),
    private val client: OkHttpClient = OkHttp.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val normalizeChannelName: (String) -> String = ChannelUtil::standardChannelName,
) {

    /** Refresh failure with usable cache is explicit; failure without usable cache is thrown. */
    suspend fun getEpgList(
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ): ValidatedFileCache.Result<EpgList> = load(filteredChannels, refreshTimeThreshold)

    /** Emit local data first, including expired data; revalidate without keeping the UI waiting. */
    fun observeEpgList(
        filteredChannels: List<String> = emptyList(),
        refreshTimeThreshold: Int,
    ): Flow<ValidatedFileCache.Result<EpgList>> = channelFlow {
        send(load(filteredChannels, refreshTimeThreshold) { send(it) })
    }.distinctUntilChanged()

    private suspend fun load(
        filteredChannels: List<String>,
        refreshTimeThreshold: Int,
        onCached: suspend (ValidatedFileCache.Result<EpgList>) -> Unit = {},
    ): ValidatedFileCache.Result<EpgList> = cache.load(
        onCached = onCached,
        isExpired = { EpgRefreshPolicy.isExpired(it, System.currentTimeMillis(), refreshTimeThreshold) },
        validate = { file ->
            withContext(Dispatchers.Default) {
                file.inputStream().use { EpgXmlParser.parse(it, filteredChannels.toSet(), normalizeChannelName) }
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
