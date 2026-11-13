package ec.finanzas.locales

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ec.finanzas.capture.CaptureManager
import ec.finanzas.database.*
import ec.finanzas.ledger.OperationDraft
import ec.finanzas.model.*
import ec.finanzas.security.DatabaseKeyVault
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DataSettingsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "f5_export_test"
    private var db: BootstrapDatabase? = null
    private val file get() = File(context.cacheDir, "f5-export-test.csv")
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context,name).deleteAfterDatabaseRemoval(); file.delete() }

    @Test fun cancelDialogsNeverDeletesAndRequiresExplicitAcknowledgement() {
        compose.onNodeWithText("Explorar la app").performScrollTo().performClick()
        compose.onNodeWithText("Ajustes").performScrollTo().performClick()
        compose.onNodeWithText("Exportar movimientos CSV").performScrollTo().performClick()
        compose.onNodeWithText("Exportar datos sensibles").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText("Eliminar todos los datos").performScrollTo().performClick()
        compose.onNodeWithText("Borrar definitivamente").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Entiendo que el borrado es irreversible").performScrollTo().performClick()
        compose.onNodeWithText("Borrar definitivamente").assertIsEnabled()
        compose.onNodeWithText("Conservar mis datos").performClick()
        compose.onNodeWithText("Eliminar todos los datos").assertExists()
    }

    @Test fun exportUsesExactSnapshotExcludesNonCurrentRecordsAndIncludesOwnTransfers(): Unit = runBlocking {
        val database = EncryptedDatabaseFactory.open(context,name); db=database
        val book=BookStore(database)
        book.saveAccount("bank","=Banco",BankProvider.PICHINCHA,InstrumentType.BANK_ACCOUNT,10000)
        book.saveAccount("wallet","Billetera",BankProvider.DEUNA,InstrumentType.WALLET,0)
        book.save(OperationDraft("buy","bank",399,TransactionKind.EXPENSE,1000,MoneyDirection.OUTGOING,
            note="café, \"local\"\nsegunda línea"))
        book.save(OperationDraft("top","bank",500,TransactionKind.WALLET_TOP_UP,2000,MoneyDirection.OUTGOING,counterAccountId="wallet"))
        val original=database.book().transaction("buy")!!
        for ((id,status,origin,deleted,merged) in listOf(
            listOf("pending","NEEDS_REVIEW","MANUAL",null,null),
            listOf("test","MANUAL_CONFIRMED","SYNTHETIC",null,null),
            listOf("deleted","MANUAL_CONFIRMED","MANUAL",1L,null),
            listOf("merged","MANUAL_CONFIRMED","MANUAL",null,"buy"))) {
            database.book().transaction(original.copy(id=id as String,status=status as String,origin=origin as String,
                deletedAt=deleted as Long?,mergedIntoId=merged as String?))
        }
        val snapshot=book.observe().first()
        assertEquals(listOf("buy","top"), snapshot.exportMovements().map { it.id })
        val vm=DataSettingsViewModel(context, Provider { database }, CaptureManager(context,Provider { database }))
        vm.export(Uri.fromFile(file))
        val result=withTimeout(30000) { vm.state.first { !it.busy } }
        assertNull(result.error)
        val csv=file.readText(Charsets.UTF_8)
        assertTrue(csv.contains("\"3.99\"")); assertTrue(csv.contains("\"'=Banco\""))
        assertTrue(csv.contains("\"WALLET_TOP_UP\"")); assertTrue(csv.contains("\"Billetera\""))
        assertTrue(csv.contains("café, \"\"local\"\"\nsegunda línea"))
        assertEquals(6,database.book().transactions().size)
        ProductStore(database).initialize()
        book.save(operationDraft(database.book().transaction("buy")!!).copy(categoryId="food",categoryChanged=true))
        book.save(OperationDraft("refund","bank",100,TransactionKind.REFUND,3000,MoneyDirection.INCOMING,originalId="buy"))
        book.save(operationDraft(database.book().transaction("buy")!!).copy(categoryId="transport",categoryChanged=true))
        assertEquals("Transporte",book.observe().first().exportMovements().single { it.id=="refund" }.category)
    }

    @Test fun cancelledAndFailedExportsPreserveLedger(): Unit = runBlocking {
        val database=EncryptedDatabaseFactory.open(context,name); db=database
        database.probe().save(BootstrapProbe(value="keep"))
        val vm=DataSettingsViewModel(context,Provider { database },CaptureManager(context,Provider { database }))
        vm.export(null)
        assertFalse(file.exists()); assertNotNull(vm.state.value.message)
        vm.export(Uri.parse("content://ec.finanzas.missing.provider/document/none"))
        val state=withTimeout(30000) { vm.state.first { !it.busy } }
        assertNotNull(state.error); assertEquals("keep",database.probe().read())
    }
}
