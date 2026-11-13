package ec.finanzas.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ec.finanzas.security.DatabaseKeyVault
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "encryption_test"
    private var database: BootstrapDatabase? = null

    @After fun cleanUp() {
        database?.close()
        context.deleteDatabase("$name.db")
        DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval()
    }

    @Test fun encryptedDatabaseReopensUsingWrappedKey() = runBlocking {
        database = EncryptedDatabaseFactory.open(context, name)
        database!!.probe().save(BootstrapProbe(value = "synthetic-marker"))
        database!!.close()
        val header = ByteArray(16)
        RandomAccessFile(context.getDatabasePath("$name.db"), "r").use { it.readFully(header) }
        assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray()))
        // Evitar que el manejador por defecto borre la BD cifrada al considerarla corrupta.
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            val plain = android.database.sqlite.SQLiteDatabase.openDatabase(
                context.getDatabasePath("$name.db").path, null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY, android.database.DatabaseErrorHandler { },
            )
            try {
                plain.rawQuery("SELECT value FROM bootstrap_probe", null).use { it.moveToFirst() }
            } finally { plain.close() }
        }
        database = EncryptedDatabaseFactory.open(context, name)
        assertEquals("synthetic-marker", database!!.probe().read())
        assertTrue(DatabaseKeyVault(context, name).getOrCreate(true).contentEquals(
            DatabaseKeyVault(context, name).getOrCreate(true)))
    }

    @Test fun missingWrappedSecretNeverResetsAnExistingDatabase() = runBlocking {
        database = EncryptedDatabaseFactory.open(context, name)
        database!!.probe().save(BootstrapProbe(value = "synthetic-marker"))
        database!!.close()
        DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval()
        assertThrows(IllegalStateException::class.java) { EncryptedDatabaseFactory.open(context, name) }
        assertTrue(context.getDatabasePath("$name.db").exists())
    }

    @Test fun copiedDatabaseWithoutKeyIsUnreadableAndOriginalStillOpens(): Unit = runBlocking {
        database = EncryptedDatabaseFactory.open(context, name)
        database!!.probe().save(BootstrapProbe(value = "copy-marker")); database!!.close()
        val copy = java.io.File(context.cacheDir, "f5-encrypted-copy.db")
        context.getDatabasePath("$name.db").copyTo(copy, overwrite = true)
        try {
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                val plain = android.database.sqlite.SQLiteDatabase.openDatabase(copy.path, null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY, android.database.DatabaseErrorHandler { })
                try { plain.rawQuery("SELECT value FROM bootstrap_probe", null).use { it.moveToFirst() } }
                finally { plain.close() }
            }
            database = EncryptedDatabaseFactory.open(context, name)
            assertEquals("copy-marker", database!!.probe().read())
        } finally { copy.delete() }
    }
}

