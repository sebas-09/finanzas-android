package ec.finanzas.locales

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import ec.finanzas.database.*
import ec.finanzas.designsystem.FinanzasTheme
import ec.finanzas.model.*
import ec.finanzas.security.DatabaseKeyVault
import javax.inject.Provider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class BookFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "f3_ui_test"
    private var db: BootstrapDatabase? = null
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval() }
    @Test fun manualEntryThroughFormUpdatesCashAndUndoRemovesOnlyItsEffects() = runBlocking {
        db = EncryptedDatabaseFactory.open(context, name)
        val database = db!!
        BookStore(database).saveAccount("bank", "Banco de prueba", BankProvider.PICHINCHA, InstrumentType.BANK_ACCOUNT, 10000)
        val vm = BookViewModel(Provider { database })
        withTimeout(30_000) { vm.state.first { !it.loading } }
        compose.setContent {
            val state = vm.state.collectAsState().value
            var open by remember { mutableStateOf(true) }
            FinanzasTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { TransactionsBookScreen(state, vm) }
                if (open) OperationEditor(state, vm, null, null) { open = false }
            }
        }
        compose.onNodeWithContentDescription("Cuenta principal / del aviso").performScrollTo().performClick()
        compose.onNodeWithText("Banco de prueba").performClick()
        compose.onNodeWithContentDescription("Clasificación").performScrollTo().performClick()
        compose.onNodeWithText("Gasto").performClick()
        compose.onNodeWithText("Importe USD").performScrollTo().performTextReplacement("3,99")
        compose.onNodeWithText("Importe USD").performImeAction()
        compose.onNodeWithText("Contabilizar operación").performScrollTo().assertIsEnabled().performClick()
        val saved = withTimeout(30_000) { vm.state.first { !it.busy && it.book?.transactions?.count(::included) == 1 } }
        val savedTotals = requireNotNull(saved.totals)
        assertEquals(399L, savedTotals.expense); assertEquals(9601L, savedTotals.balances["bank"])
        compose.onNodeWithText("Deshacer última acción").performScrollTo().performClick()
        val undone = withTimeout(30_000) { vm.state.first { !it.busy && it.book?.transactions?.isEmpty() == true } }
        val undoneTotals = requireNotNull(undone.totals)
        assertEquals(0L, undoneTotals.expense); assertEquals(10000L, undoneTotals.balances["bank"])
        assertEquals(0, database.book().postings().size)
    }
}
