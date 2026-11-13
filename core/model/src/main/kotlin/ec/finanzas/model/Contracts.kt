package ec.finanzas.model

import java.time.Instant
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun observeAccounts(): Flow<List<FinancialAccount>>
    suspend fun save(account: FinancialAccount)
}

/** Implementación futura: cambios de operación, postings y auditoría atómicos. */
interface LedgerRepository {
    fun observeTransactions(from: Instant, until: Instant): Flow<List<LedgerTransaction>>
    suspend fun save(transaction: LedgerTransaction)
    suspend fun softDelete(id: String, deletedAt: Instant)
}

interface ObservationRepository {
    suspend fun saveIfAbsent(observation: SourceObservation): Boolean
    fun observeReviewQueue(): Flow<List<ReviewCandidate>>
}

interface CaptureRepository {
    fun observeState(): Flow<CaptureState>
    suspend fun start(at: Instant)
    suspend fun pause(at: Instant)
}

fun interface ParseNotification { operator fun invoke(input: NotificationInput): ParseResult }
fun interface ReconcileObservation { suspend operator fun invoke(id: String) }
fun interface CreateTransaction { suspend operator fun invoke(transaction: LedgerTransaction) }
fun interface UpdateLedger { suspend operator fun invoke(transaction: LedgerTransaction) }
fun interface CalculateInsights {
    operator fun invoke(transactions: List<LedgerTransaction>): FinancialInsights
}
