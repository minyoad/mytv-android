package top.yogiczy.mytv.core.util

import org.junit.Assert.assertEquals
import org.junit.Test
import top.yogiczy.mytv.core.util.utils.TimingRecorder

class TimingRecorderTest {
    @Test fun cancelledAndRepeatedMeasurementsAreNotReported() {
        var time = 10L
        val samples = mutableListOf<Pair<String, Long>>()
        val recorder = TimingRecorder({ time }) { name, elapsed -> samples += name to elapsed }
        val cancelled = recorder.start("aborted")
        cancelled.cancel(); cancelled.finish()
        val completed = recorder.start("frame")
        time = 35; completed.finish(); completed.finish()
        assertEquals(listOf("frame" to 25L), samples)
    }
}
