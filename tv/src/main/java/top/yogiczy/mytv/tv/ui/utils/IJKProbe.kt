package top.yogiczy.mytv.tv.ui.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import top.yogiczy.mytv.core.data.repositories.iptv.DeepProbeResult
import top.yogiczy.mytv.core.data.utils.Logger
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 使用 IJKPlayer 进行深度探测
 */
object IJKProbe {
    private val log = Logger.create("IJKProbe")

    /**
     * 将像素高度映射为常见画质分辨率标签
     */
    private fun resolveResolution(width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        val longEdge = maxOf(width, height)
        return when {
            longEdge >= 2160 -> "4K"
            longEdge >= 1440 -> "2K"
            longEdge >= 1080 -> "1080p"
            longEdge >= 720 -> "720p"
            longEdge >= 540 -> "540p"
            longEdge >= 480 -> "480p"
            else -> "360p"
        }
    }

    /**
     * 探测 URL 是否可播放，并尽量提取物理分辨率
     * @return 探测耗时与分辨率，不可播放则返回 null
     */
    suspend fun probe(context: Context, url: String, timeout: Long = 5000): DeepProbeResult? {
        val deferred = CompletableDeferred<DeepProbeResult?>()
        val startTime = System.currentTimeMillis()

        // 记录视频宽高（可能由 onVideoSizeChanged 异步回调填充）
        var videoWidth = 0
        var videoHeight = 0

        val mediaPlayer = IjkMediaPlayer().apply {
            // 禁用视频渲染以节省资源，但保留视频解码以提取分辨率
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 0) // keep video decoding for resolution
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "video-detect-frame-count", 1)
            
            // 快速探测设置
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzedduration", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 10)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", timeout * 1000)
            
            setOnVideoSizeChangedListener { _, width, height, _, _ ->
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                }
            }

            setOnPreparedListener {
                val latency = System.currentTimeMillis() - startTime
                log.d("Probe success: $url, latency=$latency, resolution=${videoWidth}x$videoHeight")
                deferred.complete(
                    DeepProbeResult(
                        latency = latency,
                        resolution = resolveResolution(videoWidth, videoHeight),
                    )
                )
            }
            
            setOnErrorListener { _, what, extra ->
                log.e("Probe failed: $url, what=$what, extra=$extra")
                deferred.complete(null)
                true
            }

            setOnInfoListener { _, what, extra ->
                log.d("Probe info: $url, what=$what, extra=$extra")
                false
            }
        }

        return try {
            mediaPlayer.setDataSource(context, Uri.parse(url))
            mediaPlayer.prepareAsync()

            withTimeoutOrNull(timeout) {
                deferred.await()
            }
        } catch (e: Exception) {
            log.e("Probe exception: $url", e)
            null
        } finally {
            mediaPlayer.release()
        }
    }
}
