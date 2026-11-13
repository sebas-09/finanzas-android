package ec.finanzas.locales

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.collectAsState
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

class ReviewFlowTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "f2_review_test"
    private var db: BootstrapDatabase? = null
    @After fun clean() {
        db?.close()
        context.deleteDatabase("$name.db")
        DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval()
    }
    @Test fun completeValidateConfirmAndIgnoreWithoutAccounting() = runBlocking {
        db = EncryptedDatabaseFactory.open(context, name)
        val database = db!!
        val dao = database.capture()
        dao.candidate(CandidateEntity("review-test", provider = "DEUNA", instrument = "WALLET", candidateKind = "PAYMENT",
            receivedAt = 1000, origin = "SYNTHETIC"))
        val model = ReviewViewModel(Provider { database })
        withTimeout(20_000) { model.state.first { !it.loading } }
        model.save("review-test", "3,99", null, null, null, true)
        withTimeout(20_000) { model.state.first { !it.busy && it.error != null } }
        assertEquals("NEEDS_REVIEW", dao.candidate("review-test")!!.status)
        model.createAccount("Billetera prueba", BankProvider.DEUNA, InstrumentType.WALLET)
        val state = withTimeout(20_000) { model.state.first { !it.busy && it.accounts.isNotEmpty() } }
        val account = state.accounts.single().id
        model.save("review-test", "3,99", account, TransactionKind.INCOME, MoneyDirection.OUTGOING, true)
        withTimeout(20_000) { model.state.first { !it.busy && it.error != null } }
        assertEquals("NEEDS_REVIEW", dao.candidate("review-test")!!.status)
        model.save("review-test", "3,99", account, TransactionKind.EXPENSE, MoneyDirection.OUTGOING, false)
        withTimeout(20_000) { model.state.first { !it.busy && it.error == null && it.candidates.singleOrNull()?.accountId == account } }
        assertEquals(399L, dao.candidate("review-test")!!.amountMinor)
        compose.setContent {
            val ui = model.state.collectAsState().value
            FinanzasTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ReviewScreen(ui, model) } }
        }
        compose.onNodeWithText("Revisar candidato").performScrollTo().performClick()
        compose.onNodeWithText("Importe USD").performScrollTo().performTextReplacement("4,00")
        compose.onNodeWithText("Importe USD").performImeAction()
        compose.onNodeWithText("Confirmar candidato").performScrollTo().assertIsEnabled().performClick()
        withTimeout(20_000) { model.state.first { !it.busy && it.candidates.isEmpty() } }
        assertEquals("MANUAL_CONFIRMED", dao.candidate("review-test")!!.status)
        assertEquals(400L, dao.candidate("review-test")!!.amountMinor)
        assertEquals(0L, database.ledger().transactionCount())
        compose.onNodeWithText("Candidato confirmado. La contabilización aún no está disponible.").assertExists()
        dao.candidate(CandidateEntity("ignore-test", receivedAt = 2000, origin = "SYNTHETIC"))
        withTimeout(20_000) { model.state.first { it.candidates.size == 1 } }
        compose.waitForIdle()
        compose.onNodeWithText("Revisar candidato").performScrollTo().performClick()
        compose.onNodeWithText("Ignorar candidato").performScrollTo().performClick()
        compose.onNodeWithText("Ignorar", useUnmergedTree = true).performClick()
        withTimeout(20_000) { model.state.first { !it.busy && it.candidates.isEmpty() } }
        assertEquals("IGNORED", dao.candidate("ignore-test")!!.status)
        assertEquals(0L, database.ledger().transactionCount())
    }
}

