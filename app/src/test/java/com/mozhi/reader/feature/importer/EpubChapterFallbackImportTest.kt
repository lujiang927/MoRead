package com.mozhi.reader.feature.importer

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.epub.EpubChapterFallback
import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.ChapterDraft
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.readium.ReadiumServices
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class EpubChapterFallbackImportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oneFailedChapterStillImportsTheWholeBookAndUsesNativeTextLayout() = runBlocking {
        val files = temporary.newFolder("files")
        val caches = temporary.newFolder("cache")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = caches
        }
        val source = temporary.newFile("fallback.epub")
        EpubGenerator().generate(source, "降级回归", "作者", listOf(
            TxtChapter(0, "正常章", "这一章保留原生精排。", 0, 10),
            TxtChapter(1, "降级章", "这一章仍然完整。🙂\n换行后的正文也要保留。", 10, 40)
        ))
        val realParser = EpubLayoutDocumentParser()
        val parser = mockk<EpubLayoutDocumentParser>()
        every { parser.parseWithText(any(), any(), any(), any()) } answers {
            if (secondArg<Int>() == 1) throw IllegalArgumentException("synthetic layout failure")
            realParser.parseWithText(firstArg(), secondArg(), thirdArg(), arg(3))
        }
        val drafts = slot<List<ChapterDraft>>()
        val texts = slot<List<ChapterTextInput>>()
        val library = mockk<LibraryRepository>(relaxed = true)
        coEvery { library.insertBook(any(), capture(drafts), any()) } returns 1L
        coEvery { library.findBookBySourceHash(any()) } returns null
        coEvery { library.getEpubBooksWithoutSource() } returns emptyList()
        coEvery { library.materializeBookText(1, capture(texts), false) } returns Unit
        val store = BookLayoutStore(context)
        val importer = ImportCoordinator(context, mockk(), mockk(), mockk(), EpubTextExtractor(), parser,
            EpubPackageInspector(), store, BookMediaStore(context), mockk(), ReadiumServices(context), library)
        assertEquals(1L, importer.importDirectly(Uri.fromFile(source)))
        coVerify(exactly = 1) { library.markTextReady(1) }
        coVerify(exactly = 0) { library.deleteBook(any()) }
        assertEquals(2, texts.captured.size)
        // Chapter rows start with zero lengths; materializeBookText writes their final ranges.
        assertEquals(texts.captured.map { it.index }, drafts.captured.map { it.index })
        assertTrue(texts.captured[1].body.contains("这一章仍然完整。🙂\n换行后的正文也要保留。"))
        val normal = store.readChapter(1, 0, texts.captured[0].body)!!
        assertFalse(EpubChapterFallback.isTextFallback(normal.document))
        assertNotNull(normal.dom)
        assertTrue(normal.stylesheets.isNotEmpty())
        val body = texts.captured[1].body
        val fallback = BookLayoutStore(context).readChapter(1, 1, body)!!
        assertTrue(EpubChapterFallback.isTextFallback(fallback.document))
        assertEquals(body.length, fallback.document.textLength)
        val spec = TypesetSpec(300f, 600f, 25f, 32f, 8f, 8f, 10f, 20f)
        val typesetter = ChapterTypesetter(spec, FakeMeasure())
        val plain = typesetter.typeset(1, "降级章", body)
        val laid = typesetter.typeset(1, "降级章", body, epubLayout = fallback)
        assertEquals(plain.pageCount, laid.pageCount)
        assertEquals(plain.pages.flatMap { it.lines }.map { Triple(it.text, it.chapterPosition, it.charLength) },
            laid.pages.flatMap { it.lines }.map { Triple(it.text, it.chapterPosition, it.charLength) })
        assertNull(laid.layoutCapability)
        assertTrue(laid.pages.flatMap { it.lines }.any { it.text.contains("完整") })
        assertTrue(store.hasCurrentLayout(1, texts.captured.map { it.body.length }))
    }

    @Test fun legacyLayoutRepairPreservesMatchingCanonicalTextWithoutRewritingIt() = runBlocking {
        verifyLegacyRepair(editedText = false)
    }

    @Test fun legacyLayoutRepairRejectsFallbackTextThatWouldChangeSavedCoordinates() = runBlocking {
        verifyLegacyRepair(editedText = true)
    }

    private suspend fun verifyLegacyRepair(editedText: Boolean) {
        val files = temporary.newFolder("legacy-files")
        val caches = temporary.newFolder("legacy-cache")
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = caches
        }
        val source = File(files, "legacy.epub")
        EpubGenerator().generate(source, "旧书", "作者", listOf(TxtChapter(0, "第一章", "原来的正文🙂", 0, 8)))
        val pkg = EpubPackageInspector().inspect(source)
        val href = pkg.spine.first().href!!
        val original = ZipFile(source).use { zip ->
            EpubTextExtractor().extract(zip.getInputStream(zip.getEntry(href)).use { it.readBytes() }, href)
        }
        val stored = if (editedText) original.replace("原来", "编辑") else original
        assertEquals(original.length, stored.length)
        val book = BookEntity(id = 1, title = "旧书", author = "作者", coverPath = null,
            epubPath = source.absolutePath, sourceType = BookSourceType.EPUB, importedAt = 1,
            totalChapters = 1, textVersion = 1, lastReadChapterIndex = 0, lastReadCharOffset = 4)
        val chapter = ChapterEntity(bookId = 1, chapterIndex = 0, title = "第一章", href = href, charCount = stored.length)
        val library = mockk<LibraryRepository>(relaxed = true)
        coEvery { library.getBook(1) } returns book
        coEvery { library.getChapters(1) } returns listOf(chapter)
        coEvery { library.readChapterText(1, chapter) } returns stored
        val parser = mockk<EpubLayoutDocumentParser>()
        every { parser.parseWithText(any(), any(), any(), any()) } throws IllegalArgumentException("style failure")
        val store = BookLayoutStore(context)
        val importer = ImportCoordinator(context, mockk(), mockk(), mockk(), EpubTextExtractor(), parser,
            EpubPackageInspector(), store, BookMediaStore(context), mockk(), ReadiumServices(context), library)
        if (editedText) {
            try {
                importer.materializeLegacyBook(book)
                fail("Existing canonical text must not be replaced with a different extraction")
            } catch (failure: IllegalStateException) {
                assertTrue(failure.message.orEmpty().contains("Stored text differs"))
            }
            coVerify(exactly = 0) { library.markTextReady(1) }
            assertFalse(File(files, "book-layout/1/${BookLayoutStore.INDEX_FILE}").exists())
        } else {
            assertTrue(importer.materializeLegacyBook(book))
            coVerify(exactly = 1) { library.markTextReady(1) }
            assertTrue(EpubChapterFallback.isTextFallback(store.readChapter(1, 0, stored)!!.document))
        }
        coVerify(exactly = 0) { library.materializeBookText(any(), any(), any()) }
        coVerify(exactly = 0) { library.updateReadPosition(any(), any(), any()) }
        coVerify(exactly = 0) { library.updateBookmarkPosition(any(), any(), any()) }
    }
}
