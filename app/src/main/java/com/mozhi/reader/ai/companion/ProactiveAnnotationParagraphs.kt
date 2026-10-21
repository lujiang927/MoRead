package com.mozhi.reader.ai.companion

import java.security.MessageDigest

data class ProactiveAnnotationParagraph(val start: Int, val end: Int)

/** All candidate selection happens locally; no model ever sees a suffix after its target. */
object ProactiveAnnotationParagraphs {
    const val MAX_PREFIX_CHARS = 28_000
    /**
     * 目标单元的上限。长段按句切成小块，缩短单次生成的目标范围，保留逐段点评和原文定位。
     */
    const val MAX_TARGET_CHARS = 200
    private const val SCORE_LENGTH_CAP = 200
    private const val COVERAGE_INTERVAL_CHARS = 800

    private val heading = Regex("^(?:第.{1,20}[章节卷回]|序章|序言|序幕|楔子|前言|后记|尾声|chapter\\s+(?:[0-9]+|[ivxlcdm]+)\\b).*", RegexOption.IGNORE_CASE)
    internal fun isHeading(line: String): Boolean = heading.matches(line.trim())

    fun split(body: String): List<ProactiveAnnotationParagraph> {
        var offset = 0
        return body.split('\n').flatMap { line ->
            val start = offset + line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val end = offset + line.trimEnd().length
            offset += line.length + 1
            if (end - start < 40 || isHeading(line)) emptyList()
            else buildList {
                var cursor = start
                while (cursor < end) {
                    var boundary = minOf(cursor + MAX_TARGET_CHARS, end)
                    if (boundary < end) {
                        // Split abnormally long paragraphs at a sentence boundary, preserving source offsets.
                        val sentence = (boundary - 1 downTo cursor + MAX_TARGET_CHARS / 2)
                            .firstOrNull { body[it] in "。！？!?；;" }
                        if (sentence != null) boundary = sentence + 1
                        if (end - boundary in 1..5) boundary -= 6 - (end - boundary)
                        if (body[boundary - 1].isHighSurrogate() && body[boundary].isLowSurrogate()) boundary--
                    }
                    if (boundary - cursor >= 6) add(ProactiveAnnotationParagraph(cursor, boundary))
                    cursor = boundary
                }
            }
        }
    }

    fun candidates(body: String, limit: Int, from: Int = 0): List<ProactiveAnnotationParagraph> =
        buckets(body, limit, from).map { it.first() }

    /**
     * 不限条数时先在各个正文区间写一段，再回头补各区间的下一段。逐段从章首写到章尾会让
     * 尚未完成的预生成只有开头有段评；这里仅改变调用顺序，候选总数和每段的前文边界不变。
     * 区间固定在本轮起点，不能随已完成段落或翻页重新分组，否则切片续跑会重复或漏段。
     */
    fun generationBuckets(body: String, limit: Int, from: Int = 0): List<List<ProactiveAnnotationParagraph>> {
        if (limit != Int.MAX_VALUE) return buckets(body, limit, from)
        val start = from.coerceIn(0, body.length)
        val groups = split(body).filter { it.start >= start }
            .groupBy { (it.start - start) / COVERAGE_INTERVAL_CHARS }.values.toList()
        return buildList {
            for (row in 0 until (groups.maxOfOrNull { it.size } ?: 0)) {
                for (group in groups) group.getOrNull(row)?.let { add(listOf(it)) }
            }
        }
    }

    /**
     * 按原文字符把 [from] 之后的正文均分成 [limit] 个区间，每个区间内的候选按分数从高到低排。
     * 首位是正选；正选生成失败（引文对不上、格式不对）时可以改用同一区间的次选，不让这一格空着。
     */
    fun buckets(body: String, limit: Int, from: Int = 0): List<List<ProactiveAnnotationParagraph>> {
        val start = from.coerceIn(0, body.length)
        val paragraphs = split(body).filter { it.start >= start }
        val count = minOf(limit.coerceAtLeast(0), paragraphs.size)
        if (count == 0) return emptyList()
        // Balance by source characters, not paragraph count: long prose and short dialogue mix unevenly.
        var cursor = 0
        return (0 until count).map { bucket ->
            val boundary = start + (body.length - start).toLong() * (bucket + 1) / count
            val lastAllowed = paragraphs.size - (count - bucket - 1)
            var end = cursor + 1
            while (end < lastAllowed && paragraphs[end - 1].end < boundary) end++
            val group = paragraphs.subList(cursor, end)
            cursor = end
            group.sortedByDescending { p ->
                minOf(p.end - p.start, SCORE_LENGTH_CAP) + body.substring(p.start, p.end)
                    .count { it in "！!？?“”\"" } * 12
            }
        }.sortedBy { it.first().end }
    }

    fun prefix(body: String, paragraph: ProactiveAnnotationParagraph, maxChars: Int = MAX_PREFIX_CHARS): String {
        var start = minOf(paragraph.start, (paragraph.end - maxChars.coerceAtLeast(0)).coerceAtLeast(0))
        if (start > 0 && body[start].isLowSurrogate() && body[start - 1].isHighSurrogate()) start++
        return body.substring(start, paragraph.end)
    }

    fun revision(body: String): String = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
