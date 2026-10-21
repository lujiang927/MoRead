package com.mozhi.reader.feature.importer

import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubArchiveNormalizerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun ordinaryArchivesRemainByteIdentical() {
        val file = archive(duplicate = false)
        val original = file.readBytes()
        assertFalse(EpubArchiveNormalizer.normalize(file))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun duplicateMimetypeIsRepairedWithoutChangingResourceNamesOrContents() {
        val file = archive()
        val original = temporary.newFile("original.epub").apply { file.copyTo(this, overwrite = true) }
        val originalBytes = original.readBytes()
        val expected = contents(file)
        assertTrue(EpubArchiveNormalizer.normalize(file))
        assertEquals(expected.keys, contents(file).keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, contents(file).getValue(name)) }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(entries.size, entries.map { it.name }.toSet().size)
            assertEquals("mimetype", entries.first().name)
            assertEquals(ZipEntry.STORED, entries.first().method)
        }
        assertArrayEquals(originalBytes, original.readBytes())
        val normalizedBytes = file.readBytes()
        assertFalse(EpubArchiveNormalizer.normalize(file))
        assertArrayEquals(normalizedBytes, file.readBytes())
    }

    @Test fun conflictingMimetypeFailsWithoutReplacingTheArchiveOrLeavingTemporaryFiles() {
        val file = archive(secondMime = "application/not-an-epub")
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { EpubArchiveNormalizer.normalize(file) }
        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf(file.name), temporary.root.list()!!.toList())
    }

    @Test fun duplicateChapterIsNeverSilentlyDiscarded() {
        val file = archive(duplicateChapter = true)
        val original = file.readBytes()
        assertThrows(IllegalArgumentException::class.java) { EpubArchiveNormalizer.normalize(file) }
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun identicalDuplicateResourcesAreAlsoRepaired() {
        val file = archive(duplicate = false, duplicateChapter = true, secondChapter = "<p>测试正文🙂</p>")
        val expected = contents(file)
        assertTrue(EpubArchiveNormalizer.normalize(file))
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(names.size, names.toSet().size)
        }
        val actual = contents(file)
        assertEquals(expected.keys, actual.keys)
        expected.forEach { (name, bytes) -> assertArrayEquals(name, bytes, actual.getValue(name)) }
    }

    @Test fun cancellationLeavesTheArchiveIntactAndCleansUp() {
        val file = archive()
        val original = file.readBytes()
        assertThrows(CancellationException::class.java) {
            EpubArchiveNormalizer.normalize(file) { throw CancellationException() }
        }
        assertArrayEquals(original, file.readBytes())
        assertEquals(listOf(file.name), temporary.root.list()!!.toList())
    }

    private fun archive(
        duplicate: Boolean = true,
        secondMime: String = "application/epub+zip",
        duplicateChapter: Boolean = false,
        secondChapter: String = "different text"
    ): File {
        val file = temporary.newFile("fixture.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip".toByteArray())
            if (duplicate) put("mimetyp2", secondMime.toByteArray())
            put("OPS/Text/:*one.xhtml", "<p>测试正文🙂</p>".toByteArray())
            put("OPS/Images/:*one.png", byteArrayOf(0, 1, 2, 127, -1))
            if (duplicateChapter) put("OPS/Text/:*two.xhtml", secondChapter.toByteArray())
        }
        // ZipOutputStream rejects duplicates. Patch equal-length local/central entry names
        // to reproduce the malformed producer output without committing a real book.
        val bytes = file.readBytes()
        fun rename(from: String, to: String) {
            val pattern = from.toByteArray()
            val replacement = to.toByteArray()
            require(pattern.size == replacement.size)
            for (offset in 0..bytes.size - pattern.size) {
                if (pattern.indices.all { bytes[offset + it] == pattern[it] }) {
                    replacement.copyInto(bytes, offset)
                }
            }
        }
        if (duplicate) rename("mimetyp2", "mimetype")
        if (duplicateChapter) rename("OPS/Text/:*two.xhtml", "OPS/Text/:*one.xhtml")
        file.writeBytes(bytes)
        return file
    }

    private fun contents(file: File): Map<String, ByteArray> = ZipFile(file).use { zip ->
        zip.entries().asSequence().associate { entry ->
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        }
    }
}
