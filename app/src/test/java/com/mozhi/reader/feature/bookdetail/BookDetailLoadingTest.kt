package com.mozhi.reader.feature.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.embedding.EmbeddingProgressTracker
import com.mozhi.reader.core.database.dao.PersonaDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.datastore.CompanionAutonomySettings
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import com.mozhi.reader.core.library.ShelfOrganizationSnapshot
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BookDetailLoadingTest {
    private val library = mockk<LibraryRepository>()
    private val notes = mockk<NoteRepository>()
    private val annotations = mockk<AnnotationRepository>()
    private val illustrations = mockk<IllustrationRepository>(relaxed = true)
    private val settings = mockk<ReaderSettingsRepository>()
    private val shelf = mockk<ShelfOrganizationRepository>()
    private val audiobook = mockk<AudiobookRepository>()
    private val embedding = mockk<EmbeddingProgressTracker>()
    private val personas = mockk<PersonaDao>()
    private val book = BookEntity(id = 1, title = "加载回归", author = "作者", coverPath = null, epubPath = "",
        sourceType = BookSourceType.TXT, importedAt = 1, totalChapters = 1)
    private val books = MutableStateFlow<BookEntity?>(book)
    private val chapter = ChapterEntity(bookId = 1, chapterIndex = 0, title = "简介", href = "", charCount = 6)
    private val chapters = MutableStateFlow(listOf(chapter))
    private val subscriptions = AtomicInteger()
    private var model: BookDetailViewModel? = null

    @Before fun prepare() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { library.observeBook(1) } returns books
        every { library.observeChapters(1) } returns chapters.onStart { subscriptions.incrementAndGet() }
        every { library.observeBookmarks(1) } returns flowOf(emptyList())
        every { library.observeReadingDays(1) } returns flowOf(emptyList())
        every { library.observeBookReadSpan(1) } returns flowOf(null)
        every { notes.observeForBook(1) } returns flowOf(emptyList())
        every { annotations.observeForBook(1) } returns flowOf(emptyList())
        every { illustrations.observeForBook(1) } returns flowOf(emptyList())
        every { personas.observePersonas() } returns flowOf(emptyList())
        every { settings.settings } returns flowOf(ReaderSettings())
        every { settings.companionSpoilerProtectionEnabled } returns flowOf(true)
        every { settings.companionAutonomySettings } returns flowOf(CompanionAutonomySettings())
        every { shelf.snapshot } returns flowOf(ShelfOrganizationSnapshot())
        every { audiobook.observeChapters(1) } returns flowOf(emptyList())
        every { audiobook.observeRoles(1) } returns flowOf(emptyList())
        every { embedding.observeBook(1) } returns flowOf(BookEmbeddingProgress(1))
        coEvery { library.readChapterText(1, any()) } returns "书籍简介正文"
    }

    @After fun cleanup() {
        model?.viewModelScope?.cancel()
        Dispatchers.resetMain()
    }

    private fun model() = BookDetailViewModel(SavedStateHandle(mapOf("bookId" to 1L)),
        library, notes, annotations, illustrations, settings, mockk(), mockk(), mockk(), shelf,
        audiobook, embedding, personas).also { model = it }

    private suspend fun awaitDetails(vm: BookDetailViewModel) = withContext(Dispatchers.Default) {
        withTimeout(5_000) { vm.uiState.first { it.book != null && !it.isLoading } }
    }

    @Test fun bookIdentityIsAvailableWhileSecondaryQueriesAreStillWaiting() = runTest {
        every { notes.observeForBook(1) } returns flow { awaitCancellation() }
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()
        assertEquals(book, vm.uiState.value.book)
        assertTrue(vm.uiState.value.isLoading)
    }

    @Test fun descriptionReadsDoNotBlockThePageAndChapterQueriesAreShared() = runTest {
        val release = CompletableDeferred<Unit>()
        coEvery { library.readChapterText(1, any()) } coAnswers {
            release.await()
            "书籍简介正文"
        }
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()
        awaitDetails(vm)
        assertEquals(book, vm.uiState.value.book)
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("", vm.uiState.value.description)
        assertEquals(1, subscriptions.get())
        release.complete(Unit)
    }

    @Test fun metadataUpdatesKeepTheLoadedReadingMaterial() = runTest {
        val note = NoteEntity(id = 1, bookId = 1, title = "保留的笔记", contentMarkdown = "笔记正文",
            createdAt = 1, updatedAt = 1)
        every { notes.observeForBook(1) } returns flowOf(listOf(note))
        val vm = model()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
        advanceUntilIdle()
        awaitDetails(vm)
        books.value = book.copy(title = "新的书名")
        advanceUntilIdle()
        assertEquals("新的书名", vm.uiState.value.book?.title)
        assertEquals(listOf(note), vm.uiState.value.notes)
        assertFalse(vm.uiState.value.isLoading)
    }
}
