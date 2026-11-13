package ec.finanzas.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ec.finanzas.model.*
import java.time.Instant
import java.time.format.DateTimeFormatter

@Composable
fun ConsentForm(saved: Boolean, onConsent: () -> Unit, onExplore: () -> Unit) {
    var accepted by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.consent_text))
    if (saved) Text(stringResource(R.string.consent_saved))
    else Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = accepted, role = Role.Checkbox,
        onValueChange = { accepted = it })) {
        Checkbox(checked = accepted, onCheckedChange = null)
        Text(stringResource(R.string.consent_label), modifier = Modifier.padding(top = 12.dp))
    }
    Button(onClick = onConsent, enabled = saved || accepted, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.consent_continue))
    }
    OutlinedButton(onClick = onExplore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.explore)) }
}

@Composable
fun CaptureStatus(snapshot: CaptureSnapshot, compact: Boolean = false) {
    Text(stringResource(R.string.state_title), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(when (snapshot.state) {
        CaptureState.NOT_ACTIVATED -> R.string.state_none
        CaptureState.ACTIVE -> R.string.state_active
        CaptureState.PAUSED -> R.string.state_paused
        CaptureState.PERMISSION_REVOKED -> R.string.state_revoked
        CaptureState.DISCONNECTED -> R.string.state_disconnected
    }))
    if (!compact) {
        if (snapshot.mayHaveGaps) Text(stringResource(R.string.gaps))
        Text(stringResource(R.string.phase_limit), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun CaptureControls(state: CaptureUiState, onPermission: () -> Unit, onRefresh: () -> Unit,
    onActivate: () -> Unit, onPause: () -> Unit) {
    if (state.actionError) Text(stringResource(R.string.error), color = MaterialTheme.colorScheme.error)
    Text(stringResource(if (state.snapshot.permissionGranted) R.string.permission_granted else R.string.permission_missing))
    OutlinedButton(onClick = onPermission, enabled = state.snapshot.consentAccepted,
        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.permission_open)) }
    Text(stringResource(R.string.restricted_help), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.refresh)) }
    if (state.snapshot.requestedActive) {
        Button(onClick = onPause, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pause)) }
    } else {
        val eligible = state.snapshot.consentAccepted && state.snapshot.permissionGranted &&
            state.snapshot.sources.any { it.enabled && it.available }
        Button(onClick = onActivate, enabled = eligible, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (state.snapshot.captureStartedAt == null) R.string.activate else R.string.resume))
        }
        if (!eligible) Text(stringResource(R.string.activation_help))
    }
}

@Composable
fun SourceSelection(state: CaptureUiState, configure: (DiscoveredSource, BankProvider?, Boolean, Boolean) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.source_help))
    if (!state.snapshot.consentAccepted) Text(stringResource(R.string.consent_first))
    OutlinedTextField(value = query, onValueChange = { query = it },
        label = { Text(stringResource(R.string.search_apps)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    val sources = state.discovered.filter { it.displayName.contains(query, true) || it.packageName.contains(query, true) }
        .sortedByDescending { candidate -> state.snapshot.sources.any { it.packageName == candidate.packageName && it.enabled } }
    if (sources.isEmpty()) Text(stringResource(R.string.no_sources))
    if (sources.size > 30) Text(stringResource(R.string.showing_limit))
    sources.take(30).forEach { source -> key(source.packageName) {
        SourceCard(source, state.snapshot.sources.firstOrNull { it.packageName == source.packageName },
            state.snapshot.consentAccepted, configure)
    } }
    state.snapshot.sources.filter { !it.available }.forEach { source ->
        Text(source.displayName, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.source_missing), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SourceCard(source: DiscoveredSource, selection: CaptureSource?, consent: Boolean,
    configure: (DiscoveredSource, BankProvider?, Boolean, Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var confirmOutlook by remember { mutableStateOf(false) }
    val outlook = selection?.channel == "OUTLOOK"
    val switchLabel = stringResource(R.string.source_enable) + ": " + source.displayName
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(source.displayName, style = MaterialTheme.typography.titleMedium)
            Text(source.packageName, style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { expanded = true }, enabled = consent) {
                    Text(if (outlook) stringResource(R.string.outlook) else selection?.provider?.displayName()
                        ?: stringResource(R.string.provider_choose))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    BankProvider.entries.forEach { provider ->
                        DropdownMenuItem(text = { Text(provider.displayName()) }, onClick = {
                            expanded = false; configure(source, provider, false, false)
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.outlook)) }, onClick = {
                        expanded = false; configure(source, null, true, false)
                    })
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.source_enable), modifier = Modifier.weight(1f).padding(end = 12.dp))
                Switch(checked = selection?.enabled == true, modifier = Modifier.semantics { contentDescription = switchLabel },
                    enabled = consent && (selection?.provider != null || outlook),
                    onCheckedChange = { enabled ->
                        if (enabled && outlook) confirmOutlook = true
                        else configure(source, selection?.provider, outlook, enabled)
                    })
            }
            selection?.lastSeenAt?.let {
                Text(stringResource(R.string.last_seen, DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                    .withZone(FINANCIAL_ZONE).format(Instant.ofEpochMilli(it))), style = MaterialTheme.typography.bodySmall)
            } ?: Text(stringResource(R.string.never_seen), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (confirmOutlook) AlertDialog(onDismissRequest = { confirmOutlook = false },
        title = { Text(stringResource(R.string.outlook_title)) }, text = { Text(stringResource(R.string.outlook_warning)) },
        confirmButton = { TextButton(onClick = { confirmOutlook = false; configure(source, null, true, true) }) {
            Text(stringResource(R.string.enable)) } },
        dismissButton = { TextButton(onClick = { confirmOutlook = false }) { Text(stringResource(R.string.cancel)) } })
}

@Composable
fun CaptureDiagnostics(snapshot: CaptureSnapshot, onSynthetic: () -> Unit) {
    Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Text(stringResource(R.string.observed, snapshot.observedCount))
    Text(stringResource(R.string.synthetic, snapshot.syntheticCount))
    Text(stringResource(R.string.queued, snapshot.queuedCount))
    Text(stringResource(R.string.discarded, snapshot.discardedCount))
    Text(stringResource(R.string.test_help), style = MaterialTheme.typography.bodyMedium)
    OutlinedButton(onClick = onSynthetic, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.test_button)) }
}

private fun BankProvider.displayName(): String = when (this) {
    BankProvider.PICHINCHA -> "Banco Pichincha"
    BankProvider.GUAYAQUIL -> "Banco Guayaquil"
    BankProvider.PRODUBANCO -> "Produbanco"
    BankProvider.DEUNA -> "Deuna"
    BankProvider.PEIGO -> "PeiGo · parser desactivado"
}
