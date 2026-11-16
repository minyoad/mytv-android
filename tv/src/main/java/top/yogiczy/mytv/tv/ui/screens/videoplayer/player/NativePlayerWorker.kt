package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import java.util.concurrent.Executors

/** JNI can wait on decoder/render locks. Never run it on, or join it from, the Android UI thread. */
internal class NativePlayerWorker(name: String) {
    private val executor = Executors.newSingleThreadExecutor { action -> Thread(action, name).apply { isDaemon = true } }
    private val lock = Any()
    private var closed = false
    private var finished = false
    private val afterClose = mutableListOf<() -> Unit>()

    fun execute(action: () -> Unit): Boolean = synchronized(lock) {
        if (closed) false else { executor.execute(action); true }
    }

    fun close(cleanup: () -> Unit) = synchronized(lock) {
        if (!closed) {
            closed = true
            executor.execute {
                try { cleanup() } finally {
                    val callbacks = synchronized(lock) {
                        finished = true
                        afterClose.toList().also { afterClose.clear() }
                    }
                    callbacks.forEach { runCatching(it) }
                }
            }
            executor.shutdown()
        }
    }

    fun afterClosed(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (finished) true else { afterClose += action; false }
        }
        if (runNow) action()
    }
}
