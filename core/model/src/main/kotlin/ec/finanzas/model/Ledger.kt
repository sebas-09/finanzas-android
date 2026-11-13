package ec.finanzas.model

import java.time.Instant
import java.time.ZoneId

val FINANCIAL_ZONE: ZoneId = ZoneId.of("America/Guayaquil")

enum class AccountKind { BANK, WALLET, CREDIT_CARD }

/** Activos: saldo positivo. Tarjeta: deuda positiva, no efectivo disponible. */
data class FinancialAccount(
    val id: String,
    val alias: String,
    val provider: BankProvider,
    val kind: AccountKind,
    val openingBalanceMinor: Long? = null,
    val active: Boolean = true,
)

/** Única fuente del cambio de saldo; transfer_link nunca vuelve a aplicar estos efectos. */
data class AccountPosting(val accountId: String, val deltaMinor: Long)

data class LedgerTransaction(
    val id: String,
    val amountMinor: Long,
    val kind: TransactionKind,
    val occurredAt: Instant,
    val observedAt: Instant,
    val status: ReviewStatus,
    val origin: DataOrigin,
    val postings: List<AccountPosting>,
    val categoryId: String? = null,
    val merchantAlias: String? = null,
    val originalTransactionId: String? = null,
    val deletedAt: Instant? = null,
) {
    init {
        require(amountMinor > 0)
        require(postings.map { it.accountId }.distinct().size == postings.size)
        require(kind != TransactionKind.REFUND || originalTransactionId != null)
    }
    val countsInConfirmedLedger: Boolean get() = deletedAt == null && origin != DataOrigin.SYNTHETIC &&
        kind != TransactionKind.UNKNOWN &&
        status in setOf(ReviewStatus.AUTO_CONFIRMED, ReviewStatus.MANUAL_CONFIRMED)
}

data class SourceObservation(
    val id: String,
    val transactionId: String?,
    val event: NormalizedBankEvent,
    val eventFingerprint: String,
    val parserVersion: String,
    val status: ReviewStatus,
    val origin: DataOrigin = DataOrigin.NOTIFICATION,
)

data class ReviewCandidate(val observation: SourceObservation, val reasons: Set<EvidenceCode>)

/** Separación explícita entre consumo, ingreso externo y variación de cuentas. */
data class FinancialInsights(
    val expenseMinor: Long,
    val externalIncomeMinor: Long,
    val balanceMinor: Long,
)
