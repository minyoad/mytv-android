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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.tv.ui.utils.Configs
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val MAX_RETRY_COUNT = 3
private val RETRY_DELAYS_MS = listOf(500L, 1500L, 3000L)
private val RETRYABLE_ERROR_CODES = setOf(-10000, -110, -138)

class IJKVideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
    private val playerFactory: () -> IMediaPlayer = { IjkMediaPlayer() },
    private val timeoutProvider: () -> Long = { Configs.videoPlayerLoadTimeout },
    private val surfaceFactory: (SurfaceTexture) -> Surface = { Surface(it) },
    private val dispatchToUi: (() -> Unit) -> Unit = { action ->
        coroutineScope.launch(Dispatchers.Main.immediate) { action() }; Unit
    },
    private val report: (String, Throwable?) -> Unit = { message, error ->
        val log = Logger.create("IJKVideoPlayer")
        if (error == null) log.w(message) else log.e(message, error)
    },
) : VideoPlayer(coroutineScope, timeoutProvider) {
    private val worker = NativePlayerWorker("ijk-native")
    private val epoch = AtomicLong()
    @Volatile private var released = false
    @Volatile private var playWhenReady = true
    private var player: IMediaPlayer? = null // accessed only by worker
    private var prepared = false // accessed only by worker
    private var started = false // accessed only by worker
    private var nativeOutput: Surface? = null // accessed only by worker
    private var currentUrl: String? = null
    private var retryCount = 0
    private var retryJob: Job? = null
    private var frameTimeoutJob: Job? = null
    private var pollJob: Job? = null
    private val pollPending = AtomicBoolean()
    private val metadataPending = AtomicBoolean()

    private data class Output(val surface: Surface, val owned: Boolean)
    @Volatile private var output: Output? = null
    private var boundSurfaceView: SurfaceView? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var boundTextureView: TextureView? = null

    private fun valid(token: Long) = !released && token == epoch.get()
    private fun onUi(token: Long, action: () -> Unit) = dispatchToUi { if (valid(token)) action() }
    private fun native(token: Long, action: (IMediaPlayer) -> Unit) {
        worker.execute {
            if (valid(token)) {
                try { player?.let(action) }
                catch (error: Exception) { onUi(token) { handleError(token, -1, error) } }
            }
        }
    }

    override fun initialize() {
        super.initialize()
        // Native creation is deferred to the serialized prepare command.
    }

    override fun prepare(url: String) = prepare(url, true)
    override fun prepare(url: String, playWhenReady: Boolean) {
        if (released) return
        currentUrl = url
        this.playWhenReady = playWhenReady
        retryCount = 0
        retryJob?.cancel()
        beginAttempt(url, epoch.incrementAndGet())
    }

    private fun beginAttempt(url: String, token: Long) {
        pollJob?.cancel()
        frameTimeoutJob?.cancel()
        super.stop()
        triggerBuffering(true)
        triggerPrepared() // start the load watchdog before JNI/network preparation, not after onPrepared
        frameTimeoutJob = coroutineScope.launch {
            delay(timeoutProvider())
            if (valid(token)) fail(token, PlaybackException.LOAD_TIMEOUT)
        }
        worker.execute {
            if (!valid(token)) return@execute
            try {
                val nativePlayer = player ?: playerFactory().also { player = it }
                prepared = false
                started = false
                nativePlayer.reset()
                installListeners(nativePlayer, token)
                // reset clears the native renderer. Reattach even if the view existed before this engine.
                nativeOutput = output?.surface
                nativePlayer.setSurface(nativeOutput)
                configure(nativePlayer)
                if (url.startsWith("content:", ignoreCase = true)) {
                    nativePlayer.setDataSource(context, Uri.parse(url), emptyMap())
                } else nativePlayer.setDataSource(url)
                nativePlayer.prepareAsync()
            } catch (error: Exception) {
                onUi(token) { handleError(token, -1, error) }
            } catch (error: LinkageError) {
                onUi(token) {
                    report("IJK native library unavailable", error)
                    fail(token, PlaybackException("IJK_NATIVE_UNAVAILABLE", 10004))
                }
            }
        }
    }

    private fun configure(nativePlayer: IMediaPlayer) {
        val ijk = nativePlayer as? IjkMediaPlayer ?: return
        ijk.apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_timeout", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "http-detect-range-support", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "reconnect", 2)
            // FFmpeg timeouts are microseconds; the application setting is milliseconds.
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "timeout", timeoutProvider() * 1000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 2_000_000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1_000_000L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 512L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "fflags", "fastseek")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "allowed_extensions", "ALL")
            if (Configs.videoPlayerForceSoftDecode) {
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 0)
            } else {
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
            }
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "crypto,file,http,https,tcp,tls,udp,rtp,rtmp,rtsp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "fast", 1)
            // Start explicitly after preparation AND output binding; preserve a paused engine switch.
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_flags", "prefer_tcp")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "buffer_size", 1316)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "infbuf", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1L)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 0)
        }
    }

    private fun installListeners(nativePlayer: IMediaPlayer, token: Long) {
        nativePlayer.setOnPreparedListener {
            native(token) { p ->
                prepared = true
                onUi(token) {
                    retryCount = 0
                    triggerReady()
                    startPolling(token)
                }
                startIfPossible(p, token)
                updateMetadata(p, token)
            }
        }
        nativePlayer.setOnInfoListener { _, what, _ ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> onUi(token) { triggerBuffering(true) }
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> onUi(token) { triggerBuffering(false) }
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    onUi(token) {
                        frameTimeoutJob?.cancel()
                        triggerFirstFrame()
                        triggerIsPlayingChanged(true)
                    }
                    requestMetadata(token)
                }
            }
            true
        }
        nativePlayer.setOnErrorListener { _, what, extra ->
            onUi(token) { handleError(token, what, IllegalStateException("IJK error $what/$extra")) }
            true
        }
        nativePlayer.setOnVideoSizeChangedListener { _, width, height, _, _ ->
            onUi(token) { triggerResolution(width, height) }
            requestMetadata(token)
        }
        nativePlayer.setOnCompletionListener { onUi(token) { triggerIsPlayingChanged(false) } }
    }

    private fun startIfPossible(p: IMediaPlayer, token: Long) {
        if (prepared && playWhenReady && nativeOutput != null && !started) {
            p.start()
            started = true
            onUi(token) { triggerIsPlayingChanged(true) }
        }
    }

    override fun play() {
        if (released) return
        playWhenReady = true
        val token = epoch.get()
        native(token) { startIfPossible(it, token) }
    }
    override fun pause() {
        if (released) return
        playWhenReady = false
        val token = epoch.get()
        native(token) { p ->
            if (prepared && started) { p.pause(); started = false }
            onUi(token) { triggerIsPlayingChanged(false) }
        }
    }
    override fun seekTo(position: Long) {
        val token = epoch.get()
        native(token) { p ->
            val duration = p.duration
            if (prepared && duration > 0 && position in 0 until duration) p.seekTo(position)
        }
    }
    override fun stop() {
        if (released) return
        val token = epoch.incrementAndGet()
        playWhenReady = false
        cancelTimers()
        native(token) { p -> if (prepared) p.stop(); prepared = false; started = false }
        super.stop()
    }
    override fun release() {
        if (released) return
        released = true
        epoch.incrementAndGet()
        cancelTimers()
        removeViewCallbacks()
        val retired = output
        output = null
        super.release()
        worker.close {
            try { player?.setSurface(null) } catch (error: Exception) { report("IJK detach failed", error) }
            try { player?.release() } catch (error: Exception) { report("IJK release failed", error) }
            finally { if (retired?.owned == true) retired.surface.release(); player = null }
        }
    }
    private fun cancelTimers() { pollJob?.cancel(); retryJob?.cancel(); frameTimeoutJob?.cancel() }

    private fun startPolling(token: Long) {
        pollJob?.cancel()
        pollJob = coroutineScope.launch {
            while (isActive && valid(token)) {
                if (pollPending.compareAndSet(false, true)) {
                    if (!worker.execute {
                        try {
                            if (valid(token) && prepared) {
                                val position = player?.currentPosition ?: 0L
                                val duration = player?.duration ?: 0L
                                onUi(token) { triggerCurrentPosition(position); triggerDuration(duration) }
                            }
                        } catch (error: Exception) { report("IJK position query failed", error) }
                        finally { pollPending.set(false) }
                    }) pollPending.set(false)
                }
                delay(1000)
            }
        }
    }
    private fun requestMetadata(token: Long) {
        if (!metadataPending.compareAndSet(false, true)) return
        if (!worker.execute {
            try { if (valid(token)) player?.let { updateMetadata(it, token) } }
            finally { metadataPending.set(false) }
        }) metadataPending.set(false)
    }
    private fun updateMetadata(p: IMediaPlayer, token: Long) {
        try {
            val info = p.mediaInfo ?: return
            val video = info.mMeta?.mVideoStream
            val audio = info.mMeta?.mAudioStream
            val fpsNum = video?.mFpsNum ?: 0
            val fpsDen = video?.mFpsDen ?: 0
            val layout = audio?.mChannelLayout ?: 0L
            val result = Metadata(
                videoMimeType = video?.mCodecName.toMimeType(true),
                videoWidth = video?.mWidth?.takeIf { it > 0 } ?: p.videoWidth,
                videoHeight = video?.mHeight?.takeIf { it > 0 } ?: p.videoHeight,
                videoFrameRate = if (fpsNum > 0 && fpsDen > 0) fpsNum.toFloat() / fpsDen else 0f,
                videoBitrate = (video?.mBitrate?.takeIf { it > 0 } ?: info.mMeta?.mBitrate ?: 0L).toInt(),
                videoDecoder = info.mVideoDecoder.orEmpty(),
                audioMimeType = audio?.mCodecName.toMimeType(false),
                audioChannels = if (layout > 0) java.lang.Long.bitCount(layout) else 0,
                audioSampleRate = audio?.mSampleRate ?: 0,
                audioDecoder = info.mAudioDecoder.orEmpty(),
            )
            onUi(token) {
                metadata = result
                triggerMetadata(result)
                if (info.mMeta != null && video == null && audio != null) frameTimeoutJob?.cancel()
            }
        } catch (error: Exception) { report("IJK metadata query failed", error) }
    }

    private fun handleError(token: Long, what: Int, error: Exception) {
        if (!valid(token)) return
        report("IJK playback error", error)
        if ((what in RETRYABLE_ERROR_CODES || what == -1) && retryCount < MAX_RETRY_COUNT) {
            val url = currentUrl ?: return
            val wait = RETRY_DELAYS_MS[retryCount++]
            val next = epoch.incrementAndGet()
            pollJob?.cancel()
            frameTimeoutJob?.cancel()
            triggerBuffering(true)
            retryJob?.cancel()
            retryJob = coroutineScope.launch { delay(wait); if (valid(next)) beginAttempt(url, next) }
        } else fail(token, PlaybackException("IJKPlayerError", what))
    }
    private fun fail(token: Long, error: PlaybackException) {
        if (!valid(token)) return
        epoch.incrementAndGet()
        cancelTimers()
        triggerBuffering(false)
        triggerIsPlayingChanged(false)
        triggerError(error)
    }

    private fun removeViewCallbacks() {
        surfaceCallback?.let { boundSurfaceView?.holder?.removeCallback(it) }
        boundSurfaceView = null
        surfaceCallback = null
        // Keep a TextureView's destruction callback until detach: returning false defers texture release.
        boundTextureView = null
    }
    private fun replaceOutput(next: Output?, afterDetach: () -> Unit = {}) {
        val previous = output
        output = next
        val token = epoch.get()
        val accepted = worker.execute {
            try {
                // Always detach in FIFO order before releasing old owned surfaces/textures.
                // A newer UI binding must not make us skip an earlier detach.
                nativeOutput = if (released) null else next?.surface
                if (nativeOutput == null && prepared && started) {
                    player?.pause()
                    started = false
                    onUi(token) { triggerIsPlayingChanged(false) }
                }
                player?.setSurface(nativeOutput)
                if (!released && nativeOutput != null) player?.let { startIfPossible(it, token) }
            } catch (error: Exception) { onUi(token) { handleError(token, -1, error) } }
            finally { if (previous?.owned == true && previous !== next) previous.surface.release(); afterDetach() }
        }
        if (!accepted) worker.afterClosed {
            if (previous?.owned == true && previous !== next) previous.surface.release()
            afterDetach()
        }
    }
    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        val view = surfaceView
        if (released || boundSurfaceView === view) return
        removeViewCallbacks()
        boundSurfaceView = view
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (!released && boundSurfaceView === view) replaceOutput(Output(holder.surface, false))
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (boundSurfaceView === view) replaceOutput(null)
            }
        }
        surfaceCallback = callback
        view.holder.addCallback(callback)
        if (view.holder.surface.isValid) replaceOutput(Output(view.holder.surface, false))
    }
    override fun setVideoTextureView(textureView: TextureView) {
        val view = textureView
        if (released || boundTextureView === view) return
        removeViewCallbacks()
        boundTextureView = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                if (!released && boundTextureView === view) replaceOutput(Output(surfaceFactory(texture), true))
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                if (boundTextureView === view) replaceOutput(null) { texture.release() }
                else if (!worker.execute { texture.release() }) worker.afterClosed { texture.release() }
                return false // release only after the native renderer has detached on its worker
            }
        }
        if (view.isAvailable) view.surfaceTexture?.let { replaceOutput(Output(surfaceFactory(it), true)) }
    }
    override fun clearVideoSurface() {
        if (released) return
        removeViewCallbacks()
        replaceOutput(null)
    }

    private fun String?.toMimeType(isVideo: Boolean): String {
        if (isNullOrEmpty()) return ""
        val videoMap = mapOf(
            "h264" to "video/avc",
            "hevc" to "video/hevc",
            "mpeg2video" to "video/mpeg2",
            "mpeg4" to "video/mp4v-es",
            "vp8" to "video/x-vnd.on2.vp8",
            "vp9" to "video/x-vnd.on2.vp9",
            "av1" to "video/av01",
            "mjpeg" to "video/jpeg",
        )
        val audioMap = mapOf(
            "aac" to "audio/mp4a-latm",
            "aac_latm" to "audio/mp4a-latm",
            "mp3" to "audio/mpeg",
            "ac3" to "audio/ac3",
            "eac3" to "audio/eac3",
            "dts" to "audio/vnd.dts",
            "opus" to "audio/opus",
            "vorbis" to "audio/vorbis",
            "flac" to "audio/flac",
            "pcm_mulaw" to "audio/g711-mulaw",
            "pcm_alaw" to "audio/g711-alaw",
        )
        return (if (isVideo) videoMap else audioMap)[this]
            ?: if (isVideo) "video/$this"
            else if (startsWith("pcm_")) "audio/raw"
            else "audio/$this"
    }

}
