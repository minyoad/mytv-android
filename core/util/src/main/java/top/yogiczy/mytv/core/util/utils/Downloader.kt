package top.yogiczy.mytv.core.util.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.File
import java.util.concurrent.TimeUnit

object Downloader {
    suspend fun downloadTo(url: String, filePath: String, onProgressCb: ((Int) -> Unit)?) =
        withContext(Dispatchers.IO) {
            val interceptor = Interceptor { chain ->
                val originalResponse = chain.proceed(chain.request())
                originalResponse.newBuilder()
                    .body(DownloadResponseBody(originalResponse, onProgressCb)).build()
            }

            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(interceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS) // 整体不限时（仅按 read/write 分段超时）
                .retryOnConnectionFailure(true)
                .sslSocketFactory(
                    UnsafeTrustManager.getSSLSocketFactory(),
                    UnsafeTrustManager()
                )
                .hostnameVerifier { _, _ -> true }
                .build()
            val request = okhttp3.Request.Builder().url(url).build()

            val file = File(filePath)
            // 清理上次下载可能残留的半截文件，避免拼接到错误位置
            if (file.exists()) file.delete()

            var expectedLength = -1L
            try {
                with(client.newCall(request).execute()) {
                    if (!isSuccessful) throw Exception("下载文件失败: HTTP $code")

                    expectedLength = body!!.contentLength()

                    // 流式写盘：使用 byteStream + 8KB chunk 写入到 FileOutputStream。
                    // 避免 body.bytes() 全量加载到内存（TV 设备内存仅 1~2GB，
                    // 50~80MB APK 全量加载易 OOM，导致 catch 后用户感知为"下载失败"）。
                    body!!.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val read = input.read(buf)
                                if (read == -1) break
                                output.write(buf, 0, read)
                                output.flush() // 实时落盘，异常时也能保留已下载内容
                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                // 失败时清理半截文件，避免下次复用时误判
                runCatching { if (file.exists()) file.delete() }
                throw Exception("下载文件失败，请检查网络连接", ex)
            }

            // 完整性校验：若服务端声明了 Content-Length，必须匹配实际落盘大小
            if (expectedLength > 0 && file.length() != expectedLength) {
                runCatching { file.delete() }
                throw Exception(
                    "下载文件不完整：期望 $expectedLength 字节，实际 ${file.length()} 字节"
                )
            }

            // 兜底：进度回调可能未触发到 100%（取决于 read 调用次数）
            onProgressCb?.invoke(100)
        }

    private class DownloadResponseBody(
        private val originalResponse: okhttp3.Response,
        private val onProgressCb: ((Int) -> Unit)?,
    ) : okhttp3.ResponseBody() {
        override fun contentLength() = originalResponse.body!!.contentLength()

        override fun contentType() = originalResponse.body?.contentType()

        override fun source(): BufferedSource {
            return object : ForwardingSource(originalResponse.body!!.source()) {
                var totalBytesRead = 0L
                var lastReportedProgress = -1

                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val bytesRead = super.read(sink, byteCount)
                    totalBytesRead += if (bytesRead != -1L) bytesRead else 0
                    val length = contentLength()
                    if (length > 0) {
                        val progress = (totalBytesRead * 100 / length).toInt()
                        // 节流：仅在整数百分比变化时才回调，避免数十 MB 文件产生
                        // 数千次 read 调用 → 数千个协程轰炸 Snackbar。
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            CoroutineScope(Dispatchers.IO).launch {
                                onProgressCb?.invoke(progress)
                            }
                        }
                    }
                    return bytesRead
                }
            }.buffer()
        }
    }
}