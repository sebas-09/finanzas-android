package ec.finanzas.locales

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import ec.finanzas.capture.CaptureManager
import ec.finanzas.model.CaptureUiState
import ec.finanzas.onboarding.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import ec.finanzas.designsystem.FinanzasTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model: BootstrapViewModel by viewModels()
    private val review: BookViewModel by viewModels()
    private val dataSettings: DataSettingsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FinanzasTheme {
                val state by model.state.collectAsStateWithLifecycle()
                AppNavigation(state, model.capture, review, dataSettings = dataSettings, initialDestination = if (intent.getBooleanExtra("openBudgets", false)) Destination.BUDGETS else Destination.WELCOME, onPermission = {
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    catch (_: android.content.ActivityNotFoundException) { model.capture.reportActionFailure() }
                })
            }
        }
    }
    override fun onResume() { super.onResume(); model.capture.refresh() }
}

enum class Destination(@get:StringRes val title: Int, @get:StringRes val description: Int) {
    WELCOME(R.string.welcome, R.string.welcome_body),
    CONNECTION(R.string.connection, R.string.connection_body),
    SETUP(R.string.setup, R.string.setup_body),
    DASHBOARD(R.string.dashboard, R.string.dashboard_body),
    MOVEMENTS(R.string.movements, R.string.movements_body),
    REVIEW(R.string.review, R.string.review_body),
    ACCOUNTS(R.string.accounts, R.string.accounts_body),
    BUDGETS(R.string.budgets, R.string.budgets_body),
    SETTINGS(R.string.settings, R.string.settings_body),
}

@Composable
fun AppNavigation(state: CaptureUiState, capture: CaptureManager, review: BookViewModel, onPermission: () -> Unit,
    initialDestination: Destination = Destination.WELCOME, dataSettings: DataSettingsViewModel? = null) {
    val nav = rememberNavController()
    val book by review.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(book.book?.budgets, book.completed) {
        if (book.book != null) BudgetNotifications.schedule(context, book.book?.budgets?.any { it.alertsEnabled } == true)
    }
    NavHost(navController = nav, startDestination = initialDestination.name) {
        Destination.entries.forEach { destination ->
            composable(destination.name) {
                BaseScreen(destination, state, capture, review, dataSettings, onPermission, back = { if (!nav.popBackStack()) nav.navigate(Destination.DASHBOARD.name) },
                    navigate = { nav.navigate(it.name) { launchSingleTop = true } })
            }
        }
    }
}

@Composable
private fun BaseScreen(
    destination: Destination,
    state: CaptureUiState,
    capture: CaptureManager,
    review: BookViewModel,
    dataSettings: DataSettingsViewModel?,
    onPermission: () -> Unit,
    back: () -> Unit,
    navigate: (Destination) -> Unit,
) {
    Scaffold(topBar = {
        Surface {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).heightIn(min = 64.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (destination != Destination.WELCOME) TextButton(onClick = back) {
                    Text(stringResource(R.string.back))
                }
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            }
        }
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)
            .imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(stringResource(destination.title), style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(destination.description), style = MaterialTheme.typography.bodyLarge)
            if (destination == Destination.WELCOME || destination == Destination.SETTINGS) {
                StorageCard(if (state.loading) StorageStatus.CHECKING else if (state.storageError) StorageStatus.UNAVAILABLE else StorageStatus.READY,
                    capture::refresh)
            }
            if (destination == Destination.WELCOME) {
                ConsentForm(state.snapshot.consentAccepted, onConsent = {
                    capture.acceptConsent(); navigate(Destination.CONNECTION)
                }, onExplore = { navigate(Destination.DASHBOARD) })
            } else {
                if (destination in setOf(Destination.CONNECTION, Destination.SETTINGS, Destination.DASHBOARD)) {
                    CaptureStatus(state.snapshot, compact = destination == Destination.DASHBOARD)
                }
                Text(stringResource(R.string.estimates_notice), style = MaterialTheme.typography.bodyMedium)
                if (destination == Destination.DASHBOARD) {
                    val bookState by review.state.collectAsStateWithLifecycle()
                    DashboardScreen(bookState, review)
                    Destination.entries.filter { it != Destination.WELCOME && it != Destination.DASHBOARD }
                        .forEach { target ->
                            OutlinedButton(onClick = { navigate(target) }, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(target.title))
                            }
                        }
                } else if (destination == Destination.CONNECTION || destination == Destination.SETTINGS) {
                    if (destination == Destination.SETTINGS && dataSettings != null) DataSettingsScreen(dataSettings)
                    CaptureControls(state, onPermission, capture::refresh, capture::activate, capture::pause)
                    SourceSelection(state, capture::configureSource)
                    CaptureDiagnostics(state.snapshot, capture::syntheticTest)
                    val bookState by review.state.collectAsStateWithLifecycle()
                    SourceActivity(bookState, state)
                } else if (destination == Destination.REVIEW) {
                    val reviewState by review.state.collectAsStateWithLifecycle()
                    ReviewBookScreen(reviewState, review)
                } else if (destination == Destination.ACCOUNTS || destination == Destination.SETUP) {
                    val bookState by review.state.collectAsStateWithLifecycle()
                    AccountsBookScreen(bookState, review)
                } else if (destination == Destination.MOVEMENTS) {
                    val bookState by review.state.collectAsStateWithLifecycle()
                    TransactionsBookScreen(bookState, review)
                } else if (destination == Destination.BUDGETS) {
                    val bookState by review.state.collectAsStateWithLifecycle()
                    BudgetsScreen(bookState, review)
                } else {
                    Text(stringResource(R.string.pending_feature), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun StorageCard(status: StorageStatus, retry: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(when (status) {
                StorageStatus.CHECKING -> R.string.storage_checking
                StorageStatus.READY -> R.string.storage_ready
                StorageStatus.UNAVAILABLE -> R.string.storage_error
            }), style = MaterialTheme.typography.bodyLarge)
            if (status == StorageStatus.CHECKING) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (status == StorageStatus.UNAVAILABLE) {
                Text(stringResource(R.string.storage_error_body))
                OutlinedButton(onClick = retry) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

