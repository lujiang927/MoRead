package com.mozhi.reader.core.datastore

import android.app.Application
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShelfGridColumnsPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    private val columnsKey = intPreferencesKey("shelf_grid_columns")

    private fun store(scope: CoroutineScope) = PreferenceDataStoreFactory.create(
        storage = OkioStorage(
            fileSystem = FileSystem.SYSTEM,
            serializer = PreferencesSerializer,
            producePath = { File(temporary.root, "reader.preferences_pb").absolutePath.toPath() }
        ),
        scope = scope
    )

    @Test
    fun freshAndInvalidStoredColumnsUseAutomaticLayout() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope)
            val repository = ReaderSettingsRepository(store)
            assertEquals(0, ReaderSettings().shelfGridColumns)
            assertEquals(0, repository.settings.first().shelfGridColumns)
            listOf(Int.MIN_VALUE, -1, 0, 1, 2, 7, Int.MAX_VALUE).forEach { value ->
                store.edit { it[columnsKey] = value }
                assertEquals(0, repository.settings.first().shelfGridColumns)
            }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun validOverridesAndAutomaticPersistAcrossStoreReopening() = runTest {
        var previousValue = 0
        listOf(3, 4, 5, 6, 0).forEach { value ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = store(scope)
                val repository = ReaderSettingsRepository(store)
                assertEquals(previousValue, repository.settings.first().shelfGridColumns)
                repository.setShelfGridColumns(value)
                assertEquals(value, repository.settings.first().shelfGridColumns)
                assertEquals(value, store.data.first()[columnsKey])
                previousValue = value
            } finally {
                scope.coroutineContext.job.cancelAndJoin()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            assertEquals(0, ReaderSettingsRepository(store(scope)).settings.first().shelfGridColumns)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun setterClampsNonAutomaticValuesToThreeThroughSix() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = store(scope)
            val repository = ReaderSettingsRepository(store)
            listOf(Int.MIN_VALUE to 3, -1 to 3, 1 to 3, 2 to 3, 7 to 6, Int.MAX_VALUE to 6, 0 to 0)
                .forEach { (value, expected) ->
                    repository.setShelfGridColumns(value)
                    assertEquals(expected, repository.settings.first().shelfGridColumns)
                    assertEquals(expected, store.data.first()[columnsKey])
                }
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
