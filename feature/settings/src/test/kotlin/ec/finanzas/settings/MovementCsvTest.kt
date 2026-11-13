package ec.finanzas.settings

import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class MovementCsvTest {
    private fun record(note: String? = null) = ExportMovement("id", 0L, "Banco", "OTHER", "BANK_ACCOUNT", null,
        399L, "USD", "EXPENSE", "OUTGOING", null, null, note, "MANUAL", "MANUAL_CONFIRMED", null, false)

    @Test fun quotesCommasQuotesAndNewlinesWithoutLosingText() {
        val writer = StringWriter()
        assertEquals(1, MovementCsv.write(writer, listOf(record("café, \"local\"\r\nsegunda línea"))))
        assertTrue(writer.toString().contains("\"café, \"\"local\"\"\r\nsegunda línea\""))
        assertTrue(writer.toString().contains("\"1969-12-31T19:00:00-05:00\""))
        assertTrue(writer.toString().endsWith("\"false\"\r\n"))
    }

    @Test fun neutralizesSpreadsheetFormulasAfterWhitespaceAndControlPrefixes() {
        for (formula in listOf("=1+1", "+SUM(A1)", "-1+1", "@SUM(A1)", " \t=cmd()", "\r\n+1", "\uFEFF=1")) {
            val writer = StringWriter()
            MovementCsv.write(writer, listOf(record(formula)))
            assertTrue(formula, writer.toString().contains("\"'$formula\""))
        }
    }

    @Test fun preservesExactCentsAndEmptyExportStillHasHeader() {
        val writer = StringWriter()
        MovementCsv.write(writer, listOf(record().copy(amountMinor = Long.MAX_VALUE)))
        assertTrue(writer.toString().contains("\"92233720368547758.07\""))
        val empty = StringWriter()
        assertEquals(0, MovementCsv.write(empty, emptyList()))
        assertEquals(1, empty.toString().split("\r\n").count { it.isNotEmpty() })
        assertFalse(empty.toString().contains("sourcePackage"))
    }

    @Test fun propagatesWriteFailureInsteadOfReportingSuccessfulExport() {
        val writer = object : java.io.Writer() {
            override fun write(buffer: CharArray, offset: Int, count: Int) { throw java.io.IOException("full") }
            override fun flush() = Unit
            override fun close() = Unit
        }
        assertThrows(java.io.IOException::class.java) { MovementCsv.write(writer, listOf(record())) }
    }
}
