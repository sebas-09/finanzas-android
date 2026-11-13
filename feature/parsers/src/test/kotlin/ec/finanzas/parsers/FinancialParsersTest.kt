package ec.finanzas.parsers

import ec.finanzas.model.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FinancialParsersTest {
    private fun input(text: String, title: String? = null, big: String? = null, group: Boolean = false) =
        NotificationInput("selected.synthetic", "private-key", Instant.parse("2026-10-15T18:00:00Z"),
            Instant.parse("2026-10-15T18:00:01Z"), title, text, big, isGroupSummary = group)
    private fun candidate(bank: BankProvider, text: String, title: String? = null) =
        (NotificationParsers.parse(input(text, title), bank, false) as ParseResult.Candidate).event

    @Test fun exactCentsAndGrouping() {
        mapOf("0.01" to 1L, "1,00" to 100L, "3.99" to 399L, "1,234.56" to 123456L,
            "1.234,56" to 123456L, "2" to 200L, "92233720368547758.07" to Long.MAX_VALUE).forEach { (s, n) -> assertEquals(s, n, MoneyNormalizer.cents(s)) }
    }
    @Test fun rejectsAmbiguityOverflowAndSigns() {
        listOf("1,000", "1.000", "3.9", "-1", "+1", "0", "NaN", "1e3", "92233720368547758.08", "12,34.56", "").forEach { assertNull(it, MoneyNormalizer.cents(it)) }
    }
    @Test fun pichinchaOutgoingRequiresSuccess() {
        val e = candidate(BankProvider.PICHINCHA, "La transferencia por $1.00 a la cuenta 123456 fue exitosa", "¡Transferencia exitosa!")
        assertEquals(100L, e.amountMinor); assertEquals(CandidateKind.OUTGOING_TRANSFER, e.kind)
        assertEquals(MoneyDirection.OUTGOING, e.direction); assertNull(e.merchantNormalized)
    }
    @Test fun incomingDoesNotInventPurpose() {
        val e = candidate(BankProvider.PICHINCHA, "Una persona te envió 1.00", "¡Recibiste 1.00!")
        assertEquals(100L, e.amountMinor); assertEquals(CandidateKind.INCOMING_TRANSFER, e.kind)
        assertTrue(EvidenceCode.UNKNOWN_PURPOSE in e.evidenceCodes)
    }
    @Test fun guayaquilIgnoresAccountNumber() {
        val e = candidate(BankProvider.GUAYAQUIL, "Se ha realizado una transferencia de $0.01 a la Cuenta AHORROS 1234567890")
        assertEquals(1L, e.amountMinor)
    }
    @Test fun produbancoSeparatesOperationAndReceipt() {
        val e = candidate(BankProvider.PRODUBANCO, "Por $0.01 a la cuenta 123456 el 10/14/2026 11:24:35", "Transferencia enviada")
        assertEquals(Instant.parse("2026-10-14T16:24:35Z"), e.occurredAt)
        assertNotEquals(e.observedAt, e.occurredAt)
    }
    @Test fun ambiguousInvalidAndFutureDatesStayUnknown() {
        listOf("10/11/2026", "02/30/2026", "12/31/2027").forEach { date ->
            assertNull(candidate(BankProvider.PRODUBANCO, "Por $0.01 el $date 11:24:35", "Transferencia enviada").occurredAt)
        }
    }
    @Test fun deunaPaymentAndTopupStayDifferent() {
        val pay = candidate(BankProvider.DEUNA, "¡Listo! Pagaste $1,00")
        val top = candidate(BankProvider.DEUNA, "Recargaste $1,00")
        assertEquals(CandidateKind.PAYMENT, pay.kind); assertEquals(CandidateKind.WALLET_TOP_UP, top.kind)
        assertEquals(MoneyDirection.INCOMING, top.direction)
        assertTrue(EvidenceCode.UNKNOWN_PURPOSE in top.evidenceCodes)
    }
    @Test fun outlookOptInDoesNotGuessSpotifyOrBank() {
        val i = input("Consumo tarjeta de débito por USD 3.99")
        assertTrue(NotificationParsers.parse(i, null, false) is ParseResult.Rejected)
        val e = (NotificationParsers.parse(i, null, true) as ParseResult.Candidate).event
        assertEquals(399L, e.amountMinor); assertNull(e.merchantNormalized); assertNull(e.bank)
        assertTrue(EvidenceCode.EXPERIMENTAL_SOURCE in e.evidenceCodes)
    }
    @Test fun merchantRequiresExplicitFieldAndIsMinimized() {
        val e = (NotificationParsers.parse(input("Consumo tarjeta de débito por USD 3.99\nComercio: SPOTIFY"), null, true) as ParseResult.Candidate).event
        assertEquals("SPOTIFY", e.merchantNormalized)
        val bad = (NotificationParsers.parse(input("Consumo tarjeta de débito por USD 3.99\nComercio: Cuenta 12345678"), null, true) as ParseResult.Candidate).event
        assertNull(bad.merchantNormalized)
    }
    @Test fun contradictoryAmountsRemainIncomplete() {
        val result = NotificationParsers.parse(input("Pagaste $1,00", big = "Pagaste $2,00"), BankProvider.DEUNA, false) as ParseResult.Candidate
        assertNull(result.event.amountMinor); assertTrue(EvidenceCode.AMBIGUOUS_AMOUNT in result.event.evidenceCodes)
    }
    @Test fun missingAmountNeverUsesDateOrAccount() {
        val e = candidate(BankProvider.PRODUBANCO, "Cuenta 123456 el 10/14/2026 11:24:35", "Transferencia enviada")
        assertNull(e.amountMinor)
    }
    @Test fun summariesAreRejectedAndTruncationExplained() {
        assertEquals(ParseResult.Rejected(EvidenceCode.GROUP_SUMMARY), NotificationParsers.parse(input("Pagaste $1,00", group = true), BankProvider.DEUNA, false))
        val e = (NotificationParsers.parse(input("Consumo tarjeta de débito por USD 3.99 …"), null, true) as ParseResult.Candidate).event
        assertTrue(EvidenceCode.TRUNCATED_CONTENT in e.evidenceCodes); assertNull(e.merchantNormalized)
    }
    @Test fun securityPromotionsFailuresAndOtherCurrencyRejected() {
        listOf("Código OTP para transferencia exitosa $1.00", "Oferta transferencia exitosa $1.00", "Transferencia fallida $1.00",
            "Transferencia exitosa EUR 1.00", "Transferencia exitosa no fue realizada $1.00", "Transferencia pendiente $1.00").forEach { text ->
            assertTrue(text, NotificationParsers.parse(input(text), BankProvider.PICHINCHA, false) is ParseResult.Rejected)
        }
    }
    @Test fun peigoStaysDisabledAndOtherProvidersCannotReuseFormat() {
        assertTrue(DisabledPeigoParser().parse(input("Pagaste $1.00")) is ParseResult.Rejected)
        assertTrue(NotificationParsers.parse(input("Pagaste $1.00"), BankProvider.GUAYAQUIL, false) is ParseResult.Rejected)
    }
    @Test fun repeatedViewsAreNotConflictingAmounts() {
        val e = (NotificationParsers.parse(input("Pagaste $1,00", "Pagaste $1,00", "Pagaste $1.00"), BankProvider.DEUNA, false) as ParseResult.Candidate).event
        assertEquals(100L, e.amountMinor)
    }
    @Test fun malformedTokensCannotBeAcceptedAsNumericPrefixes() {
        listOf("1e3", "1 000.00", "1\n000.00", "1\u00a0000,00", "1:00", "1/00", "1.00abc", "-1.00", "+1.00").forEach { token ->
            assertNull(token, candidate(BankProvider.DEUNA, "Pagaste $$token").amountMinor)
            assertNull(token, candidate(BankProvider.PICHINCHA, "¡Recibiste $token!").amountMinor)
        }
        assertNull(candidate(BankProvider.DEUNA, "Pagaste -$1.00").amountMinor)
        assertNull(candidate(BankProvider.DEUNA, "Pagaste - $1.00").amountMinor)
    }
    @Test fun invalidAmountInAnotherViewMakesTheCandidateIncomplete() {
        val e = (NotificationParsers.parse(input("Pagaste $1e3", title = "Pagaste $1.00"), BankProvider.DEUNA, false) as ParseResult.Candidate).event
        assertNull(e.amountMinor)
        assertTrue(EvidenceCode.AMBIGUOUS_AMOUNT in e.evidenceCodes)
    }
}
