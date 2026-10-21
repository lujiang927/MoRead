package com.mozhi.reader.feature.importer

import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.math.abs

internal class TxtRegexInputTooLargeException : IllegalArgumentException("Custom chapter regex input exceeds the safe memory limit")

/** Android ICU requires a String even when Matcher accepts CharSequence. */
internal fun chapterRegexInput(text: CharSequence, checkActive: () -> Unit = {}): String {
    if (text is String) return text
    if (text.length > 8_000_000) throw TxtRegexInputTooLargeException()
    return buildString(text.length) {
        for (index in 0 until text.length) {
            if (index and 0xFFFF == 0) checkActive()
            append(text[index])
        }
    }
}

/** Offsets index UTF-16 source units. Rule selection never copies chapter bodies. */
data class TxtChapterRange(val index: Int, val title: String, val startOffset: Int, val endOffset: Int,
    val bodyStart: Int, val bodyEnd: Int) {
    val charCount: Int get() = bodyEnd - bodyStart
}

data class TxtIndexedSplit(val rule: TxtTocRule?, val chapters: List<TxtChapterRange>, val score: Double,
    val usedFallback: Boolean = false) {
    fun materialize(source: CharSequence): TxtSplitResult = TxtSplitResult(
        rule, chapters.map {
            TxtChapter(it.index, it.title, source.subSequence(it.bodyStart, it.bodyEnd).toString(), it.startOffset, it.endOffset)
        }, score, usedFallback
    )
}

class TxtChapterSplitter @Inject constructor() {
    private val patterns = object : LinkedHashMap<String, Pattern>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pattern>): Boolean = size > 64
    }

    fun chooseBest(text: String, rules: List<TxtTocRule>): TxtSplitResult =
        indexBest(text, rules).materialize(text)

    /** Scan once; run automatic regexes only on bounded heading lines, never long body lines. */
    fun indexBest(text: CharSequence, rules: List<TxtTocRule>, checkActive: () -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }): TxtIndexedSplit {
        val enabled = rules.filter { it.enable && it.rule.isNotBlank() }
        val lineRules = scanHeadingLines(text, enabled.filterNot(::needsFullSource), checkActive, onProgress)
        var best: TxtIndexedSplit? = null
        enabled.forEach { rule ->
            checkActive()
            val headings = lineRules.firstOrNull { it.first === rule }?.third
                ?: fullSourceHeadings(text, rule, checkActive, MAX_AUTOMATIC_HEADINGS)
            val candidate = headings?.takeIf { it.size <= MAX_AUTOMATIC_HEADINGS }?.let { indexed(text, rule, it, checkActive) }
            if (candidate != null && candidate.chapters.size >= MIN_REASONABLE_CHAPTERS &&
                (best == null || candidate.score > requireNotNull(best).score)) best = candidate
        }
        checkActive()
        onProgress(text.length, text.length)
        return best ?: fallback(text, checkActive)
    }

    private fun scanHeadingLines(text: CharSequence, rules: List<TxtTocRule>, checkActive: () -> Unit,
        onProgress: (Int, Int) -> Unit = { _, _ -> }, maxHeadings: Int = MAX_AUTOMATIC_HEADINGS):
        List<Triple<TxtTocRule, java.util.regex.Matcher, ArrayList<Heading>>> {
        val lineRules = rules
            .mapNotNull { rule -> pattern(rule)?.let {
                // Android's ICU Matcher converts any CharSequence to String. A staged file is
                // therefore never a regex input; reset with only the bounded heading context.
                Triple(rule, it.matcher("").useAnchoringBounds(false).useTransparentBounds(true), ArrayList<Heading>())
            } }
        val pending = ArrayList<HeadingLine>()
        var windowStart = 0
        var windowEnd = 0
        fun flush() {
            if (pending.isEmpty()) return
            checkActive()
            val window = text.subSequence(windowStart, windowEnd).toString()
            val regionEnd = pending.last().regionEnd - windowStart
            lineRules.forEach { (_, matcher, headings) ->
                if (headings.size > maxHeadings) return@forEach
                matcher.reset(window).region(pending.first().regionStart - windowStart, regionEnd)
                var lineIndex = 0
                var attemptedLine = -1
                while (matcher.find()) {
                    checkActive()
                    val matchStart = matcher.start() + windowStart
                    val matchEnd = matcher.end() + windowStart
                    while (lineIndex < pending.size && pending[lineIndex].end <= matchStart) lineIndex++
                    if (lineIndex >= pending.size) break
                    val line = pending[lineIndex]
                    if (matchStart < line.regionStart || matchStart >= line.end || attemptedLine == lineIndex ||
                        line.start < (headings.lastOrNull()?.end ?: 0)) continue
                    attemptedLine = lineIndex
                    var accepted = false
                    if (matchEnd <= line.regionEnd) {
                        var end = maxOf(line.end, matchEnd)
                        while (end < text.length && text[end] != '\n') end++
                        val bounds = if (end == line.end) line.firstNonBlank to line.lastNonBlank + 1
                            else trimmed(text, line.start, end, checkActive)
                        if (bounds.second - bounds.first in 1..MAX_TITLE_LENGTH) {
                            headings += Heading(line.start, end, text.subSequence(bounds.first, bounds.second).toString())
                            accepted = true
                            if (headings.size > maxHeadings) break
                        }
                    }
                    // An invalid cross-line match must not consume a later valid title. The old
                    // per-line search retried there; restart only for this uncommon case.
                    val next = pending.getOrNull(lineIndex + 1)
                    if (!accepted && next != null && matchEnd > next.regionStart) {
                        lineIndex++
                        matcher.region(next.regionStart - windowStart, regionEnd)
                    }
                }
            }
            pending.clear()
        }
        if (lineRules.isNotEmpty()) {
            var start = 0
            var cursor = 0
            var firstNonBlank = -1
            var lastNonBlank = -1
            while (cursor <= text.length) {
                if (cursor and 0xFFFF == 0) { checkActive(); onProgress(cursor, text.length) }
                if (cursor == text.length || text[cursor] == '\n') {
                    if (firstNonBlank >= 0 && lastNonBlank - firstNonBlank < MAX_TITLE_LENGTH) {
                        // Keep real source boundaries for ^, $, and lookbehind. Do not scan huge padding.
                        val regionStart = maxOf(start, firstNonBlank - MAX_TITLE_LENGTH)
                        // Built-in \s quantifiers can consume a short wrapped heading (e.g. 第\n一章).
                        // A bounded lookahead preserves that behavior without scanning body paragraphs.
                        val regionEnd = minOf(text.length, cursor + MAX_TITLE_LENGTH + 1,
                            firstNonBlank + MAX_TITLE_LENGTH * 2 + 1)
                        val candidateStart = maxOf(0, regionStart - MAX_TITLE_LENGTH - 1)
                        val candidateEnd = minOf(text.length, regionEnd + 1)
                        if (pending.isNotEmpty() && candidateEnd - windowStart > REGEX_WINDOW_CHARACTERS) flush()
                        if (pending.isEmpty()) windowStart = candidateStart
                        windowEnd = candidateEnd
                        pending += HeadingLine(start, cursor, firstNonBlank, lastNonBlank, regionStart, regionEnd)
                    }
                    start = cursor + 1
                    firstNonBlank = -1
                    lastNonBlank = -1
                } else if (!text[cursor].isWhitespace()) {
                    if (firstNonBlank < 0) firstNonBlank = cursor
                    lastNonBlank = cursor
                }
                cursor++
            }
        }
        flush()
        return lineRules
    }

    // Built-in selections share the bounded scan; arbitrary custom rules keep full-source semantics.
    fun splitWithRule(text: String, rule: TxtTocRule): TxtSplitResult? =
        indexWithRule(text, rule, maxHeadings = Int.MAX_VALUE)?.materialize(text)

    fun indexWithRule(text: CharSequence, rule: TxtTocRule, checkActive: () -> Unit = {},
        maxHeadings: Int = MAX_AUTOMATIC_HEADINGS): TxtIndexedSplit? =
        (if (needsFullSource(rule)) fullSourceHeadings(text, rule, checkActive, maxHeadings)
        else scanHeadingLines(text, listOf(rule), checkActive, maxHeadings = maxHeadings).firstOrNull()?.third
            ?.takeIf { it.size <= maxHeadings })?.let { indexed(text, rule, it, checkActive) }

    fun indexWithCustomRegex(text: CharSequence, regexText: String, checkActive: () -> Unit = {}): TxtIndexedSplit? =
        indexWithRule(text, customRule(regexText), checkActive)

    fun splitWithCustomRegex(text: String, regexText: String): TxtSplitResult? = splitWithRule(
        text, customRule(regexText)
    )

    private fun customRule(regexText: String) =
        TxtTocRule(Long.MIN_VALUE, true, "自定义规则", regexText, serialNumber = Int.MAX_VALUE)

    private fun pattern(rule: TxtTocRule): Pattern? {
        if (rule.rule.isBlank()) return null
        return synchronized(patterns) {
            patterns[rule.rule] ?: runCatching { Pattern.compile(rule.rule, Pattern.MULTILINE) }.getOrNull()
                ?.also { patterns[rule.rule] = it }
        }
    }

    private fun needsFullSource(rule: TxtTocRule): Boolean =
        rule.id !in -25L..-1L || rule.rule.contains("\\n") || rule.rule.contains("\\r") ||
            rule.rule.contains('\n') || rule.rule.contains('\r')

    private fun fullSourceHeadings(text: CharSequence, rule: TxtTocRule,
        checkActive: () -> Unit = {}, maxHeadings: Int = Int.MAX_VALUE): List<Heading>? {
        val compiled = pattern(rule) ?: return null
        // Arbitrary cross-line custom rules need the entire input on Android. Keep that one
        // allocation bounded instead of letting ICU copy an unbounded file or its object name.
        checkActive()
        val input = chapterRegexInput(text, checkActive)
        val matcher = compiled.matcher(input)
        val headings = ArrayList<Heading>()
        while (matcher.find()) {
            checkActive()
            var start = matcher.start()
            while (start > 0 && text[start - 1] != '\n') start--
            var end = matcher.end()
            while (end < text.length && text[end] != '\n') end++
            val bounds = trimmed(text, start, end, checkActive)
            if (bounds.second - bounds.first in 1..MAX_TITLE_LENGTH && headings.lastOrNull()?.start != start)
                headings += Heading(start, end, text.subSequence(bounds.first, bounds.second).toString())
            if (headings.size > maxHeadings) return null
        }
        return headings
    }

    private fun indexed(text: CharSequence, rule: TxtTocRule, headings: List<Heading>, checkActive: () -> Unit = {}): TxtIndexedSplit? {
        if (headings.size < 2) return null
        val chapters = ArrayList<TxtChapterRange>(headings.size + 1)
        fun add(title: String, start: Int, end: Int, bodyStart: Int) {
            checkActive()
            val bounds = trimmed(text, bodyStart, end, checkActive)
            chapters += TxtChapterRange(chapters.size, title, start, end, bounds.first, bounds.second)
        }
        val first = headings.first().start
        if (first > 0 && trimmed(text, 0, first, checkActive).let { it.first < it.second }) add("序章", 0, first, 0)
        headings.forEachIndexed { index, heading ->
            val end = headings.getOrNull(index + 1)?.start ?: text.length
            add(heading.title, heading.start, end, heading.end.coerceAtMost(end))
        }
        return TxtIndexedSplit(rule, chapters, score(chapters))
    }

    private fun score(chapters: List<TxtChapterRange>): Double {
        var total = 0L
        var short = 0
        var huge = 0
        chapters.forEach {
            val length = it.charCount.coerceAtLeast(1)
            total += length
            if (length < 80) short++
            if (length > 120_000) huge++
        }
        val count = chapters.size.toDouble()
        return count * 100 - short / count * 2_000 - huge / count * 5_000 -
            (abs(total / count - 8_000) / 8_000).coerceAtMost(5.0) * 30
    }

    private fun fallback(text: CharSequence, checkActive: () -> Unit): TxtIndexedSplit {
        val (first, last) = trimmed(text, 0, text.length, checkActive)
        if (first == last) return TxtIndexedSplit(null, emptyList(), 0.0, usedFallback = true)
        val chapters = ArrayList<TxtChapterRange>()
        var start = first
        while (start < last) {
            checkActive()
            val desiredEnd = minOf(start + FALLBACK_CHAPTER_SIZE, last)
            var end = desiredEnd
            if (desiredEnd < last) {
                // Search only the 2,000-character lookahead window.
                for (position in desiredEnd until minOf(desiredEnd + 2_001, last - 1)) {
                    if (text[position] == '\n' && text[position + 1] == '\n') {
                        if (position > desiredEnd) end = position
                        break
                    }
                }
                if (end > start && end < last && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end++
            }
            val bounds = trimmed(text, start, end, checkActive)
            chapters += TxtChapterRange(chapters.size, if (chapters.isEmpty()) "正文" else "第 ${chapters.size + 1} 节",
                start, end, bounds.first, bounds.second)
            start = end
        }
        return TxtIndexedSplit(null, chapters, 0.0, usedFallback = true)
    }

    private fun trimmed(text: CharSequence, start: Int, end: Int, checkActive: () -> Unit = {}): Pair<Int, Int> {
        var first = start
        var last = end
        while (first < last && text[first].isWhitespace()) {
            if (first and 0xFFFF == 0) checkActive()
            first++
        }
        while (last > first && text[last - 1].isWhitespace()) {
            if (last and 0xFFFF == 0) checkActive()
            last--
        }
        return first to last
    }

    private data class Heading(val start: Int, val end: Int, val title: String)
    private data class HeadingLine(val start: Int, val end: Int, val firstNonBlank: Int, val lastNonBlank: Int,
        val regionStart: Int, val regionEnd: Int)
    private companion object {
        const val MIN_REASONABLE_CHAPTERS = 4
        const val MAX_TITLE_LENGTH = 80
        const val REGEX_WINDOW_CHARACTERS = 64 * 1024
        // Excessive matches are usually paragraph-level false positives; fallback preserves all text.
        const val MAX_AUTOMATIC_HEADINGS = 50_000
        const val FALLBACK_CHAPTER_SIZE = 10_000
    }
}
