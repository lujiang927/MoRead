package com.mozhi.reader.core.library

import java.io.BufferedOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A chapter's slice of a book's `text.mz`.
 *
 * [byteOffset]/[byteLength] index the decoded UTF-8 stream. [charCount] counts UTF-16 code units in the
 * decoded string. The two spaces are never interchangeable — every read seeks by bytes, every
 * reading position is expressed in characters.
 */
data class ChapterTextRange(
    val index: Int,
    val byteOffset: Long,
    val byteLength: Int,
    val charCount: Int
)

data class ChapterTextInput(val index: Int, val body: String)

data class ChapterTextSlice(val index: Int, val body: CharSequence)

/**
 * Writes every chapter body of a book into one UTF-8 blob and returns the byte ranges to store on
 * the chapter rows. Normalization happens here, once at import, so the layout engine never has to
 * defend against source quirks.
 */
@Singleton
class BookTextWriter @Inject constructor() {

    /** Bounded output even for one huge chapter/paragraph. Byte ranges still index canonical UTF-8. */
    fun writeSlices(target: File, chapters: List<ChapterTextSlice>, checkActive: () -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }): List<ChapterTextRange> {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("text-write-", ".tmp", target.parentFile)
        try {
            val ranges = ArrayList<ChapterTextRange>(chapters.size)
            val buffer = CharArray(8 * 1024)
            CountingOutput(temporary.outputStream().buffered()).use { output ->
                output.writer(Charsets.UTF_8).use { writer ->
                    chapters.sortedBy(ChapterTextSlice::index).forEachIndexed { position, chapter ->
                        checkActive()
                        val offset = output.count
                        val chars = writeNormalized(writer, chapter.body, checkActive, buffer)
                        writer.flush()
                        val bytes = output.count - offset
                        require(bytes <= Int.MAX_VALUE) { "章节正文超出存储上限" }
                        ranges += ChapterTextRange(chapter.index, offset, bytes.toInt(), chars)
                        onProgress(position + 1, chapters.size)
                    }
                }
            }
            BookTextArchive.compact(temporary, checkActive)
            checkActive()
            BookTextArchive.replaceAtomically(temporary, target)
            return ranges
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    private fun writeNormalized(writer: Writer, source: CharSequence, checkActive: () -> Unit, buffer: CharArray): Int {
        var cursor = 0
        var wroteAny = false
        var pendingBlank = false
        var chars = 0
        while (cursor < source.length) {
            checkActive()
            val lineStart = cursor
            while (cursor < source.length && source[cursor] != '\n' && source[cursor] != '\r') {
                if (cursor and 0xFFFF == 0) checkActive()
                cursor++
            }
            var start = lineStart
            var end = cursor
            while (start < end && (source[start] == '\t' || source[start] in LEADING_BLANKS)) {
                if (start and 0xFFFF == 0) checkActive()
                start++
            }
            while (end > start && source[end - 1].isWhitespace()) {
                if (end and 0xFFFF == 0) checkActive()
                end--
            }
            if (start == end) {
                if (wroteAny) pendingBlank = true
            } else {
                if (wroteAny) { writer.write('\n'.code); chars++ }
                if (pendingBlank) { writer.write('\n'.code); chars++; pendingBlank = false }
                while (start < end) {
                    checkActive()
                    val count = minOf(buffer.size, end - start)
                    for (index in 0 until count) {
                        val char = source[start + index]
                        buffer[index] = if (char == '\t') ' ' else char
                    }
                    writer.write(buffer, 0, count)
                    chars += count
                    start += count
                }
                wroteAny = true
            }
            if (cursor < source.length) {
                val cr = source[cursor++] == '\r'
                if (cr && cursor < source.length && source[cursor] == '\n') cursor++
            }
        }
        return chars
    }

    private class CountingOutput(output: OutputStream) : FilterOutputStream(output) {
        var count = 0L
            private set
        override fun write(value: Int) { out.write(value); count++ }
        override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length); count += length }
    }

    fun write(target: File, chapters: List<ChapterTextInput>): List<ChapterTextRange> {
        target.parentFile?.mkdirs()
        val temporary = File.createTempFile("text-write-", ".tmp", target.parentFile)
        val ranges = ArrayList<ChapterTextRange>(chapters.size)
        try {
            BufferedOutputStream(temporary.outputStream()).use { stream ->
                var offset = 0L
                chapters.sortedBy(ChapterTextInput::index).forEach { chapter ->
                    val body = normalize(chapter.body)
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    stream.write(bytes)
                    ranges += ChapterTextRange(
                        index = chapter.index,
                        byteOffset = offset,
                        byteLength = bytes.size,
                        charCount = body.length
                    )
                    offset += bytes.size
                }
            }
            BookTextArchive.compact(temporary)
            BookTextArchive.replaceAtomically(temporary, target)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
        return ranges
    }

    /**
     * Leading ideographic spaces are stripped because the engine applies its own paragraph indent;
     * keeping the source's would double it.
     */
    fun normalize(body: String): String {
        val unified = body.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
        val paragraphs = unified.split('\n').map { line ->
            line.trimStart(*LEADING_BLANKS).trimEnd()
        }
        val result = StringBuilder(unified.length)
        var pendingBlank = false
        var wroteAny = false
        paragraphs.forEach { paragraph ->
            if (paragraph.isEmpty()) {
                if (wroteAny) pendingBlank = true
                return@forEach
            }
            if (wroteAny) result.append('\n')
            if (pendingBlank) {
                result.append('\n')
                pendingBlank = false
            }
            result.append(paragraph)
            wroteAny = true
        }
        return result.toString()
    }

    private companion object {
        /** Space, no-break space, ideographic space. */
        val LEADING_BLANKS = charArrayOf(' ', ' ', '　')
    }
}
