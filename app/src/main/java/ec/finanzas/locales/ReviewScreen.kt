package ec.finanzas.locales

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import ec.finanzas.database.CandidateEntity
import ec.finanzas.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun ReviewScreen(state: ReviewUiState, model: ReviewViewModel) {
    Text("Revisa la evidencia antes de confirmar. Los candidatos todavía no modifican gastos ni saldos.")
    if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.error != null) OutlinedButton(onClick = model::retry, enabled = !state.busy) { Text("Reintentar") }
    state.message?.let { Text(it) }
    if (!state.loading && state.error == null && state.candidates.isEmpty()) Text("No hay candidatos pendientes. Las fuentes solo aportan notificaciones nuevas mientras la captura está activa.")
    state.candidates.take(30).forEach { candidate ->
        key(candidate.id) { CandidateCard(candidate, state, model) }
    }
    if (state.candidates.size > 30) Text("Se muestran los 30 pendientes más recientes. Al resolverlos aparecerán los anteriores.")
    if (state.candidates.isNotEmpty()) {
        HorizontalDivider()
        Text("Crear cuenta para revisión", style = MaterialTheme.typography.titleMedium)
        Text("Usa un alias, por ejemplo «Cuenta principal». No escribas números de cuenta. La gestión de saldos aún no está disponible.")
        var alias by rememberSaveable { mutableStateOf("") }
        var bank by rememberSaveable { mutableStateOf(BankProvider.PICHINCHA.name) }
        var instrument by rememberSaveable { mutableStateOf(InstrumentType.BANK_ACCOUNT.name) }
        OutlinedTextField(alias, { alias = it.take(40) }, label = { Text("Alias de cuenta") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Choice("Entidad", BankProvider.entries.map { it.name to it.name }, bank) { bank = it }
        Choice("Instrumento", InstrumentType.entries.map { it.name to instrumentLabel(it) }, instrument) { instrument = it }
        Button(onClick = { model.createAccount(alias, BankProvider.valueOf(bank), InstrumentType.valueOf(instrument)) }, enabled = !state.busy && alias.isNotBlank()) { Text("Crear cuenta") }
    }
}

@Composable
private fun CandidateCard(c: CandidateEntity, state: ReviewUiState, model: ReviewViewModel) {
    var amount by rememberSaveable(c.id) { mutableStateOf(c.amountMinor?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty()) }
    var account by rememberSaveable(c.id) { mutableStateOf(c.accountId) }
    var classification by rememberSaveable(c.id) { mutableStateOf(c.classification) }
    var direction by rememberSaveable(c.id) { mutableStateOf(c.direction) }
    var ignoring by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(c.id) { mutableStateOf(false) }
    val validAmount = amount.isBlank() || ec.finanzas.parsers.MoneyNormalizer.cents(amount) != null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(c.provider ?: if (c.parserVersion.startsWith("outlook")) "Correo experimental · entidad sin identificar" else "Entidad sin identificar", style = MaterialTheme.typography.titleMedium)
            Text(candidateLabel(c.candidateKind))
            Text(c.amountMinor?.let { "USD ${BigDecimal.valueOf(it, 2).toPlainString()}" } ?: "Importe por completar")
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Cerrar detalle" else "Revisar candidato") }
            if (expanded) {
            if (c.origin == "SYNTHETIC") Text("Prueba sintética: no es una operación real.")
            Text("Recibido: ${date(c.receivedAt)}")
            Text("Fecha de operación: ${c.occurredAt?.let(::date) ?: "sin evidencia inequívoca"}")
            Text("Comercio: ${c.merchant ?: "no identificado"}")
            Text("Confianza de extracción: ${(c.confidence * 100).toInt()} %. Requiere revisión.")
            Text(c.evidenceCodes.split(',').joinToString("\n") { evidenceReason(it) }, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(amount, { amount = it.take(24) }, label = { Text("Importe USD") }, singleLine = true,
                isError = !validAmount, supportingText = { Text(if (validAmount) "Ejemplo: 3,99. Sin separador de miles." else "Escribe un importe positivo, por ejemplo 3,99.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
            Choice("Cuenta", state.accounts.filter { c.provider == null || it.provider == c.provider }.map { it.id to it.alias }, account) { account = it }
            if (state.accounts.none { c.provider == null || it.provider == c.provider }) Text("Crea una cuenta de esta entidad al final de la bandeja.")
            Choice("Sentido", listOf("OUTGOING" to "Salida", "INCOMING" to "Entrada"), direction) { direction = it }
            Choice("Clasificación", TransactionKind.entries.filter { it != TransactionKind.UNKNOWN }.map { it.name to classificationLabel(it) }, classification) { classification = it }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val kind = classification?.let(TransactionKind::valueOf)
            val flow = direction?.let(MoneyDirection::valueOf)
            OutlinedButton(onClick = { model.save(c.id, amount, account, kind, flow, false) }, enabled = !state.busy && validAmount) { Text("Guardar cambios") }
            Button(onClick = { model.save(c.id, amount, account, kind, flow, true) },
                enabled = !state.busy && validAmount && amount.isNotBlank() && account != null && kind != null && flow != null) { Text("Confirmar candidato") }
            TextButton(onClick = { ignoring = true }, enabled = !state.busy) { Text("Ignorar candidato") }
            }
        }
    }
    if (ignoring) AlertDialog(onDismissRequest = { ignoring = false }, title = { Text("Ignorar candidato") },
        text = { Text("Se conservará la decisión y se retirará de los pendientes.") },
        confirmButton = { TextButton(onClick = { ignoring = false; model.ignore(c.id) }) { Text("Ignorar") } },
        dismissButton = { TextButton(onClick = { ignoring = false }) { Text("Volver") } })
}

@Composable
private fun Choice(label: String, options: List<Pair<String, String>>, selected: String?, select: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = options.isNotEmpty(), modifier = Modifier.fillMaxWidth().semantics { contentDescription = label }) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Seleccionar")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { select(value); open = false }) }
            }
        }
    }
}
private fun date(value: Long) = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm").withZone(ZoneId.of("America/Guayaquil")).format(Instant.ofEpochMilli(value))
private fun instrumentLabel(value: InstrumentType) = when (value) {
    InstrumentType.BANK_ACCOUNT -> "Cuenta bancaria"
    InstrumentType.WALLET -> "Billetera"
    InstrumentType.DEBIT_CARD -> "Tarjeta de débito"
    InstrumentType.CREDIT_CARD -> "Tarjeta de crédito"
}
private fun candidateLabel(value: String) = when (value) {
    "OUTGOING_TRANSFER" -> "Transferencia enviada"
    "INCOMING_TRANSFER" -> "Transferencia recibida; finalidad sin determinar"
    "DEBIT_PURCHASE" -> "Posible consumo con débito"
    "PAYMENT" -> "Pago"
    "WALLET_TOP_UP" -> "Recarga de billetera; origen sin determinar"
    else -> "Formato sin interpretar"
}
private fun classificationLabel(value: TransactionKind) = when (value) {
    TransactionKind.EXPENSE -> "Gasto"
    TransactionKind.INCOME -> "Ingreso"
    TransactionKind.OWN_TRANSFER -> "Transferencia propia"
    TransactionKind.CREDIT_CARD_PAYMENT -> "Pago de tarjeta"
    TransactionKind.REFUND -> "Devolución"
    TransactionKind.CREDIT_CARD_CHARGE -> "Compra a crédito"
    TransactionKind.WALLET_TOP_UP -> "Recarga de billetera"
    TransactionKind.UNKNOWN -> "Sin clasificar"
}
fun evidenceReason(code: String) = when (code) {
    "ALLOWED_SOURCE" -> "Fuente seleccionada por ti."
    "EXPLICIT_KIND" -> "El mensaje declara el tipo de operación."
    "EXPLICIT_DIRECTION" -> "El mensaje declara entrada o salida."
    "MISSING_ACCOUNT" -> "Falta vincular una cuenta."
    "UNKNOWN_PURPOSE" -> "La finalidad requiere tu clasificación."
    "UNAMBIGUOUS_AMOUNT" -> "Importe extraído sin contradicciones."
    "AMBIGUOUS_AMOUNT" -> "Importe ausente, inválido o contradictorio."
    "MISSING_OPERATION_DATE" -> "La fecha de operación no está probada."
    "TRUNCATED_CONTENT" -> "El contenido podría estar recortado."
    "EXPERIMENTAL_SOURCE" -> "Outlook es experimental; solo se leyó la notificación."
    "POSSIBLE_DUPLICATE" -> "Comprueba que no sea otra notificación de la misma operación."
    else -> "No hay un formato interpretado para esta evidencia."
}
