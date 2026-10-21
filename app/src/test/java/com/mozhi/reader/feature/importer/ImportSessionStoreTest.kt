package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import android.app.Application
import android.content.ContextWrapper
import com.mozhi.reader.core.library.*
import io.mockk.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
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
class ImportSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context get() = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = temporary.root
    }
    private val splitter = TxtChapterSplitter()
    private val rule = TxtTocRule(-2, true, "章节", "^第\\d+章.*$")
    private val rules get() = mockk<TxtTocRuleLoader> { every { rules } returns listOf(rule) }
    private val text = "前言🙂\n第1章 标题\n正文一。\n第2章 标题\n正文二。"

    @Test fun legacyUtf8SessionsMigrateInChunksAndRetainSelectedOrFallbackRules() {
        listOf(rule, null).forEach { selected ->
            val id = UUID.randomUUID().toString()
            val directory = File(temporary.root, "txt_import_sessions").apply { mkdirs() }
            File(directory, "$id.txt").writeText(text)
            val metadata = buildJsonObject {
                put("id", id); put("sourceName", "book.txt"); put("suggestedTitle", "书")
                put("charsetName", "UTF-8")
                put("selectedRule", selected?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
            }
            File(directory, "$id.json").writeText(metadata.toString())
            val store = ImportSessionStore(context, splitter, rules)
            val session = store.get(id)!!
            session.openText().use { assertEquals(text, it.subSequence(0, it.length).toString()) }
            assertEquals(selected?.id, session.splitResult.rule?.id)
            assertEquals(selected == null, session.splitResult.usedFallback)
            assertFalse(File(directory, "$id.txt").exists())
            assertTrue(File(directory, "$id.utf16").isFile)
            assertEquals(session.toPreview(), ImportSessionStore(context, splitter, rules).get(id)!!.toPreview())
            store.remove(id)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun cancellationDuringLegacyMigrationKeepsTheRecoverableSession() {
        val id = UUID.randomUUID().toString()
        val directory = File(temporary.root, "txt_import_sessions").apply { mkdirs() }
        File(directory, "$id.txt").writeText(text)
        File(directory, "$id.json").writeText(buildJsonObject {
            put("id", id); put("sourceName", "book.txt"); put("suggestedTitle", "书")
            put("charsetName", "UTF-8"); put("selectedRule", JsonNull)
        }.toString())
        val store = ImportSessionStore(context, splitter, rules)
        assertThrows(CancellationException::class.java) { store.get(id) { throw CancellationException() } }
        assertFalse(File(directory, "$id.utf16.tmp").exists())
        assertTrue(File(directory, "$id.txt").isFile)
        assertNotNull(store.get(id))
    }

    @Test fun failedAndCancelledConfirmationKeepsDiskSessionForRetry() = runTest {
        val store = ImportSessionStore(context, splitter, rules)
        val decoded = temporary.newFile("decoded.utf16").apply { writeText(text, Charsets.UTF_16LE) }
        val session = store.create("book.txt", "书", "", "UTF-8", decoded,
            listOf(rule), splitter.indexWithRule(text, rule)!!)
        assertFalse(decoded.exists())
        val library = mockk<LibraryRepository>(relaxed = true)
        val importer = ImportCoordinator(context, TextEncodingDetector(), rules, splitter, EpubTextExtractor(),
            EpubLayoutDocumentParser(), EpubPackageInspector(), BookLayoutStore(context), BookMediaStore(context),
            store, mockk(), library)
        listOf(IOException("disk full"), CancellationException("stopped")).forEach { failure ->
            coEvery { library.insertTextBook(any(), any(), any(), any(), any()) } throws failure
            try { importer.confirmTxt(session.id, "书", ""); fail("Expected failure") }
            catch (actual: Exception) {
                assertEquals(failure.javaClass, actual.javaClass)
                assertEquals(failure.message, actual.message)
            }
            assertEquals(session.toPreview(), ImportSessionStore(context, splitter, rules).get(session.id)!!.toPreview())
            assertTrue(session.sourceFile.isFile)
        }
        coEvery { library.insertTextBook(any(), any(), any(), any(), any()) } returns 9L
        assertEquals(9L, importer.confirmTxt(session.id, "书", ""))
        assertNull(store.get(session.id))
    }
}
