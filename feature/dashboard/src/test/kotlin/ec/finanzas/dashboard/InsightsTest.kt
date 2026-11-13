package ec.finanzas.dashboard

import ec.finanzas.ledger.*
import ec.finanzas.model.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class InsightsTest {
    private fun at(value: String) = LocalDateTime.parse(value).atZone(FINANCIAL_ZONE).toInstant().toEpochMilli()
    private fun record(id: String, amount: Long, kind: TransactionKind, date: String, category: String? = "food", live: Boolean = true,
        account: String = "bank", effects: List<BookEffect> = emptyList()) = InsightRecord(BookOperation(id, amount, kind, account, effects = effects, included = live), at(date), category)

    @Test fun monthUsesGuayaquilBoundariesAndRefundMonthWithoutDuplicatingTransfersCA11() {
        val rows = listOf(record("before", 500, TransactionKind.EXPENSE, "2026-10-31T23:59:59"),
            record("buy", 2500, TransactionKind.CREDIT_CARD_CHARGE, "2026-11-01T00:00:00"),
            record("pay", 2500, TransactionKind.CREDIT_CARD_PAYMENT, "2026-11-02T12:00:00"),
            record("top", 1000, TransactionKind.WALLET_TOP_UP, "2026-11-02T12:00:00"),
            record("own", 1000, TransactionKind.OWN_TRANSFER, "2026-11-02T12:00:00"),
            record("refund-old", 500, TransactionKind.REFUND, "2026-11-30T23:59:59"),
            record("salary", 10000, TransactionKind.INCOME, "2026-11-04T12:00:00"),
            record("removed", 900, TransactionKind.EXPENSE, "2026-11-02T12:00:00", live = false),
            record("after", 900, TransactionKind.EXPENSE, "2026-12-01T00:00:00"))
        val totals = Insights.month(YearMonth.of(2026, 11), rows)
        assertEquals(2000L, totals.expense); assertEquals(10000L, totals.income); assertEquals(8000L, totals.net)
        assertEquals(2000L, totals.categories["food"])
    }
    @Test fun combinedFiltersIncludeCounterAccountAndNormalizedAlias() {
        val row = record("payment", 100, TransactionKind.CREDIT_CARD_PAYMENT, "2026-11-30T23:59:59", account = "credit", effects = listOf(BookEffect("bank", -100), BookEffect("credit", -100)))
            .copy(provider = "PEIGO", instrument = "CREDIT_CARD", merchant = "CafÉ", providers = setOf("PEIGO", "PICHINCHA"), instruments = setOf("CREDIT_CARD", "BANK_ACCOUNT"))
        val filter = MovementFilter(LocalDate.of(2026,11,1), LocalDate.of(2026,11,30), "PICHINCHA", "bank", "BANK_ACCOUNT", "food", "MANUAL", "MANUAL_CONFIRMED", TransactionKind.CREDIT_CARD_PAYMENT, "ahorros")
        assertEquals(listOf(row), Insights.filter(listOf(row), filter, mapOf("bank" to "Ahorros")))
        assertTrue(Insights.filter(listOf(row), filter.copy(query = "otra"), emptyMap()).isEmpty())
        assertEquals(listOf(row), Insights.filter(listOf(row), filter.copy(query = "cafe"), emptyMap()))
    }
    @Test fun rulesAreDeterministicAndDoNotInferAbsentMerchants() {
        val a = RuleSpec("a", "CONTAINS", "SPOTIFY", "Spotify", "subscription", 10, true)
        val b = a.copy(id = "b", categoryId = "other")
        assertEquals(a, Insights.rule("  Spotify   Premium ", listOf(b, a)))
        assertEquals(b, Insights.rule("Spotify", listOf(a, b.copy(priority = 11)))?.let { it.copy(priority = 10) })
        assertNull(Insights.rule(null, listOf(a))); assertNull(Insights.rule("Otro", listOf(a)))
        assertNull(Insights.rule("Spotify Premium", listOf(a.copy(matchType = "EXACT"))))
        assertNull(Insights.rule("Spotify", listOf(a.copy(enabled = false))))
    }
    @Test fun creditCycleClampsShortMonthAndCountsPaymentsFromEitherSide() {
        val rows = listOf(record("buy", 2500, TransactionKind.CREDIT_CARD_CHARGE, "2026-02-28T23:59:59", account = "credit"),
            record("pay", 500, TransactionKind.CREDIT_CARD_PAYMENT, "2026-02-01T12:00:00", effects = listOf(BookEffect("credit", -500))),
            record("next", 500, TransactionKind.CREDIT_CARD_CHARGE, "2026-03-01T00:00:00", account = "credit"))
        val cycle = Insights.credit(LocalDate.of(2026,2,28),31,10,"credit",rows)
        assertEquals(LocalDate.of(2026,2,1),cycle.start); assertEquals(LocalDate.of(2026,3,1),cycle.endExclusive)
        assertEquals(LocalDate.of(2026,3,10),cycle.due); assertEquals(2500L,cycle.consumption); assertEquals(500L,cycle.payments)
        assertEquals(LocalDate.of(2026,3,1),Insights.credit(LocalDate.of(2026,3,1),31,null,"credit",rows).start)
    }
    @Test fun budgetThresholdsAndProgressUseExactIntegersNearLongLimit() {
        assertEquals(0, Insights.budgetLevel(79,100,80)); assertEquals(1, Insights.budgetLevel(80,100,80))
        assertEquals(2, Insights.budgetLevel(100,100,80)); assertEquals(0, Insights.budgetLevel(-10,100,80))
        assertEquals(1, Insights.budgetLevel(Long.MAX_VALUE - 1,Long.MAX_VALUE,80))
        assertEquals(2, Insights.budgetLevel(Long.MAX_VALUE,Long.MAX_VALUE,100))
    }
    @Test fun aggregateOverflowIsRejectedInsteadOfWrapping() {
        val rows = listOf(record("a",Long.MAX_VALUE,TransactionKind.EXPENSE,"2026-11-01T00:00:00"), record("b",1,TransactionKind.EXPENSE,"2026-11-01T00:00:01"))
        assertThrows(IllegalArgumentException::class.java) { Insights.month(YearMonth.of(2026,11),rows) }
        assertThrows(IllegalArgumentException::class.java) { Insights.filter(rows,MovementFilter(LocalDate.of(2026,12,1),LocalDate.of(2026,11,1)),emptyMap()) }
    }
}
