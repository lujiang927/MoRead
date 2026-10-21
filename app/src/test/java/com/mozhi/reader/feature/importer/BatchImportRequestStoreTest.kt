package com.mozhi.reader.feature.importer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImportRequestStoreTest {

    @Test fun `checkpoint survives recreation and does not rewrite the long request list`() {
        val directory = Files.createTempDirectory("moread-batch-checkpoint").toFile()
        try {
            val files = BatchImportRequestFiles(directory)
            val id = files.create(BatchImportRequestPayload((1..500).map { BatchImportRequestEntry("content://books/$it") }))
            val manifest = directory.resolve("$id.json").readBytes()
            val checkpoint = BatchImportCheckpoint(nextIndex = 17, succeeded = 15, failed = 1, skipped = 1,
                failures = listOf("失败"), skippedLabels = listOf("重复"), importedBookId = 42L)
            files.checkpoint(id, checkpoint)
            val restored = BatchImportRequestFiles(directory).read(id)!!
            assertEquals(500, restored.entries.size)
            assertEquals(checkpoint, restored.checkpoint)
            assertTrue(manifest.contentEquals(directory.resolve("$id.json").readBytes()))
            files.delete(id)
            assertFalse(directory.resolve("$id.progress.json").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun `corrupt checkpoint cannot silently restart a completed batch`() {
        val directory = Files.createTempDirectory("moread-batch-checkpoint").toFile()
        try {
            val files = BatchImportRequestFiles(directory)
            val id = files.create(BatchImportRequestPayload(listOf(BatchImportRequestEntry("file:///book.txt"))))
            directory.resolve("$id.progress.json").writeText("{")
            assertNull(files.read(id))
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun `large request survives disk round trip without WorkManager Data`() {
        val directory = Files.createTempDirectory("moread-batch-import").toFile()
        try {
            val store = BatchImportRequestFiles(directory)
            val payload = BatchImportRequestPayload(
                entries = (1..500).map { index ->
                    BatchImportRequestEntry(
                        uri = "content://com.android.externalstorage.documents/document/primary%3A" +
                            "小说%2F很长的分类名称%2F第${index}本书.epub",
                        groupPath = "小说/很长的分类名称/第${index}组"
                    )
                },
                deleteSourceAfterImport = false
            )

            val requestId = store.create(payload)

            assertEquals(payload, store.read(requestId))
            assertTrue(directory.resolve("$requestId.json").length() > 10_240L)

            store.delete(requestId)
            assertNull(store.read(requestId))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid request id cannot escape request directory`() {
        val directory = Files.createTempDirectory("moread-batch-import").toFile()
        try {
            val store = BatchImportRequestFiles(directory)
            assertNull(store.read("../outside"))
            assertFalse(directory.resolve("outside.json").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
