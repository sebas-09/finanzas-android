package ec.finanzas.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import ec.finanzas.security.DatabaseKeyVault
import ec.finanzas.dashboard.*
import ec.finanzas.ledger.OperationDraft
import ec.finanzas.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class F4MigrationTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="f4_migration_test"
    private var db: BootstrapDatabase?=null
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context,name).deleteAfterDatabaseRemoval() }
    @Test fun encryptedV4CategoriesRulesBudgetsAndBalanceSurviveWithAlertsOff() = runBlocking {
        System.loadLibrary("sqlcipher")
        val secret=DatabaseKeyVault(context,name).getOrCreate(false)
        val schema=JSONObject(context.assets.open("ec.finanzas.database.BootstrapDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper=SupportOpenHelperFactory(secret).create(SupportSQLiteOpenHelper.Configuration.builder(context).name("$name.db").callback(object: SupportSQLiteOpenHelper.Callback(4) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities=schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity=entities.getJSONObject(i); val table=entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}",table))
                    val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                    for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",table))
                }
                db.execSQL("CREATE TABLE room_master_table(id INTEGER PRIMARY KEY, identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES(42,?)",arrayOf(schema.getString("identityHash")))
                db.execSQL("INSERT INTO financial_account VALUES('bank','Cuenta previa','PICHINCHA','BANK',12345,'USD',1,1000)")
                db.execSQL("INSERT INTO category VALUES('old-category','Categoría previa',NULL,'category',1)")
                db.execSQL("INSERT INTO merchant_rule VALUES('old-rule','EXACT','TIENDA','Alias previo','old-category',7,1)")
                db.execSQL("INSERT INTO budget VALUES('old-budget','old-category','2026-11',10000)")
                db.execSQL("INSERT INTO ledger_transaction(id,accountId,occurredAt,observedAt,amountMinor,currency,direction,kind,categoryId,status,origin,createdAt,updatedAt) VALUES('old','bank',1000,1000,100,'USD','OUTGOING','EXPENSE','old-category','MANUAL_CONFIRMED','MANUAL',1000,1000)")
                db.execSQL("INSERT INTO account_posting VALUES('old:bank','old','bank',-100)")
            }
            override fun onUpgrade(db: SupportSQLiteDatabase,oldVersion:Int,newVersion:Int)=Unit
        }).build())
        helper.writableDatabase; helper.close()
        db=EncryptedDatabaseFactory.open(context,name)
        val database=db!!
        assertEquals("Categoría previa",database.product().categories().single().name)
        assertEquals("Alias previo",database.product().rules().single().merchantAlias)
        val budget=database.product().budgets().single()
        assertFalse(budget.alertsEnabled); assertEquals(80,budget.threshold); assertEquals(0,budget.lastNotifiedLevel)
        assertEquals(12245L,BookStore(database).observe().first().totals().balances["bank"])
        val book=BookStore(database)
        val records=book.observe().first().insightRecords()
        assertEquals(listOf("old"),Insights.filter(records,MovementFilter(instrument="BANK_ACCOUNT"),emptyMap()).map { it.operation.id })
        database.capture().observation(SourceObservationEntity("legacy-notice",sourcePackage="fixture",eventFingerprint="legacy",keyHash="key",revisionHash="rev",
            postedAt=1001,receivedAt=1001,lastReceivedAt=1001,provider="PICHINCHA",origin="NOTIFICATION",state="NEEDS_REVIEW",amountMinor=10,occurredAt=1001,direction="OUTGOING"))
        database.capture().candidate(CandidateEntity("legacy-notice",10,1001,"OUTGOING","PICHINCHA","BANK_ACCOUNT",receivedAt=1001,origin="NOTIFICATION"))
        book.save(OperationDraft("candidate:legacy-notice","bank",10,TransactionKind.EXPENSE,1001,MoneyDirection.OUTGOING,candidateId="legacy-notice"))
        assertEquals(12235L,book.observe().first().totals().balances["bank"])
        book.undo("candidate:legacy-notice")
        book.saveAccount("bank","Alias corregido",BankProvider.PICHINCHA,InstrumentType.BANK_ACCOUNT,12345)
        assertEquals("Alias corregido",database.book().account("bank")!!.alias)
        assertEquals("BANK_ACCOUNT",database.book().account("bank")!!.kind)
        assertEquals(12245L,book.observe().first().totals().balances["bank"])
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { assertTrue(it.moveToFirst()); assertEquals(5,it.getInt(0)) }
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
