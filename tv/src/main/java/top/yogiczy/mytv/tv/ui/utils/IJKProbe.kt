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
            // 禁用音视频渲染以节省资源
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "vn", 1) // disable video
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "an", 1) // disable audio
            
            // 快速探测设置
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzedduration", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 10)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", timeout * 1000) // ijk timeout is in microsec? No, it depends on version, usually millis or micro. 
            // Standard ffmpeg timeout is in microseconds for some options. 
            // But let's follow the project's Configs.videoPlayerLoadTimeout which is used as millis.
            
            setOnPreparedListener {
                val latency = System.currentTimeMillis() - startTime
                deferred.complete(latency)
            }
            
            setOnErrorListener { _, what, extra ->
                log.e("Probe failed: $url, what=$what, extra=$extra")
                deferred.complete(null)
                true
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
