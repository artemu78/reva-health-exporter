package dev.reva.healthexporter

import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticDocumentExporterTest {
    private val content = "{\"schemaVersion\":1}"

    @Test
    fun `successful output writes UTF-8 document`() {
        val stream = ByteArrayOutputStream()
        val exporter = DiagnosticDocumentExporter(DocumentOutput { stream })

        val result = exporter.export("content://snapshot", content)

        assertEquals(DocumentExportResult.Success, result)
        assertArrayEquals(content.toByteArray(Charsets.UTF_8), stream.toByteArray())
    }

    @Test
    fun `cancelled picker is reported without opening output`() {
        var opened = false
        val exporter = DiagnosticDocumentExporter(DocumentOutput { opened = true; ByteArrayOutputStream() })

        assertEquals(DocumentExportResult.Cancelled, exporter.export(null, content))
        assertEquals(false, opened)
    }

    @Test
    fun `unavailable destination is distinct from write failure`() {
        val unavailable = DiagnosticDocumentExporter(DocumentOutput { null })
        val failing = DiagnosticDocumentExporter(DocumentOutput { throw IOException("synthetic failure") })

        assertEquals(DocumentExportResult.DestinationUnavailable, unavailable.export("content://missing", content))
        assertEquals(DocumentExportResult.WriteFailed, failing.export("content://failure", content))
    }
}
