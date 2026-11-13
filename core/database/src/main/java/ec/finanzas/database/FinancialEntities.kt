package ec.finanzas.database

import androidx.room.*

@Entity(tableName = "financial_account")
data class FinancialAccountEntity(
    @PrimaryKey val id: String,
    val alias: String,
    val provider: String,
    val kind: String,
    val openingBalanceMinor: Long? = null,
    val currency: String = "USD",
    val active: Boolean = true,
    val createdAt: Long
)

@Entity(tableName = "card_profile", foreignKeys = [
    ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)
], indices = [
    Index(value = ["accountId"], unique = true)
])
data class CardProfileEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val type: String,
    val issuer: String,
    val billingDay: Int? = null,
    val dueDay: Int? = null,
    val creditLimitMinor: Long? = null
)

@Entity(tableName = "category", foreignKeys = [
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.SET_NULL)
], indices = [
    Index(value = ["parentId"], unique = false)
])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val icon: String = "category",
    val active: Boolean = true
)

@Entity(tableName = "ledger_transaction", foreignKeys = [
    ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["originalTransactionId"], onDelete = ForeignKey.SET_NULL)
], indices = [
    Index(value = ["occurredAt"], unique = false),
    Index(value = ["accountId", "occurredAt"], unique = false),
    Index(value = ["origin", "status"], unique = false),
    Index(value = ["categoryId"], unique = false),
    Index(value = ["originalTransactionId"], unique = false)
])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String? = null,
    val occurredAt: Long,
    val observedAt: Long,
    val amountMinor: Long,
    val currency: String = "USD",
    val direction: String,
    val kind: String,
    val categoryId: String? = null,
    val merchantAlias: String? = null,
    val status: String,
    val origin: String,
    val originalTransactionId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val counterAccountId: String? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "0") val revision: Long = 0,
    @ColumnInfo(defaultValue = "0") val estimatedDate: Boolean = false,
    val mergedIntoId: String? = null
)

@Entity(tableName = "account_posting", foreignKeys = [
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = FinancialAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.RESTRICT)
], indices = [
    Index(value = ["transactionId", "accountId"], unique = true),
    Index(value = ["accountId"], unique = false)
])
data class AccountPostingEntity(
    @PrimaryKey val id: String,
    val transactionId: String,
    val accountId: String,
    val deltaMinor: Long
)

@Entity(tableName = "source_observation", foreignKeys = [
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["transactionId"], onDelete = ForeignKey.SET_NULL)
], indices = [
    Index(value = ["sourcePackage", "eventFingerprint", "origin"], unique = true),
    Index(value = ["sourcePackage", "keyHash", "postedAt"]),
    Index(value = ["receivedAt"], unique = false),
    Index(value = ["origin", "state"], unique = false),
    Index(value = ["transactionId"], unique = false)
])
data class SourceObservationEntity(
    @PrimaryKey val id: String,
    val transactionId: String? = null,
    val sourcePackage: String,
    val eventFingerprint: String,
    val keyHash: String,
    val revisionHash: String,
    val parserVersion: String = "capture-v1-unparsed",
    val postedAt: Long,
    val receivedAt: Long,
    val lastReceivedAt: Long,
    val provider: String? = null,
    val origin: String,
    val state: String = "AWAITING_PARSER",
    val confidence: Double = 0.0,
    val evidenceCodes: String = "PARSER_NOT_AVAILABLE",
    val amountMinor: Long? = null,
    val occurredAt: Long? = null,
    val currency: String = "USD",
    val direction: String? = null,
    val merchantNormalized: String? = null,
    val transactionReferenceHash: String? = null
)

@Entity(tableName = "transfer_link", foreignKeys = [
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["debitTransactionId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["creditTransactionId"], onDelete = ForeignKey.CASCADE)
], indices = [
    Index(value = ["debitTransactionId", "creditTransactionId"], unique = true),
    Index(value = ["creditTransactionId"], unique = false)
])
data class TransferLinkEntity(
    @PrimaryKey val id: String,
    val debitTransactionId: String,
    val creditTransactionId: String,
    val linkType: String,
    val confirmationState: String
)

@Entity(tableName = "merchant_rule", foreignKeys = [
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)
], indices = [
    Index(value = ["categoryId"], unique = false)
])
data class MerchantRuleEntity(
    @PrimaryKey val id: String,
    val matchType: String,
    val pattern: String,
    val merchantAlias: String,
    val categoryId: String? = null,
    val priority: Int = 0,
    val enabled: Boolean = true
)

@Entity(tableName = "review_queue", foreignKeys = [
    ForeignKey(entity = SourceObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"], onDelete = ForeignKey.CASCADE)
], indices = [
    Index(value = ["observationId", "reasonCode"], unique = true)
])
data class ReviewQueueEntity(
    @PrimaryKey val id: String,
    val observationId: String,
    val reasonCode: String,
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val resolution: String? = null
)

@Entity(tableName = "capture_session", indices = [
    Index(value = ["startedAt"], unique = false)
])
data class CaptureSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long? = null,
    val endReason: String? = null
)

@Entity(tableName = "capture_settings", foreignKeys = [
    ForeignKey(entity = CaptureSessionEntity::class, parentColumns = ["id"], childColumns = ["currentSessionId"], onDelete = ForeignKey.SET_NULL)
], indices = [
    Index(value = ["currentSessionId"], unique = false)
])
data class CaptureSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val consentAt: Long? = null,
    val consentVersion: Int = 0,
    val onboardingCompleted: Boolean = false,
    val captureStartedAt: Long? = null,
    val requestedActive: Boolean = false,
    val pausedAt: Long? = null,
    val permissionGranted: Boolean = false,
    val permissionEverGranted: Boolean = false,
    val listenerConnected: Boolean = false,
    val currentSessionId: Long? = null,
    val mayHaveGaps: Boolean = false
)

@Entity(tableName = "allowed_source")
data class AllowedSourceEntity(
    @PrimaryKey val packageName: String,
    val displayName: String,
    val provider: String? = null,
    val channel: String = "BANK_APP",
    val enabled: Boolean = false,
    val enabledSince: Long? = null,
    val signingDigest: String,
    val available: Boolean = true,
    val lastSeenAt: Long? = null
)

@Entity(tableName = "capture_queue", foreignKeys = [
    ForeignKey(entity = CaptureSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.SET_NULL)
], indices = [
    Index(value = ["receivedAt"], unique = false),
    Index(value = ["sessionId"], unique = false)
])
data class CaptureQueueEntity(
    @PrimaryKey val eventFingerprint: String,
    val sourcePackage: String,
    val keyHash: String,
    val revisionHash: String,
    val sessionId: Long? = null,
    val postedAt: Long,
    val receivedAt: Long,
    val lastReceivedAt: Long,
    val provider: String? = null,
    val origin: String
)

@Entity(tableName = "diagnostic_counter")
data class DiagnosticCounterEntity(
    @PrimaryKey val reason: String,
    val count: Long = 0
)

@Entity(tableName = "notification_cursor")
data class NotificationCursorEntity(
    @PrimaryKey val keyHash: String,
    val eventFingerprint: String,
    val sessionId: Long,
    val active: Boolean = true,
)

@Entity(tableName = "change_audit", indices = [
    Index(value = ["timestamp"], unique = false)
])
data class ChangeAuditEntity(
    @PrimaryKey val id: String,
    val entityId: String,
    val changeKind: String,
    val timestamp: Long,
    val fieldsChanged: String
)

@Entity(tableName = "budget", foreignKeys = [
    ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.CASCADE)
], indices = [
    Index(value = ["categoryId", "month"], unique = true)
])
data class BudgetEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val month: String,
    val limitMinor: Long,
    @ColumnInfo(defaultValue = "0") val alertsEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "80") val threshold: Int = 80,
    @ColumnInfo(defaultValue = "0") val lastNotifiedLevel: Int = 0
)

@Entity(tableName = "card_cycle", foreignKeys = [
    ForeignKey(entity = CardProfileEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE)
], indices = [
    Index(value = ["cardId", "startsAt"], unique = true)
])
data class CardCycleEntity(
    @PrimaryKey val id: String,
    val cardId: String,
    val startsAt: Long,
    val endsAt: Long,
    val dueAt: Long
)

/** F2: decisión sobre evidencia; no representa un asiento ni modifica saldos. */
@Entity(tableName = "candidate", indices = [Index(value = ["status", "receivedAt"])])
data class CandidateEntity(
    @PrimaryKey val id: String,
    val amountMinor: Long? = null,
    val occurredAt: Long? = null,
    val direction: String? = null,
    val provider: String? = null,
    val instrument: String? = null,
    val merchant: String? = null,
    val candidateKind: String = "UNKNOWN",
    val parserVersion: String = "capture-v1-unparsed",
    val confidence: Double = 0.0,
    val evidenceCodes: String = "PARSER_NOT_AVAILABLE",
    val receivedAt: Long,
    val origin: String,
    val status: String = "NEEDS_REVIEW",
    val accountId: String? = null,
    val classification: String? = null,
    val counterAccountId: String? = null,
    val originalTransactionId: String? = null,
    val note: String? = null,
    @ColumnInfo(defaultValue = "0") val estimatedDate: Boolean = false,
    val categoryId: String? = null,
    @ColumnInfo(defaultValue = "0") val categoryDecided: Boolean = false,
)
