package top.yogiczy.mytv.core.data.repositories

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A cache entry is usable only after validation. Failed refreshes retain the last valid entry. */
class ValidatedFileCache(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    sealed interface Download {
        data class Modified(val etag: String?) : Download
        data object NotModified : Download
    }

    data class Result<T>(val value: T, val checkedAt: Long, val refreshError: Exception? = null)

    private class Entry {
        val mutex = Mutex()
        val revision = AtomicLong()
        var refreshError: Exception? = null
    }

    private val entry = entries.getOrPut(file.canonicalPath) { Entry() }
    private val etagFile get() = File(file.path + ".etag")

    suspend fun <T : Any> load(
        isExpired: (Long) -> Boolean,
        validate: suspend (File) -> T,
        onCached: suspend (Result<T>) -> Unit = {},
        download: suspend (temporaryFile: File, etag: String?) -> Download,
    ): Result<T> = withContext(Dispatchers.IO) {
        val observedRevision = entry.revision.get()
        entry.mutex.withLock {
            val cached = if (file.isFile) {
                try {
                    validate(file)
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    null
                }
            } else null
            val checkedAt = file.lastModified()
            // Consumers can display a validated local entry before any conditional request or timeout.
            if (cached != null) onCached(Result(cached, checkedAt))
            // Another caller refreshed this entry while we were waiting for its lock.
            if (entry.revision.get() != observedRevision) {
                if (cached != null) return@withLock Result(cached, checkedAt, entry.refreshError)
                entry.refreshError?.let { throw it }
            }
            if (cached != null && !isExpired(checkedAt)) return@withLock Result(cached, checkedAt)

            file.parentFile?.mkdirs()
            var temporary: File? = null
            try {
                val staging = File.createTempFile(file.name + ".", ".tmp", file.parentFile)
                temporary = staging
                val etag = if (cached != null && etagFile.isFile) etagFile.readText() else null
                when (val downloaded = download(staging, etag)) {
                    Download.NotModified -> {
                        if (cached == null) throw IOException("收到304响应，但没有有效缓存")
                        currentCoroutineContext().ensureActive()
                        if (!file.setLastModified(now())) throw IOException("无法更新缓存时间")
                        entry.refreshError = null
                        entry.revision.incrementAndGet()
                        Result(cached, file.lastModified())
                    }
                    is Download.Modified -> {
                        val value = validate(staging)
                        currentCoroutineContext().ensureActive()
                        RandomAccessFile(staging, "rw").use { it.fd.sync() }
                        // Remove the old validator before replacing data: a crash can only cause a full download,
                        // never a 304 against unrelated content. Publish the new ETag after the data commit.
                        if (etagFile.exists() && !etagFile.delete()) throw IOException("无法清除旧缓存ETag")
                        if (!staging.setLastModified(now()) || !staging.renameTo(file)) {
                            throw IOException("无法原子替换缓存")
                        }
                        if (!downloaded.etag.isNullOrEmpty()) {
                            try {
                                val metadata = File.createTempFile(file.name + ".", ".tmp", file.parentFile)
                                try {
                                    metadata.writeText(downloaded.etag)
                                    metadata.renameTo(etagFile)
                                } finally {
                                    metadata.delete()
                                }
                            } catch (_: IOException) {
                                // Metadata is optional; retry without If-None-Match next time.
                                etagFile.delete()
                            }
                        }
                        entry.refreshError = null
                        entry.revision.incrementAndGet()
                        Result(value, file.lastModified())
                    }
                }
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                entry.refreshError = ex
                entry.revision.incrementAndGet()
                if (cached == null) throw ex
                Result(cached, checkedAt, ex)
            } finally {
                temporary?.delete()
            }
        }
    }

    suspend fun <T> read(validate: suspend (File) -> T): T? = withContext(Dispatchers.IO) {
        entry.mutex.withLock { if (file.isFile) validate(file) else null }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        entry.mutex.withLock {
            if (file.exists() && !file.delete()) throw IOException("无法删除缓存")
            etagFile.delete()
            entry.refreshError = null
            entry.revision.incrementAndGet()
            Unit
        }
    }

    companion object {
        private val entries = ConcurrentHashMap<String, Entry>()
    }
}
