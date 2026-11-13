package ec.finanzas.ledger

import ec.finanzas.model.*
import java.math.BigInteger

data class BookAccount(val id: String, val kind: InstrumentType, val opening: Long? = null)
data class OperationDraft(
    val id: String,
    val accountId: String,
    val amount: Long,
    val kind: TransactionKind,
    val occurredAt: Long,
    val direction: MoneyDirection,
    val counterAccountId: String? = null,
    val merchant: String? = null,
    val note: String? = null,
    val originalId: String? = null,
    val candidateId: String? = null,
    val expectedRevision: Long? = null,
    val estimatedDate: Boolean = false,
    val correctedEvidence: Boolean = false,
    val categoryId: String? = null,
    val categoryChanged: Boolean = false,
)
data class BookEffect(val accountId: String, val delta: Long)
data class BookOperation(val id: String, val amount: Long, val kind: TransactionKind, val accountId: String,
    val originalId: String? = null, val effects: List<BookEffect>, val included: Boolean = true)
data class BookTotals(val expense: Long, val income: Long, val net: Long, val changes: Map<String, Long>, val balances: Map<String, Long?>)

object LedgerRules {
    fun direction(kind: TransactionKind, account: InstrumentType, ownDirection: MoneyDirection = MoneyDirection.OUTGOING) = when (kind) {
        TransactionKind.INCOME, TransactionKind.REFUND -> MoneyDirection.INCOMING
        TransactionKind.CREDIT_CARD_PAYMENT -> if (account == InstrumentType.CREDIT_CARD) MoneyDirection.INCOMING else MoneyDirection.OUTGOING
        TransactionKind.WALLET_TOP_UP -> if (account == InstrumentType.WALLET) MoneyDirection.INCOMING else MoneyDirection.OUTGOING
        TransactionKind.OWN_TRANSFER -> ownDirection
        else -> MoneyDirection.OUTGOING
    }

    fun effects(d: OperationDraft, accounts: Map<String, BookAccount>, original: BookOperation? = null): List<BookEffect> {
        require(d.amount > 0 && d.occurredAt > 0 && d.kind != TransactionKind.UNKNOWN) { "Completa importe, fecha y clasificación válidos." }
        val a = requireNotNull(accounts[d.accountId]) { "Selecciona una cuenta válida." }
        val b = d.counterAccountId?.let { requireNotNull(accounts[it]) { "Selecciona la otra cuenta." } }
        require(a.id != b?.id) { "Las cuentas deben ser diferentes." }
        require(d.direction == direction(d.kind, a.kind, d.direction)) { "El sentido no corresponde a la clasificación y la cuenta." }
        fun asset(value: BookAccount) = value.kind != InstrumentType.CREDIT_CARD
        fun pair(): BookAccount = requireNotNull(b) { "Esta operación requiere otra cuenta propia." }
        val result = when (d.kind) {
            TransactionKind.EXPENSE -> {
                require(asset(a) && b == null) { "Un gasto de efectivo requiere cuenta bancaria o billetera." }
                listOf(BookEffect(a.id, -d.amount))
            }
            TransactionKind.INCOME -> {
                require(asset(a) && b == null) { "Un ingreso externo requiere cuenta bancaria o billetera." }
                listOf(BookEffect(a.id, d.amount))
            }
            TransactionKind.CREDIT_CARD_CHARGE -> {
                require(a.kind == InstrumentType.CREDIT_CARD && b == null) { "Una compra a crédito requiere tarjeta de crédito." }
                listOf(BookEffect(a.id, d.amount))
            }
            TransactionKind.OWN_TRANSFER -> {
                val other = pair()
                require(asset(a) && asset(other)) { "Una transferencia propia requiere dos cuentas de efectivo o billetera." }
                val sign = if (d.direction == MoneyDirection.OUTGOING) -d.amount else d.amount
                listOf(BookEffect(a.id, sign), BookEffect(other.id, -sign))
            }
            TransactionKind.WALLET_TOP_UP -> {
                val other = pair()
                require(setOf(a.kind, other.kind).let { InstrumentType.WALLET in it &&
                    (InstrumentType.BANK_ACCOUNT in it || InstrumentType.DEBIT_CARD in it) }) { "La recarga requiere billetera y cuenta bancaria de origen." }
                listOf(BookEffect(a.id, if (a.kind == InstrumentType.WALLET) d.amount else -d.amount),
                    BookEffect(other.id, if (other.kind == InstrumentType.WALLET) d.amount else -d.amount))
            }
            TransactionKind.CREDIT_CARD_PAYMENT -> {
                val other = pair()
                require((a.kind == InstrumentType.CREDIT_CARD) != (other.kind == InstrumentType.CREDIT_CARD)) {
                    "El pago requiere una tarjeta de crédito y una cuenta de efectivo."
                }
                listOf(BookEffect(a.id, -d.amount), BookEffect(other.id, -d.amount))
            }
            TransactionKind.REFUND -> {
                require(b == null && original != null && original.id != d.id && original.included && original.id == d.originalId &&
                    original.accountId == a.id && original.kind in setOf(TransactionKind.EXPENSE, TransactionKind.CREDIT_CARD_CHARGE)) {
                    "Vincula la devolución a una compra vigente de la misma cuenta."
                }
                listOf(BookEffect(a.id, if (a.kind == InstrumentType.CREDIT_CARD) -d.amount else d.amount))
            }
            TransactionKind.UNKNOWN -> error("Clasifica la operación antes de contabilizar.")
        }
        require(d.kind == TransactionKind.REFUND || d.originalId == null) { "Solo las devoluciones se vinculan a una compra original." }
        return result
    }

    fun totals(accounts: List<BookAccount>, operations: List<BookOperation>): BookTotals {
        val included = operations.filter { it.included }
        var expense = BigInteger.ZERO
        var income = BigInteger.ZERO
        val changes = accounts.associate { it.id to BigInteger.ZERO }.toMutableMap()
        for (op in included) {
            when (op.kind) {
                TransactionKind.EXPENSE, TransactionKind.CREDIT_CARD_CHARGE -> expense += op.amount.toBigInteger()
                TransactionKind.REFUND -> expense -= op.amount.toBigInteger()
                TransactionKind.INCOME -> income += op.amount.toBigInteger()
                else -> Unit
            }
            op.effects.forEach { effect -> changes[effect.accountId] = requireNotNull(changes[effect.accountId]) + effect.delta.toBigInteger() }
        }
        for (op in included.filter { it.kind == TransactionKind.REFUND }) {
            val original = included.singleOrNull { it.id == op.originalId }
            require(original != null && original.accountId == op.accountId && original.kind in setOf(TransactionKind.EXPENSE, TransactionKind.CREDIT_CARD_CHARGE)) {
                "Hay una devolución sin compra vigente."
            }
            val refunded = included.filter { it.kind == TransactionKind.REFUND && it.originalId == original.id }
                .fold(BigInteger.ZERO) { total, refund -> total + refund.amount.toBigInteger() }
            require(refunded <= original.amount.toBigInteger()) { "Las devoluciones superan el importe de la compra original." }
        }
        fun exact(value: BigInteger): Long {
            require(value >= BigInteger.valueOf(Long.MIN_VALUE) && value <= BigInteger.valueOf(Long.MAX_VALUE)) { "El total supera el rango de centavos permitido." }
            return value.toLong()
        }
        return BookTotals(exact(expense), exact(income), exact(income - expense), changes.mapValues { exact(it.value) },
            accounts.associate { it.id to it.opening?.let { opening -> exact(opening.toBigInteger() + changes.getValue(it.id)) } })
    }
}
