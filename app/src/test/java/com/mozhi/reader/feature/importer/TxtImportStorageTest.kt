package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import android.app.Application
import android.content.ContextWrapper
import android.net.Uri
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.library.*
import com.mozhi.reader.core.readium.ReadiumServices
import io.mockk.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TxtImportStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun batchTxtUsesFileBackedTextAndCleansTemporaryFiles() = runTest {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(temporary.root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(temporary.root, "cache").apply { mkdirs() }
        }
        val source = temporary.newFile("novel.txt").apply {
            writeText((1..8).joinToString("\n\n") { "第${it}章 标题\n${"正文🙂。".repeat(100)}" })
        }
        val splitter = TxtChapterSplitter()
        val rules = mockk<TxtTocRuleLoader> { every { rules } returns listOf(
            TxtTocRule(-2, true, "章节", "^第\\d+章.*$")
        ) }
        val sessions = ImportSessionStore(context, splitter, rules)
        val library = mockk<LibraryRepository>(relaxed = true)
        coEvery { library.findBookBySourceHash(any()) } returns null
        coEvery { library.insertTextBook(any(), any(), any(), any(), any()) } coAnswers {
            val bodies = thirdArg<List<ChapterTextSlice>>()
            assertTrue(bodies.all { it.body !is String })
            val ranges = BookTextWriter().writeSlices(File(temporary.root, "canonical.mz"), bodies)
            assertEquals(8, ranges.size)
            42L
        }
        val importer = ImportCoordinator(context, TextEncodingDetector(), rules, splitter, EpubTextExtractor(),
            EpubLayoutDocumentParser(), EpubPackageInspector(), BookLayoutStore(context), BookMediaStore(context),
            sessions, mockk(), library)
        val stages = mutableSetOf<String>()
        assertEquals(42L, importer.importDirectly(Uri.fromFile(source)) { stage, _, _ -> stages += stage })
        assertTrue(source.isFile)
        assertTrue(File(context.cacheDir, "txt-import").listFiles().orEmpty().isEmpty())
        assertTrue(File(context.filesDir, "txt_import_sessions").listFiles().orEmpty().isEmpty())
        assertTrue(stages.containsAll(listOf(com.mozhi.reader.R.string.import_reading_file,
            com.mozhi.reader.R.string.import_detecting_encoding, com.mozhi.reader.R.string.import_detecting_chapters).map(context::getString)))
        coVerify(exactly = 0) { library.insertBook(any(), any(), any()) }
    }

    @Test fun txtImportStoresCanonicalTextWithoutGeneratingOrOpeningAnEpub() = runTest {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = temporary.root }
        val splitter = TxtChapterSplitter()
        val rules = mockk<TxtTocRuleLoader> { every { rules } returns emptyList() }
        val sessions = ImportSessionStore(context, splitter, rules)
        val text = "第一章\n正文🙂。\n第二章\n另一个章节。"
        val split = splitter.indexWithCustomRegex(text, "^第[一二]章$")!!
        val decoded = temporary.newFile("decoded.utf16").apply { writeText(text, Charsets.UTF_16LE) }
        val session = sessions.create("book.txt", "书", "作者", "UTF-8", decoded, emptyList(), split)
        val library = mockk<LibraryRepository>(relaxed = true)
        val saved = slot<BookEntity>()
        val savedBodies = mutableListOf<String>()
        coEvery { library.insertTextBook(capture(saved), any(), any(), any(), any()) } coAnswers {
            val bodies = thirdArg<List<ChapterTextSlice>>()
            assertTrue(bodies.all { it.body !is String })
            savedBodies += bodies.map { it.body.toString() }
            BookTextWriter().writeSlices(File(temporary.root, "book-text/1/text.mz"), bodies)
            1L
        }
        val readium = mockk<ReadiumServices>()
        val importer = ImportCoordinator(context, mockk(), rules, splitter, EpubTextExtractor(), EpubLayoutDocumentParser(),
            EpubPackageInspector(), BookLayoutStore(context), BookMediaStore(context), sessions, readium, library)
        assertEquals(1L, importer.confirmTxt(session.id, "书", "作者"))
        assertEquals("", saved.captured.epubPath)
        assertEquals(split.materialize(text).chapters.map { it.content }, savedBodies)
        assertTrue(File(temporary.root, "book-text/1/text.mz").isFile)
        assertFalse(temporary.root.walkTopDown().any { it.extension == "epub" })
        assertNull(sessions.get(session.id))
        coVerify(exactly = 0) { readium.open(any()) }
        coVerify(exactly = 0) { library.insertBook(any(), any(), any()) }
        coVerify(exactly = 0) { library.materializeBookText(any(), any(), any()) }
    }

    @Test fun sixtyFivePointSevenMegabyteSingleTxtUsesDiskForPreviewRulesRecoveryAndConfirmation() = runTest {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(temporary.root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(temporary.root, "cache").apply { mkdirs() }
        }
        val source = temporary.newFile("large.txt")
        val body = "正文🙂。".repeat(200)
        val block = "第1章 标题\n$body\n\n".toByteArray(Charsets.UTF_8)
        val sourceBytes = 65_700_000L
        val count = (sourceBytes / block.size).toInt()
        source.outputStream().buffered().use { output ->
            repeat(count) { output.write(block) }
            repeat((sourceBytes % block.size).toInt()) { output.write(' '.code) }
        }
        assertEquals(sourceBytes, source.length())
        val splitter = TxtChapterSplitter()
        val rule = TxtTocRule(-2, true, "章节", "^第\\d+章.*$")
        val rules = mockk<TxtTocRuleLoader> { every { rules } returns listOf(rule) }
        var sessions = ImportSessionStore(context, splitter, rules)
        val library = mockk<LibraryRepository>(relaxed = true)
        coEvery { library.findBookBySourceHash(any()) } returns null
        val detector = spyk(TextEncodingDetector())
        fun coordinator() = ImportCoordinator(context, detector, rules, splitter, EpubTextExtractor(),
            EpubLayoutDocumentParser(), EpubPackageInspector(), BookLayoutStore(context), BookMediaStore(context),
            sessions, mockk(), library)
        val prepared = coordinator().prepare(Uri.fromFile(source), allowDuplicate = true) as PreparedImport.PreviewReady
        val preview = coordinator().preview(prepared.sessionId)!!
        assertEquals(count, preview.chapterTitles.size)
        assertEquals(count * body.length, preview.totalCharacters)
        assertTrue(File(context.cacheDir, "txt-import").listFiles().orEmpty().isEmpty())
        // A worker/process recreation must restore an index from disk, never load a full String.
        sessions = ImportSessionStore(context, splitter, rules)
        assertEquals(preview, coordinator().preview(prepared.sessionId))
        assertEquals(count, coordinator().selectRule(prepared.sessionId, rule.id).chapterTitles.size)
        assertEquals(count, coordinator().applyCustomRegex(prepared.sessionId, rule.rule).chapterTitles.size)
        val stored = sessions.get(prepared.sessionId)!!
        stored.openText().use { assertFalse(AiChapterRuleSampler.sample(it).contains("正文")) }
        coEvery { library.insertTextBook(any(), any(), any(), any(), any()) } coAnswers {
            val bodies = thirdArg<List<ChapterTextSlice>>()
            assertEquals(count, bodies.size)
            assertTrue(bodies.all { it.body !is String })
            val target = File(temporary.root, "canonical.mz")
            val ranges = BookTextWriter().writeSlices(target, bodies)
            BookTextArchive.Reader(target).use { archive ->
                listOf(ranges.first(), ranges.last()).forEach { range ->
                    assertEquals(body, archive.read(range.byteOffset, range.byteLength).toString(Charsets.UTF_8))
                }
            }
            65L
        }
        assertEquals(65L, coordinator().confirmTxt(prepared.sessionId, "大书", ""))
        assertNull(sessions.get(prepared.sessionId))
        assertTrue(File(context.filesDir, "txt_import_sessions").listFiles().orEmpty().isEmpty())
        assertTrue(source.isFile)
        verify(exactly = 0) { detector.decode(any()) }
    }
}
