package ec.finanzas.settings

import java.io.Writer
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Exportación de movimientos vigentes; no es un respaldo ni incluye observaciones de notificaciones. */
data class ExportMovement(
    val id: String, val occurredAt: Long, val account: String, val provider: String,
    val instrument: String, val counterAccount: String?, val amountMinor: Long,
    val currency: String, val kind: String, val direction: String, val category: String?,
    val merchant: String?, val note: String?, val origin: String, val status: String,
    val originalId: String?, val estimatedDate: Boolean,
)

object MovementCsv {
    private val zone = ZoneId.of("America/Guayaquil")
    private val header = listOf("id", "fecha_guayaquil", "cuenta_alias", "entidad", "instrumento",
        "contracuenta_alias", "importe", "moneda", "tipo", "sentido", "categoria", "comercio_alias",
        "nota", "origen", "estado", "operacion_original_id", "fecha_estimada")

    /** RFC 4180, UTF-8 definido por el llamante. Siempre entrecomilla y neutraliza fórmulas. */
    fun write(writer: Writer, records: Iterable<ExportMovement>): Int {
        fun row(cells: List<String?>) {
            writer.write(cells.joinToString(",") { cell(it.orEmpty()) })
            writer.write("\r\n")
        }
        row(header)
        var count = 0
        for (record in records) {
            require(record.amountMinor > 0 && record.currency == "USD")
            row(listOf(record.id, DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                Instant.ofEpochMilli(record.occurredAt).atZone(zone)), record.account, record.provider,
                record.instrument, record.counterAccount, BigDecimal.valueOf(record.amountMinor, 2).toPlainString(),
                record.currency, record.kind, record.direction, record.category, record.merchant, record.note,
                record.origin, record.status, record.originalId, record.estimatedDate.toString()))
            count++
        }
        return count
    }

    private fun cell(value: String): String {
        val meaningful = value.dropWhile { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' }
        val safe = if (meaningful.firstOrNull() in listOf('=', '+', '-', '@')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
}
