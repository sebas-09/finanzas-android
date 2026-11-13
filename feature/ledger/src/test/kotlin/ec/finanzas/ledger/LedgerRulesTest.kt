package ec.finanzas.ledger

import ec.finanzas.model.*
import org.junit.Assert.*
import org.junit.Test

class LedgerRulesTest {
    private val accounts = listOf(BookAccount("bank", InstrumentType.BANK_ACCOUNT, 10000),
        BookAccount("wallet", InstrumentType.WALLET, 0), BookAccount("credit", InstrumentType.CREDIT_CARD, 0))
    private val map = accounts.associateBy { it.id }
    private fun d(id: String, account: String, amount: Long, kind: TransactionKind, counter: String? = null,
        direction: MoneyDirection = LedgerRules.direction(kind, map.getValue(account).kind)) =
        OperationDraft(id, account, amount, kind, 1000, direction, counter)
    private fun op(d: OperationDraft, original: BookOperation? = null) = BookOperation(d.id, d.amount, d.kind, d.accountId, d.originalId, LedgerRules.effects(d, map, original))
    @Test fun debitExpenseReducesCashAndCountsConsumption() {
        val t = LedgerRules.totals(accounts, listOf(op(d("p", "bank", 399, TransactionKind.EXPENSE))))
        assertEquals(399L, t.expense); assertEquals(9601L, t.balances["bank"]); assertEquals(-399L, t.net)
    }
    @Test fun creditPurchaseAndPaymentNeverDoubleConsumption() {
        val purchase = op(d("p", "credit", 2500, TransactionKind.CREDIT_CARD_CHARGE))
        val bought = LedgerRules.totals(accounts, listOf(purchase))
        assertEquals(2500L, bought.balances["credit"]); assertEquals(10000L, bought.balances["bank"])
        val paid = LedgerRules.totals(accounts, listOf(purchase, op(d("pay", "bank", 2500, TransactionKind.CREDIT_CARD_PAYMENT, "credit"))))
        assertEquals(2500L, paid.expense); assertEquals(7500L, paid.balances["bank"]); assertEquals(0L, paid.balances["credit"])
    }
    @Test fun internalTopupAndCardPaymentExcludedFromConsumptionCA11() {
        val t = LedgerRules.totals(accounts, listOf(op(d("buy", "credit", 1000, TransactionKind.CREDIT_CARD_CHARGE)),
            op(d("top", "bank", 500, TransactionKind.WALLET_TOP_UP, "wallet")), op(d("pay", "bank", 1000, TransactionKind.CREDIT_CARD_PAYMENT, "credit"))))
        assertEquals(1000L, t.expense); assertEquals(0L, t.income); assertEquals(8500L, t.balances["bank"])
        assertEquals(500L, t.balances["wallet"]); assertEquals(0L, t.balances["credit"])
    }
    @Test fun ownTransferBothDirectionsProduceEquivalentEffects() {
        val outgoing = LedgerRules.effects(d("a", "bank", 100, TransactionKind.OWN_TRANSFER, "wallet"), map).associate { it.accountId to it.delta }
        val incoming = LedgerRules.effects(d("b", "wallet", 100, TransactionKind.OWN_TRANSFER, "bank", MoneyDirection.INCOMING), map).associate { it.accountId to it.delta }
        assertEquals(outgoing, incoming); assertEquals(0L, outgoing.values.sum())
    }
    @Test fun walletSideTopupAndCardSidePaymentAreValid() {
        assertEquals(100L, LedgerRules.effects(d("t", "wallet", 100, TransactionKind.WALLET_TOP_UP, "bank"), map).first().delta)
        assertEquals(-100L, LedgerRules.effects(d("p", "credit", 100, TransactionKind.CREDIT_CARD_PAYMENT, "bank"), map).first().delta)
    }
    @Test fun externalIncomeIsDifferentFromInternalTransfer() {
        val t = LedgerRules.totals(accounts, listOf(op(d("i", "bank", 400, TransactionKind.INCOME)), op(d("t", "bank", 100, TransactionKind.OWN_TRANSFER, "wallet"))))
        assertEquals(400L, t.income); assertEquals(0L, t.expense); assertEquals(400L, t.net)
    }
    @Test fun partialRefundReducesExpenseNotIncome() {
        val purchase = op(d("p", "bank", 1000, TransactionKind.EXPENSE))
        val refund = op(d("r", "bank", 400, TransactionKind.REFUND).copy(originalId = "p"), purchase)
        val t = LedgerRules.totals(accounts, listOf(purchase, refund))
        assertEquals(600L, t.expense); assertEquals(0L, t.income); assertEquals(9400L, t.balances["bank"])
    }
    @Test fun creditRefundReducesDebt() {
        val purchase = op(d("p", "credit", 1000, TransactionKind.CREDIT_CARD_CHARGE))
        val refund = op(d("r", "credit", 300, TransactionKind.REFUND).copy(originalId = "p"), purchase)
        val t = LedgerRules.totals(accounts, listOf(purchase, refund))
        assertEquals(700L, t.balances["credit"]); assertEquals(700L, t.expense)
    }
    @Test fun refundCannotExceedOriginalIncludingSeveralPartialRefunds() {
        val purchase = op(d("p", "bank", 100, TransactionKind.EXPENSE))
        val r1 = op(d("r1", "bank", 60, TransactionKind.REFUND).copy(originalId = "p"), purchase)
        val r2 = op(d("r2", "bank", 41, TransactionKind.REFUND).copy(originalId = "p"), purchase)
        assertThrows(IllegalArgumentException::class.java) { LedgerRules.totals(accounts, listOf(purchase, r1, r2)) }
    }
    @Test fun unknownOpeningShowsOnlyVariation() {
        val unknown = accounts.map { it.copy(opening = null) }
        val t = LedgerRules.totals(unknown, listOf(op(d("e", "bank", 100, TransactionKind.EXPENSE))))
        assertNull(t.balances["bank"]); assertEquals(-100L, t.changes["bank"])
    }
    @Test fun excludedOperationsHaveNoEffect() {
        val excluded = op(d("e", "bank", 100, TransactionKind.EXPENSE)).copy(included = false)
        assertEquals(0L, LedgerRules.totals(accounts, listOf(excluded)).expense)
        assertEquals(10000L, LedgerRules.totals(accounts, listOf(excluded)).balances["bank"])
    }
    @Test fun rejectsMissingCounterAndWrongAccountTypes() {
        listOf(d("a", "bank", 100, TransactionKind.OWN_TRANSFER), d("b", "bank", 100, TransactionKind.OWN_TRANSFER, "bank"),
            d("c", "credit", 100, TransactionKind.EXPENSE), d("d", "bank", 100, TransactionKind.CREDIT_CARD_CHARGE),
            d("e", "bank", 100, TransactionKind.WALLET_TOP_UP, "credit"), d("f", "bank", 100, TransactionKind.CREDIT_CARD_PAYMENT, "wallet"),
            d("g", "bank", 100, TransactionKind.UNKNOWN)).forEach { draft -> assertThrows(draft.id, IllegalArgumentException::class.java) { LedgerRules.effects(draft, map) } }
    }
    @Test fun rejectsZeroAndRefundWithoutOriginal() {
        assertThrows(IllegalArgumentException::class.java) { op(d("e", "bank", 0, TransactionKind.EXPENSE)) }
        assertThrows(IllegalArgumentException::class.java) { op(d("r", "bank", 100, TransactionKind.REFUND)) }
    }
    @Test fun totalsNeverOverflowOrUseFloatingPoint() {
        val max = BookAccount("bank", InstrumentType.BANK_ACCOUNT, Long.MAX_VALUE)
        val income = BookOperation("i", 1, TransactionKind.INCOME, "bank", effects = listOf(BookEffect("bank", 1)))
        assertThrows(IllegalArgumentException::class.java) { LedgerRules.totals(listOf(max), listOf(income)) }
        val huge = BookOperation("a", Long.MAX_VALUE, TransactionKind.EXPENSE, "bank", effects = listOf(BookEffect("bank", -Long.MAX_VALUE)))
        val refund = BookOperation("r", Long.MAX_VALUE, TransactionKind.REFUND, "bank", "a", listOf(BookEffect("bank", Long.MAX_VALUE)))
        val another = huge.copy(id = "b")
        assertEquals(Long.MAX_VALUE, LedgerRules.totals(listOf(BookAccount("bank", InstrumentType.BANK_ACCOUNT)), listOf(huge, another, refund)).expense)
    }
}
