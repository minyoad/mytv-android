package top.yogiczy.mytv.tv.diagnostics

import android.os.SystemClock
import top.yogiczy.mytv.core.data.utils.Logger
import top.yogiczy.mytv.core.util.utils.TimingRecorder

object AppPerformance {
    private val log = Logger.create("Performance")
    val recorder = TimingRecorder(SystemClock::elapsedRealtime) { name, elapsed -> log.i("$name=$elapsed") }
    private var composition: TimingRecorder.Measurement? = null
    private var firstVideo: TimingRecorder.Measurement? = null

    fun start() {
        composition = recorder.start("startup_to_composition_ms")
        firstVideo = recorder.start("startup_to_first_video_frame_ms")
    }
    fun onComposition() { composition?.finish(); composition = null }
    fun onFirstVideoFrame() { firstVideo?.finish(); firstVideo = null }
}
