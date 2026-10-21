package com.mozhi.reader.feature.reader

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolCall
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.library.MessageAttachment
import kotlinx.serialization.builtins.ListSerializer

/**
 * 导出文档里随界面语言变化的字样。由 [ConversationExportViewModel] 在导出那一刻从资源解析，
 * 构建函数本身不碰 Android，便于单测。
 */
internal data class TranscriptLabels(
    val userName: String,
    val assistantName: String,
    /** 标题下第一行引言，如「《书名》 · 与某角色的伴读对话」；null = 不写。 */
    val subtitle: String?,
    /** 引言第二行：导出来源与时间。 */
    val exportedLine: String,
    val reasoning: String,
    val tools: (List<String>) -> String,
    val voice: String,
    val widget: String,
    val image: String,
    val attachment: (String) -> String
)

/**
 * 把一个会话的落库消息拼成 Markdown 文档。
 *
 * - 只导出用户与 AI 的发言；system 与 tool 消息不进正文。
 * - AI 回复走与气泡同一套解析（[parseCompanionParts]），`[语音]`、`[整段]` 等标记不会漏成正文，
 *   互动界面以其静态文字投影导出。
 * - 一轮里工具循环产生的多条 AI 消息合在同一个发言人标题下。
 * - [includeProcess] 为 true 时，在 AI 发言前以引用块附上思考过程与调用过的工具。
 */
internal fun buildConversationMarkdown(
    title: String,
    messages: List<MessageEntity>,
    labels: TranscriptLabels,
    includeProcess: Boolean,
    formatTime: (Long) -> String
): String = buildString {
    append("# ").append(title.trim().ifBlank { labels.assistantName }).append("\n\n")
    labels.subtitle?.let { append("> ").append(it).append('\n') }
    append("> ").append(labels.exportedLine).append('\n')
    var lastSpeaker: String? = null
    messages.forEach { message ->
        val blocks = when (message.role) {
            "user" -> userBlocks(message, labels)
            "assistant" -> assistantBlocks(message, labels, includeProcess)
            else -> emptyList()
        }
        if (blocks.isEmpty()) return@forEach
        if (message.role != lastSpeaker) {
            val name = if (message.role == "user") labels.userName else labels.assistantName
            append("\n---\n\n**").append(name).append("** · ").append(formatTime(message.createdAt)).append('\n')
            lastSpeaker = message.role
        }
        blocks.forEach { append('\n').append(it).append('\n') }
    }
}

private fun userBlocks(message: MessageEntity, labels: TranscriptLabels): List<String> = buildList {
    MessageAttachment.decode(message.attachmentsJson).forEach { attachment ->
        add(
            if (attachment.type == MessageAttachment.TYPE_IMAGE) {
                "*${labels.image}*"
            } else {
                "*${labels.attachment(attachment.name ?: attachment.path.substringAfterLast('/'))}*"
            }
        )
    }
    message.content.trim().takeIf(String::isNotEmpty)?.let(::add)
}

private fun assistantBlocks(
    message: MessageEntity,
    labels: TranscriptLabels,
    includeProcess: Boolean
): List<String> = buildList {
    if (includeProcess) {
        message.reasoningContent?.trim()?.takeIf(String::isNotEmpty)?.let { reasoning ->
            add(quoteBlock("**${labels.reasoning}**\n\n$reasoning"))
        }
        val tools = message.toolCallsJson?.let { json ->
            runCatching { AiJson.decodeFromString(ListSerializer(ToolCall.serializer()), json) }.getOrNull()
        }.orEmpty().map { toolDisplayName(it.name) }.distinct()
        if (tools.isNotEmpty()) add(quoteBlock(labels.tools(tools)))
    }
    parseCompanionParts(message.content, multiBubble = false).forEach { part ->
        val text = part.text.trim()
        if (text.isEmpty()) return@forEach
        add(
            when (part) {
                is CompanionBubblePart.Text -> text
                is CompanionBubblePart.Voice -> "*${labels.voice}* $text"
                is CompanionBubblePart.Widget -> quoteBlock("*${labels.widget}*\n\n$text")
            }
        )
    }
}

private fun quoteBlock(text: String): String =
    text.lines().joinToString("\n") { line -> if (line.isBlank()) ">" else "> $line" }

/** 导出文件名：去掉文件系统不认的字符，过长截断。 */
internal fun conversationExportFileName(title: String, fallback: String, stamp: String): String {
    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().take(40)
    return "${safe.ifBlank { fallback }}-$stamp.md"
}
