package com.mozhi.reader.core.library

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookLayoutCacheTrimTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context = mockk<Context>()

    private fun cache(bookBudget: Long, totalBudget: Long): BookLayoutCache {
        every { context.cacheDir } returns temporary.root
        return BookLayoutCache(context, mockk(), bookBudget, totalBudget)
    }

    private fun entry(bookId: Long, name: String, bytes: Int, modified: Long): File =
        File(temporary.root, "book-dom/$bookId/$name").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes))
            assertTrue(setLastModified(modified))
        }

    @Test fun evictionRespectsBookAndGlobalBudgetsInOldestFirstOrder() {
        val prefix = "p${EpubLayoutPackage.CURRENT_PARSER_REVISION}"
        val oldest = entry(1, "$prefix-ch-0.json.gz", 4, 1_000)
        val newest = entry(1, "$prefix-ch-1.json.gz", 4, 3_000)
        val otherBook = entry(2, "$prefix-ch-0.json.gz", 4, 2_000)
        cache(bookBudget = 5, totalBudget = 5).trim(1)
        assertFalse(oldest.exists())
        assertFalse(otherBook.exists())
        assertTrue(newest.exists())
    }

    @Test fun oldParserGenerationsAreRemovedWithoutEvictingCurrentSmallCaches() {
        val prefix = "p${EpubLayoutPackage.CURRENT_PARSER_REVISION}"
        val old = entry(1, "p0-ch-0.json.gz", 4, 1_000)
        val current = entry(1, "$prefix-ch-0.json.gz", 4, 2_000)
        cache(bookBudget = 100, totalBudget = 100).trim(1)
        assertFalse(old.exists())
        assertTrue(current.exists())
    }
}
