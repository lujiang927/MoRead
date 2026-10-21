package com.mozhi.reader.feature.reader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.R
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.companion.LibraryBookScopes
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.ui.components.MoReadSwitchRow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface ConversationExportEvent {
    /** 文档已备好，请系统文件选择器让用户挑保存位置。 */
    data class PickDocument(val fileName: String) : ConversationExportEvent
    data class Share(val intent: Intent) : ConversationExportEvent
    data class Finished(@StringRes val message: Int) : ConversationExportEvent
}

private class PreparedTranscript(val fileName: String, val markdown: String)

/**
 * 伴读对话导出：书内伴读与书库伴读共用（经 [CompanionConversationSheet] 进入）。
 * 只读数据库、不改会话；保存走系统文件选择器（无需存储权限），分享经 FileProvider。
 */
@HiltViewModel
internal class ConversationExportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val chatRepository: AiChatRepository,
    private val personaRepository: PersonaRepository,
    private val libraryRepository: LibraryRepository
) : ViewModel() {

    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()

    private val eventChannel = Channel<ConversationExportEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** 等文件选择器回来再写；在此期间用户看到的是导出那一刻的内容。 */
    private var pendingDocument: PreparedTranscript? = null

    fun saveAsDocument(conversationId: Long, includeProcess: Boolean) = launchExport {
        val transcript = prepare(conversationId, includeProcess) ?: return@launchExport
        pendingDocument = transcript
        eventChannel.send(ConversationExportEvent.PickDocument(transcript.fileName))
    }

    fun writeDocument(uri: Uri?) {
        val transcript = pendingDocument
        pendingDocument = null
        if (uri == null) return
        launchExport {
            // 进程在选择器期间被回收时，待写内容已丢失：提示重试而不是写个空文件。
            if (transcript == null) {
                eventChannel.send(ConversationExportEvent.Finished(R.string.companion_export_failed))
                return@launchExport
            }
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(transcript.markdown.toByteArray(Charsets.UTF_8))
                } ?: error("cannot open document")
            }
            eventChannel.send(ConversationExportEvent.Finished(R.string.companion_export_saved))
        }
    }

    fun share(conversationId: Long, includeProcess: Boolean) = launchExport {
        val transcript = prepare(conversationId, includeProcess) ?: return@launchExport
        val uri = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val file = File(dir, transcript.fileName)
            file.writeText(transcript.markdown, Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, transcript.fileName.removeSuffix(".md"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        eventChannel.send(ConversationExportEvent.Share(intent))
    }

    private fun launchExport(block: suspend () -> Unit) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                eventChannel.send(ConversationExportEvent.Finished(R.string.companion_export_failed))
            } finally {
                _working.value = false
            }
        }
    }

    /** null = 会话已不存在或没有可导出的发言（已发出提示）。 */
    private suspend fun prepare(conversationId: Long, includeProcess: Boolean): PreparedTranscript? =
        withContext(Dispatchers.IO) {
            val conversation = chatRepository.getConversation(conversationId)
            val messages = chatRepository.getMessages(conversationId)
            if (conversation == null || messages.none { it.role == "user" || it.role == "assistant" }) {
                eventChannel.send(ConversationExportEvent.Finished(R.string.companion_export_empty))
                return@withContext null
            }
            val personaName = conversation.personaId?.let { personaRepository.getPersona(it)?.name }
                ?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.companion_export_assistant_fallback)
            val bookTitle = conversation.bookId?.let { libraryRepository.getBook(it)?.title }
                ?.takeIf(String::isNotBlank)
            val subtitle = when {
                bookTitle != null -> context.getString(R.string.companion_export_subtitle_book, bookTitle, personaName)
                conversation.type == LibraryBookScopes.CONVERSATION_TYPE ->
                    context.getString(R.string.companion_export_subtitle_library, personaName)
                else -> null
            }
            val now = Date()
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val separator = context.getString(R.string.companion_export_tool_separator)
            val labels = TranscriptLabels(
                userName = context.getString(R.string.companion_export_user),
                assistantName = personaName,
                subtitle = subtitle,
                exportedLine = context.getString(R.string.companion_export_exported_line, timeFormat.format(now)),
                reasoning = context.getString(R.string.companion_export_reasoning),
                tools = { names -> context.getString(R.string.companion_export_tools, names.joinToString(separator)) },
                voice = context.getString(R.string.companion_export_voice),
                widget = context.getString(R.string.companion_export_widget),
                image = context.getString(R.string.companion_export_image),
                attachment = { name -> context.getString(R.string.companion_export_attachment, name) }
            )
            val markdown = buildConversationMarkdown(
                title = conversation.title,
                messages = messages,
                labels = labels,
                includeProcess = includeProcess,
                formatTime = { timeFormat.format(Date(it)) }
            )
            val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(now)
            PreparedTranscript(
                fileName = conversationExportFileName(
                    conversation.title,
                    context.getString(R.string.companion_export_file_fallback),
                    stamp
                ),
                markdown = markdown
            )
        }
}

/** 导出选项弹窗：选是否带上过程信息，再分享或保存为 Markdown 文件。 */
@Composable
internal fun ConversationExportDialog(
    conversation: ConversationEntity,
    onDismiss: () -> Unit,
    viewModel: ConversationExportViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val working by viewModel.working.collectAsStateWithLifecycle()
    var includeProcess by rememberSaveable { mutableStateOf(false) }
    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> viewModel.writeDocument(uri) }
    val chooserTitle = stringResource(R.string.companion_export_share_chooser)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationExportEvent.PickDocument -> runCatching {
                    documentLauncher.launch(event.fileName)
                }.onFailure {
                    Toast.makeText(context, R.string.companion_export_failed, Toast.LENGTH_SHORT).show()
                }
                is ConversationExportEvent.Share -> {
                    runCatching { context.startActivity(Intent.createChooser(event.intent, chooserTitle)) }
                    onDismiss()
                }
                is ConversationExportEvent.Finished -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                    if (event.message != R.string.companion_export_empty) onDismiss()
                }
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.companion_export_title)) },
        text = {
            Column {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                MoReadSwitchRow(
                    title = stringResource(R.string.companion_export_include_process),
                    subtitle = stringResource(R.string.companion_export_include_process_hint),
                    checked = includeProcess,
                    onCheckedChange = { includeProcess = it }
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { viewModel.share(conversation.id, includeProcess) }, enabled = !working) {
                    Text(stringResource(R.string.companion_export_share))
                }
                TextButton(onClick = { viewModel.saveAsDocument(conversation.id, includeProcess) }, enabled = !working) {
                    Text(stringResource(R.string.companion_export_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
