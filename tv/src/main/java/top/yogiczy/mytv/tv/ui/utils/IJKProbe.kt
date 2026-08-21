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
        val shortEdge = minOf(width, height)
        return when {
            shortEdge >= 2160 -> "4K"
            shortEdge >= 1440 -> "2K"
            shortEdge >= 1080 -> "1080p"
            shortEdge >= 720 -> "720p"
            shortEdge >= 540 -> "540p"
            shortEdge >= 480 -> "480p"
            else -> "360p"
        }
    }

    /**
     * 探测 URL 是否可播放，并尽量提取物理分辨率
     * @return 探测耗时与分辨率，不可播放则返回 null
     */
    suspend fun probe(context: Context, url: String, timeout: Long = 5000): DeepProbeResult? {
        // 分辨率等待上限：onPrepared 后若仍未拿到尺寸，最多再等这一小段
        val resolutionWaitMs = 800L

        // prepared 完成（可用/失败）
        val prepared = CompletableDeferred<Boolean?>()
        // 探测最终结果（含分辨率）
        val done = CompletableDeferred<DeepProbeResult?>()

        val startTime = System.currentTimeMillis()
        var videoWidth = 0
        var videoHeight = 0

        val mediaPlayer = IjkMediaPlayer().apply {
            // 必须启用视频解码（vn=0）才能通过流信息获取物理分辨率。
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 0)
            // 播放头解析阶段即探测一帧以获取物理尺寸
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "video-detect-frame-count", 1)
            // 关闭画质缓冲，让首帧尽快就绪，避免额外等待
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0)
            // 关闭音画同步等待，不因缺音频/渲染阻塞
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 0)
            // 解码帧直接丢弃，不做渲染与队列缓冲，进一步降低开销
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "video-pictq-size", 0)

            // 快速探测设置
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 2_000_000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzedduration", 1_000_000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 512L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", timeout * 1000)

            // RTSP & 网络设置
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "crypto,file,http,https,tcp,tls,udp,rtmp,rtsp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "buffer_size", 1024 * 1024L)

            setOnVideoSizeChangedListener { _, width, height, _, _ ->
                if (width > 0 && height > 0) {
                    videoWidth = width
                    videoHeight = height
                    // 尺寸到位后立即完成，避免不必要等待
                    if (!done.isCompleted && videoWidth > 0) {
                        val res = resolveResolution(videoWidth, videoHeight)
                        log.i("Detected resolution via onVideoSizeChanged: $url, ${videoWidth}x${videoHeight} -> $res")
                        done.complete(
                            DeepProbeResult(
                                latency = System.currentTimeMillis() - startTime,
                                resolution = res,
                            )
                        )
                    }
                }
            }

            // onPrepared 即代表线路可用。同步读取尺寸（getVideoWidth/Height 已可用）。
            setOnPreparedListener {
                prepared.complete(true)
                val w = if (videoWidth > 0) videoWidth else getVideoWidth()
                val h = if (videoHeight > 0) videoHeight else getVideoHeight()
                if (w > 0 && h > 0) {
                    videoWidth = w
                    videoHeight = h
                    val res = resolveResolution(w, h)
                    log.i("Detected resolution via onPrepared: $url, ${w}x${h} -> $res")
                    done.complete(
                        DeepProbeResult(
                            latency = System.currentTimeMillis() - startTime,
                            resolution = res,
                        )
                    )
                } else {
                    log.d("Resolution not available onPrepared for $url, starting playback to probe...")
                    // 尺寸未就绪，驱动解码并在短上限内等待尺寸回调
                    try { start() } catch (_: Exception) {}
                }
            }

            setOnErrorListener { _, what, extra ->
                log.e("Probe failed: $url, what=$what, extra=$extra")
                prepared.complete(null)
                if (!done.isCompleted) done.complete(null)
                true
            }

            setOnInfoListener { _, what, _ ->
                log.d("Probe info: $url, what=$what")
                false
            }
        }

        var result: DeepProbeResult? = null

        return try {
            mediaPlayer.setDataSource(context, Uri.parse(url))
            mediaPlayer.prepareAsync()

            // 1) 等待流可用判定：prepared == true(可用) / null(失败) / null(超时)
            val ready = withTimeoutOrNull(timeout) { prepared.await() } == true

            // 2) 已可用：等待最终结果（含分辨率），最多再等 resolutionWaitMs
            if (ready) {
                result = withTimeoutOrNull(resolutionWaitMs) { done.await() }
                // 3) 等待结束仍无尺寸：按「可用但无分辨率」返回
                if (result == null && !done.isCompleted) {
                    result = DeepProbeResult(
                        latency = System.currentTimeMillis() - startTime,
                        resolution = null,
                    )
                }
            } else {
                // 不可用（失败/超时）：等待错误回调完成，若无则返回 null
                result = withTimeoutOrNull(resolutionWaitMs) { done.await() }
            }

            result
        } catch (e: Exception) {
            log.e("Probe exception: $url", e)
            null
        } finally {
            log.i("Probe finished for $url: latency=${result?.latency}, resolution=${result?.resolution}")
            mediaPlayer.release()
        }
    }
}
