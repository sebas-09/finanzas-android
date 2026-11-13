package ec.finanzas.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class CaptureConfiguration(@Embedded val settings: CaptureSettingsEntity, val sessionStartedAt: Long?)
data class ObservationCounts(val observed: Long, val synthetic: Long)

@Dao
interface CaptureDao {
    @Query("SELECT id FROM source_observation WHERE sourcePackage=:source AND keyHash=:key AND postedAt=:posted AND revisionHash=:revision AND origin='NOTIFICATION' ORDER BY receivedAt LIMIT 1")
    suspend fun exactPublication(source: String, key: String, posted: Long, revision: String): String?
    @Query("SELECT * FROM candidate WHERE status='NEEDS_REVIEW' ORDER BY receivedAt DESC")
    fun candidates(): Flow<List<CandidateEntity>>
    @Query("SELECT * FROM candidate WHERE id=:id")
    suspend fun candidate(id: String): CandidateEntity?
    @Upsert suspend fun candidate(candidate: CandidateEntity)
    @Query("SELECT * FROM financial_account WHERE active=1 ORDER BY alias")
    fun accounts(): Flow<List<FinancialAccountEntity>>
    @Query("SELECT * FROM financial_account WHERE id=:id AND active=1")
    suspend fun accountById(id: String): FinancialAccountEntity?
    @Query("UPDATE review_queue SET resolvedAt=:at, resolution=:resolution WHERE observationId=:id AND resolvedAt IS NULL")
    suspend fun resolve(id: String, at: Long, resolution: String)

    @Query("SELECT * FROM notification_cursor WHERE keyHash=:hash")
    suspend fun cursor(hash: String): NotificationCursorEntity?
    @Upsert suspend fun cursor(cursor: NotificationCursorEntity)
    @Query("UPDATE notification_cursor SET active=0 WHERE keyHash=:hash")
    suspend fun closeCursor(hash: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun ensureSettings(settings: CaptureSettingsEntity): Long
    @Query("SELECT * FROM capture_settings WHERE id=1")
    suspend fun settings(): CaptureSettingsEntity
    @Upsert suspend fun settings(settings: CaptureSettingsEntity)
    @Query("SELECT capture_settings.*, capture_session.startedAt AS sessionStartedAt FROM capture_settings LEFT JOIN capture_session ON capture_settings.currentSessionId=capture_session.id WHERE capture_settings.id=1")
    fun configurationFlow(): Flow<CaptureConfiguration?>
    @Insert suspend fun session(session: CaptureSessionEntity): Long
    @Query("UPDATE capture_session SET endedAt=:at, endReason=:reason WHERE id=:id AND endedAt IS NULL")
    suspend fun closeSession(id: Long, at: Long, reason: String)
    @Query("SELECT * FROM capture_session WHERE id=:id")
    suspend fun session(id: Long): CaptureSessionEntity?
    @Query("SELECT * FROM allowed_source ORDER BY displayName")
    fun sourcesFlow(): Flow<List<AllowedSourceEntity>>
    @Query("SELECT * FROM allowed_source")
    suspend fun sources(): List<AllowedSourceEntity>
    @Query("SELECT * FROM allowed_source WHERE packageName=:packageName")
    suspend fun source(packageName: String): AllowedSourceEntity?
    @Upsert suspend fun source(source: AllowedSourceEntity)
    @Query("UPDATE allowed_source SET lastSeenAt=:at WHERE packageName=:packageName")
    suspend fun lastSeen(packageName: String, at: Long)
    @Upsert suspend fun queue(event: CaptureQueueEntity)
    @Query("SELECT * FROM capture_queue WHERE eventFingerprint=:fingerprint")
    suspend fun queued(fingerprint: String): CaptureQueueEntity?
    @Query("SELECT * FROM capture_queue ORDER BY receivedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<CaptureQueueEntity>
    @Query("DELETE FROM capture_queue WHERE eventFingerprint=:fingerprint")
    suspend fun removeQueued(fingerprint: String)
    @Query("SELECT COUNT(*) FROM capture_queue")
    suspend fun queueCount(): Long
    @Query("SELECT COUNT(*) FROM capture_queue")
    fun queueCountFlow(): Flow<Long>
    @Query("SELECT * FROM source_observation WHERE id=:id")
    suspend fun observation(id: String): SourceObservationEntity?
    @Upsert suspend fun observation(observation: SourceObservationEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun review(review: ReviewQueueEntity): Long
    @Query("SELECT COALESCE(SUM(CASE WHEN origin='NOTIFICATION' THEN 1 ELSE 0 END),0) AS observed, COALESCE(SUM(CASE WHEN origin='SYNTHETIC' THEN 1 ELSE 0 END),0) AS synthetic FROM source_observation")
    fun countsFlow(): Flow<ObservationCounts>
    @Query("SELECT COUNT(*) FROM source_observation WHERE origin=:origin")
    suspend fun observationCount(origin: String): Long
    @Query("INSERT INTO diagnostic_counter(reason,count) VALUES(:reason,1) ON CONFLICT(reason) DO UPDATE SET count=count+1")
    suspend fun count(reason: String)
    @Query("SELECT COALESCE(SUM(count),0) FROM diagnostic_counter")
    fun discardedFlow(): Flow<Long>
    @Insert suspend fun audit(audit: ChangeAuditEntity)
}

@Dao
interface FinancialDao {
    @Insert suspend fun account(account: FinancialAccountEntity)
    @Insert suspend fun transaction(transaction: TransactionEntity)
    @Insert suspend fun posting(posting: AccountPostingEntity)
    @Query("SELECT COUNT(*) FROM ledger_transaction") suspend fun transactionCount(): Long
}
