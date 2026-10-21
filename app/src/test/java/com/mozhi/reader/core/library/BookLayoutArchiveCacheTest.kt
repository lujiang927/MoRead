package com.mozhi.reader.core.library

import android.content.Context
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubChapterFallback
import com.mozhi.reader.core.epub.EpubTextExtractor
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookLayoutArchiveCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy { mockk<Context>().also {
        every { it.filesDir } returns temporary.newFolder("files")
        every { it.cacheDir } returns temporary.newFolder("cache")
    } }
    private val parser = EpubLayoutDocumentParser()
    private val href = "OPS/chapter.xhtml"
    private val html = "<html><head><title>第一章</title></head><body><h1>第一章</h1><p>雨夜里的灯塔，照亮了归途。🙂</p></body></html>".toByteArray()
    private val parsed get() = parser.parseWithText(html, 0, href, emptyMap())
    private fun archive() = File(context.filesDir, "books/book.epub").apply {
        parentFile.mkdirs()
        ZipOutputStream(outputStream()).use { zip -> zip.putNextEntry(ZipEntry(href)); zip.write(html); zip.closeEntry() }
    }
    private fun pkg() = EpubLayoutPackage(packageDocumentPath = "OPS/content.opf",
        resources = listOf(EpubLayoutResource("chapter", href, href, "application/xhtml+xml", kind = EpubLayoutResourceKind.DOCUMENT, sizeBytes = html.size.toLong())))
    private fun cache(id: Long = 1) = File(context.cacheDir, "book-dom/$id")

    @Test fun importWritesOnlyAnIndexAndEvictedChaptersAreRebuiltFromTheArchive() = runTest {
        val store = BookLayoutStore(context)
        val value = parsed
        store.initialize(1, archive(), pkg(), listOf(EpubLayoutChapterInput(0, href, value.document)))
        val root = File(context.filesDir, "book-layout/1")
        assertFalse(File(root, "chapters").exists())
        assertFalse(File(root, "legacy-v9").exists())
        assertFalse(cache().exists())
        assertTrue(store.hasCurrentLayout(1, listOf(value.text.length)))
        val first = store.readChapter(1, 0, value.text)!!
        assertEquals(value.dom, first.dom)
        assertEquals(value.document, first.document)
        assertTrue(cache().listFiles()!!.single().name.endsWith(".gz"))
        cache().deleteRecursively()
        assertTrue(store.hasCurrentLayout(1, listOf(value.text.length)))
        assertEquals(first, BookLayoutStore(context).readChapter(1, 0, value.text))
    }

    @Test fun parserRevisionInvalidatesOnlyTheCacheAndContentMismatchFallsBackWithoutMovingAnchors() = runTest {
        val store = BookLayoutStore(context)
        val value = parsed
        store.initialize(1, archive(), pkg(), listOf(EpubLayoutChapterInput(0, href, value.document)))
        assertNotNull(store.readChapter(1, 0, value.text))
        val cached = cache().listFiles()!!.single()
        val current = EpubLayoutPackage.CURRENT_PARSER_REVISION
        GzipTextFiles.replaceWithGzip(cached, GzipTextFiles.readText(cached)
            .replace("\"parserRevision\":$current", "\"parserRevision\":${current - 1}"))
        assertEquals(value.dom, store.readChapter(1, 0, value.text)!!.dom)
        assertTrue(GzipTextFiles.readText(cached).contains("\"parserRevision\":$current"))
        assertNull(store.readChapter(1, 0, "错".repeat(value.text.length)))
        assertTrue(store.hasCurrentLayout(1, listOf(value.text.length)))
        assertNotNull(store.readChapter(1, 0, value.text))
    }

    @Test fun oldMaterializedLayoutsBecomeOneIndexWithoutRebuildingText() = runTest {
        val store = BookLayoutStore(context)
        val epub = archive()
        val value = parsed
        store.replace(1, epub, pkg(), listOf(EpubLayoutChapterInput(0, href, value.document, value.dom)))
        assertTrue(File(context.filesDir, "book-layout/1/legacy-v9").isDirectory)
        assertTrue(store.adoptArchive(1, epub, listOf(value.text.length)))
        assertFalse(File(context.filesDir, "book-layout/1/legacy-v9").exists())
        assertEquals(value.dom, store.readChapter(1, 0, value.text)!!.dom)
    }

    @Test fun editedCanonicalTextUpdatesOnlyTheCoordinateIndexAndNeverRestoresTheOriginalBody() = runTest {
        val store = BookLayoutStore(context)
        val epub = archive()
        val value = parsed
        store.replace(1, epub, pkg(), listOf(EpubLayoutChapterInput(0, href, value.document, value.dom)))
        assertNotNull(store.readChapter(1, 0, value.text))
        val edited = value.text + "修改后的段落。"
        assertTrue(store.adoptArchive(1, epub, listOf(edited.length)))
        assertTrue(store.hasCurrentLayout(1, listOf(edited.length)))
        assertNull(store.readChapter(1, 0, edited))
        assertTrue(store.adoptArchive(1, epub, listOf(edited.length + 2)))
        assertTrue(store.hasCurrentLayout(1, listOf(edited.length + 2)))
        assertNull(store.readChapter(1, 0, edited + "🙂"))
        assertTrue(epub.isFile)
    }

    @Test fun cacheEnforcesPerBookAndGlobalByteBudgetsEvenWhenAnEntryDoesNotFit() = runTest {
        val value = parsed
        val epub = archive()
        val pkg = pkg().copy(sourceArchive = "books/book.epub")
        val cache = BookLayoutCache(context, parser, bookBudget = 300, totalBudget = 450)
        repeat(5) { index ->
            assertNotNull(cache.read(index + 1L, pkg, EpubLayoutChapterRef(0, href, value.text.length, ""), value.text))
            assertTrue(this@BookLayoutArchiveCacheTest.cache(index + 1L).walkTopDown().filter(File::isFile).sumOf(File::length) <= 300L)
            assertTrue(File(context.cacheDir, "book-dom").walkTopDown().filter(File::isFile).sumOf(File::length) <= 450L)
        }
        assertTrue(epub.isFile)
    }

    @Test fun missingSchemaCannotBeMistakenForTheCurrentVersion() {
        val json = "{\"chapterIndex\":0,\"href\":\"chapter.xhtml\",\"bodyNode\":{\"tag\":\"body\"},\"textLength\":0}"
        assertThrows(SerializationException::class.java) { Json.decodeFromString<EpubDomChapter>(json) }
        assertThrows(SerializationException::class.java) { Json.decodeFromString<EpubLayoutPackage>("{\"packageDocumentPath\":\"content.opf\"}") }
    }

    @Test fun fallbackSurvivesCacheEvictionRestartAndParserRecoveryWithoutMovingTextAnchors() = runTest {
        val bytes = "<html><head><link rel='stylesheet' href='book.css'/></head><body><span>甲🙂</span><span>乙</span><img src='art.png'/></body></html>".toByteArray()
        val styles = listOf(EpubStylesheetText("OPS/book.css", "span { display: block; }"))
        val stylesheetMap = styles.associate { it.href to it.css }
        val canonical = EpubTextExtractor().extractWithImages(bytes, href).text
        assertNotEquals(canonical, parser.parseWithText(bytes, 0, href, stylesheetMap).text)
        val failing = mockk<EpubLayoutDocumentParser>()
        every { failing.parseWithText(any(), any(), any(), any()) } throws IllegalArgumentException("style failure")
        val imported = EpubChapterFallback.parse(failing, bytes, 0, href, stylesheetMap)
        val epub = File(context.filesDir, "books/fallback.epub").apply {
            parentFile?.mkdirs()
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(href)); zip.write(bytes); zip.closeEntry()
            }
        }
        val store = BookLayoutStore(context)
        store.initialize(1, epub, pkg().copy(stylesheets = styles),
            listOf(EpubLayoutChapterInput(0, href, imported.document)))
        val index = File(context.filesDir, "book-layout/1/${BookLayoutStore.INDEX_FILE}").readText()
        assertTrue(index.contains(EpubChapterFallback.DIAGNOSTIC_CODE))
        val before = Json.decodeFromString<EpubLayoutPackage>(index)
        assertEquals(EpubLayoutPackage.CURRENT_SCHEMA_VERSION, before.schemaVersion)
        repeat(2) {
            val restarted = BookLayoutStore(context) // A healthy rich parser must still honor the saved fallback.
            val bundle = restarted.readChapter(1, 0, canonical)!!
            assertTrue(EpubChapterFallback.isTextFallback(bundle.document))
            assertEquals(canonical.length, bundle.document.textLength)
            assertTrue(bundle.document.blocks.isEmpty())
            assertNull(bundle.dom)
            assertTrue(bundle.stylesheets.isEmpty()) // Global CSS cannot accidentally route to an empty rich DOM.
            assertNull(restarted.readChapter(1, 0, "错".repeat(canonical.length)))
            cache().deleteRecursively()
            assertNull(BookLayoutStore(context).readChapter(1, 0, "错".repeat(canonical.length)))
        }
        assertEquals(index, File(context.filesDir, "book-layout/1/${BookLayoutStore.INDEX_FILE}").readText())
        assertTrue(store.hasCurrentLayout(1, listOf(canonical.length)))
    }

    @Test fun oldBooksDoNotAdoptDifferentTextWhenRichRebuildFails() = runTest {
        val value = parsed
        val store = BookLayoutStore(context)
        store.initialize(1, archive(), pkg(), listOf(EpubLayoutChapterInput(0, href, value.document)))
        val failing = mockk<EpubLayoutDocumentParser>()
        every { failing.parseWithText(any(), any(), any(), any()) } throws IllegalArgumentException("new parser failure")
        val reader = BookLayoutStore(context, failing)
        val preserved = value.text.replace('雨', '雪')
        assertEquals(value.text.length, preserved.length)
        assertNull(reader.readChapter(1, 0, preserved))
        assertTrue(reader.hasCurrentLayout(1, listOf(preserved.length)))
        assertTrue(EpubChapterFallback.isTextFallback(reader.readChapter(1, 0, value.text)!!.document))
    }
}
