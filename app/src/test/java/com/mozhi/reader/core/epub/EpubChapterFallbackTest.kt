package com.mozhi.reader.core.epub

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class EpubChapterFallbackTest {
    private val href = "OPS/chapter.xhtml"
    private val bytes = "<html><body><p>正文🙂</p><img src='art.png' alt='插图'/><p>最后一段</p></body></html>".toByteArray()
    private val extractor = EpubTextExtractor()

    @Test fun successfulChaptersKeepTheOriginalRichParseAndCoordinates() {
        val parser = EpubLayoutDocumentParser()
        assertEquals(parser.parseWithText(bytes, 2, href, emptyMap()),
            EpubChapterFallback.parse(parser, bytes, 2, href, emptyMap()))
    }

    @Test fun richFailureKeepsAllExtractedTextImagesAndADiagnostic() {
        val parser = mockk<EpubLayoutDocumentParser>()
        every { parser.parseWithText(any(), any(), any(), any()) } throws IllegalArgumentException("bad style")
        val parsed = EpubChapterFallback.parse(parser, bytes, 2, href, emptyMap())
        val expected = extractor.extractWithImages(bytes, href)
        assertEquals(expected.text, parsed.text)
        assertEquals(expected.images, parsed.images)
        assertEquals(parsed.text.length, parsed.document.textLength)
        assertEquals(parsed.text.length, parsed.dom.textLength)
        assertTrue(parsed.document.blocks.isEmpty())
        assertTrue(parsed.dom.bodyNode.children.isEmpty())
        val diagnostic = parsed.document.diagnostics.single()
        assertEquals(EpubChapterFallback.DIAGNOSTIC_CODE, diagnostic.code)
        assertEquals(href, diagnostic.href)
        assertTrue(diagnostic.message.contains("bad style"))
        assertEquals(parsed.document.diagnostics, parsed.dom.diagnostics)
        assertEquals(parsed.text.indexOf("［图片］"), parsed.images.single().charOffset)
    }

    @Test fun recordedFallbackDoesNotRetryRichParsingOrChangeItsExtractionPolicy() {
        val parser = mockk<EpubLayoutDocumentParser>()
        every { parser.parseWithText(any(), any(), any(), any()) } throws IllegalStateException("old failure")
        val imported = EpubChapterFallback.parse(parser, bytes, 0, href, emptyMap())
        val rebuilt = EpubChapterFallback.parse(parser, bytes, 0, href, emptyMap(),
            recordedFallback = imported.document.diagnostics.single())
        assertEquals(imported, rebuilt)
        verify(exactly = 1) { parser.parseWithText(any(), any(), any(), any()) }
    }

    @Test fun cancellationAndVmErrorsAreNeverConvertedToTextFallbacks() {
        val parser = mockk<EpubLayoutDocumentParser>()
        val text = mockk<EpubTextExtractor>()
        val cancellation = CancellationException("stopped")
        every { parser.parseWithText(any(), any(), any(), any()) } throws cancellation
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            EpubChapterFallback.parse(parser, bytes, 0, href, emptyMap(), text)
        })
        val fatal = OutOfMemoryError("exhausted")
        every { parser.parseWithText(any(), any(), any(), any()) } throws fatal
        assertSame(fatal, assertThrows(OutOfMemoryError::class.java) {
            EpubChapterFallback.parse(parser, bytes, 0, href, emptyMap(), text)
        })
        verify(exactly = 0) { text.extractWithImages(any(), any()) }
    }

    @Test fun textExtractionFailureStillFailsInsteadOfPublishingAnEmptyChapter() {
        val parser = mockk<EpubLayoutDocumentParser>()
        val text = mockk<EpubTextExtractor>()
        every { parser.parseWithText(any(), any(), any(), any()) } throws IllegalArgumentException("style")
        val failure = IllegalStateException("unreadable content")
        every { text.extractWithImages(any(), any()) } throws failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            EpubChapterFallback.parse(parser, bytes, 0, href, emptyMap(), text)
        })
    }
}
