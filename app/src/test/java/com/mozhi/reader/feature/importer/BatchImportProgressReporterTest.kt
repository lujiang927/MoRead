package com.mozhi.reader.feature.importer

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BatchImportProgressReporterTest {
    @Test fun `rapid books and stages publish only the latest bounded-rate progress`() = runTest {
        val published = mutableListOf<BatchBookProgress>()
        val reporter = BatchImportProgressReporter(this) { published += it }
        reporter.update(BatchBookProgress(0, "book0", "reading", 0, 100))
        runCurrent()
        repeat(500) { index ->
            reporter.update(BatchBookProgress(index, "book$index", "decoding", 30, 100))
            reporter.update(BatchBookProgress(index, "book$index", "writing", 80, 100))
        }
        runCurrent()
        assertEquals(1, published.size)
        advanceTimeBy(750)
        runCurrent()
        assertEquals(2, published.size)
        assertEquals(499, published.last().index)
        assertEquals("writing", published.last().stage)
        reporter.close()
        advanceTimeBy(1500)
        assertEquals(2, published.size)
    }
}
