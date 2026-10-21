package com.mozhi.reader.core.epub

import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.core.library.EpubLayoutDiagnostic
import com.mozhi.reader.core.library.EpubLayoutDiagnosticSeverity
import kotlinx.coroutines.CancellationException

/** One extraction policy for import and disposable layout-cache rebuilds. */
internal object EpubChapterFallback {
    // Persisted in the existing package diagnostics. Keep v1 extraction stable: its UTF-16
    // coordinates become canonical text even if a future rich parser can handle the chapter.
    const val DIAGNOSTIC_CODE = "chapter-text-fallback-v1"

    fun isTextFallback(document: EpubLayoutChapter): Boolean =
        document.diagnostics.any { it.code == DIAGNOSTIC_CODE }

    fun parse(
        parser: EpubLayoutDocumentParser,
        bytes: ByteArray,
        chapterIndex: Int,
        href: String,
        stylesheets: Map<String, String>,
        textExtractor: EpubTextExtractor = EpubTextExtractor(),
        recordedFallback: EpubLayoutDiagnostic? = null
    ): ParsedEpubLayoutChapter {
        val diagnostic = recordedFallback ?: try {
            return parser.parseWithText(bytes, chapterIndex, href, stylesheets)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            EpubLayoutDiagnostic(
                severity = EpubLayoutDiagnosticSeverity.WARNING,
                code = DIAGNOSTIC_CODE,
                message = "Native layout parse failed; using text extraction: " +
                    "${error.javaClass.simpleName}: ${error.message.orEmpty().take(512)}",
                href = href
            )
        }
        // If extraction also fails, propagate the error; never turn missing content into an
        // apparently successful empty chapter. Cancellation and VM errors are not layout failures.
        val extracted = textExtractor.extractWithImages(bytes, href)
        return ParsedEpubLayoutChapter(
            text = extracted.text,
            images = extracted.images,
            document = EpubLayoutChapter(
                chapterIndex = chapterIndex, href = href, textLength = extracted.text.length,
                diagnostics = listOf(diagnostic)
            ),
            dom = EpubDomChapter(
                chapterIndex = chapterIndex, href = href, textLength = extracted.text.length,
                bodyNode = EpubDomNode(tag = "body"), diagnostics = listOf(diagnostic)
            )
        )
    }
}
