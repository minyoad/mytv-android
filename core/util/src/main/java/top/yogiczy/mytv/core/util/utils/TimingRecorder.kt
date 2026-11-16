package top.yogiczy.mytv.core.util.utils

import java.util.concurrent.atomic.AtomicBoolean

/** Monotonic, single-use measurements. Interrupted attempts never become successful timing samples. */
class TimingRecorder(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onSample: (String, Long) -> Unit = { _, _ -> },
) {
    inner class Measurement internal constructor(private val name: String, private val startedAt: Long) {
        private val closed = AtomicBoolean()
        fun finish() {
            if (closed.compareAndSet(false, true)) onSample(name, (clock() - startedAt).coerceAtLeast(0))
        }
        fun cancel() { closed.set(true) }
    }
    fun start(name: String) = Measurement(name, clock())
}
