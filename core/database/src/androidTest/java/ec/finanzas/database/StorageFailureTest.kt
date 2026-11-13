package ec.finanzas.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ec.finanzas.ledger.OperationDraft
import ec.finanzas.model.*
import ec.finanzas.security.DatabaseKeyVault
import java.security.KeyStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class StorageFailureTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val name="f5_storage_failure"
    private var db: BootstrapDatabase?=null
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context,name).deleteAfterDatabaseRemoval() }

    @Test fun postingFailureRollsBackTransactionHistoryAndBalance(): Unit = runBlocking {
        val database=EncryptedDatabaseFactory.open(context,name); db=database
        val book=BookStore(database)
        book.saveAccount("bank","Banco",BankProvider.PICHINCHA,InstrumentType.BANK_ACCOUNT,10000)
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER f5_fail_posting BEFORE INSERT ON account_posting BEGIN SELECT RAISE(ABORT, 'injected_write_failure'); END")
        var failed=false
        try { book.save(OperationDraft("buy","bank",399,TransactionKind.EXPENSE,1000,MoneyDirection.OUTGOING)) }
        catch (_: Exception) { failed=true }
        assertTrue(failed)
        assertNull(database.book().transaction("buy"))
        assertTrue(database.book().postings().isEmpty())
        assertEquals(10000L,book.observe().first().totals().balances["bank"])
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER f5_fail_posting")
        book.save(OperationDraft("buy","bank",399,TransactionKind.EXPENSE,1000,MoneyDirection.OUTGOING))
        assertEquals(9601L,book.observe().first().totals().balances["bank"])
    }

    @Test fun databasePageLimitRejectsWriteWithoutLosingCommittedData(): Unit = runBlocking {
        val database=EncryptedDatabaseFactory.open(context,name); db=database
        database.probe().save(BootstrapProbe(value="committed"))
        val sql=database.openHelper.writableDatabase
        val pages=sql.query("PRAGMA page_count").use { it.moveToFirst(); it.getLong(0) }
        sql.query("PRAGMA max_page_count=$pages").use { assertTrue(it.moveToFirst()); assertEquals(pages,it.getLong(0)) }
        var failed=false
        try { database.probe().save(BootstrapProbe(value="x".repeat(2_000_000))) }
        catch (_: Exception) { failed=true }
        assertTrue("SQLCipher must reject growth beyond its page limit",failed)
        assertEquals("committed",database.probe().read())
        database.close()
        db=EncryptedDatabaseFactory.open(context,name)
        assertEquals("committed",db!!.probe().read())
    }

    @Test fun inaccessibleWrappingKeyPreservesDatabaseAndWrappedSecret(): Unit = runBlocking {
        val database=EncryptedDatabaseFactory.open(context,name); db=database
        database.probe().save(BootstrapProbe(value="committed")); database.close()
        val file=context.getDatabasePath("$name.db")
        val before=file.readBytes()
        val secret=java.io.File(context.noBackupFilesDir,"$name.key")
        val wrapped=secret.readBytes()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("ec.finanzas.locales.db.$name.v1") }
        assertThrows(IllegalStateException::class.java) { EncryptedDatabaseFactory.open(context,name) }
        assertArrayEquals(before,file.readBytes()); assertArrayEquals(wrapped,secret.readBytes())
    }
}
