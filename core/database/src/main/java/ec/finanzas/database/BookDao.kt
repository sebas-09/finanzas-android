package ec.finanzas.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ledger_history", indices = [Index(value = ["ownerId"])])
data class LedgerHistoryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val ownerId: String,
    val beforeJson: String, val afterJson: String, val createdAt: Long, val undoneAt: Long? = null)

@Entity(tableName = "ledger_version")
data class LedgerVersionEntity(@PrimaryKey val id: String, val version: Long)

internal class BookVersions(private val db: BootstrapDatabase) {
    suspend fun next(id: String): Long {
        val previous = db.book().version(id)?.version ?: db.book().transaction(id)?.revision ?: -1L
        val next = Math.addExact(previous, 1)
        db.book().version(LedgerVersionEntity(id, next))
        return next
    }
}

@Dao
interface BookDao {
    @Query("SELECT * FROM candidate WHERE status IN ('NEEDS_REVIEW','MANUAL_CONFIRMED') ORDER BY receivedAt DESC") suspend fun pending(): List<CandidateEntity>
    @Query("SELECT * FROM candidate WHERE status='IGNORED' ORDER BY receivedAt DESC LIMIT 50") suspend fun ignored(): List<CandidateEntity>
    @Query("SELECT * FROM ledger_version WHERE id=:id") suspend fun version(id: String): LedgerVersionEntity?
    @Upsert suspend fun version(version: LedgerVersionEntity)
    @Query("UPDATE ledger_history SET afterJson=:json WHERE id=:id") suspend fun updateHistoryExpectation(id: Long, json: String)
    @Query("SELECT * FROM financial_account ORDER BY id") fun accountsFlow(): Flow<List<FinancialAccountEntity>>
    @Query("SELECT * FROM ledger_transaction ORDER BY id") fun transactionsFlow(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM account_posting ORDER BY id") fun postingsFlow(): Flow<List<AccountPostingEntity>>
    @Query("SELECT * FROM candidate WHERE status IN ('NEEDS_REVIEW','MANUAL_CONFIRMED') ORDER BY receivedAt DESC")
    fun pendingFlow(): Flow<List<CandidateEntity>>
    @Query("SELECT * FROM source_observation ORDER BY id") fun observationsFlow(): Flow<List<SourceObservationEntity>>
    @Query("SELECT * FROM card_profile ORDER BY id") fun cardsFlow(): Flow<List<CardProfileEntity>>
    @Query("SELECT * FROM candidate WHERE status='IGNORED' ORDER BY receivedAt DESC LIMIT 50") fun ignoredFlow(): Flow<List<CandidateEntity>>
    @Query("SELECT * FROM financial_account ORDER BY id") suspend fun accounts(): List<FinancialAccountEntity>
    @Query("SELECT * FROM ledger_transaction ORDER BY id") suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM account_posting ORDER BY id") suspend fun postings(): List<AccountPostingEntity>
    @Query("SELECT * FROM source_observation ORDER BY id") suspend fun observations(): List<SourceObservationEntity>
    @Query("SELECT * FROM ledger_transaction WHERE id=:id") suspend fun transaction(id: String): TransactionEntity?
    @Query("SELECT * FROM financial_account WHERE id=:id") suspend fun account(id: String): FinancialAccountEntity?
    @Upsert suspend fun account(account: FinancialAccountEntity)
    @Upsert suspend fun card(card: CardProfileEntity)
    @Query("SELECT * FROM card_profile ORDER BY id") suspend fun cards(): List<CardProfileEntity>
    @Upsert suspend fun transaction(transaction: TransactionEntity)
    @Insert suspend fun posting(posting: AccountPostingEntity)
    @Query("DELETE FROM account_posting WHERE transactionId IN (:ids)") suspend fun clearPostings(ids: List<String>)
    @Query("DELETE FROM ledger_transaction WHERE id=:id") suspend fun removeTransaction(id: String)
    @Query("SELECT * FROM transfer_link WHERE debitTransactionId IN (:ids) OR creditTransactionId IN (:ids) ORDER BY id")
    suspend fun links(ids: List<String>): List<TransferLinkEntity>
    @Query("DELETE FROM transfer_link WHERE debitTransactionId IN (:ids) OR creditTransactionId IN (:ids)") suspend fun clearLinks(ids: List<String>)
    @Upsert suspend fun link(link: TransferLinkEntity)
    @Query("SELECT * FROM review_queue WHERE observationId IN (:ids) ORDER BY id") suspend fun reviews(ids: List<String>): List<ReviewQueueEntity>
    @Upsert suspend fun review(review: ReviewQueueEntity)
    @Insert suspend fun history(history: LedgerHistoryEntity): Long
    @Query("SELECT * FROM ledger_history WHERE ownerId=:owner AND undoneAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun latestHistory(owner: String): LedgerHistoryEntity?
    @Query("UPDATE ledger_history SET undoneAt=:at WHERE id=:id") suspend fun undoHistory(id: Long, at: Long)
}
