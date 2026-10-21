package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTranscriptTest {

    private val labels = TranscriptLabels(
        userName = "我",
        assistantName = "黛玉",
        subtitle = "《红楼梦》 · 与 黛玉 的伴读对话",
        exportedLine = "由墨知 MoRead 导出 · 2026-10-19 10:00",
        reasoning = "思考过程",
        tools = { "调用工具：" + it.joinToString("、") },
        voice = "[语音]",
        widget = "互动界面",
        image = "[图片]",
        attachment = { "[附件：$it]" }
    )

    private fun message(
        id: Long,
        role: String,
        content: String,
        toolCallsJson: String? = null,
        reasoning: String? = null,
        attachments: String? = null
    ) = MessageEntity(
        id = id,
        conversationId = 1,
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        createdAt = id,
        reasoningContent = reasoning,
        attachmentsJson = attachments
    )

    private val toolCall = """[{"id":"c1","name":"search_book","arguments":"{}"}]"""

    private val messages = listOf(
        message(1, "system", "系统提示词不该导出"),
        message(2, "user", "宝玉为什么摔玉？", attachments = """[{"type":"image","path":"a/b.jpg","mime":"image/jpeg"}]"""),
        message(3, "assistant", "", toolCallsJson = toolCall, reasoning = "先查原文"),
        message(4, "tool", "检索结果原文不该导出").copy(toolCallId = "c1"),
        message(5, "assistant", "他觉得众人都没有。\n[语音] 你说呢？\n[整段]\n第一行\n第二行\n[/整段]")
    )

    private fun build(includeProcess: Boolean) = buildConversationMarkdown(
        title = "摔玉",
        messages = messages,
        labels = labels,
        includeProcess = includeProcess,
        formatTime = { "t$it" }
    )

    @Test
    fun `exports only spoken turns and strips bubble markers`() {
        val markdown = build(includeProcess = false)
        assertTrue(markdown.startsWith("# 摔玉\n\n> 《红楼梦》 · 与 黛玉 的伴读对话\n> 由墨知 MoRead 导出"))
        assertFalse(markdown.contains("系统提示词"))
        assertFalse(markdown.contains("检索结果原文"))
        assertFalse(markdown.contains("先查原文"))
        assertFalse(markdown.contains("调用工具"))
        assertFalse(markdown.contains("[整段]"))
        assertTrue(markdown.contains("*[图片]*\n\n宝玉为什么摔玉？"))
        assertTrue(markdown.contains("*[语音]* 你说呢？"))
        assertTrue(markdown.contains("第一行\n第二行"))
        // 工具轮次没有可见内容时不留空标题；发言人标题取第一条有内容的 AI 消息。
        assertEquals(1, Regex("\\*\\*黛玉\\*\\*").findAll(markdown).count())
        assertTrue(markdown.contains("**黛玉** · t5"))
    }

    @Test
    fun `process mode quotes reasoning and tools under one speaker heading`() {
        val markdown = build(includeProcess = true)
        assertTrue(markdown.contains("**黛玉** · t3"))
        assertEquals(1, Regex("\\*\\*黛玉\\*\\*").findAll(markdown).count())
        assertTrue(markdown.contains("> **思考过程**\n>\n> 先查原文"))
        assertTrue(markdown.contains("> 调用工具：检索书中原文"))
        assertFalse(markdown.contains("检索结果原文"))
    }

    @Test
    fun `file name drops characters the file system rejects`() {
        assertEquals("ab c-20261019-1000.md", conversationExportFileName("a/b: c?", "伴读对话", "20261019-1000"))
        assertEquals("伴读对话-20261019-1000.md", conversationExportFileName(" */ ", "伴读对话", "20261019-1000"))
    }
}
