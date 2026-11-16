package top.yogiczy.mytv.tv.ui.screens.videoplayer

import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.yogiczy.mytv.core.util.utils.TimingRecorder
import top.yogiczy.mytv.tv.data.PlayerSettings
import top.yogiczy.mytv.tv.ui.screens.videoplayer.player.VideoPlayer
import top.yogiczy.mytv.tv.ui.utils.Configs

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerStateTest {
    private val settings = PlayerSettings(Configs.VideoPlayerType.MEDIA3, "test", 10000,
        VideoPlayerDisplayMode.ORIGINAL, false, Configs.VideoPlayerRenderMode.SURFACE_VIEW, true, false, true)
    private class Fake(scope: CoroutineScope) : VideoPlayer(scope, { 100L }) {
        val prepared = mutableListOf<String>()
        val seeks = mutableListOf<Long>()
        var plays = 0
        var pauses = 0
        var releases = 0
        override fun prepare(url: String) { prepared += url }
        override fun play() { plays++ }
        override fun pause() { pauses++ }
        override fun seekTo(position: Long) { seeks += position }
        override fun setVideoSurfaceView(surfaceView: SurfaceView) {}
        override fun setVideoTextureView(textureView: TextureView) {}
        override fun release() { releases++; super.release() }
        fun ready() = triggerReady()
        fun playing(value: Boolean) = triggerIsPlayingChanged(value)
        fun buffering(value: Boolean) = triggerBuffering(value)
        fun failed() = triggerError(PlaybackException.LOAD_TIMEOUT)
        fun progress(position: Long, duration: Long) { triggerDuration(duration); triggerCurrentPosition(position) }
        fun firstFrame() = triggerFirstFrame()
    }

    @Test fun statusTransitionsAndNewPrepareClearPreviousMedia() = runTest {
        val player = Fake(backgroundScope)
        val state = VideoPlayerState(player, settings.type, { player }, { settings })
        state.initialize()
        state.prepare("first")
        assertEquals(VideoPlayerState.Phase.LOADING, state.status.phase)
        player.ready(); player.playing(true)
        assertTrue(state.isPlaying)
        player.progress(500, 1000)
        player.buffering(true)
        assertTrue(state.isBuffering)
        player.failed()
        assertFalse(state.isPlaying)
        assertFalse(state.isBuffering)
        assertNotNull(state.error)
        player.playing(true); player.buffering(false); player.ready()
        assertEquals(VideoPlayerState.Phase.FAILED, state.status.phase)
        assertFalse(state.isPlaying)
        state.prepare("second")
        assertNull(state.error)
        assertEquals(0L, state.currentPosition)
        assertEquals(0L, state.duration)
        state.release()
    }

    @Test fun engineSwitchWaitsForReadyBeforeRestoringVodAndPause() = runTest {
        val old = Fake(backgroundScope)
        val replacement = Fake(backgroundScope)
        val state = VideoPlayerState(old, settings.type, { replacement }, { settings })
        state.initialize(); state.prepare("vod"); old.ready(); old.progress(500, 1000)
        state.pause()
        state.changePlayerType(Configs.VideoPlayerType.IJK)
        assertEquals(1, old.releases)
        assertEquals(listOf("vod"), replacement.prepared)
        assertTrue(replacement.seeks.isEmpty())
        assertEquals(0, replacement.pauses)
        replacement.ready()
        assertEquals(listOf(500L), replacement.seeks)
        assertEquals(1, replacement.pauses)
        state.release()
    }

    @Test fun liveSwitchDoesNotSeekAndRepeatedTypeDoesNotRecreate() = runTest {
        val old = Fake(backgroundScope)
        val replacement = Fake(backgroundScope)
        var created = 0
        val state = VideoPlayerState(old, settings.type, { created++; replacement }, { settings })
        state.initialize(); state.prepare("live"); old.progress(5000, 0)
        state.changePlayerType(settings.type)
        assertEquals(0, created)
        state.changePlayerType(Configs.VideoPlayerType.IJK)
        replacement.ready()
        assertTrue(replacement.seeks.isEmpty())
        assertEquals(1, replacement.plays)
        state.release()
    }

    @Test fun queuedEventsFromRetiredEngineCannotOverwriteReplacement() = runTest {
        val old = Fake(backgroundScope)
        val replacement = Fake(backgroundScope)
        val queued = mutableListOf<() -> Unit>()
        val state = VideoPlayerState(old, settings.type, { replacement }, { settings }, dispatch = { queued += it })
        state.initialize(); state.prepare("first"); old.failed(); old.progress(999, 1000)
        state.changePlayerType(Configs.VideoPlayerType.IJK)
        queued.toList().forEach { it() }
        assertNull(state.error)
        assertEquals(0L, state.currentPosition)
        assertEquals(VideoPlayerState.Phase.LOADING, state.status.phase)
        state.release()
    }

    @Test fun changingChannelDuringEngineSwitchCancelsPendingRestore() = runTest {
        val old = Fake(backgroundScope)
        val replacement = Fake(backgroundScope)
        val state = VideoPlayerState(old, settings.type, { replacement }, { settings })
        state.initialize(); state.prepare("first"); old.progress(500, 1000)
        state.changePlayerType(Configs.VideoPlayerType.IJK)
        state.prepare("second"); replacement.ready()
        assertTrue(replacement.seeks.isEmpty())
        assertEquals("second", state.currentUrl)
        state.release()
    }

    @Test fun queuedEventsFromPreviousChannelOnSameEngineAreIgnored() = runTest {
        val player = Fake(backgroundScope)
        val queued = mutableListOf<() -> Unit>()
        val state = VideoPlayerState(player, settings.type, { player }, { settings }, dispatch = { queued += it })
        state.initialize(); state.prepare("first")
        player.failed(); player.progress(999, 1000); player.firstFrame()
        state.prepare("second")
        queued.forEach { it() }
        assertEquals(VideoPlayerState.Phase.LOADING, state.status.phase)
        assertNull(state.error)
        assertEquals(0L, state.currentPosition)
        state.release()
    }

    @Test fun releaseIsIdempotentAndIgnoresQueuedCallbacks() = runTest {
        val player = Fake(backgroundScope)
        val queued = mutableListOf<() -> Unit>()
        val state = VideoPlayerState(player, settings.type, { player }, { settings }, dispatch = { queued += it })
        state.initialize(); state.prepare("first"); player.playing(true)
        state.release(); state.release()
        queued.forEach { it() }
        assertEquals(1, player.releases)
        assertEquals(VideoPlayerState.Phase.RELEASED, state.status.phase)
        assertFalse(state.isPlaying)
        assertNull(state.currentUrl)
    }

    @Test fun firstFrameMetricExcludesInterruptedAttemptsAndDuplicates() = runTest {
        var time = 0L
        val samples = mutableListOf<Long>()
        val player = Fake(backgroundScope)
        val recorder = TimingRecorder({ time }) { _, elapsed -> samples += elapsed }
        val state = VideoPlayerState(player, settings.type, { player }, { settings }, timings = recorder)
        state.initialize(); state.prepare("aborted")
        time = 10; state.prepare("completed")
        time = 30; player.firstFrame(); player.firstFrame()
        assertEquals(listOf(20L), samples)
        state.release()
    }
}
