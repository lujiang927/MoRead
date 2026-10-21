package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.importer.DuplicateBookException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BatchImportRunnerTest {
    private fun request(size: Int, groups: Boolean = false) = BatchImportRequestPayload(
        (1..size).map { BatchImportRequestEntry("file:///book-$it.txt", if (groups) "分类" else "") }, true
    )

    @Test fun `five hundred entries stay serial and isolate ordinary failures and duplicates`() = runTest {
        var active = 0
        var calls = 0
        var checkpoint = BatchImportCheckpoint()
        var deleted = 0
        val result = BatchImportRunner(
            importBook = { _, progress ->
                assertEquals(0, active++)
                calls++
                progress("识别章节", 1, 2)
                active--
                when {
                    calls % 10 == 0 -> throw IllegalArgumentException("坏文件")
                    calls % 10 == 1 -> throw DuplicateBookException(1, "重复", false)
                    else -> calls.toLong()
                }
            }, assignGroup = { _, _ -> }, persist = { checkpoint = it }, deleteSource = { deleted++ },
            progress = { _, _, _, _, _ -> }
        ).run(request(500))
        assertEquals(500, calls)
        assertEquals(500, result.nextIndex)
        assertEquals(400, result.succeeded)
        assertEquals(50, result.failed)
        assertEquals(50, result.skipped)
        assertEquals(8, result.failures.size)
        assertEquals(8, result.skippedLabels.size)
        assertEquals(450, deleted)
        assertEquals(result, checkpoint)
    }

    @Test fun `stop after seventeen books resumes without importing completed entries`() = runTest {
        var checkpoint = BatchImportCheckpoint()
        val imported = mutableListOf<String>()
        var stop = true
        fun runner() = BatchImportRunner(
            importBook = { uri, _ ->
                if (stop && imported.size == 17) throw CancellationException("worker stopped")
                imported += uri
                imported.size.toLong()
            }, assignGroup = { _, _ -> }, persist = { checkpoint = it }, deleteSource = {},
            progress = { _, _, _, _, _ -> }
        )
        try { runner().run(request(500)); fail("expected stop") } catch (_: CancellationException) { }
        assertEquals(17, checkpoint.nextIndex)
        assertEquals(17, checkpoint.succeeded)
        stop = false
        val result = runner().run(request(500).copy(checkpoint = checkpoint))
        assertEquals(500, result.succeeded)
        assertEquals(500, imported.distinct().size)
        assertEquals(500, imported.size)
    }

    @Test fun `resume uses committed book before grouping and checkpoint precedes source deletion`() = runTest {
        var checkpoint = BatchImportCheckpoint()
        try {
            BatchImportRunner({ _, _ -> 42 }, { _, _ -> throw CancellationException() },
                { checkpoint = it }, { fail("must not delete incomplete entry") }, { _, _, _, _, _ -> })
                .run(request(1, groups = true))
            fail("expected stop")
        } catch (_: CancellationException) { }
        assertEquals(0, checkpoint.nextIndex)
        assertEquals(42L, checkpoint.importedBookId)
        var grouped = false
        var deleted = false
        val result = BatchImportRunner({ _, _ -> error("must not reimport") }, { id, group ->
            assertEquals(42L, id); assertEquals("分类", group); grouped = true
        }, { checkpoint = it }, {
            assertEquals(1, checkpoint.nextIndex); assertTrue(grouped); deleted = true
        }, { _, _, _, _, _ -> }).run(request(1, groups = true).copy(checkpoint = checkpoint))
        assertTrue(deleted)
        assertEquals(1, result.succeeded)
    }

    @Test fun `checkpoint failure stops the batch and does not discard sources`() = runTest {
        var calls = 0
        try {
            BatchImportRunner({ _, _ -> (++calls).toLong() }, { _, _ -> },
                { throw java.io.IOException("disk full") }, { fail("must not delete") }, { _, _, _, _, _ -> })
                .run(request(2))
            fail("expected persistence failure")
        } catch (error: IllegalStateException) { assertTrue(error.cause is java.io.IOException) }
        assertEquals(1, calls)
    }
}
