package ec.finanzas.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import ec.finanzas.security.DatabaseKeyVault
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Tabla exclusivamente técnica para validar F0. El esquema financiero se incorpora en F1. */
@Entity(tableName = "bootstrap_probe")
data class BootstrapProbe(@PrimaryKey val id: Int = 1, val value: String)

@Dao
interface BootstrapDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(probe: BootstrapProbe)
    @Query("SELECT value FROM bootstrap_probe WHERE id = 1")
    suspend fun read(): String?
}

@Database(entities = [BootstrapProbe::class,
    FinancialAccountEntity::class,
    CardProfileEntity::class,
    CategoryEntity::class,
    TransactionEntity::class,
    AccountPostingEntity::class,
    SourceObservationEntity::class,
    TransferLinkEntity::class,
    MerchantRuleEntity::class,
    ReviewQueueEntity::class,
    CaptureSessionEntity::class,
    CaptureSettingsEntity::class,
    AllowedSourceEntity::class,
    CaptureQueueEntity::class,
    DiagnosticCounterEntity::class, NotificationCursorEntity::class,
    ChangeAuditEntity::class,
    CandidateEntity::class, LedgerHistoryEntity::class, LedgerVersionEntity::class,
    BudgetEntity::class,
    CardCycleEntity::class
], version = 5, exportSchema = true)
abstract class BootstrapDatabase : RoomDatabase() {
    abstract fun probe(): BootstrapDao
    abstract fun capture(): CaptureDao
    abstract fun ledger(): FinancialDao
    abstract fun book(): BookDao
    abstract fun product(): ProductDao
}

object EncryptedDatabaseFactory {
    @Synchronized
    fun open(context: Context, name: String = "ledger"): BootstrapDatabase {
        System.loadLibrary("sqlcipher")
        val fileName = "$name.db"
        val secret = DatabaseKeyVault(context, name).getOrCreate(context.getDatabasePath(fileName).exists())
        // SQLCipher conserva el arreglo durante la vida de su helper: no ponerlo a cero antes de cerrar.
        return Room.databaseBuilder(context.applicationContext, BootstrapDatabase::class.java, fileName)
            .openHelperFactory(SupportOpenHelperFactory(secret))
            .addMigrations(FinancialMigrations.FROM_1_TO_2, FinancialMigrations.FROM_2_TO_3, FinancialMigrations.FROM_3_TO_4, FinancialMigrations.FROM_4_TO_5)
            .build()
    }
}

