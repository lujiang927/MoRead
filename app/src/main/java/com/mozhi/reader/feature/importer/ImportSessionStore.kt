package com.mozhi.reader.feature.importer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TxtRuleOption(
    val id: Long,
    val name: String,
    val example: String,
    val enabledByDefault: Boolean
)

data class TxtImportPreview(
    val sessionId: String,
    val sourceName: String,
    val suggestedTitle: String,
    val suggestedAuthor: String,
    val charsetName: String,
    val selectedRuleId: Long?,
    val selectedRuleName: String,
    val usedFallback: Boolean,
    val rules: List<TxtRuleOption>,
    val chapterTitles: List<String>,
    val totalCharacters: Int
)

data class TxtImportSession(
    val id: String,
    val sourceName: String,
    val suggestedTitle: String,
    val suggestedAuthor: String,
    val charsetName: String,
    val sourceFile: File,
    val rules: List<TxtTocRule>,
    val splitResult: TxtIndexedSplit,
    val sourceSha256: String? = null,
    val sourceByteSize: Long? = null
) {
    internal fun openText(): TxtFileText = TxtFileText(sourceFile)

    fun toPreview(): TxtImportPreview = TxtImportPreview(
        sessionId = id,
        sourceName = sourceName,
        suggestedTitle = suggestedTitle,
        suggestedAuthor = suggestedAuthor,
        charsetName = charsetName,
        selectedRuleId = splitResult.rule?.id,
        selectedRuleName = splitResult.rule?.name ?: "按字数自动分节",
        usedFallback = splitResult.usedFallback,
        rules = rules.map { rule ->
            TxtRuleOption(
                id = rule.id,
                name = rule.name,
                example = rule.example,
                enabledByDefault = rule.enable
            )
        },
        chapterTitles = splitResult.chapters.map(TxtChapterRange::title),
        totalCharacters = splitResult.chapters.sumOf(TxtChapterRange::charCount)
    )
}

@Serializable
private data class StoredTxtImportSession(
    val id: String,
    val sourceName: String,
    val suggestedTitle: String,
    val suggestedAuthor: String = "",
    val charsetName: String,
    val selectedRule: TxtTocRule?,
    val sourceSha256: String? = null,
    val sourceByteSize: Long? = null,
    val textFormat: Int = 1
)

@Singleton
class ImportSessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val chapterSplitter: TxtChapterSplitter,
    private val ruleLoader: TxtTocRuleLoader
) {
    private val sessions = ConcurrentHashMap<String, TxtImportSession>()
    private val directory = File(context.filesDir, "txt_import_sessions").apply { mkdirs() }
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    init {
        cleanupExpiredSessions()
    }

    @Synchronized
    fun create(
        sourceName: String,
        suggestedTitle: String,
        suggestedAuthor: String,
        charsetName: String,
        decodedFile: File,
        rules: List<TxtTocRule>,
        splitResult: TxtIndexedSplit,
        fingerprint: com.mozhi.reader.core.importer.SourceFingerprint? = null
    ): TxtImportSession {
        val sessionId = UUID.randomUUID().toString()
        val sourceFile = textFile(sessionId)
        Files.move(decodedFile.toPath(), sourceFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        val session = TxtImportSession(
            id = sessionId,
            sourceName = sourceName,
            suggestedTitle = suggestedTitle,
            suggestedAuthor = suggestedAuthor,
            charsetName = charsetName,
            sourceFile = sourceFile,
            rules = rules,
            splitResult = splitResult,
            sourceSha256 = fingerprint?.sha256,
            sourceByteSize = fingerprint?.size
        )
        try {
            persist(session)
            sessions[session.id] = session
        } catch (error: Throwable) {
            remove(sessionId)
            throw error
        }
        return session
    }

    @Synchronized
    fun get(sessionId: String, checkActive: () -> Unit = {}): TxtImportSession? =
        sessions[sessionId] ?: load(sessionId, checkActive)?.also { sessions[sessionId] = it }

    @Synchronized
    fun update(session: TxtImportSession) {
        persist(session)
        sessions[session.id] = session
    }

    @Synchronized
    fun remove(sessionId: String) {
        sessions.remove(sessionId)
        metadataFileOrNull(sessionId)?.delete()
        textFileOrNull(sessionId)?.delete()
        legacyTextFileOrNull(sessionId)?.delete()
    }

    private fun persist(session: TxtImportSession) {
        val metadata = StoredTxtImportSession(
            id = session.id,
            sourceName = session.sourceName,
            suggestedTitle = session.suggestedTitle,
            suggestedAuthor = session.suggestedAuthor,
            charsetName = session.charsetName,
            selectedRule = session.splitResult.rule,
            sourceSha256 = session.sourceSha256,
            sourceByteSize = session.sourceByteSize,
            textFormat = 2
        )
        val destination = metadataFile(session.id)
        val temporary = File(directory, "${session.id}.json.tmp")
        try {
            temporary.outputStream().use { output ->
                output.write(json.encodeToString(metadata).toByteArray(StandardCharsets.UTF_8))
                output.fd.sync()
            }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private fun load(sessionId: String, checkActive: () -> Unit): TxtImportSession? {
        return try {
            val metadataFile = requireNotNull(metadataFileOrNull(sessionId))
            val sourceFile = requireNotNull(textFileOrNull(sessionId))
            if (!metadataFile.isFile) return null

            val metadata = json.decodeFromString<StoredTxtImportSession>(
                metadataFile.readText(StandardCharsets.UTF_8)
            )
            require(metadata.id == sessionId && metadata.textFormat in 1..2)
            val legacy = requireNotNull(legacyTextFileOrNull(sessionId))
            if (metadata.textFormat == 1) {
                if (!legacy.isFile) return null
                val temporary = File(directory, "$sessionId.utf16.tmp")
                try {
                    legacy.reader(StandardCharsets.UTF_8).use { reader ->
                        temporary.writer(StandardCharsets.UTF_16LE).use { writer ->
                            val buffer = CharArray(16 * 1024)
                            while (true) {
                                checkActive()
                                val count = reader.read(buffer)
                                if (count < 0) break
                                writer.write(buffer, 0, count)
                            }
                        }
                    }
                    Files.move(temporary.toPath(), sourceFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
            if (!sourceFile.isFile) return null
            val splitResult = TxtFileText(sourceFile).use { text ->
                metadata.selectedRule?.let { chapterSplitter.indexWithRule(text, it, checkActive) }
                    ?: chapterSplitter.indexBest(text, emptyList(), checkActive)
            }

            TxtImportSession(
                id = metadata.id,
                sourceName = metadata.sourceName,
                suggestedTitle = metadata.suggestedTitle,
                suggestedAuthor = metadata.suggestedAuthor,
                charsetName = metadata.charsetName,
                sourceFile = sourceFile,
                rules = ruleLoader.rules,
                splitResult = splitResult,
                sourceSha256 = metadata.sourceSha256,
                sourceByteSize = metadata.sourceByteSize
            ).also {
                if (metadata.textFormat == 1) {
                    persist(it)
                    legacy.delete()
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
    }

    private fun metadataFile(sessionId: String): File =
        File(directory, "${UUID.fromString(sessionId)}.json")

    private fun textFile(sessionId: String): File =
        File(directory, "${UUID.fromString(sessionId)}.utf16")

    private fun metadataFileOrNull(sessionId: String): File? =
        runCatching { metadataFile(sessionId) }.getOrNull()

    private fun textFileOrNull(sessionId: String): File? =
        runCatching { textFile(sessionId) }.getOrNull()

    private fun legacyTextFileOrNull(sessionId: String): File? =
        runCatching { File(directory, "${UUID.fromString(sessionId)}.txt") }.getOrNull()

    private fun cleanupExpiredSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_RETENTION_MS
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.lastModified() < cutoff }
            .forEach(File::delete)
    }

    private companion object {
        const val SESSION_RETENTION_MS = 7L * 24 * 60 * 60 * 1_000
    }
}
