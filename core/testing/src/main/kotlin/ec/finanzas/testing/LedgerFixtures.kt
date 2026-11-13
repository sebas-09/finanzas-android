package ec.finanzas.testing

import ec.finanzas.model.*
import java.time.Instant

object LedgerFixtures {
    val time: Instant = Instant.parse("2026-10-14T17:00:01Z")
    fun confirmed(id: String, amount: Long, kind: TransactionKind, vararg postings: AccountPosting) =
        LedgerTransaction(id, amount, kind, time, time, ReviewStatus.MANUAL_CONFIRMED,
            DataOrigin.MANUAL, postings.toList())

    val ca06 = listOf(
        confirmed("credit-25", 2500, TransactionKind.CREDIT_CARD_CHARGE, AccountPosting("peigo", 2500)),
        confirmed("pay-25", 2500, TransactionKind.CREDIT_CARD_PAYMENT,
            AccountPosting("bank", -2500), AccountPosting("peigo", -2500)),
    )
    val ca11 = listOf(
        confirmed("credit-10", 1000, TransactionKind.CREDIT_CARD_CHARGE, AccountPosting("peigo", 1000)),
        confirmed("topup-5", 500, TransactionKind.WALLET_TOP_UP,
            AccountPosting("bank", -500), AccountPosting("deuna", 500)),
        confirmed("pay-10", 1000, TransactionKind.CREDIT_CARD_PAYMENT,
            AccountPosting("bank", -1000), AccountPosting("peigo", -1000)),
    )
    const val CA06_EXPECTED_EXPENSE_MINOR = 2500L
    const val CA11_EXPECTED_EXPENSE_MINOR = 1000L
    // Los cálculos y la suite contable se implementarán en F3; no se simula el motor en F0.
}

