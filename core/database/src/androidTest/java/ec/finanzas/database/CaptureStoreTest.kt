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

class CaptureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "capture_store_test"
    private var db: BootstrapDatabase? = null
    private fun open(): BootstrapDatabase = EncryptedDatabaseFactory.open(context, name).also { db = it }
    @After fun clean() {
        db?.close()
        context.deleteDatabase("$name.db")
        DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval()
    }
    private suspend fun active(database: BootstrapDatabase): CaptureStore {
        val store = CaptureStore(database)
        store.initializeAfterProcessStart(1000)
        store.consent(1000)
        store.permission(true, 1100)
        store.connection(true, 1100)
        store.source(AllowedSourceEntity("ec.finanzas.fixture", "Fuente sintética", "DEUNA",
            enabled = true, signingDigest = "synthetic-signature"), 1500)
        store.activate(2000)
        return store
    }
    private fun event(id: String, session: Long, at: Long, key: String = id, revision: String = "v1") =
        CaptureEnvelope(id, "ec.finanzas.fixture", key, revision, session, at, at + 1, DataOrigin.NOTIFICATION)

    @Test fun consentSessionsPauseRevocationAndDelayedSourcesAreEnforced() = runBlocking {
        val database = open()
        val store = active(database)
        val first = requireNotNull(database.capture().settings().currentSessionId)
        assertFalse(store.enqueue(event("old", first, 1999)))
        assertTrue(store.enqueue(event("first", first, 2100)))
        store.pause(2200)
        assertFalse(store.enqueue(event("paused", first, 2300)))
        store.activate(2400)
        val second = requireNotNull(database.capture().settings().currentSessionId)
        assertNotEquals(first, second)
        assertFalse(store.enqueue(event("pause-gap", second, 2350)))
        store.source(AllowedSourceEntity("ec.finanzas.fixture", "Fuente sintética", "DEUNA",
            enabled = false, signingDigest = "synthetic-signature"), 2500)
        store.source(AllowedSourceEntity("ec.finanzas.fixture", "Fuente sintética", "DEUNA",
            enabled = true, signingDigest = "synthetic-signature"), 2600)
        store.activate(2700)
        val third = requireNotNull(database.capture().settings().currentSessionId)
        assertFalse(store.enqueue(event("before-reenable", third, 2599)))
        assertTrue(store.enqueue(event("new", third, 2800)))
        store.permission(false, 2900)
        assertFalse(store.enqueue(event("revoked", third, 3000)))
        assertEquals(CaptureState.PERMISSION_REVOKED, store.observe().first().state)
        store.drain()
        assertEquals(2L, database.capture().observationCount("NOTIFICATION"))
        assertEquals(0L, database.ledger().transactionCount())
    }

    @Test fun durableQueueReplaysOnceAndUpdatesDoNotDuplicatePublication() = runBlocking {
        var database = open()
        var store = active(database)
        val session = requireNotNull(database.capture().settings().currentSessionId)
        assertTrue(store.enqueue(event("publication", session, 2100, key = "same-key")))
        repeat(5) { assertFalse(store.enqueue(event("publication", session, 2100, key = "same-key"))) }
        assertTrue(store.enqueue(event("different-post-time", session, 2150, key = "same-key", revision = "v2")))
        assertEquals(1L, database.capture().queueCount())
        database.close()
        database = open()
        store = CaptureStore(database)
        store.initializeAfterProcessStart(2200)
        assertFalse(store.drain())
        assertFalse(store.drain())
        assertEquals(1L, database.capture().observationCount("NOTIFICATION"))
        assertEquals(0L, database.capture().queueCount())
        assertEquals("v2", database.capture().observation("publication")!!.revisionHash)
        store.enqueue(CaptureEnvelope("synthetic", "ec.finanzas.fixture", "test-key", "test-rev", null,
            2300, 2300, DataOrigin.SYNTHETIC))
        store.drain()
        assertEquals(1L, database.capture().observationCount("SYNTHETIC"))
        assertEquals(1L, database.capture().observationCount("NOTIFICATION"))
        assertEquals(0L, database.ledger().transactionCount())
    }

    @Test fun keyReuseAfterRemovalCreatesSeparateObservation() = runBlocking {
        val database = open()
        val store = active(database)
        val session = requireNotNull(database.capture().settings().currentSessionId)
        store.enqueue(event("one", session, 2100, key = "shared"))
        store.drain()
        store.notificationRemoved("shared")
        store.enqueue(event("two", session, 2200, key = "shared"))
        store.drain()
        assertEquals(2L, database.capture().observationCount("NOTIFICATION"))
    }

    @Test fun encryptedVersionOneMigratesWithoutLosingDataAndEnforcesForeignKeys() = runBlocking {
        System.loadLibrary("sqlcipher")
        val secret = DatabaseKeyVault(context, name).getOrCreate(false)
        val schema = JSONObject(context.assets.open("ec.finanzas.database.BootstrapDatabase/1.json")
            .bufferedReader().use { it.readText() }).getJSONObject("database")
        val helper = SupportOpenHelperFactory(secret).create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("$name.db").callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE bootstrap_probe (id INTEGER NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                    db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                    db.execSQL("INSERT INTO room_master_table VALUES(42, ?)", arrayOf(schema.getString("identityHash")))
                    db.execSQL("INSERT INTO bootstrap_probe VALUES(1, 'migration-marker')")
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val database = open()
        assertEquals("migration-marker", database.probe().read())
        database.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertEquals(5, cursor.getInt(0))
        }
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        database.ledger().transaction(TransactionEntity("tx", occurredAt = 1000, observedAt = 1000,
            amountMinor = 1, direction = "OUTGOING", kind = "EXPENSE", status = "MANUAL_CONFIRMED",
            origin = "MANUAL", createdAt = 1000, updatedAt = 1000))
        try {
            database.ledger().posting(AccountPostingEntity("invalid", "tx", "missing-account", -1))
            fail("La FK debe impedir postings sin cuenta")
        } catch (failure: Exception) {
            assertTrue(failure.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
        }
    }
}
