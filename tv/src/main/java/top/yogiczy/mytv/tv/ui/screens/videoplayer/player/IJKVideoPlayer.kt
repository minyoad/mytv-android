package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.tv.ui.utils.Configs

class IJKVideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : VideoPlayer(coroutineScope) {
    private val log = Logger.create(javaClass.simpleName)

    private var currentUrl: String? = null
    private var retryCount = 0
    private val MAX_RETRY_COUNT = 3
    private var prepareJob: Job? = null
    
    // 用于确保底层播放器方法的调用是串行的，防止并发导致的 JNI 层崩溃
    private val playerMutex = Mutex()

    private val ijkPlayer by lazy {
        IjkMediaPlayer().apply {
            //            IjkMediaPlayer.native_setLogLevel(IjkMediaPlayer.IJK_LOG_INFO)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 2)
            setOption(
                IjkMediaPlayer.OPT_CATEGORY_FORMAT,
                "timeout",
                Configs.videoPlayerLoadTimeout
            )
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 100L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzedduration", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 10)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek")
        }
    }

    private fun setOption() {
        ijkPlayer.apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "allowed_extensions", "ALL")
            if (Configs.videoPlayerForceSoftDecode)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)
            else{
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
            }
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "crypto,file,http,https,tcp,tls,udp,rtmp,rtsp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "fast", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1)

            // rtsp设置 https://ffmpeg.org/ffmpeg-protocols.html#rtsp
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "buffer_size", 1316)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1)  // 无限读
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)

            //  关闭播放器缓冲，这个必须关闭，否则会出现播放一段时间后，一直卡住，控制台打印 FFP_MSG_BUFFERING_START
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0)

            //https://www.cnblogs.com/Fitz/p/18537127
            // setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_loop_filter",0) //丢弃一些“无用”的数据包，例如AVI格式中的零大小数据包
            // setOption(IjkMediaPlayer.OPT_CATEGORY_CODEC, "skip_frame", 0) //不跳帧，解码所有帧
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
            // -110 ETIMEDOUT  -138 ENOSYS  均做二次重试
            if ((what == -110 || what == -138) && retryCount < MAX_RETRY_COUNT) {
                retryCount++
                coroutineScope.launch {
                    delay(1500 * retryCount.toLong())
                    currentUrl?.let { prepare(it) }
                }
                return true   // 自己消化掉，不抛到 UI 层
            }
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
        prepareJob?.cancel()
        val player = ijkPlayer
        coroutineScope.launch(Dispatchers.IO) {
            playerMutex.withLock {
                try {
                    player.reset()
                    player.release()
                } catch (e: Exception) {
                    log.e("release error", e)
                }
            }
        }
        super.release()
    }

    override fun prepare(url: String) {
        currentUrl = url
        prepareJob?.cancel()
        prepareJob = coroutineScope.launch(Dispatchers.IO) {
            playerMutex.withLock {
                try {
                    ijkPlayer.reset()
                    // 在设置数据源前确保Surface有效
                    if (currentSurface != null) {
                        ijkPlayer.setSurface(currentSurface)
                    }
                    /* 关键：不要带任何自定义头，防止服务器拒SETUP */
                    val headers = emptyMap<String, String>()   // ← 空 map，让 FFmpeg 走原生流程
                    ijkPlayer.setDataSource(context, Uri.parse(url), headers)
                    setOption()
                    ijkPlayer.prepareAsync()
                    retryCount = 0
                } catch (e: Exception) {
                    handleError(e)
                }
            }
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
        coroutineScope.launch(Dispatchers.IO) {
            playerMutex.withLock {
                try {
                    if (!ijkPlayer.isPlaying) {
                        ijkPlayer.start()
                        triggerIsPlayingChanged(true)
                    }
                } catch (e: Exception) {
                    log.e("play error", e)
                }
            }
        }
    }

    override fun pause() {
        coroutineScope.launch(Dispatchers.IO) {
            playerMutex.withLock {
                try {
                    if (ijkPlayer.isPlaying) {
                        ijkPlayer.pause()
                        triggerIsPlayingChanged(false)
                    }
                } catch (e: Exception) {
                    log.e("pause error", e)
                }
            }
        }
    }

    override fun seekTo(position: Long) {
//        ijkPlayer.seekTo(position)
        // 对于直播流（duration <= 0），seek操作不仅无效，还可能导致播放器状态异常。
        // 增加保护，只对点播视频执行seek。
        coroutineScope.launch(Dispatchers.IO) {
            playerMutex.withLock {
                try {
                    if (ijkPlayer.duration > 0) {
                        log.d("Seeking to $position")
                        ijkPlayer.seekTo(position)
                    } else {
                        log.w("Seek is ignored for live streams.")
                    }
                } catch (e: Exception) {
                    log.e("seek error", e)
                }
            }
        }
    }

    override fun stop() {
        updatePositionJob?.cancel()
        prepareJob?.cancel()
        coroutineScope.launch(Dispatchers.IO) {
            playerMutex.withLock {
                try {
                    ijkPlayer.stop()
                } catch (e: Exception) {
                    log.e("stop error", e)
                }
            }
        }
        super.stop()
    }

    // 添加成员变量保存当前Surface
    private var currentSurface: Surface? = null

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                currentSurface = holder.surface
                coroutineScope.launch(Dispatchers.IO) {
                    playerMutex.withLock {
                        try {
                            ijkPlayer.setDisplay(holder)
                            play()
                        } catch (e: Exception) {
                            log.e("setSurface error", e)
                        }
                    }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                pause()
                // 必须在主线程同步执行：在操作系统销毁 Surface 前，让底层的渲染线程解除绑定
                // 否则底层的 IJK 可能会尝试把画面渲染到一个已经被回收的 Surface 上，导致 SIGSEGV (SEGV_MAPERR) 崩溃
                try {
                    ijkPlayer.setDisplay(null)
                    ijkPlayer.setSurface(null)
                } catch (e: Exception) {}
                currentSurface?.release()
                currentSurface = null
            }
        })
        coroutineScope.launch(Dispatchers.IO) {
            playerMutex.withLock {
                try {
                    ijkPlayer.setDisplay(surfaceView.holder)
                } catch (e: Exception) {}
            }
        }
    }

    override fun setVideoTextureView(textureView: TextureView) {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                currentSurface = Surface(surface)
                coroutineScope.launch(Dispatchers.IO) {
                    playerMutex.withLock {
                        try {
                            ijkPlayer.setSurface(currentSurface)
                            play()
                        } catch (e: Exception) {}
                    }
                }
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                pause()
                // 必须在主线程同步解除绑定
                try {
                    ijkPlayer.setSurface(null)
                } catch (e: Exception) {}
                currentSurface?.release()
                currentSurface = null
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        if (textureView.isAvailable) {
            val newSurface = Surface(textureView.surfaceTexture)
            currentSurface = newSurface
            coroutineScope.launch(Dispatchers.IO) {
                playerMutex.withLock {
                    try {
                        ijkPlayer.setSurface(newSurface)
                    } catch (e: Exception) {}
                }
            }
        }
    }
}