package top.yogiczy.mytv.tv.ui.screens.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Coalesce identical active work and cancel superseded work before starting a new request. */
internal class LatestTask<K>(private val scope: CoroutineScope) {
    private var key: K? = null
    private var job: Job? = null

    fun start(requestKey: K, block: suspend () -> Unit) {
        if (key == requestKey && job?.isActive == true) return
        job?.cancel()
        key = requestKey
        job = scope.launch { block() }
    }

    fun cancel() { job?.cancel(); job = null; key = null }
}
