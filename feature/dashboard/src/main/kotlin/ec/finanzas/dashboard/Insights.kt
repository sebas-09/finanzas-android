package ec.finanzas.dashboard

import ec.finanzas.ledger.*
import ec.finanzas.model.*
import java.math.BigInteger
import java.text.Normalizer
import java.time.*
import java.util.Locale

data class InsightRecord(
    val operation: BookOperation,
    val occurredAt: Long,
    val categoryId: String? = null,
    val merchant: String? = null,
    val provider: String? = null,
    val instrument: String? = null,
    val origin: String = "MANUAL",
    val status: String = "MANUAL_CONFIRMED",
    val estimatedDate: Boolean = false,
    val providers: Set<String> = emptySet(),
    val instruments: Set<String> = emptySet(),
)
data class MovementFilter(
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val provider: String? = null,
    val accountId: String? = null,
    val instrument: String? = null,
    val categoryId: String? = null,
    val origin: String? = null,
    val status: String? = null,
    val kind: TransactionKind? = null,
    val query: String = "",
)
data class MonthInsight(val expense: Long, val income: Long, val net: Long, val categories: Map<String?, Long>, val estimatedDates: Int)
data class CreditInsight(val start: LocalDate, val endExclusive: LocalDate, val due: LocalDate?, val consumption: Long, val payments: Long)
data class RuleSpec(val id: String, val matchType: String, val pattern: String, val alias: String, val categoryId: String?, val priority: Int, val enabled: Boolean)

object Insights {
    fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    fun exact(value: BigInteger): Long {
        require(value >= Long.MIN_VALUE.toBigInteger() && value <= Long.MAX_VALUE.toBigInteger()) { "El total supera el rango de centavos permitido." }
        return value.toLong()
    }

    private fun expense(record: InsightRecord): BigInteger = when (record.operation.kind) {
        TransactionKind.EXPENSE, TransactionKind.CREDIT_CARD_CHARGE -> record.operation.amount.toBigInteger()
        TransactionKind.REFUND -> -record.operation.amount.toBigInteger()
        else -> BigInteger.ZERO
    }

    fun month(month: YearMonth, records: List<InsightRecord>): MonthInsight {
        val start = month.atDay(1).atStartOfDay(FINANCIAL_ZONE).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(FINANCIAL_ZONE).toInstant().toEpochMilli()
        val selected = records.filter { it.operation.included && it.occurredAt >= start && it.occurredAt < end }
        val expense = selected.fold(BigInteger.ZERO) { total, record -> total + expense(record) }
        val income = selected.filter { it.operation.kind == TransactionKind.INCOME }.fold(BigInteger.ZERO) { total, record -> total + record.operation.amount.toBigInteger() }
        val categories = selected.filter { it.operation.kind in setOf(TransactionKind.EXPENSE, TransactionKind.CREDIT_CARD_CHARGE, TransactionKind.REFUND) }
            .groupBy { it.categoryId }.mapValues { (_, group) -> exact(group.fold(BigInteger.ZERO) { total, record -> total + expense(record) }) }
        return MonthInsight(exact(expense), exact(income), exact(income - expense), categories, selected.count { it.estimatedDate })
    }

    fun filter(records: List<InsightRecord>, filter: MovementFilter, aliases: Map<String, String>): List<InsightRecord> {
        require(filter.from == null || filter.through == null || filter.from <= filter.through) { "La fecha inicial debe ser anterior a la final." }
        val start = filter.from?.atStartOfDay(FINANCIAL_ZONE)?.toInstant()?.toEpochMilli()
        val end = filter.through?.plusDays(1)?.atStartOfDay(FINANCIAL_ZONE)?.toInstant()?.toEpochMilli()
        val query = normalize(filter.query)
        return records.filter { record ->
            val op = record.operation
            (start == null || record.occurredAt >= start) && (end == null || record.occurredAt < end) &&
                (filter.provider == null || record.provider == filter.provider || filter.provider in record.providers) &&
                (filter.accountId == null || op.accountId == filter.accountId || op.effects.any { it.accountId == filter.accountId }) &&
                (filter.instrument == null || record.instrument == filter.instrument || filter.instrument in record.instruments) &&
                (filter.categoryId == null || record.categoryId == filter.categoryId || (filter.categoryId.isEmpty() && record.categoryId == null)) &&
                (filter.origin == null || record.origin == filter.origin) && (filter.status == null || record.status == filter.status) &&
                (filter.kind == null || op.kind == filter.kind) &&
                (query.isEmpty() || normalize(listOfNotNull(record.merchant, aliases[op.accountId], *op.effects.mapNotNull { aliases[it.accountId] }.toTypedArray()).joinToString(" ")).contains(query))
        }.sortedWith(compareByDescending<InsightRecord> { it.occurredAt }.thenBy { it.operation.id })
    }

    fun rule(merchant: String?, rules: List<RuleSpec>): RuleSpec? {
        val text = merchant?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return null
        return rules.sortedWith(compareByDescending<RuleSpec> { it.priority }.thenBy { it.id }).firstOrNull {
            val pattern = normalize(it.pattern)
            it.enabled && pattern.isNotEmpty() && when (it.matchType) {
                "EXACT" -> text == pattern
                "CONTAINS" -> text.contains(pattern)
                else -> false
            }
        }
    }

    fun credit(today: LocalDate, billingDay: Int, dueDay: Int?, account: String, records: List<InsightRecord>): CreditInsight {
        require(billingDay in 1..31 && (dueDay == null || dueDay in 1..31))
        fun day(month: YearMonth, value: Int) = month.atDay(minOf(value, month.lengthOfMonth()))
        val current = YearMonth.from(today)
        val closingMonth = if (today > day(current, billingDay)) current.plusMonths(1) else current
        val close = day(closingMonth, billingDay)
        val start = day(closingMonth.minusMonths(1), billingDay).plusDays(1)
        val end = close.plusDays(1)
        val due = dueDay?.let { value -> day(closingMonth, value).let { if (it <= close) day(closingMonth.plusMonths(1), value) else it } }
        val fromMs = start.atStartOfDay(FINANCIAL_ZONE).toInstant().toEpochMilli()
        val endMs = minOf(end.atStartOfDay(FINANCIAL_ZONE).toInstant().toEpochMilli(), today.plusDays(1).atStartOfDay(FINANCIAL_ZONE).toInstant().toEpochMilli())
        val cycle = records.filter { it.operation.included && it.occurredAt >= fromMs && it.occurredAt < endMs }
        val consumption = cycle.filter { it.operation.accountId == account }.fold(BigInteger.ZERO) { total, record -> total + expense(record) }
        val payments = cycle.filter { it.operation.kind == TransactionKind.CREDIT_CARD_PAYMENT && it.operation.effects.any { effect -> effect.accountId == account } }
            .fold(BigInteger.ZERO) { total, record -> total + record.operation.amount.toBigInteger() }
        return CreditInsight(start, end, due, exact(consumption), exact(payments))
    }

    fun budgetLevel(consumed: Long, limit: Long, threshold: Int): Int {
        require(limit > 0 && threshold in 1..100)
        val percent = consumed.toBigInteger() * 100.toBigInteger()
        return when {
            consumed >= limit -> 2
            percent >= limit.toBigInteger() * threshold.toBigInteger() -> 1
            else -> 0
        }
    }
}
