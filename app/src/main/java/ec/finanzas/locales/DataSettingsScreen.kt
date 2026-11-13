package ec.finanzas.locales

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun DataSettingsScreen(model: DataSettingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var exportConfirm by rememberSaveable { mutableStateOf(false) }
    var eraseConfirm by rememberSaveable { mutableStateOf(false) }
    var eraseAcknowledged by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"), model::export)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Tus datos", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Exporta todos los movimientos confirmados y vigentes, incluidas operaciones propias. Se excluyen pendientes, pruebas y borrados. El CSV no es un respaldo de la app.")
            OutlinedButton(onClick = { exportConfirm = true }, enabled = !state.busy) { Text("Exportar movimientos CSV") }
            Text("El borrado completo elimina cuentas, movimientos, observaciones, reglas, presupuestos, claves y ajustes. Los CSV guardados fuera de la app permanecen.")
            OutlinedButton(onClick = { eraseAcknowledged = false; eraseConfirm = true }, enabled = !state.busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Eliminar todos los datos") }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (state.erasing) "Borrado solicitado a Android. La app se cerrará; vuelve a abrirla para empezar sin datos." else "Guardando CSV…")
            }
            state.message?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            state.error?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (exportConfirm) AlertDialog(onDismissRequest = { exportConfirm = false },
        title = { Text("Exportar datos sensibles") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text("El CSV contiene importes, alias y notas en texto claro. Cualquier app o persona con acceso al destino podrá leerlo. Elige una ubicación de confianza. No incluye mensajes de notificaciones ni claves.") } },
        confirmButton = { TextButton(onClick = {
            exportConfirm = false
            launcher.launch("finanzas-movimientos-${LocalDate.now(ZoneId.of("America/Guayaquil"))}.csv")
        }) { Text("Elegir destino") } },
        dismissButton = { TextButton(onClick = { exportConfirm = false }) { Text("Cancelar") } })
    if (eraseConfirm) AlertDialog(onDismissRequest = { eraseConfirm = false },
        title = { Text("¿Eliminar todos los datos?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Esta acción es irreversible. Android limpiará el almacenamiento de la app y la cerrará. Al abrirla de nuevo tendrás que aceptar el consentimiento y seleccionar fuentes otra vez; no se recuperará historial previo. Los CSV externos no se borran.")
            Row {
                Checkbox(checked = eraseAcknowledged, onCheckedChange = { eraseAcknowledged = it },
                    modifier = Modifier.semantics { contentDescription = "Entiendo que el borrado es irreversible" })
                Text("Entiendo que no se puede deshacer", Modifier.padding(top = 12.dp))
            }
        } },
        confirmButton = { TextButton(onClick = { eraseConfirm = false; model.erase() }, enabled = eraseAcknowledged && !state.busy,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Borrar definitivamente") } },
        dismissButton = { TextButton(onClick = { eraseConfirm = false }) { Text("Conservar mis datos") } })
}
