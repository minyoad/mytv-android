package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import top.yogiczy.mytv.core.data.utils.Logger

class IJKVideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : VideoPlayer(coroutineScope) {
    private val log = Logger.create(javaClass.simpleName)
    
    private val ijkPlayer by lazy {
        IjkMediaPlayer().apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)

            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32.toLong())
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1)

            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)

            // 启用rtsp协议支持
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "rtsp,http,https,tcp,tls,udp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")  // 强制使用TCP传输

            // 添加以下配置优化网络和缓冲
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1) // 自动重连
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer") // 减少缓冲
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max-buffer-size", "1024000") // 设置最大缓冲大小
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp") // 优先使用TCP

            // 针对数据包损坏的处理
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "skip_loop_filter", 48) // 跳过循环过滤
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "skip_frame", 48) // 跳过帧

        }
    }
    
    private var updatePositionJob: Job? = null
    
    private val playerListener = object : IMediaPlayer.OnPreparedListener,
        IMediaPlayer.OnInfoListener,
        IMediaPlayer.OnErrorListener,
        IMediaPlayer.OnVideoSizeChangedListener,
        IMediaPlayer.OnCompletionListener {
        
        override fun onPrepared(mp: IMediaPlayer?) {
            triggerPrepared()
            triggerReady()
            
            updatePositionJob?.cancel()
            updatePositionJob = coroutineScope.launch {
                while (true) {
                    triggerCurrentPosition(ijkPlayer.currentPosition)
                    triggerDuration(ijkPlayer.duration)
                    delay(1000)
                }
            }
        }
        
        override fun onInfo(mp: IMediaPlayer?, what: Int, extra: Int): Boolean {
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> triggerBuffering(true)
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> triggerBuffering(false)
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> triggerIsPlayingChanged(true)
            }
            return true
        }
        
        override fun onError(mp: IMediaPlayer?, what: Int, extra: Int): Boolean {
            log.e("onError what=$what extra=$extra")
            triggerError(PlaybackException("IJKPlayerError", what))
            return true
        }
        
        override fun onVideoSizeChanged(
            mp: IMediaPlayer?,
            width: Int,
            height: Int,
            sarNum: Int,
            sarDen: Int
        ) {
            triggerResolution(width, height)
        }
        
        override fun onCompletion(mp: IMediaPlayer?) {
            triggerIsPlayingChanged(false)
        }
    }

    override fun initialize() {
        super.initialize()
        ijkPlayer.apply {
            setOnPreparedListener(playerListener)
            setOnInfoListener(playerListener)
            setOnErrorListener(playerListener)
            setOnVideoSizeChangedListener(playerListener)
            setOnCompletionListener(playerListener)
        }
    }

    override fun release() {
        updatePositionJob?.cancel()
        ijkPlayer.reset()
        ijkPlayer.release()
        super.release()
    }
    // 在类中添加重试计数器
    private var retryCount = 0
    private val MAX_RETRY_COUNT = 3

    override fun prepare(url: String) {
        try {
            ijkPlayer.reset()
            // 在设置数据源前确保Surface有效
            if (currentSurface != null) {
                ijkPlayer.setSurface(currentSurface)
            }
            ijkPlayer.setDataSource(context, Uri.parse(url))
            ijkPlayer.prepareAsync()
        } catch (e: Exception) {
            log.e("prepare error", e)
            if (retryCount < MAX_RETRY_COUNT) {
                retryCount++
                coroutineScope.launch {
                    delay(1000)
                    prepare(url) // 自动重试
                }
            } else {
                triggerError(PlaybackException("PrepareError", -1))
            }        }
    }

    override fun play() {
        if (!ijkPlayer.isPlaying) {
            ijkPlayer.start()
            triggerIsPlayingChanged(true)
        }
    }

    override fun pause() {
        if (ijkPlayer.isPlaying) {
            ijkPlayer.pause()
            triggerIsPlayingChanged(false)
        }
    }

    override fun seekTo(position: Long) {
        ijkPlayer.seekTo(position)
    }

    override fun stop() {
        updatePositionJob?.cancel()
        ijkPlayer.stop()
        super.stop()
    }

    // 添加成员变量保存当前Surface
    private var currentSurface: Surface? = null

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                currentSurface = holder.surface
                ijkPlayer.setDisplay(holder)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                currentSurface = null
            }
        })
        ijkPlayer.setDisplay(surfaceView.holder)
    }

    override fun setVideoTextureView(textureView: TextureView) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                currentSurface = Surface(surface)
                ijkPlayer.setSurface(currentSurface)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                currentSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }
    }
}
