package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import tv.danmaku.ijk.media.player.IMediaPlayer
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class IjkVideoPlayer(
    private val context: Context,
    private val coroutineScope: CoroutineScope,
) : VideoPlayer(coroutineScope) {

    private val ijkMediaPlayer: IMediaPlayer by lazy {
        IjkMediaPlayer().apply {
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32.toLong())
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "rtsp,http,https,tcp,udp,file,crypto")
            setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "rtsp_transport", "tcp") // 使用 TCP 传输
            setOption( IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1);

        }
    }

    private var updatePositionJob: Job? = null

    override fun initialize() {
        super.initialize()
        ijkMediaPlayer.setOnVideoSizeChangedListener { iMediaPlayer: IMediaPlayer, width: Int, height: Int, i2: Int, i3: Int ->
            triggerResolution(width, height)
        }
        ijkMediaPlayer.setOnErrorListener { _, what, extra ->
            triggerError(PlaybackException("ijkplayer error", what))
            false
        }
        ijkMediaPlayer.setOnInfoListener { _, what, extra ->
            when (what) {
                IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                    triggerReady()
                    updatePositionJob?.cancel()
                    updatePositionJob = coroutineScope.launch {
                        while (true) {
                            triggerCurrentPosition(ijkMediaPlayer.currentPosition.toLong())
                            delay(1000)
                        }
                    }
                    triggerDuration(ijkMediaPlayer.duration.toLong())
                }
                IMediaPlayer.MEDIA_INFO_BUFFERING_START -> triggerBuffering(true)
                IMediaPlayer.MEDIA_INFO_BUFFERING_END -> triggerBuffering(false)
            }
            true
        }
        ijkMediaPlayer.setOnPreparedListener {
            triggerPrepared()
            ijkMediaPlayer.start()
        }
    }

    override fun release() {
        updatePositionJob?.cancel()
        ijkMediaPlayer.release()
        super.release()
    }

    override fun prepare(url: String) {
        ijkMediaPlayer.reset()
        ijkMediaPlayer.setDataSource(url)
        ijkMediaPlayer.prepareAsync()
    }

    override fun play() {
        ijkMediaPlayer.start()
    }

    override fun pause() {
        ijkMediaPlayer.pause()
    }

    override fun seekTo(position: Long) {
        ijkMediaPlayer.seekTo(position)
    }

    override fun stop() {
        ijkMediaPlayer.stop()
        updatePositionJob?.cancel()
        super.stop()
    }

    override fun setVideoSurfaceView(surfaceView: SurfaceView) {
        ijkMediaPlayer.setDisplay(surfaceView.holder)
    }

    override fun setVideoTextureView(textureView: TextureView) {
        // ijkMediaPlayer.setSurface(textureView.surface)
        TODO("Not yet implemented")
    }
}
