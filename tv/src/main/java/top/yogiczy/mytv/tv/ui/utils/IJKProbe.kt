package top.yogiczy.mytv.tv.ui.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import top.yogiczy.mytv.core.data.utils.Logger
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer

/**
 * 使用 IJKPlayer 进行深度探测
 */
object IJKProbe {
    private val log = Logger.create("IJKProbe")

    /**
     * 探测 URL 是否可播放
     * @return 耗时 (ms)，如果不可播放则返回 null
     */
    suspend fun probe(context: Context, url: String, timeout: Long = 5000): Long? {
        val deferred = CompletableDeferred<Long?>()
        val startTime = System.currentTimeMillis()

        val mediaPlayer = IjkMediaPlayer().apply {
            // 禁用视频渲染以节省资源，但保留音频解析以确保能进入 Prepared 状态
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1) // disable video
            
            // 快速探测设置
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzedduration", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 10)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", timeout * 1000)
            
            setOnPreparedListener {
                val latency = System.currentTimeMillis() - startTime
                log.d("Probe success: $url, latency=$latency")
                deferred.complete(latency)
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
