package top.yogiczy.mytv.tv.ui.screens.main

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestTaskTest {
    @Test fun repeatedResumeOrChannelRefreshDoesNotRestartActiveMetadata() = runTest {
        val tasks = LatestTask<String>(backgroundScope)
        var starts = 0
        tasks.start("same-source") { starts++; awaitCancellation() }
        runCurrent()
        tasks.start("same-source") { starts++ }
        runCurrent()
        assertEquals(1, starts)
        tasks.cancel()
    }

    @Test fun newSourceDoesNotWaitForOldSourcesNetworkTimeout() = runTest {
        val tasks = LatestTask<String>(backgroundScope)
        var cancelled = false
        var newVisible = false
        tasks.start("old") {
            try { awaitCancellation() } finally { cancelled = true }
        }
        runCurrent()
        tasks.start("new") { newVisible = true }
        runCurrent()
        assertTrue(cancelled)
        assertTrue(newVisible)
    }

    @Test fun completedWorkCanBeRefreshedAgain() = runTest {
        val tasks = LatestTask<String>(backgroundScope)
        var starts = 0
        tasks.start("source") { starts++ }
        runCurrent()
        tasks.start("source") { starts++ }
        runCurrent()
        assertEquals(2, starts)
    }

    @Test fun cancelledWorkCannotPublishAndSameKeyCanRestartAfterReset() = runTest {
        val metadata = LatestTask<String>(backgroundScope)
        val waiting = CompletableDeferred<Unit>()
        var displayed = false
        metadata.start("epg") { waiting.await(); displayed = true }
        runCurrent()
        metadata.cancel()
        waiting.complete(Unit)
        runCurrent()
        assertFalse(displayed)
        metadata.start("epg") { displayed = true }
        runCurrent()
        assertTrue(displayed)
    }
}
