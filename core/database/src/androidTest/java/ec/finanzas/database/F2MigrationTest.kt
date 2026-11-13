package ec.finanzas.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import ec.finanzas.model.*
import ec.finanzas.security.DatabaseKeyVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class F2MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "f2_migration_test"
    private var db: BootstrapDatabase? = null
    @After fun clean() {
        db?.close()
        context.deleteDatabase("$name.db")
        DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval()
    }
    @Test fun migratesEncryptedF1ObservationWithoutInventingEvidence() = runBlocking {
        System.loadLibrary("sqlcipher")
        val schema = JSONObject(context.assets.open("ec.finanzas.database.BootstrapDatabase/2.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val secret = DatabaseKeyVault(context, name).getOrCreate(false)
        val helper = SupportOpenHelperFactory(secret).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("$name.db").callback(object : SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
                    db.execSQL("INSERT INTO source_observation(id,sourcePackage,eventFingerprint,keyHash,revisionHash,parserVersion,postedAt,receivedAt,lastReceivedAt,provider,origin,state,confidence,evidenceCodes,currency) VALUES('f1-marker','synthetic','f1-marker','hash','revision','capture-v1-unparsed',1000,1001,1001,'DEUNA','SYNTHETIC','AWAITING_PARSER',0,'PARSER_NOT_AVAILABLE','USD')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        db = EncryptedDatabaseFactory.open(context, name)
        val candidate = requireNotNull(db!!.capture().candidate("f1-marker"))
        assertNull(candidate.amountMinor); assertNull(candidate.occurredAt); assertNull(candidate.accountId)
        assertEquals("capture-v1-unparsed", candidate.parserVersion)
        assertEquals("DEUNA", candidate.provider)
        assertEquals("NEEDS_REVIEW", candidate.status)
        assertEquals(0L, db!!.ledger().transactionCount())
        db!!.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
    @Test fun normalizedQueueSurvivesReopenAndRepeatedDrainWithoutLedgerEffects() = runBlocking {
        db = EncryptedDatabaseFactory.open(context, name)
        val store = CaptureStore(db!!)
        store.initializeAfterProcessStart(1000)
        val normalized = NormalizedBankEvent("synthetic", "hash", Instant.ofEpochMilli(1001), null, 399,
            direction = MoneyDirection.OUTGOING, bank = BankProvider.DEUNA, instrument = InstrumentType.WALLET,
            merchantNormalized = null, transactionReferenceHash = null, kind = CandidateKind.PAYMENT, confidence = 0.75,
            evidenceCodes = setOf(EvidenceCode.MISSING_ACCOUNT))
        val event = CaptureEnvelope("f2-event", "synthetic", "hash", "revision", null, 1000, 1001,
            DataOrigin.SYNTHETIC, ParseResult.Candidate(normalized, "synthetic-f2-test"))
        assertTrue(store.enqueue(event))
        db!!.close()
        db = EncryptedDatabaseFactory.open(context, name)
        val restarted = CaptureStore(db!!)
        restarted.initializeAfterProcessStart(2000)
        assertFalse(restarted.drain()); assertFalse(restarted.drain())
        assertFalse(restarted.enqueue(event))
        val observation = requireNotNull(db!!.capture().observation("f2-event"))
        assertEquals(399L, observation.amountMinor)
        assertEquals("synthetic-f2-test", observation.parserVersion)
        assertEquals(1, db!!.capture().candidates().first().size)
        assertEquals(0L, db!!.ledger().transactionCount())
    }
}
