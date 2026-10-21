package com.mozhi.reader.feature.importer

import java.nio.ByteBuffer
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import org.mozilla.universalchardet.UniversalDetector

class TextEncodingDetector @Inject constructor() {
    /** Validate/decode in bounded chunks. An invalid encoding retries from disk, not from an in-memory copy. */
    fun decodeToFile(source: File, target: File, checkActive: () -> Unit = {},
        onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val detected = source.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            val detector = UniversalDetector(null)
            var first = true
            while (!detector.isDone) {
                checkActive()
                val read = input.read(bytes)
                if (read < 0) break
                if (first) {
                    first = false
                    detectBom(bytes.copyOf(minOf(read, 3)))?.let { return@use it }
                }
                detector.handleData(bytes, 0, read)
            }
            detector.dataEnd()
            detector.detectedCharset
        }
        val primary = runCatching { Charset.forName(detected ?: "UTF-8") }.getOrDefault(Charsets.UTF_8)
        val candidates = listOf(primary) + fallbackCharsets(primary)
        for (charset in candidates) {
            try {
                transcode(source, target, charset, strict = true, checkActive, onProgress)
                return charset.name()
            } catch (_: CharacterCodingException) {
                // Detection is heuristic; strictly validate the entire source, including its tail.
            }
        }
        transcode(source, target, primary, strict = false, checkActive, onProgress)
        return primary.name()
    }

    private fun transcode(source: File, target: File, charset: Charset, strict: Boolean,
        checkActive: () -> Unit, onProgress: (Long, Long) -> Unit) {
        val action = if (strict) CodingErrorAction.REPORT else CodingErrorAction.REPLACE
        val decoder = charset.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
        source.inputStream().use { input ->
            InputStreamReader(input, decoder).use { reader ->
                target.outputStream().buffered().writer(Charsets.UTF_16LE).use { writer ->
                    val buffer = CharArray(16 * 1024)
                    val normalized = CharArray(buffer.size)
                    var first = true
                    var previousCr = false
                    while (true) {
                        checkActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        var written = 0
                        for (index in 0 until count) {
                            val char = buffer[index]
                            if (first) {
                                first = false
                                if (char == '\uFEFF') continue
                            }
                            if (char == '\n' && previousCr) { previousCr = false; continue }
                            normalized[written++] = if (char == '\r') '\n' else char
                            previousCr = char == '\r'
                        }
                        writer.write(normalized, 0, written)
                        onProgress(input.channel.position(), source.length())
                    }
                }
            }
        }
    }

    fun decode(bytes: ByteArray): DetectedText {
        val bomCharset = detectBom(bytes)
        val detected = bomCharset ?: detectWithUniversalDetector(bytes)
        val charset = runCatching { Charset.forName(detected ?: "UTF-8") }
            .getOrElse { Charsets.UTF_8 }

        val decoded = (listOf(charset) + fallbackCharsets(charset))
            .firstNotNullOfOrNull { candidate ->
                decodeStrict(bytes, candidate)?.let { candidate to it }
            }
            ?: (charset to bytes.toString(charset))

        return DetectedText(
            charsetName = decoded.first.name(),
            text = decoded.second.removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
        )
    }

    private fun detectWithUniversalDetector(bytes: ByteArray): String? {
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        return detector.detectedCharset.also { detector.reset() }
    }

    private fun detectBom(bytes: ByteArray): String? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> "UTF-8"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
        else -> null
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun fallbackCharsets(primary: Charset): List<Charset> =
        listOf("UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE")
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .filterNot { it == primary }
}
