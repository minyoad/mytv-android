package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import tv.danmaku.ijk.media.player.IMediaPlayer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class IJKVideoPlayerTest {
    @Test fun missingNativeLibraryIsReportedWithoutCrashingUiOrRetryingForever() = runBlocking<Unit> {
        withTimeout(5000) {
            val failure = CompletableDeferred<VideoPlayer.PlaybackException?>()
            var creations = 0
            val player = IJKVideoPlayer(mock(Context::class.java), this,
                playerFactory = { creations++; throw UnsatisfiedLinkError("unsupported ABI") },
                timeoutProvider = { 10000L }, dispatchToUi = { action -> launch { action() }; Unit },
                report = { _, _ -> })
            player.initialize()
            player.onError { failure.complete(it) }
            try {
                player.prepare("https://example.invalid/live.ts")
                assertEquals("IJK_NATIVE_UNAVAILABLE", failure.await()?.errorCodeName)
                assertEquals(1, creations)
            } finally { player.release() }
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val mainThread = Thread.currentThread().id
        val threads = Collections.synchronizedSet(mutableSetOf<Long>())
        private val preparedListener = AtomicReference<IMediaPlayer.OnPreparedListener>()
        val ready = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val metadataEntered = CompletableDeferred<Unit>()
        val metadataGate = CountDownLatch(1)
        val blockMetadata = AtomicBoolean()
        val blockDetach = AtomicBoolean()
        val detachEntered = CompletableDeferred<Unit>()
        val detachGate = CountDownLatch(1)
        val native = mock(IMediaPlayer::class.java) { call ->
            threads += Thread.currentThread().id
            when (call.method.name) {
                "setOnPreparedListener" -> { preparedListener.set(call.arguments[0] as IMediaPlayer.OnPreparedListener); null }
                "prepareAsync" -> { preparedListener.get().onPrepared(call.mock as IMediaPlayer); null }
                "start" -> { started.complete(Unit); null }
                "release" -> { released.complete(Unit); null }
                "getMediaInfo" -> {
                    if (blockMetadata.get()) { metadataEntered.complete(Unit); check(metadataGate.await(5, TimeUnit.SECONDS)) }
                    null
                }
                "setSurface" -> {
                    if (call.arguments[0] == null && blockDetach.get()) {
                        detachEntered.complete(Unit)
                        check(detachGate.await(5, TimeUnit.SECONDS))
                    }
                    null
                }
                else -> RETURNS_DEFAULTS.answer(call)
            }
        }
        val surface = mock(Surface::class.java).also { `when`(it.isValid).thenReturn(true) }
        val holder = mock(SurfaceHolder::class.java).also { `when`(it.surface).thenReturn(surface) }
        val view = mock(SurfaceView::class.java).also { `when`(it.holder).thenReturn(holder) }
        val player = IJKVideoPlayer(mock(Context::class.java), scope,
            playerFactory = { threads += Thread.currentThread().id; native },
            timeoutProvider = { 10000L }, surfaceFactory = { surface },
            dispatchToUi = { action -> scope.launch { action() }; Unit }, report = { _, _ -> })
        init { player.initialize(); player.onReady { ready.complete(Unit) } }
        fun unblock() { metadataGate.countDown(); detachGate.countDown() }
    }

    @Test fun alreadyCreatedSurfaceIsReattachedAfterResetAndCallbacksAreNotDuplicated() = runBlocking<Unit> {
        withTimeout(5000) {
            val f = Fixture(this)
            try {
                f.player.setVideoSurfaceView(f.view)
                f.player.setVideoSurfaceView(f.view)
                f.player.prepare("https://example.invalid/live.ts")
                f.ready.await(); f.started.await()
                verify(f.holder, times(1)).addCallback(any(SurfaceHolder.Callback::class.java))
                val calls = inOrder(f.native)
                calls.verify(f.native).reset()
                calls.verify(f.native).setSurface(f.surface)
                calls.verify(f.native).setDataSource("https://example.invalid/live.ts")
                calls.verify(f.native).prepareAsync()
                calls.verify(f.native).start()
            } finally { f.unblock(); f.player.release(); f.released.await() }
            verify(f.holder, times(1)).removeCallback(any(SurfaceHolder.Callback::class.java))
            verify(f.surface, never()).release() // SurfaceView owns this surface, not the player
            assertFalse(f.mainThread in f.threads)
        }
    }

    @Test fun preparedEngineWaitsForNewViewBeforeStarting() = runBlocking<Unit> {
        withTimeout(5000) {
            val f = Fixture(this)
            try {
                f.player.prepare("https://example.invalid/live.ts")
                f.ready.await()
                assertFalse(f.started.isCompleted)
                f.player.setVideoSurfaceView(f.view)
                f.started.await()
            } finally { f.unblock(); f.player.release(); f.released.await() }
        }
    }

    @Test fun blockedMetadataQueryCannotFreezeUiOrReleaseCaller() = runBlocking<Unit> {
        withTimeout(5000) {
            val f = Fixture(this)
            f.blockMetadata.set(true)
            try {
                f.player.setVideoSurfaceView(f.view)
                f.player.prepare("https://example.invalid/live.ts")
                f.ready.await(); f.metadataEntered.await()
                f.player.pause()
                f.player.release() // Must return while native getMediaInfo remains blocked.
                assertFalse(f.released.isCompleted)
                assertFalse(f.mainThread in f.threads)
            } finally { f.unblock(); f.player.release(); f.released.await() }
        }
    }

    @Test fun pausedPreparationDoesNotAutostartAfterEngineSwitch() = runBlocking<Unit> {
        withTimeout(5000) {
            val f = Fixture(this)
            try {
                f.player.setVideoSurfaceView(f.view)
                f.player.prepare("https://example.invalid/live.ts", false)
                f.ready.await()
                assertFalse(f.started.isCompleted)
                f.player.play()
                f.started.await()
            } finally { f.unblock(); f.player.release(); f.released.await() }
        }
    }

    @Test fun textureDestructionReturnsBeforeBlockedDetachAndDefersOwnedResources() = runBlocking<Unit> {
        withTimeout(5000) {
            val f = Fixture(this)
            val texture = mock(SurfaceTexture::class.java)
            val view = mock(TextureView::class.java)
            val listener = AtomicReference<TextureView.SurfaceTextureListener>()
            `when`(view.isAvailable).thenReturn(true)
            `when`(view.surfaceTexture).thenReturn(texture)
            doAnswer { call -> listener.set(call.arguments[0] as TextureView.SurfaceTextureListener); null }
                .`when`(view).setSurfaceTextureListener(any(TextureView.SurfaceTextureListener::class.java))
            val textureReleased = CompletableDeferred<Unit>()
            doAnswer { textureReleased.complete(Unit); null }.`when`(texture).release()
            try {
                f.player.setVideoTextureView(view)
                f.player.setVideoTextureView(view)
                f.player.prepare("https://example.invalid/live.ts")
                f.ready.await(); f.started.await()
                f.blockDetach.set(true)
                assertFalse(listener.get().onSurfaceTextureDestroyed(texture))
                f.detachEntered.await()
                assertFalse(textureReleased.isCompleted)
                verify(f.surface, never()).release()
                f.detachGate.countDown()
                textureReleased.await()
                val calls = inOrder(f.native, f.surface, texture)
                calls.verify(f.native).setSurface(null)
                calls.verify(f.surface).release()
                calls.verify(texture).release()
                verify(view, times(1)).setSurfaceTextureListener(any(TextureView.SurfaceTextureListener::class.java))
            } finally { f.unblock(); f.player.release(); f.released.await() }
        }
    }
}
