package com.mozhi.reader.core.library

import android.app.Application
import android.content.ContextWrapper
import androidx.room.Room
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import dagger.Lazy
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TxtAtomicPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var context: ContextWrapper
    private lateinit var database: MoReadDatabase
    private lateinit var library: LibraryRepository

    @Before fun setup() {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = temporary.root
        }
        database = Room.inMemoryDatabaseBuilder(context, MoReadDatabase::class.java).build()
        library = LibraryRepository(context, database, database.bookDao(), BookTextStore(context), BookTextWriter(),
            BookMediaStore(context), BookLayoutStore(context), Lazy { error("unused") }, Lazy { error("unused") })
    }
    @After fun close() { database.close() }
    private fun book() = BookEntity(title = "书", author = "", coverPath = null, epubPath = "",
        sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 2)

    @Test fun publishesReadyBookWithCanonicalChapterRangesInOneTransaction() = runBlocking {
        val bodies = listOf("　正文🙂\n\n第二段", "另一章\t正文")
        val id = library.insertTextBook(book(), bodies.mapIndexed { index, text -> ChapterDraft(index, "第${index + 1}章", "", text.length) },
            bodies.mapIndexed { index, text -> ChapterTextSlice(index, text) })
        assertEquals(LibraryRepository.CURRENT_TEXT_VERSION, library.getBook(id)!!.textVersion)
        val chapters = library.getChapters(id)
        assertEquals(2, chapters.size)
        assertEquals(2, library.getTocEntries(id).size)
        chapters.forEachIndexed { index, chapter ->
            val expected = BookTextWriter().normalize(bodies[index])
            assertEquals(expected, library.readChapterTextStrict(id, chapter))
            assertEquals(expected.length, chapter.charCount)
        }
    }

    @Test fun cancelledWritingDoesNotExposeBookOrLeaveStagedText() = runBlocking {
        var checks = 0
        try {
            library.insertTextBook(book(), listOf(ChapterDraft(0, "正文", "", 1_000_000)),
                listOf(ChapterTextSlice(0, "中".repeat(1_000_000))), checkActive = {
                    if (++checks > 3) throw kotlinx.coroutines.CancellationException()
                })
            fail("expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(library.getBooks().isEmpty())
        assertTrue(File(context.filesDir, "book-text").walkTopDown().none { it.isFile })
    }

    @Test fun transactionFailureRollsBackBookAndStagedText() = runBlocking {
        // Duplicate chapter indices violate the Room constraint after the book row was inserted.
        val chapters = listOf(ChapterDraft(0, "一", "", 1), ChapterDraft(0, "二", "", 1))
        try {
            library.insertTextBook(book(), chapters, listOf(ChapterTextSlice(0, "甲"), ChapterTextSlice(1, "乙")))
            fail("expected transaction failure")
        } catch (_: Exception) { }
        assertTrue(library.getBooks().isEmpty())
        assertTrue(File(context.filesDir, "book-text").walkTopDown().none { it.isFile })
    }
}
