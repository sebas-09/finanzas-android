package ec.finanzas.locales

import android.Manifest
import android.app.NotificationManager
import android.os.Build
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
import ec.finanzas.ledger.OperationDraft
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

class ProductFlowTest {
    @get:Rule val compose=createComposeRule()
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private val name="f4_ui_test"
    private var db: BootstrapDatabase?=null
    @After fun clean() { db?.close(); context.deleteDatabase("$name.db"); DatabaseKeyVault(context,name).deleteAfterDatabaseRemoval() }

    @Test fun budgetFormPersistsMonthlyLimitWithAlertsOffAndDashboardShowsConsumption(): Unit = runBlocking {
        val database=EncryptedDatabaseFactory.open(context,name); db=database
        ProductStore(database).initialize()
        val book=BookStore(database)
        book.saveAccount("bank","Banco de prueba",BankProvider.PRODUBANCO,InstrumentType.BANK_ACCOUNT,10000)
        book.save(OperationDraft("buy","bank",399,TransactionKind.EXPENSE,System.currentTimeMillis()-1000,MoneyDirection.OUTGOING,
            merchant="Spotify",categoryId="subscriptions",categoryChanged=true))
        val vm=BookViewModel(Provider { database })
        withTimeout(30000) { vm.state.first { !it.loading } }
        var dashboard by mutableStateOf(false)
        compose.setContent {
            val state=vm.state.collectAsState().value
            FinanzasTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (dashboard) DashboardScreen(state,vm) else BudgetsScreen(state,vm)
            } }
        }
        compose.onNodeWithText("Añadir presupuesto mensual").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Categoría del presupuesto").performScrollTo().performClick()
        compose.onAllNodesWithText("Suscripciones").onLast().performClick()
        compose.onNodeWithText("Límite mensual USD").performScrollTo().performTextReplacement("10,00")
        compose.onNodeWithText("Límite mensual USD").performImeAction()
        compose.onNodeWithText("Guardar presupuesto").performScrollTo().performClick()
        withTimeout(30000) { vm.state.first { !it.busy && it.book?.budgets?.size==1 } }
        val saved=database.product().budgets().single()
        assertEquals(1000L,saved.limitMinor); assertFalse(saved.alertsEnabled)
        compose.runOnIdle { dashboard=true }
        compose.onNodeWithText("Gasto de consumo neto USD $3.99").performScrollTo().assertIsDisplayed()
    }

    @Test fun notificationIsGenericAndStableIdReplacesPreviousThreshold() {
        val manager=context.getSystemService(NotificationManager::class.java)
        val tag="budget:f4-test"
        try {
            if (Build.VERSION.SDK_INT>=33) {
                instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}").use { descriptor ->
                    java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
                }
            }
            assertTrue(BudgetNotifications.post(context,"f4-test",1))
            assertTrue(BudgetNotifications.post(context,"f4-test",2))
            compose.waitUntil(10000) { manager.activeNotifications.any { it.tag == tag } }
            val active=manager.activeNotifications.filter { it.tag==tag }
            assertEquals(1,active.size)
            val text=active.single().notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
            assertFalse(text.contains('$')); assertFalse(text.contains("Suscripciones"))
        } finally {
            manager.cancel(tag,41)
        }
    }
}
