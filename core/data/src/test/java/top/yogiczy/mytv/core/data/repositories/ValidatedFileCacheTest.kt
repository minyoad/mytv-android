package top.yogiczy.mytv.core.data.repositories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ValidatedFileCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val validate: suspend (File) -> Int = { it.readText().toInt() }
    private fun cacheFile() = File(temporary.root, "source.txt")

    private suspend fun seed(file: File) {
        ValidatedFileCache(file) { 100_000L }.load({ true }, validate) { stage, etag ->
            assertNull(etag)
            stage.writeText("1")
            ValidatedFileCache.Download.Modified("old-etag")
        }
    }

    @Test fun failedDownloadKeepsDataEtagAndTimestamp() = runBlocking<Unit> {
        val file = cacheFile()
        seed(file)
        val result = ValidatedFileCache(file) { 200_000L }.load({ true }, validate) { stage, etag ->
            assertEquals("old-etag", etag)
            stage.writeText("partial")
            assertEquals("1", file.readText())
            throw IOException("connection lost")
        }
        assertEquals(1, result.value)
        assertNotNull(result.refreshError)
        assertEquals(100_000L, result.checkedAt)
        assertEquals("1", file.readText())
        assertEquals("old-etag", File(file.path + ".etag").readText())
        assertEquals(100_000L, file.lastModified())
        assertFalse(temporary.root.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun malformedResponseCannotPoisonCache() = runBlocking<Unit> {
        val file = cacheFile()
        seed(file)
        val result = ValidatedFileCache(file).load({ true }, validate) { stage, _ ->
            stage.writeText("<html>error</html>")
            ValidatedFileCache.Download.Modified("bad-etag")
        }
        assertEquals(1, result.value)
        assertNotNull(result.refreshError)
        assertEquals("old-etag", File(file.path + ".etag").readText())
        assertEquals("1", file.readText())
    }

    @Test fun modifiedResponsePublishesValidatedDataAndEtag() = runBlocking<Unit> {
        val file = cacheFile()
        seed(file)
        val result = ValidatedFileCache(file) { 200_000L }.load({ true }, validate) { stage, _ ->
            assertEquals("1", file.readText())
            stage.writeText("2")
            ValidatedFileCache.Download.Modified("new-etag")
        }
        assertEquals(2, result.value)
        assertNull(result.refreshError)
        assertEquals(200_000L, result.checkedAt)
        assertEquals("2", file.readText())
        assertEquals("new-etag", File(file.path + ".etag").readText())
    }

    @Test fun notModifiedRevalidatesWithoutReplacingData() = runBlocking<Unit> {
        val file = cacheFile()
        seed(file)
        val result = ValidatedFileCache(file) { 200_000L }.load({ true }, validate) { _, etag ->
            assertEquals("old-etag", etag)
            ValidatedFileCache.Download.NotModified
        }
        assertEquals(1, result.value)
        assertNull(result.refreshError)
        assertEquals(200_000L, result.checkedAt)
        assertEquals("1", file.readText())
    }

    @Test fun notModifiedWithoutValidCacheFails() = runBlocking<Unit> {
        val file = cacheFile()
        file.writeText("broken")
        File(file.path + ".etag").writeText("unrelated")
        try {
            ValidatedFileCache(file).load({ true }, validate) { _, etag ->
                assertNull(etag)
                ValidatedFileCache.Download.NotModified
            }
            fail("304 must not make invalid cache valid")
        } catch (_: IOException) { }
    }

    @Test fun noCacheFailurePropagatesAndCleansTemporaryFile() = runBlocking<Unit> {
        val file = cacheFile()
        try {
            ValidatedFileCache(file).load({ true }, validate) { stage, _ ->
                stage.writeText("partial")
                throw IOException("offline")
            }
            fail("must propagate failure")
        } catch (_: IOException) { }
        assertFalse(file.exists())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun cancellationIsNotConvertedToStaleSuccess() = runBlocking<Unit> {
        val file = cacheFile()
        seed(file)
        try {
            ValidatedFileCache(file).load({ true }, validate) { stage, _ ->
                stage.writeText("2")
                throw CancellationException("source changed")
            }
            fail("must propagate cancellation")
        } catch (_: CancellationException) { }
        assertEquals("1", file.readText())
        assertEquals("old-etag", File(file.path + ".etag").readText())
    }

    @Test fun concurrentRepositoryInstancesShareOneRefresh() = runBlocking<Unit> {
        val file = cacheFile()
        val requests = AtomicInteger()
        val results = (1..10).map {
            async {
                ValidatedFileCache(file).load({ false }, validate) { stage, _ ->
                    requests.incrementAndGet()
                    stage.writeText("2")
                    ValidatedFileCache.Download.Modified("new")
                }.value
            }
        }.awaitAll()
        assertEquals(List(10) { 2 }, results)
        assertEquals(1, requests.get())
    }

    @Test fun differentSourcesDoNotBlockEachOther() = runBlocking<Unit> {
        withTimeout(5000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coroutineScope {
                val slow = async {
                    ValidatedFileCache(cacheFile()).load({ true }, validate) { stage, _ ->
                        entered.complete(Unit)
                        release.await()
                        stage.writeText("1")
                        ValidatedFileCache.Download.Modified(null)
                    }
                }
                entered.await()
                val other = ValidatedFileCache(File(temporary.root, "other.txt")).load({ true }, validate) { stage, _ ->
                    stage.writeText("2")
                    ValidatedFileCache.Download.Modified(null)
                }
                assertEquals(2, other.value)
                release.complete(Unit)
                slow.await()
            }
        }
    }

    @Test fun clearInvalidatesDataAndValidator() = runBlocking<Unit> {
        val file = cacheFile()
        seed(file)
        ValidatedFileCache(file).clear()
        assertFalse(file.exists())
        assertFalse(File(file.path + ".etag").exists())
    }

    @Test fun cancellationDuringValidationCannotPublishNewData() = runBlocking<Unit> {
        withTimeout(5000) {
            val file = cacheFile()
            seed(file)
            val validating = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            val job = launch {
                ValidatedFileCache(file).load({ true }, { candidate ->
                    if (candidate != file) {
                        validating.complete(Unit)
                        blocked.await()
                    }
                    candidate.readText().toInt()
                }) { stage, _ ->
                    stage.writeText("2")
                    ValidatedFileCache.Download.Modified("new-etag")
                }
            }
            validating.await()
            job.cancelAndJoin()
            assertEquals("1", file.readText())
            assertEquals("old-etag", File(file.path + ".etag").readText())
            assertFalse(temporary.root.listFiles()!!.any { it.extension == "tmp" })
        }
    }

    @Test fun clearWaitsForRefreshAndRemovesItsResult() = runBlocking<Unit> {
        withTimeout(5000) {
            val file = cacheFile()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val refresh = async {
                ValidatedFileCache(file).load({ true }, validate) { stage, _ ->
                    entered.complete(Unit)
                    release.await()
                    stage.writeText("2")
                    ValidatedFileCache.Download.Modified("new")
                }
            }
            entered.await()
            val clear = async { ValidatedFileCache(file).clear() }
            release.complete(Unit)
            refresh.await()
            clear.await()
            assertFalse(file.exists())
            assertFalse(File(file.path + ".etag").exists())
        }
    }
}
