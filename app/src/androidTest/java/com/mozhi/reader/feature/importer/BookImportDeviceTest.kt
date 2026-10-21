package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.mozhi.reader.MainActivity
import com.mozhi.reader.core.IsolatedTestContext
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.importer.DuplicateBookException
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.BookTextStore
import com.mozhi.reader.core.library.BookTextWriter
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.readium.ReadiumServices
import dagger.Lazy
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full Android import -> Room -> text.mz -> chapter/layout read, isolated from the user's shelf. */
@RunWith(AndroidJUnit4::class)
class BookImportDeviceTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val context = IsolatedTestContext(base)
    private val database = Room.inMemoryDatabaseBuilder(context, MoReadDatabase::class.java).build()
    private val layouts = BookLayoutStore(context)
    private val media = BookMediaStore(context)
    private val repository = LibraryRepository(context, database, database.bookDao(), BookTextStore(context),
        BookTextWriter(), media, layouts, Lazy { error("No vector index needed") }, Lazy { error("No image cleanup needed") })
    private fun newSessionStore() = ImportSessionStore(context, TxtChapterSplitter(), TxtTocRuleLoader(context))
    private val sessions = newSessionStore()
    private fun importer(store: ImportSessionStore = sessions) = ImportCoordinator(context, TextEncodingDetector(),
        TxtTocRuleLoader(context), TxtChapterSplitter(), EpubTextExtractor(), EpubLayoutDocumentParser(),
        EpubPackageInspector(), layouts, media, store, ReadiumServices(context), repository)

    @After fun cleanup() {
        database.close()
        context.root.deleteRecursively()
    }

    @Test fun automaticHeadingScanNeverMaterializesWholeFileOnAndroid() {
        val raw = (1..8).joinToString("\n\n") { "第${it}章 标题\n${"正文。".repeat(100)}" }
        val guarded = object : CharSequence {
            override val length = raw.length
            override fun get(index: Int) = raw[index]
            override fun subSequence(startIndex: Int, endIndex: Int) = raw.subSequence(startIndex, endIndex)
            override fun toString(): String = error("Android regex must not copy the entire staged book")
        }
        assertEquals(8, TxtChapterSplitter().indexBest(guarded, TxtTocRuleLoader(context).rules).chapters.size)
    }

    @Test fun stagedCustomAndAiRulesMatchActualTextOnAndroid() {
        val raw = (1..8).joinToString("\n\n") { "第${it}章 标题\n${"正文。".repeat(100)}" }
        val source = File(context.root, "custom.utf16").apply { writeText(raw, Charsets.UTF_16LE) }
        TxtFileText(source).use { text ->
            val splitter = TxtChapterSplitter()
            assertEquals(8, splitter.indexWithCustomRegex(text, "^第\\d+章.*$")!!.chapters.size)
            val validated = AiChapterRuleValidator.validate(text, "^第\\d+章.*$", splitter)
            assertTrue(validated is AiChapterRuleValidation.Valid)
            assertEquals(8, (validated as AiChapterRuleValidation.Valid).result.chapters.size)
        }
    }

    @Test fun batchTxt20MiBImportsAndReadsBothEnds() = runBlocking {
        val source = largeTxt("batch", 20 * 1024 * 1024)
        var stages = 0
        var lastStage = ""
        var stageStarted = SystemClock.elapsedRealtime()
        val book = measured("batch-txt-${source.length()}") {
            importer().importDirectly(Uri.fromFile(source)) { stage, _, _ ->
                stages++
                if (stage != lastStage) {
                    Log.i(TAG, "PHASE $lastStage elapsedMs=${SystemClock.elapsedRealtime() - stageStarted}")
                    lastStage = stage
                    stageStarted = SystemClock.elapsedRealtime()
                }
            }
        }
        Log.i(TAG, "PHASE $lastStage elapsedMs=${SystemClock.elapsedRealtime() - stageStarted}")
        assertTrue(stages > 3)
        verifyText(book, "测试正文", "结尾完整")
        assertTrue(File(context.cacheDir, "txt-import").listFiles().orEmpty().isEmpty())
        val duplicate = runCatching { importer().importDirectly(Uri.fromFile(source)) }.exceptionOrNull()
        assertTrue(duplicate is DuplicateBookException)
        assertEquals(1, repository.getBooks().size)
    }

    @Test fun singleTxt80MiBPreviewSurvivesSessionReloadAndConfirmation() = runBlocking {
        val source = largeTxt("single", 80 * 1024 * 1024)
        val prepared = measured("preview-txt-${source.length()}") { importer().prepare(Uri.fromFile(source), false) }
        assertTrue(prepared is PreparedImport.PreviewReady)
        val id = (prepared as PreparedImport.PreviewReady).sessionId
        val restored = importer(newSessionStore())
        val preview = requireNotNull(restored.preview(id))
        assertTrue(preview.chapterTitles.size > 100)
        val book = measured("confirm-txt-${source.length()}") {
            restored.confirmTxt(id, preview.suggestedTitle, preview.suggestedAuthor)
        }
        verifyText(book, "测试正文", "结尾完整")
        assertNull(restored.preview(id))
    }

    @Test fun legacyEncodingsAndInvalidFilesDoNotPoisonNextImport() = runBlocking {
        val coordinator = importer()
        val empty = File(context.root, "empty.txt").apply { writeText("") }
        assertTrue(runCatching { coordinator.importDirectly(Uri.fromFile(empty)) }.isFailure)
        val invalid = File(context.root, "broken.epub").apply { writeText("not a ZIP archive") }
        assertTrue(runCatching { coordinator.importDirectly(Uri.fromFile(invalid)) }.isFailure)
        assertTrue(repository.getBooks().isEmpty())
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().isEmpty())
        for (encoding in listOf("GB18030", "Big5", "UTF-16")) {
            val source = File(context.root, "$encoding.txt").apply {
                writeText((1..8).joinToString("\r\n") { "第${it}章 標題\r\n測試正文，風雨過後繼續前行。\r\n結尾完整。" }, charset(encoding))
            }
            verifyText(coordinator.importDirectly(Uri.fromFile(source)), "測試正文", "結尾完整")
        }
        assertEquals(3, repository.getBooks().size)
    }

    @Test fun realEpubFixturesImportWithReadableTextAndCompleteLayouts() = runBlocking {
        val configured = InstrumentationRegistry.getArguments().getString("importFixtures")
        assumeTrue("Pass -e importFixtures with an accessible directory", !configured.isNullOrBlank())
        val sources = File(requireNotNull(configured)).listFiles().orEmpty().filter { it.extension == "epub" }.sortedBy { it.name }
        assertTrue("EPUB fixture directory is empty", sources.isNotEmpty())
        for (source in sources) {
            val book = measured("epub-${source.name}-${source.length()}") { importer().importDirectly(Uri.fromFile(source)) }
            val chapters = repository.getChapters(book)
            assertTrue(chapters.isNotEmpty())
            assertTrue(layouts.hasCurrentLayout(book, chapters.map { it.charCount }))
            chapters.forEach { chapter ->
                val text = repository.readChapterText(book, chapter)
                assertEquals(chapter.charCount, text.length)
                assertNotNull(layouts.readChapter(book, chapter.chapterIndex, text))
            }
            assertTrue(chapters.sumOf { it.charCount.toLong() } > 0)
            assertTrue(requireNotNull(repository.getBook(book)).textVersion > 0)
            Log.i(TAG, "RESULT ${source.name}: chapters=${chapters.size}, images=${media.read(book).size}")
        }
    }

    @Test fun mixedBatchResumesAfterCommittedBookAndContinuesPastBadAndDuplicateFiles() = runBlocking<Unit> {
        val store = BatchImportRequestStore(context)
        val sources = (1..20).map { largeTxt("batch-$it", 5 * 1024 * 1024) }.toMutableList()
        sources.add(4, File(context.root, "batch-empty.txt").apply { writeText("") })
        sources.add(9, File(context.root, "batch-broken.epub").apply { writeText("broken ZIP") })
        sources.add(14, sources.first())
        val fixtures = InstrumentationRegistry.getArguments().getString("importFixtures")
            ?.let(::File)?.listFiles().orEmpty().filter { it.extension == "epub" }.sortedBy { it.name }
        sources.addAll(fixtures)
        val requestId = store.create(sources.map(Uri::fromFile), false, emptyMap())
        var interrupt = true
        var imports = 0
        val coordinator = importer()
        fun runner() = BatchImportRunner(
            importBook = { raw, progress -> imports++; coordinator.importDirectly(Uri.parse(raw), progress) },
            assignGroup = { _, _ -> },
            persist = { checkpoint ->
                store.checkpoint(requestId, checkpoint)
                if (interrupt && checkpoint.nextIndex == 3 && checkpoint.importedBookId != null) {
                    interrupt = false
                    throw CancellationException("Simulated stop after book commit")
                }
            }, deleteSource = { error("Original fixtures must be preserved") },
            progress = { _, _, _, _, _ -> }
        )
        val original = requireNotNull(store.read(requestId))
        val cancelled = runCatching { measured("batch-interrupted") { runner().run(original) } }.exceptionOrNull()
        assertNotNull(cancelled)
        val restored = requireNotNull(BatchImportRequestStore(context).read(requestId))
        assertEquals(3, restored.checkpoint.nextIndex)
        assertNotNull(restored.checkpoint.importedBookId)
        val result = measured("mixed-batch-resume-${sources.size}") { runner().run(restored) }
        assertEquals(sources.size, result.nextIndex)
        assertEquals(20 + fixtures.size, result.succeeded)
        assertEquals(2, result.failed)
        assertEquals(1, result.skipped)
        assertEquals(sources.size, imports)
        assertEquals(result.succeeded, repository.getBooks().size)
        assertEquals(result, requireNotNull(BatchImportRequestStore(context).read(requestId)).checkpoint)
        repository.getBooks().filter { it.sourceType == com.mozhi.reader.core.database.entity.BookSourceType.TXT }
            .forEach { verifyText(it.id, "测试正文", "结尾完整") }
        Log.i(TAG, "RESULT mixed-batch entries=${sources.size} success=${result.succeeded} failed=${result.failed} skipped=${result.skipped} imports=$imports")
    }

    @Test fun hundredBookWorkManagerQueueCompletesInBackground() = runBlocking<Unit> {
        // This test intentionally exercises the application's real workers and shelf, in the QA app only.
        assumeTrue("Build with -PmoreadQaDebug=true", base.packageName.endsWith(".qa"))
        val entry = EntryPointAccessors.fromApplication(base, BatchImportWorkerEntryPoint::class.java)
        val library = EntryPointAccessors.fromApplication(base, BookTextMaterializeEntryPoint::class.java).libraryRepository()
        val before = library.getBooks().map { it.id }.toSet()
        val files = (1..100).map { largeTxt("worker-${System.nanoTime()}-$it", 256 * 1024) }
        val scheduler = WorkBatchImportScheduler(base, entry.batchImportRequestStore())
        val manager = WorkManager.getInstance(base)
        val previousWork = manager.getWorkInfosForUniqueWork(BatchImportWorker.UNIQUE_WORK_NAME).get().map { it.id }.toSet()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            val infos = measured("workmanager-100-txt-background") {
                scheduler.enqueue(files.take(50).map(Uri::fromFile), false, emptyMap())
                scheduler.enqueue(files.drop(50).map(Uri::fromFile) + Uri.fromFile(files.first()), false, emptyMap())
                val deadline = SystemClock.elapsedRealtime() + 300_000
                var backgrounded = false
                while (true) {
                    val states = manager.getWorkInfosForUniqueWork(BatchImportWorker.UNIQUE_WORK_NAME).get()
                        .filter { it.id !in previousWork }
                    if (!backgrounded && states.any { it.state == WorkInfo.State.RUNNING }) {
                        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 3").close()
                        backgrounded = true
                    }
                    if (states.size >= 2 && states.all { it.state.isFinished }) {
                        assertTrue(backgrounded)
                        return@measured states
                    }
                    check(SystemClock.elapsedRealtime() < deadline) { "Batch worker timeout: ${states.map { it.state }}" }
                    delay(200)
                }
                @Suppress("UNREACHABLE_CODE") emptyList<WorkInfo>()
            }
            assertTrue(infos.all { it.state == WorkInfo.State.SUCCEEDED })
            assertEquals(100, infos.sumOf { it.outputData.getInt(BatchImportWorker.KEY_SUCCEEDED, 0) })
            assertEquals(0, infos.sumOf { it.outputData.getInt(BatchImportWorker.KEY_FAILED, -1) })
            val added = library.getBooks().filter { it.id !in before }
            assertEquals(100, added.size)
            added.forEach { book ->
                val chapters = library.getChapters(book.id)
                assertTrue(library.readChapterText(book.id, chapters.last()).contains("结尾完整"))
            }
            Log.i(TAG, "RESULT workmanager batches=${infos.size} success=100 failed=0 duplicates=1 background=true")
        } finally {
            manager.cancelUniqueWork(BatchImportWorker.UNIQUE_WORK_NAME).result.get()
            library.getBooks().filter { it.id !in before }.forEach { library.deleteBook(it) }
            scenario.close()
        }
    }

    private suspend fun verifyText(book: Long, first: String, last: String) {
        val chapters = repository.getChapters(book)
        assertTrue(chapters.size >= 4)
        assertTrue(repository.readChapterText(book, chapters.first()).contains(first))
        assertTrue(repository.readChapterText(book, chapters.last()).contains(last))
        assertTrue(requireNotNull(repository.getBook(book)).textVersion > 0)
        Log.i(TAG, "RESULT book=$book chapters=${chapters.size} chars=${chapters.sumOf { it.charCount.toLong() }}")
    }

    private fun largeTxt(name: String, bytes: Int): File {
        val file = File(context.root, "$name.txt")
        val paragraph = "测试正文，风雨过后继续前行。🙂\n".repeat(100)
        file.bufferedWriter().use { writer ->
            var size = 0
            var chapter = 1
            while (size < bytes) {
                val text = "第${chapter++}章 标题$name\n$paragraph\n结尾完整。\n\n"
                writer.write(text)
                size += text.toByteArray().size
            }
        }
        return file
    }

    private suspend fun <T> measured(label: String, block: suspend () -> T): T = kotlinx.coroutines.coroutineScope {
        val runtime = Runtime.getRuntime()
        val peak = AtomicLong(runtime.totalMemory() - runtime.freeMemory())
        val sampler = launch(Dispatchers.Default) {
            while (true) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                delay(50)
            }
        }
        val start = SystemClock.elapsedRealtime()
        try { block() } finally {
            sampler.cancelAndJoin()
            Log.i(TAG, "TIMING $label elapsedMs=${SystemClock.elapsedRealtime() - start} peakJavaMiB=${peak.get() / 1048576} maxJavaMiB=${runtime.maxMemory() / 1048576}")
        }
    }

    private companion object { const val TAG = "BookImportDeviceTest" }
}
