package com.mozhi.reader.feature.importer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class BatchBookProgress(val index: Int, val uri: String, val stage: String, val completed: Int, val total: Int)

/** Coalesce fast books and stages as well as byte updates; notification/database writes have one rate limit. */
internal typealias BatchImportProgressReporter = ImportProgressReporter<BatchBookProgress>

internal class ImportProgressReporter<T>(scope: CoroutineScope, publish: suspend (T) -> Unit) {
    private val updates = Channel<T>(Channel.CONFLATED)
    private val job = scope.launch {
        for (progress in updates) {
            publish(progress)
            delay(750)
        }
    }

    fun update(progress: T) { updates.trySend(progress) }

    suspend fun close() {
        updates.close()
        job.cancelAndJoin()
    }
}
