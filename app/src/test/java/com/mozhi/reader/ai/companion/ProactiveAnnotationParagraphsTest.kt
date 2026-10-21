package com.mozhi.reader.ai.companion

import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationParagraphsTest {
    @Test fun splitKeepsOriginalOffsetsAndSkipsShortAndTitleLines() {
        val line = "这是正文。".repeat(12)
        val body = "第一章\n\n  $line  \r\n短段\n$line"
        val rows = ProactiveAnnotationParagraphs.split(body)
        assertEquals(2, rows.size)
        rows.forEach { assertEquals(line, body.substring(it.start, it.end)) }
        assertEquals(body.indexOf(line), rows.first().start)
        assertEquals(body.length, rows.last().end)
    }
    @Test fun commonChineseAndEnglishHeadingsAreNeverCandidates() {
        listOf("序章", "楔子", "Chapter 12", "CHAPTER IV").forEach { title ->
            assertTrue(ProactiveAnnotationParagraphs.isHeading(title + " " + "标题".repeat(30)))
        }
        assertFalse(ProactiveAnnotationParagraphs.isHeading("正文讲述了第十二章的故事。"))
    }
    @Test fun deterministicCandidatesAreBoundedAndOrdered() {
        val body = (0..40).joinToString("\n") { "$it" + "句子。".repeat(30) }
        val rows = ProactiveAnnotationParagraphs.candidates(body, Int.MAX_VALUE)
        assertEquals(41, rows.size)
        assertEquals(10, ProactiveAnnotationParagraphs.candidates(body, 10).size)
        assertEquals(rows, ProactiveAnnotationParagraphs.candidates(body, Int.MAX_VALUE))
        assertEquals(rows.sortedBy { it.end }, rows)
    }
    @Test fun prefixEndsAtTargetAndNeverContainsSuffix() {
        val body = "前".repeat(40_000) + "\n" + "目标".repeat(30) + "\n禁止后文"
        val target = ProactiveAnnotationParagraphs.split(body).last()
        val prefix = ProactiveAnnotationParagraphs.prefix(body, target)
        assertTrue(prefix.endsWith("目标".repeat(30)))
        assertFalse(prefix.contains("禁止后文"))
        assertEquals(ProactiveAnnotationParagraphs.MAX_PREFIX_CHARS, prefix.length)
    }
    @Test fun longTargetSplitsWithoutDroppingTextOrExceedingTheContextBudget() {
        val body = "字🙂".repeat(40_000)
        val targets = ProactiveAnnotationParagraphs.split(body)
        assertEquals(body, targets.joinToString("") { body.substring(it.start, it.end) })
        assertTrue(targets.all { it.end - it.start <= ProactiveAnnotationParagraphs.MAX_TARGET_CHARS })
        targets.forEach {
            val prefix = ProactiveAnnotationParagraphs.prefix(body, it)
            assertTrue(prefix.length <= ProactiveAnnotationParagraphs.MAX_PREFIX_CHARS)
            assertFalse(prefix.first().isLowSurrogate())
            assertFalse(prefix.last().isHighSurrogate())
        }
        assertNotEquals(ProactiveAnnotationParagraphs.revision(body), ProactiveAnnotationParagraphs.revision(body + "新"))
    }
    @Test fun longParagraphsBecomeSentenceSizedTargetsSoTheyRarelySpanAPage() {
        val paragraph = "他沿着河岸慢慢地走，想起了很多年前的那个夏天。".repeat(25)
        val targets = ProactiveAnnotationParagraphs.split(paragraph)
        assertTrue(targets.size > 1)
        assertTrue(targets.all { it.end - it.start <= ProactiveAnnotationParagraphs.MAX_TARGET_CHARS })
        assertTrue(targets.dropLast(1).all { paragraph[it.end - 1] == '。' })
    }
    @Test fun bucketsStartAtTheReaderAndOfferAlternates() {
        val body = (0..9).joinToString("\n") { "$it" + "句子。".repeat(20) }
        val from = body.indexOf("5")
        val buckets = ProactiveAnnotationParagraphs.buckets(body, 2, from)
        assertEquals(2, buckets.size)
        assertTrue(buckets.flatten().all { it.start >= from })
        assertTrue(buckets.all { it.size > 1 })
        assertEquals(buckets.map { it.first() }, ProactiveAnnotationParagraphs.candidates(body, 2, from))
    }

    @Test fun unlimitedGenerationCoversTheChapterBeforeFillingEveryParagraphInItsOpening() {
        val body = (0..59).joinToString("\n") { "$it：" + "河岸的灯火映在水面，远处钟声缓缓响起。".repeat(5) }
        val plan = ProactiveAnnotationParagraphs.generationBuckets(body, Int.MAX_VALUE)
        val targets = plan.flatten()
        assertEquals(ProactiveAnnotationParagraphs.split(body).toSet(), targets.toSet())
        assertEquals(targets.size, targets.distinct().size)
        assertTrue(plan.all { it.size == 1 })
        // 第一轮只用少量调用就覆盖中段与末段，而非全部花在前几页。
        val firstCoverage = targets.take((body.length + 799) / 800)
        assertTrue(firstCoverage.any { it.start in body.length / 3..body.length * 2 / 3 })
        assertTrue(firstCoverage.any { it.start >= body.length * 4 / 5 })
        assertEquals(targets.first(), ProactiveAnnotationParagraphs.split(body).first())
        assertEquals(plan, ProactiveAnnotationParagraphs.generationBuckets(body, Int.MAX_VALUE))
    }

    @Test fun coveragePlanKeepsTheReaderBoundaryAndFiniteSelectionUnchanged() {
        val body = (0..49).joinToString("\n") { "$it：" + "句子。".repeat(25) }
        val from = body.indexOf("20：")
        val targets = ProactiveAnnotationParagraphs.generationBuckets(body, Int.MAX_VALUE, from).flatten()
        assertEquals(ProactiveAnnotationParagraphs.split(body).filter { it.start >= from }.toSet(), targets.toSet())
        assertTrue(targets.all { it.start >= from })
        assertEquals(ProactiveAnnotationParagraphs.buckets(body, 5, from),
            ProactiveAnnotationParagraphs.generationBuckets(body, 5, from))
    }
}
