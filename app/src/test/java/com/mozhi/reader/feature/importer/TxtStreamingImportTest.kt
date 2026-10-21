package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.BookTextArchive
import com.mozhi.reader.core.library.BookTextWriter
import com.mozhi.reader.core.library.ChapterTextSlice
import java.io.File
import java.nio.charset.Charset
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TxtStreamingImportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val splitter = TxtChapterSplitter()
    private val writer = BookTextWriter()
    private val rules: List<TxtTocRule> by lazy { Json { ignoreUnknownKeys = true }.decodeFromString(
        File("src/main/assets/txtTocRule.json").readText()
    ) }

    @Test fun `bounded heading windows preserve builtin lookarounds and wrapped headings`() {
        val samples = listOf(
            (1..8).joinToString("\n\n") { "第${it}章 标题\n第${it}章 重复标题\n${"正文。".repeat(100)}" },
            (1..8).joinToString("\n\n") { "第\n$it\n章 标题\n${"正文。".repeat(100)}" },
            (1..8).joinToString("\n") { "${" ".repeat(1000)}第${it}章 标题\n${"正文。".repeat(100)}" },
            rules.filter { it.id in -25L..-1L }.joinToString("\n\n") { "${it.example}\n${"正文。".repeat(100)}" },
            "正文。".repeat(22_000) + "\n" + (1..8).joinToString("\n\n") {
                "第${it}章 标题\n${"这一行是正文。\n".repeat(1_000)}"
            }
        )
        samples.forEach { source ->
            rules.filter { it.id in -25L..-1L }.forEach { rule ->
                // Positive IDs retain arbitrary full-source regex matching as the independent oracle.
                val expected = splitter.indexWithRule(source, rule.copy(id = 1))
                val actual = splitter.indexWithRule(source, rule)
                assertEquals("rule ${rule.id}: ${rule.name}", expected?.chapters, actual?.chapters)
            }
        }
    }

    @Test fun `invalid wrapped heading does not consume the next valid heading`() {
        val source = (1..8).joinToString("\n\n") { "第${"9".repeat(76)}\n章 第${it}章\n${"内容。".repeat(100)}" }
        val rule = rules.first { it.id == -1L }
        val result = requireNotNull(splitter.indexWithRule(source, rule))
        assertEquals((1..8).map { "章 第${it}章" }, result.chapters.filter { it.title != "序章" }.map { it.title })
    }

    @Test fun `custom regex materializes staged characters and rejects oversized input before reading`() {
        val source = (1..8).joinToString("\n\n") { "第${it}章 标题\n${"正文。".repeat(100)}" }
        val staged = temporary.newFile().apply { writeText(source, Charsets.UTF_16LE) }
        TxtFileText(staged).use { text ->
            assertEquals(8, splitter.indexWithCustomRegex(text, "^第\\d+章.*$")!!.chapters.size)
        }
        val oversized = object : CharSequence {
            override val length = 8_000_001
            override fun get(index: Int): Char = error("must reject before reading")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("must reject before copying")
        }
        try {
            splitter.indexWithCustomRegex(oversized, "^第\\d+章.*$")
            fail("expected memory guard")
        } catch (_: TxtRegexInputTooLargeException) { }
    }

    @Test fun `giant heading padding never enters the Android regex window`() {
        val raw = (1..8).joinToString("\n") { "${" ".repeat(1000)}第${it}章 标题${" ".repeat(100_000)}\n${"这是内容。".repeat(100)}" }
        val guarded = object : CharSequence {
            override val length = raw.length
            override fun get(index: Int) = raw[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
                assertTrue("regex input must stay bounded", endIndex - startIndex < 400)
                return raw.subSequence(startIndex, endIndex)
            }
            override fun toString(): String = error("must not copy whole source")
        }
        val split = splitter.indexBest(guarded, rules)
        assertTrue(split.usedFallback)
        assertEquals(raw.trim().length, split.chapters.last().endOffset - split.chapters.first().startOffset)
    }

    @Test fun `automatic index agrees with full-source regex selection for bundled rules`() {
        val samples = listOf(
            (1..8).joinToString("\n\n") { "第${it}章 标题\n${"正文。".repeat(100)}" },
            "前言\n介绍。\n" + (1..8).joinToString("\n") { "　　第 $it 章 开始\n${"内容。".repeat(40)}" },
            (1..8).joinToString("\n\n") { "Chapter $it Sample\n${"Body. ".repeat(50)}" },
            (1..8).joinToString("\n\n") { "第\n$it\n章 标题\n${"正文。".repeat(100)}" },
            (1..8).joinToString("\n") { "${" ".repeat(1000)}第${it}章 标题\n${"正文。".repeat(100)}" },
            (1..8).joinToString("\n\n") { "分节阅读_$it\n${"长段落。".repeat(100)}" },
            (1..8).joinToString("\n\n") { "☆ 第${it}节\n${"故事。".repeat(100)}" }
        )
        samples.forEach { source ->
            val expected = rules.filter { it.enable }.mapNotNull { splitter.splitWithRule(source, it) }
                .filter { it.chapters.size >= 4 }.maxByOrNull { it.score }!!
            val actual = splitter.chooseBest(source, rules)
            assertEquals(expected.rule?.id, actual.rule?.id)
            assertEquals(expected.chapters, actual.chapters)
            assertEquals(expected.score, actual.score, 0.001)
        }
    }

    @Test fun `streaming decoding preserves encodings newline boundaries and supplementary characters`() {
        val text = "书名：样例\r\n作者：作者\r" + "正文".repeat(8190) + "\r\n🙂𠀀\r结尾"
        for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charset.forName("GB18030"))) {
            val source = temporary.newFile()
            val decoded = temporary.newFile()
            val bom = when (charset) {
                Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                else -> byteArrayOf()
            }
            source.writeBytes(bom + text.toByteArray(charset))
            val detector = TextEncodingDetector()
            detector.decodeToFile(source, decoded)
            TxtFileText(decoded).use { actual ->
                assertEquals(detector.decode(source.readBytes()).text, actual.subSequence(0, actual.length).toString())
            }
        }
    }

    @Test fun `long ascii prologue does not hide later Big5 text from encoding detection`() {
        val text = "ASCII prologue. ".repeat(8_000) +
            "\n繁體中文閱讀測試，風雨過後繼續前行。這是書籍的正文，請保持文字完整。".repeat(1_000)
        val source = temporary.newFile().apply { writeBytes(text.toByteArray(Charset.forName("Big5"))) }
        val decoded = temporary.newFile()
        TextEncodingDetector().decodeToFile(source, decoded)
        TxtFileText(decoded).use { result -> assertEquals(text, result.subSequence(0, result.length).toString()) }
    }

    @Test fun `file-backed chapters round trip canonical text and byte ranges`() {
        val source = "序言\n\n" + (1..8).joinToString("\n\n") {
            "第${it}章 故事\n\t　 正文\t含 emoji 🙂\r\n\n  第二段  \n"
        }
        val raw = temporary.newFile().apply { writeText(source) }
        val decoded = temporary.newFile()
        TextEncodingDetector().decodeToFile(raw, decoded)
        val expectedSource = TextEncodingDetector().decode(raw.readBytes()).text
        val expected = splitter.chooseBest(expectedSource, rules)
        val target = temporary.newFile()
        TxtFileText(decoded).use { text ->
            val split = splitter.indexBest(text, rules)
            val ranges = writer.writeSlices(target, split.chapters.map {
                ChapterTextSlice(it.index, text.subSequence(it.bodyStart, it.bodyEnd))
            })
            assertEquals(expected.chapters.size, ranges.size)
            BookTextArchive.Reader(target).use { archive ->
                ranges.forEachIndexed { index, range ->
                    val content = archive.read(range.byteOffset, range.byteLength).toString(Charsets.UTF_8)
                    assertEquals(writer.normalize(expected.chapters[index].content), content)
                    assertEquals(content.length, range.charCount)
                }
                assertEquals(archive.length, ranges.sumOf { it.byteLength.toLong() })
            }
        }
    }

    @Test fun `fallback bounds work and never splits surrogate pairs`() {
        val raw = "中".repeat(9_999) + "🙂" + "中".repeat(50_000)
        var reads = 0L
        val source = object : CharSequence {
            override val length = raw.length
            override fun get(index: Int): Char { reads++; return raw[index] }
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("indexing must not copy bodies")
        }
        val split = splitter.indexBest(source, emptyList())
        assertTrue(reads < raw.length * 2L)
        assertEquals(raw, split.chapters.joinToString("") { raw.substring(it.bodyStart, it.bodyEnd) })
        assertFalse(split.chapters.any { it.bodyEnd < raw.length && raw[it.bodyEnd].isLowSurrogate() })
    }

    @Test fun `indexing cancellation is checked during giant whitespace input`() {
        var checks = 0
        try {
            splitter.indexBest(" ".repeat(1_000_000), emptyList(), checkActive = {
                if (++checks >= 3) throw java.util.concurrent.CancellationException()
            })
            fail("expected cancellation")
        } catch (_: java.util.concurrent.CancellationException) { assertTrue(checks >= 3) }
    }

    @Test fun `excessive automatic headings fall back without losing source text`() {
        val source = (1..50_010).joinToString("\n") { "第${it}章 标题" }
        val split = splitter.indexBest(source, listOf(TxtTocRule(-2, true, "目录", "^第\\d+章.*$")))
        assertTrue(split.usedFallback)
        assertTrue(split.chapters.size < 100)
        assertEquals(0, split.chapters.first().startOffset)
        assertEquals(source.length, split.chapters.last().endOffset)
        split.chapters.zipWithNext().forEach { (first, second) -> assertEquals(first.endOffset, second.startOffset) }
    }

    @Test fun `writer handles a giant single paragraph without materializing its CharSequence`() {
        val source = object : CharSequence {
            override val length = 2_000_000
            override fun get(index: Int) = '中'
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("must stream")
            override fun toString(): String = error("must stream")
        }
        val target = temporary.newFile()
        val range = writer.writeSlices(target, listOf(ChapterTextSlice(0, source))).single()
        assertEquals(2_000_000, range.charCount)
        assertEquals(6_000_000, range.byteLength)
        BookTextArchive.Reader(target).use { assertEquals("中中", it.read(0, 6).toString(Charsets.UTF_8)) }
    }
}
