package ec.finanzas.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import ec.finanzas.ledger.OperationDraft
import ec.finanzas.model.*
import ec.finanzas.security.DatabaseKeyVault
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class F3MigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "f3_migration_test"
    private var db: BootstrapDatabase? = null
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval() }
    @Test fun encryptedF2DecisionsAndDebitBalanceSurviveWithoutAutomaticPosting() = runBlocking {
        System.loadLibrary("sqlcipher")
        val secret = DatabaseKeyVault(context, name).getOrCreate(false)
        val schema = JSONObject(context.assets.open("ec.finanzas.database.BootstrapDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = SupportOpenHelperFactory(secret).create(SupportSQLiteOpenHelper.Configuration.builder(context).name("$name.db")
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i); val table = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    db.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES(42,?)", arrayOf(schema.getString("identityHash")))
                    db.execSQL("INSERT INTO bootstrap_probe VALUES(1,'f2-preserved')")
                    db.execSQL("INSERT INTO financial_account(id,alias,provider,kind,openingBalanceMinor,currency,active,createdAt) VALUES('debit','Cuenta previa','PRODUBANCO','DEBIT_CARD',12345,'USD',1,1000)")
                    db.execSQL("INSERT INTO source_observation(id,sourcePackage,eventFingerprint,keyHash,revisionHash,parserVersion,postedAt,receivedAt,lastReceivedAt,provider,origin,state,confidence,evidenceCodes,amountMinor,occurredAt,currency,direction) VALUES('old','fixture','old','hash','revision','produbanco-f2.2',1000,1001,1001,'PRODUBANCO','NOTIFICATION','MANUAL_CONFIRMED',0.75,'MISSING_ACCOUNT',399,1000,'USD','OUTGOING')")
                    db.execSQL("INSERT INTO candidate(id,amountMinor,occurredAt,direction,provider,instrument,candidateKind,parserVersion,confidence,evidenceCodes,receivedAt,origin,status,accountId,classification) VALUES('old',399,1000,'OUTGOING','PRODUBANCO','DEBIT_CARD','OUTGOING_TRANSFER','produbanco-f2.2',0.75,'MISSING_ACCOUNT',1001,'NOTIFICATION','MANUAL_CONFIRMED','debit','EXPENSE')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase; helper.close()
        db = EncryptedDatabaseFactory.open(context, name)
        val database = db!!
        assertEquals("f2-preserved", database.probe().read())
        assertEquals(0L, database.ledger().transactionCount())
        assertEquals("BANK_ACCOUNT", database.book().account("debit")!!.kind)
        assertEquals(12345L, database.book().account("debit")!!.openingBalanceMinor)
        assertEquals("DEBIT", database.book().cards().single().type)
        val c = database.capture().candidate("old")!!
        assertEquals("MANUAL_CONFIRMED", c.status); assertNull(c.note); assertNull(c.counterAccountId); assertFalse(c.estimatedDate)
        val book = BookStore(database)
        assertEquals(1, book.observe().first().pending.size)
        book.save(OperationDraft("candidate:old", "debit", 399, TransactionKind.EXPENSE, 1000, MoneyDirection.OUTGOING, candidateId = "old"))
        assertEquals(11946L, book.observe().first().totals().balances["debit"])
        book.undo("candidate:old")
        assertEquals("MANUAL_CONFIRMED", database.capture().candidate("old")!!.status)
        assertEquals(0L, database.ledger().transactionCount())
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(5, it.getInt(0)) }
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
