package top.yogiczy.mytv.tv.ui.screens.videoplayer.player

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NativePlayerWorkerTest {
    @Test fun blockedNativeCommandDoesNotBlockSubmitOrCloseCaller() {
        val worker = NativePlayerWorker("test-native")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val later = AtomicInteger()
        worker.execute { entered.countDown(); check(unblock.await(5, TimeUnit.SECONDS)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            assertTrue(worker.execute { later.incrementAndGet() })
            worker.close { closed.countDown() }
            assertEquals(0, later.get())
            assertEquals(1L, closed.count)
        } finally { unblock.countDown() }
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertEquals(1, later.get())
    }

    @Test fun nativeAndCleanupCommandsAreFifoOnOneNonCallingThread() {
        val worker = NativePlayerWorker("test-native")
        val caller = Thread.currentThread().id
        val events = Collections.synchronizedList(mutableListOf<Int>())
        val threads = Collections.synchronizedSet(mutableSetOf<Long>())
        val done = CountDownLatch(1)
        worker.execute { threads += Thread.currentThread().id; events += 1 }
        worker.execute { threads += Thread.currentThread().id; events += 2 }
        worker.close { threads += Thread.currentThread().id; events += 3 }
        worker.afterClosed { events += 4; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3, 4), events)
        assertEquals(1, threads.size)
        assertFalse(caller in threads)
        assertFalse(worker.execute { fail("closed worker accepted native command") })
    }
}
