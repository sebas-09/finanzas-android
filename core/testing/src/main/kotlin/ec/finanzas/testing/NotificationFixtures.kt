package ec.finanzas.testing

import ec.finanzas.model.*
import java.time.Instant

/** Paquete propio sintético; no pretende identificar ninguna app bancaria real. */
object NotificationFixtures {
    private val time = Instant.parse("2026-10-14T17:00:01Z")
    private fun input(key: String, title: String, text: String) = NotificationInput(
        "ec.finanzas.fixture", key, time, time, title, text,
    )
    data class ParserFixture(val id: String, val provider: BankProvider?, val input: NotificationInput,
        val amountMinor: Long?, val kind: CandidateKind, val merchant: String? = null)
    val parsers = listOf(
        ParserFixture("CA-02", BankProvider.PRODUBANCO,
            input("prod-transfer", "Transferencia enviada", "Por $0.01 a la cuenta [ANONIMIZADA] el 10/14/2026 11:24:35"),
            1, CandidateKind.OUTGOING_TRANSFER),
        ParserFixture("CA-03", BankProvider.PICHINCHA,
            input("pich-in", "¡Recibiste 1.00!", "[PERSONA] te envió 1.00"), 100, CandidateKind.INCOMING_TRANSFER),
        ParserFixture("CA-04-pago", BankProvider.DEUNA,
            input("deuna-payment", "¡Listo!", "Pagaste $1,00"), 100, CandidateKind.PAYMENT),
        ParserFixture("CA-04-recarga", BankProvider.DEUNA,
            input("deuna-topup", "Deuna", "Recargaste $1,00"), 100, CandidateKind.WALLET_TOP_UP),
        ParserFixture("CA-05", null,
            input("outlook-debit", "Consumo tarjeta de débito por USD 3.99", ""), 399, CandidateKind.DEBIT_PURCHASE),
        ParserFixture("guayaquil-transfer", BankProvider.GUAYAQUIL,
            input("guayaquil", "Transferencia", "Se ha realizado una transferencia de $0.01 a la Cuenta AHORROS [ANONIMIZADA]"),
            1, CandidateKind.OUTGOING_TRANSFER),
        ParserFixture("no-money", BankProvider.PRODUBANCO,
            input("date-only", "Información", "Fecha 10/14/2026. Cuenta [ANONIMIZADA]"), null, CandidateKind.UNKNOWN),
        ParserFixture("truncated", BankProvider.DEUNA,
            input("truncated", "Pagaste", "Pagaste $…"), null, CandidateKind.UNKNOWN),
    )
    // PeiGo no tiene fixture de notificación: CA-06 usará operaciones manuales hasta F6.
}

