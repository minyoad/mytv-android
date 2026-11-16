package top.yogiczy.mytv.core.data.repositories.epg

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.yogiczy.mytv.core.data.entities.epg.EpgList
import top.yogiczy.mytv.core.data.entities.epgsource.EpgSource
import top.yogiczy.mytv.core.data.repositories.ValidatedFileCache
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EpgRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun scheduleIsVisibleWhileConditionalHttpRequestIsStillWaiting() = runBlocking<Unit> {
        withTimeout(5000) {
            val file = File(temporary.root, "epg.xml")
            file.writeText("""
                <tv>
                  <channel id="a"><display-name>A</display-name></channel>
                  <programme channel="a" start="20261116070000 +0800" stop="20261116080000 +0800"><title>Cached programme</title></programme>
                </tv>
            """.trimIndent())
            file.setLastModified(100_000L)
            val releaseNetwork = CountDownLatch(1)
            val networkStarted = CompletableDeferred<Unit>()
            val firstVisible = CompletableDeferred<EpgList>()
            val revalidated = CompletableDeferred<Unit>()
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                networkStarted.complete(Unit)
                check(releaseNetwork.await(5, TimeUnit.SECONDS))
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(304).message("Not Modified").body("".toResponseBody()).build()
            }.build()
            val repository = EpgRepository(
                EpgSource("test", "https://epg.invalid/schedule.xml"),
                ValidatedFileCache(file), client, { it },
            )
            val collector = launch {
                repository.observeEpgList(listOf("A"), 0).collect { result ->
                    firstVisible.complete(result.value)
                    if (result.checkedAt != 100_000L) revalidated.complete(Unit)
                }
            }
            try {
                assertEquals("Cached programme", firstVisible.await().single().programmeList.single().title)
                networkStarted.await()
                assertFalse(revalidated.isCompleted)
                assertEquals(100_000L, file.lastModified())
                releaseNetwork.countDown()
                revalidated.await()
            } finally {
                releaseNetwork.countDown()
                collector.cancelAndJoin()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
