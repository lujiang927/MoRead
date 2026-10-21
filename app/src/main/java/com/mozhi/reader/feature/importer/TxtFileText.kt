package com.mozhi.reader.feature.importer

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/** Fixed-width UTF-16LE staging file with a bounded cache; never maps or loads the whole book. */
internal class TxtFileText(file: File) : CharSequence, Closeable {
    private val input = RandomAccessFile(file, "r")
    override val length: Int
    private val buffer = ByteArray(64 * 1024)
    private var bufferStart = -1
    private var bufferLength = 0

    init {
        val bytes = input.length()
        require(bytes % 2 == 0L && bytes / 2 <= Int.MAX_VALUE) { "Invalid staged TXT length" }
        length = (bytes / 2).toInt()
    }

    override fun get(index: Int): Char {
        require(index in 0 until length)
        if (index < bufferStart || index >= bufferStart + bufferLength) {
            bufferStart = index / (buffer.size / 2) * (buffer.size / 2)
            bufferLength = minOf(buffer.size / 2, length - bufferStart)
            input.seek(bufferStart.toLong() * 2)
            input.readFully(buffer, 0, bufferLength * 2)
        }
        val offset = (index - bufferStart) * 2
        return ((buffer[offset].toInt() and 255) or ((buffer[offset + 1].toInt() and 255) shl 8)).toChar()
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TxtTextSlice(this, startIndex, endIndex)
    override fun close() = input.close()
}

/** The source stays open until its views have been written. */
internal class TxtTextSlice(private val source: CharSequence, private val start: Int, private val end: Int) : CharSequence {
    init { require(start in 0..end && end <= source.length) }
    override val length: Int get() = end - start
    override fun get(index: Int): Char {
        require(index in 0 until length)
        return source[start + index]
    }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
        require(startIndex in 0..endIndex && endIndex <= length)
        return TxtTextSlice(source, start + startIndex, start + endIndex)
    }
    override fun toString(): String = buildString(length) {
        for (index in 0 until this@TxtTextSlice.length) append(this@TxtTextSlice[index])
    }
}
