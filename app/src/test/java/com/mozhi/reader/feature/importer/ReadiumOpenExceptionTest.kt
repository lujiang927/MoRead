package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.readium.ReadiumOpenException
import java.util.zip.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.file.FileSystemError

class ReadiumOpenExceptionTest {
    @Test fun readiumFailuresKeepTheirUnderlyingReason() {
        val failure = AssetRetriever.RetrieveUrlError.Reading(
            ReadError.Access(FileSystemError.IO(ZipException("Duplicate entry name: mimetype")))
        )
        val exception = ReadiumOpenException(failure)
        val causes = generateSequence<Throwable>(exception) { it.cause }.toList()
        assertEquals(failure.message, exception.message)
        assertTrue(causes.last().message.orEmpty().contains("Duplicate entry name: mimetype"))
        assertTrue(causes.size > 2)
    }
}
