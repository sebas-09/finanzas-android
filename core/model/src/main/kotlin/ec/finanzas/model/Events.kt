package ec.finanzas.model

import java.time.Instant

enum class BankProvider { PICHINCHA, GUAYAQUIL, PRODUBANCO, DEUNA, PEIGO }
enum class InstrumentType { BANK_ACCOUNT, WALLET, DEBIT_CARD, CREDIT_CARD }
enum class MoneyDirection { INCOMING, OUTGOING }
enum class CandidateKind {
    OUTGOING_TRANSFER, INCOMING_TRANSFER, DEBIT_PURCHASE, PAYMENT,
    WALLET_TOP_UP, CREDIT_PURCHASE, CARD_PAYMENT, REFUND, UNKNOWN,
}
enum class TransactionKind {
    EXPENSE, INCOME, OWN_TRANSFER, CREDIT_CARD_PAYMENT, REFUND,
    CREDIT_CARD_CHARGE, WALLET_TOP_UP, UNKNOWN,
}
enum class ReviewStatus { AUTO_CONFIRMED, NEEDS_REVIEW, MANUAL_CONFIRMED, IGNORED }
enum class DataOrigin { NOTIFICATION, MANUAL, SYNTHETIC }
enum class EvidenceCode {
    ALLOWED_SOURCE, EXPLICIT_KIND, UNAMBIGUOUS_AMOUNT, EXPLICIT_DIRECTION,
    MISSING_ACCOUNT, TRUNCATED_CONTENT, GROUP_SUMMARY, POSSIBLE_DUPLICATE,
    UNKNOWN_PURPOSE, UNSUPPORTED_FORMAT, AMBIGUOUS_AMOUNT, MISSING_OPERATION_DATE, EXPERIMENTAL_SOURCE,
}

data class NormalizedBankEvent(
    val sourcePackage: String,
    val notificationKeyHash: String,
    val observedAt: Instant,
    val occurredAt: Instant?,
    val amountMinor: Long?,
    val currency: String = "USD",
    val direction: MoneyDirection?,
    val bank: BankProvider?,
    val instrument: InstrumentType?,
    val merchantNormalized: String?,
    val transactionReferenceHash: String?,
    val kind: CandidateKind,
    val confidence: Double,
    val evidenceCodes: Set<EvidenceCode>,
) {
    init {
        require(currency == "USD")
        require(amountMinor == null || amountMinor > 0)
        require(confidence.isFinite() && confidence in 0.0..1.0)
    }
}

/** Contenido efímero: jamás enviarlo a logs, analytics o persistencia por defecto. */
class NotificationInput(
    val sourcePackage: String,
    val key: String,
    val postedAt: Instant,
    val receivedAt: Instant,
    val title: String?,
    val text: String?,
    val bigText: String? = null,
    val textLines: List<String> = emptyList(),
    val isGroupSummary: Boolean = false,
) {
    override fun toString() = "NotificationInput([redacted])"
}

sealed interface ParseResult {
    data class Candidate(val event: NormalizedBankEvent, val parserVersion: String) : ParseResult
    data class Rejected(val reason: EvidenceCode) : ParseResult
}

interface BankNotificationParser {
    val provider: BankProvider
    val version: String
    fun parse(input: NotificationInput): ParseResult
}

/** El adaptador de producción permanecerá desactivado hasta validar una muestra real. */
interface PeigoParser : BankNotificationParser
