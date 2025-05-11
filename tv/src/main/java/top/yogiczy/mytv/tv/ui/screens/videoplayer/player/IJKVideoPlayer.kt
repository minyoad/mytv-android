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

    private var currentUrl: String? = null
    private var retryCount = 0
    private val MAX_RETRY_COUNT = 3
    
    private val ijkPlayer by lazy {
        IjkMediaPlayer().apply {
            // 基础播放器配置
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0) // 禁用硬解码
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 0) // 禁用硬解码自动旋转
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 0) // 禁用硬解码分辨率变化处理
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            
            // Seek优化配置
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1) // 启用精确seek
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "seek-at-start", 0) // 禁用启动时seek
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "nobuffer") // 禁用文件缓冲，减少seek延迟
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-seek-forward", 1) // 启用向前seek优化
            
            // RTSP协议支持和优化
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "rtsp,http,https,tcp,tls,udp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "stimeout", 30000000) // RTSP超时设置为30秒
            
            // 网络和缓冲优化
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "buffer_size", 1024 * 1024) // 降低缓冲区到1MB以减少seek延迟
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0) // 禁用包缓冲以减少seek延迟
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "max_delay", 100) // 降低最大延迟到100ms
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 0) // 禁用无限缓冲
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 1000) // 降低分析时长到1000ms
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 1024) // 降低探测大小到1MB
            
            // H.264解码优化
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter", 48) // 跳过循环滤波以提高性能
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 0) // 不跳帧
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5) // 允许更多丢帧以保持流畅
            setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "threads", "auto") // 自动选择解码线程数
            
            // 错误恢复和容错
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "sync-av-start", 0) // 关闭音视频同步等待
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "safe", 0) // 不安全模式，提高兼容性
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect_at_eof", 1) // 文件结束时重连
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_streamed", 1) // 流媒体断开自动重连
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect_delay_max", 4000) // 最大重连延迟4秒
            
            // 音频处理
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1) // 使用OpenSL ES
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32.toLong())
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

    override fun prepare(url: String) {
        currentUrl = url
        try {
            ijkPlayer.reset()
            // 在设置数据源前确保Surface有效
            if (currentSurface != null) {
                ijkPlayer.setSurface(currentSurface)
            }
            ijkPlayer.setDataSource(context, Uri.parse(url))
            ijkPlayer.prepareAsync()
            retryCount = 0
        } catch (e: Exception) {
            handleError(e)
        }
    }
    // 添加错误处理方法
    private fun handleError(e: Exception) {
        log.e("playback error", e)
        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++
            coroutineScope.launch {
                delay(1000 * retryCount.toLong()) // 指数退避
                currentUrl?.let { prepare(it) }
            }
        } else {
            triggerError(PlaybackException("PlaybackError", -1))
        }
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
