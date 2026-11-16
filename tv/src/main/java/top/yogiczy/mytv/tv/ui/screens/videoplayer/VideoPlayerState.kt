package top.yogiczy.mytv.tv.ui.screens.videoplayer

import android.view.SurfaceView
import android.view.TextureView
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yogiczy.mytv.core.util.utils.TimingRecorder
import top.yogiczy.mytv.tv.data.PlayerSettings
import top.yogiczy.mytv.tv.data.PlayerSettingsRepository
import top.yogiczy.mytv.tv.diagnostics.AppPerformance
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.IJKVideoPlayer
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.Media3VideoPlayer
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs

@Stable
class VideoPlayerState(
    private var instance: VideoPlayer,
    private var playerType: Configs.VideoPlayerType,
    private val createPlayer: (Configs.VideoPlayerType) -> VideoPlayer,
    private val settings: () -> PlayerSettings,
    private val dispatch: (() -> Unit) -> Unit = { it() },
    private val timings: TimingRecorder = TimingRecorder(),
    private val onFirstVideoFrame: () -> Unit = {},
    private val defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
) {
    enum class Phase { IDLE, LOADING, READY, BUFFERING, PLAYING, PAUSED, STOPPED, FAILED, RELEASED }
    data class Status(val phase: Phase = Phase.IDLE, val isPlaying: Boolean = false,
                      val isBuffering: Boolean = false, val error: String? = null)
    var status by mutableStateOf(Status())
        private set
    val error get() = status.error
    val isPlaying get() = status.isPlaying
    val isBuffering get() = status.isBuffering

    var currentUrl: String? = null
        private set
    var displayMode by mutableStateOf(defaultDisplayModeProvider())
    var aspectRatio by mutableFloatStateOf(16f / 9f)
    var duration by mutableLongStateOf(0L)
        private set
    var currentPosition by mutableLongStateOf(0L)
        private set
    var metadata by mutableStateOf(VideoPlayer.Metadata())
    var renderGeneration by mutableLongStateOf(0L)
        private set

    private sealed interface RenderTarget {
        data class Surface(val view: SurfaceView) : RenderTarget
        data class Texture(val view: TextureView) : RenderTarget
    }
    private data class Restore(val position: Long?, val play: Boolean)
    private var renderTarget: RenderTarget? = null
    private var pendingRestore: Restore? = null
    private var measurement: TimingRecorder.Measurement? = null
    private var initialized = false
    private var released = false
    @Volatile private var generation = 0L
    private var playWhenReady = true
    private var isAutoDisplayModeSet = false
    private val onReadyListeners = mutableListOf<() -> Unit>()
    private val onErrorListeners = mutableListOf<() -> Unit>()
    private val onInterruptListeners = mutableListOf<() -> Unit>()

    fun prepare(url: String) = start(url, null)
    private fun start(url: String, restore: Restore?) {
        if (released) return
        generation++
        currentUrl = url
        pendingRestore = restore
        playWhenReady = restore?.play ?: true
        status = Status(Phase.LOADING, isBuffering = true)
        duration = 0
        currentPosition = 0
        metadata = VideoPlayer.Metadata()
        isAutoDisplayModeSet = false
        measurement?.cancel()
        measurement = timings.start("playback_to_first_frame_ms")
        instance.prepare(url, playWhenReady)
    }

    fun play() { if (!released) { playWhenReady = true; instance.play() } }
    fun pause() { if (!released) { playWhenReady = false; instance.pause() } }
    fun seekTo(position: Long) { if (!released) instance.seekTo(position) }
    fun stop() {
        if (released) return
        generation++
        pendingRestore = null
        measurement?.cancel()
        measurement = null
        status = Status(Phase.STOPPED)
        instance.stop()
    }
    fun setVideoSurfaceView(view: SurfaceView) {
        if (released) return
        renderTarget = RenderTarget.Surface(view)
        instance.setVideoSurfaceView(view)
    }
    fun setVideoTextureView(view: TextureView) {
        if (released) return
        renderTarget = RenderTarget.Texture(view)
        instance.setVideoTextureView(view)
    }
    fun clearVideoSurfaceView(view: SurfaceView) {
        if ((renderTarget as? RenderTarget.Surface)?.view === view) {
            renderTarget = null
            if (!released) instance.clearVideoSurface()
        }
    }
    fun clearVideoTextureView(view: TextureView) {
        if ((renderTarget as? RenderTarget.Texture)?.view === view) {
            renderTarget = null
            if (!released) instance.clearVideoSurface()
        }
    }
    fun onReady(listener: () -> Unit) { onReadyListeners.add(listener) }
    fun onError(listener: () -> Unit) { onErrorListeners.add(listener) }
    fun onInterrupt(listener: () -> Unit) { onInterruptListeners.add(listener) }

    fun changePlayerType(type: Configs.VideoPlayerType) {
        if (released || type == playerType) return
        val old = instance
        val replacement = createPlayer(type)
        val url = currentUrl
        // Live Media3 positions may be epoch timestamps, even with a positive sliding-window duration.
        val restore = Restore(currentPosition.takeIf { duration > 0 && it > 0 && it < duration }, playWhenReady)
        // Retire the old engine before release, so its queued callbacks cannot mutate the replacement.
        instance = replacement
        playerType = type
        renderTarget = null
        renderGeneration++
        old.release()
        if (initialized) bind(replacement)
        if (url != null) start(url, restore)
    }

    fun initialize() {
        if (initialized || released) return
        initialized = true
        bind(instance)
    }
    private fun bind(player: VideoPlayer) {
        player.initialize()
        fun event(action: () -> Unit) {
            val request = generation
            dispatch {
                if (!released && instance === player && request == generation &&
                    currentUrl != null && status.phase != Phase.STOPPED && status.phase != Phase.FAILED) action()
            }
        }
        player.onResolution { width, height -> event {
            if (width > 0 && height > 0) {
                aspectRatio = width.toFloat() / height
                if (settings().autoFillForSD && width == 720 && height == 576) {
                    displayMode = VideoPlayerDisplayMode.FILL
                    isAutoDisplayModeSet = true
                }
            }
        } }
        player.onError { ex -> event {
            if (ex != null) {
                measurement?.cancel()
                measurement = null
                pendingRestore = null
                status = Status(Phase.FAILED, error = "${ex.errorCodeName}(${ex.errorCode})")
                onErrorListeners.toList().forEach { it() }
            } else status = status.copy(error = null)
        } }
        player.onReady { event {
            status = status.copy(phase = Phase.READY, isBuffering = false, error = null)
            if (!isAutoDisplayModeSet) displayMode = defaultDisplayModeProvider()
            val restore = pendingRestore
            pendingRestore = null
            if (restore != null) {
                restore.position?.let(player::seekTo)
                if (restore.play) player.play() else player.pause()
            }
            onReadyListeners.toList().forEach { it() }
        } }
        player.onBuffering { buffering -> event {
            val phase = if (buffering) Phase.BUFFERING else if (status.isPlaying) Phase.PLAYING
                else if (status.phase == Phase.BUFFERING) Phase.READY else status.phase
            status = status.copy(phase = phase, isBuffering = buffering, error = if (buffering) null else status.error)
        } }
        player.onIsPlayingChanged { playing -> event {
            status = status.copy(isPlaying = playing, phase = if (status.isBuffering) Phase.BUFFERING
                else if (playing) Phase.PLAYING else Phase.PAUSED)
        } }
        player.onDurationChanged { value -> event { duration = value } }
        player.onCurrentPositionChanged { value -> event { currentPosition = value } }
        player.onMetadata { value -> event { metadata = value } }
        player.onInterrupt { event { onInterruptListeners.toList().forEach { it() } } }
        player.onFirstFrame { event {
            measurement?.finish()
            measurement = null
            onFirstVideoFrame()
        } }
    }

    fun release() {
        if (released) return
        released = true
        generation++
        measurement?.cancel()
        measurement = null
        pendingRestore = null
        renderTarget = null
        currentUrl = null
        status = Status(Phase.RELEASED)
        onReadyListeners.clear()
        onErrorListeners.clear()
        onInterruptListeners.clear()
        instance.release()
    }
}

@Composable
fun rememberVideoPlayerState(
    defaultDisplayModeProvider: () -> VideoPlayerDisplayMode = { VideoPlayerDisplayMode.ORIGINAL },
): VideoPlayerState {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val settings by PlayerSettingsRepository.settings.collectAsState()
    val latestDisplayMode by rememberUpdatedState(defaultDisplayModeProvider)
    val state = remember {
        val create: (Configs.VideoPlayerType) -> VideoPlayer = { type ->
            when (type) {
                Configs.VideoPlayerType.IJK -> IJKVideoPlayer(context, scope)
                Configs.VideoPlayerType.MEDIA3 -> Media3VideoPlayer(context, scope)
            }
        }
        VideoPlayerState(create(settings.type), settings.type, create,
            settings = { PlayerSettingsRepository.settings.value },
            dispatch = { action -> scope.launch(Dispatchers.Main.immediate) { action() } },
            timings = AppPerformance.recorder,
            onFirstVideoFrame = AppPerformance::onFirstVideoFrame,
            defaultDisplayModeProvider = { latestDisplayMode() })
    }
    DisposableEffect(state) {
        state.initialize()
        onDispose { state.release() }
    }
    LaunchedEffect(state, settings.type) { state.changePlayerType(settings.type) }
    return state
}

enum class VideoPlayerDisplayMode(
    val label: String,
    val value: Int,
) {
    /** 原始 */
    ORIGINAL("原始", 0),

    /** 填充 */
    FILL("填充", 1),

    /** 裁剪 */
    CROP("裁剪", 2),

    /** 4:3 */
    FOUR_THREE("4:3", 3),

    /** 16:9 */
    SIXTEEN_NINE("16:9", 4),

    /** 2.35:1 */
    WIDE("2.35:1", 5);

    companion object {
        fun fromValue(value: Int): VideoPlayerDisplayMode {
            return entries.firstOrNull { it.value == value } ?: ORIGINAL
        }
    }
}
