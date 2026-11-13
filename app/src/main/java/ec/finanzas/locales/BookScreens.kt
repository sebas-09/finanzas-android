package ec.finanzas.locales

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ec.finanzas.database.*
import ec.finanzas.ledger.*
import ec.finanzas.model.*
import ec.finanzas.dashboard.*
import java.util.UUID

@Composable
fun BookFeedback(s: BookUiState, vm: BookViewModel) {
    if (s.loading || s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
    s.message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (s.error != null && s.book == null) OutlinedButton(onClick = vm::retry) { Text("Reintentar libro") }
}

@Composable
fun AccountsBookScreen(s: BookUiState, vm: BookViewModel) {
    var editing by remember { mutableStateOf<FinancialAccountEntity?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    BookFeedback(s, vm)
    Text("El saldo inicial corresponde al momento anterior al primer movimiento registrado. Sin él se muestra solo la variación registrada.")
    Button(onClick = { creating = true }, enabled = !s.busy && !s.loading) { Text("Añadir cuenta o tarjeta") }
    val book = s.book
    if (book != null && book.accounts.isEmpty()) Text("Aún no hay cuentas. Añade un alias y el tipo de instrumento para comenzar.")
    book?.accounts?.forEach { a ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(a.alias, style = MaterialTheme.typography.titleMedium)
                Text("${a.provider} · ${instrumentLabel(accountType(a.kind))}${if (!a.active) " · Archivada" else ""}")
                val change = s.totals?.changes?.get(a.id) ?: 0L
                val balance = s.totals?.balances?.get(a.id)
                Text("Variación ${if (a.kind == "CREDIT_CARD") "de deuda" else "registrada"}: ${displayMoney(change)}")
                Text(if (balance == null) "Sin saldo inicial: saldo/deuda sin determinar." else "${if (a.kind == "CREDIT_CARD") "Saldo de tarjeta" else "Saldo"} estimado: ${displayMoney(balance)}")
                book.cards.firstOrNull { it.accountId == a.id }?.let { card ->
                    Text("Tarjeta ${if (card.type == "CREDIT") "de crédito" else "de débito"} · corte: ${card.billingDay ?: "sin definir"} · vencimiento: ${card.dueDay ?: "sin definir"}")
                }
                if (a.kind == "CREDIT_CARD") CreditSummary(a, book, s)
                OutlinedButton(onClick = { editing = a }, enabled = !s.busy) { Text("Editar cuenta") }
                TextButton(onClick = { vm.archive(a.id, !a.active) }, enabled = !s.busy) { Text(if (a.active) "Archivar" else "Reactivar") }
            }
        }
    }
    if (creating || editing != null) AccountEditor(s, vm, editing) { creating = false; editing = null }
}

@Composable
private fun AccountEditor(s: BookUiState, vm: BookViewModel, old: FinancialAccountEntity?, dismiss: () -> Unit) {
    val card = s.book?.cards?.firstOrNull { it.accountId == old?.id }
    var id by rememberSaveable { mutableStateOf(old?.id ?: UUID.randomUUID().toString()) }
    var alias by rememberSaveable { mutableStateOf(old?.alias.orEmpty()) }
    var provider by rememberSaveable { mutableStateOf(old?.provider ?: BankProvider.PICHINCHA.name) }
    var instrument by rememberSaveable { mutableStateOf(if (card?.type == "DEBIT") "DEBIT_CARD" else old?.let { accountType(it.kind).name } ?: "BANK_ACCOUNT") }
    var opening by rememberSaveable { mutableStateOf(old?.openingBalanceMinor?.let(::decimal).orEmpty()) }
    var billing by rememberSaveable { mutableStateOf(card?.billingDay?.toString().orEmpty()) }
    var due by rememberSaveable { mutableStateOf(card?.dueDay?.toString().orEmpty()) }
    var limit by rememberSaveable { mutableStateOf(card?.creditLimitMinor?.let(::decimal).orEmpty()) }
    var active by rememberSaveable { mutableStateOf(old?.active ?: true) }
    val completed = remember { s.completed }
    LaunchedEffect(s.completed) { if (s.completed > completed) dismiss() }
    BookDialog(if (old == null) "Nueva cuenta" else "Editar cuenta", s, dismiss) {
        Text("Usa un alias; no escribas números completos de cuenta o tarjeta.")
        BookText("Alias", alias, { alias = it.take(40) }, enabled = !s.busy)
        BookChoice("Entidad", BankProvider.entries.map { it.name to it.name }, provider, !s.busy) { provider = it }
        BookChoice("Tipo de instrumento", InstrumentType.entries.map { it.name to instrumentLabel(it) }, instrument, !s.busy) { instrument = it }
        val initialValid = opening.isBlank() || runCatching { cents(opening, false) }.isSuccess
        BookText(if (instrument == "CREDIT_CARD") "Deuda inicial USD (opcional)" else "Saldo inicial USD (opcional)", opening,
            { opening = it.take(24) }, !initialValid, "Vacío significa desconocido; 0 indica saldo inicial cero.", !s.busy, true)
        if (instrument in setOf("CREDIT_CARD", "DEBIT_CARD")) {
            if (instrument == "DEBIT_CARD") Text("La tarjeta de débito usa el saldo de esta cuenta; no crea un segundo saldo.")
            BookText("Día de corte (opcional)", billing, { billing = it.filter(Char::isDigit).take(2) }, enabled = !s.busy)
            BookText("Día de vencimiento (opcional)", due, { due = it.filter(Char::isDigit).take(2) }, enabled = !s.busy)
            if (instrument == "CREDIT_CARD") BookText("Límite USD (opcional)", limit, { limit = it.take(24) }, enabled = !s.busy, numeric = true)
        }
        BookCheck("Cuenta activa", active, !s.busy) { active = it }
        Button(onClick = { vm.account(id, alias, BankProvider.valueOf(provider), InstrumentType.valueOf(instrument), opening, active, billing, due, limit) },
            enabled = !s.busy && alias.isNotBlank() && initialValid) { Text("Guardar cuenta") }
    }
}

@Composable
fun TransactionsBookScreen(s: BookUiState, vm: BookViewModel) {
    var selected by remember { mutableStateOf<TransactionEntity?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TransactionEntity?>(null) }
    var detail by remember { mutableStateOf<TransactionEntity?>(null) }
    var limit by rememberSaveable { mutableStateOf(50) }
    BookFeedback(s, vm)
    s.totals?.let { t ->
        Text("Todo el período registrado", style = MaterialTheme.typography.titleMedium)
        Text("Gasto de consumo neto: ${displayMoney(t.expense)}\nIngresos externos: ${displayMoney(t.income)}\nDiferencia: ${displayMoney(t.net)}")
        Text("Transferencias propias, recargas y pagos de tarjeta no suman consumo. Las devoluciones reducen el gasto original.")
    }
    Button(onClick = { creating = true }, enabled = !s.loading && !s.busy) { Text("Registrar movimiento manual") }
    BookCheck("Ver borrados y vínculos anteriores", showRemoved) { showRemoved = it }
    val records = filteredMovements(s.book, showRemoved)
    if (!s.loading && records.isEmpty()) Text("No hay movimientos contabilizados. Registra uno manualmente o completa un candidato por revisar.")
    records.take(limit).forEach { t ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${kindLabel(TransactionKind.valueOf(t.kind))} · ${displayMoney(t.amountMinor)}", style = MaterialTheme.typography.titleMedium)
                Text("${s.book?.accounts?.firstOrNull { it.id == t.accountId }?.alias ?: "Cuenta sin definir"} · ${if (t.direction == "INCOMING") "Entrada" else "Salida"}")
                Text(localDate(t.occurredAt).replace('T', ' ') + if (t.estimatedDate) " · Fecha estimada" else "")
                Text("Origen: ${if (t.origin == "MANUAL") "manual" else "notificación revisada"} · ${s.book?.observations?.count { it.transactionId == t.id } ?: 0} avisos vinculados")
                t.merchantAlias?.let { Text("Comercio: $it") }; t.note?.let { Text("Nota: $it") }
                Text("Categoría: ${s.book?.categories?.firstOrNull { it.id == t.categoryId }?.name ?: "Sin categoría"}")
                TextButton(onClick = { detail = t }) { Text("Ver detalle y procedencia") }
                if (t.deletedAt != null) Text(if (t.mergedIntoId == null) "Borrado del cómputo" else "Vinculado a otra operación; sus efectos no se duplican")
                if (included(t)) {
                    OutlinedButton(onClick = { selected = t }, enabled = !s.busy) { Text("Editar movimiento") }
                    TextButton(onClick = { deleting = t }, enabled = !s.busy) { Text("Borrar movimiento") }
                }
                TextButton(onClick = { vm.undo(t) }, enabled = !s.busy) { Text("Deshacer última acción") }
            }
        }
    }
    if (records.size > limit) OutlinedButton(onClick = { limit += 50 }) { Text("Mostrar más movimientos") }
    detail?.let { t -> MovementDetail(t, s, vm) { detail = null } }
    ReconcileBookSection(s, vm)
    if (creating || selected != null) OperationEditor(s, vm, selected, null) { creating = false; selected = null }
    deleting?.let { t -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Borrar movimiento") },
        text = { Text("Se excluirán sus efectos y se recalcularán los saldos. Puedes deshacer la acción.") },
        confirmButton = { TextButton(onClick = { deleting = null; vm.delete(t) }) { Text("Borrar") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Volver") } }) }
}

@Composable
fun ReviewBookScreen(s: BookUiState, vm: BookViewModel) {
    var candidate by remember { mutableStateOf<CandidateEntity?>(null) }
    var showIgnored by rememberSaveable { mutableStateOf(false) }
    BookFeedback(s, vm)
    Text("Comprueba importe, cuenta, finalidad y fecha. Un aviso por sí solo no prueba que una transferencia sea ingreso o gasto.")
    val pending = s.book?.pending.orEmpty()
    if (!s.loading && pending.isEmpty()) Text("No hay candidatos pendientes.")
    pending.take(30).forEach { c ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${c.provider ?: "Entidad sin identificar"} · ${c.amountMinor?.let(::displayMoney) ?: "Importe por completar"}", style = MaterialTheme.typography.titleMedium)
                Text("Recibido: ${localDate(c.receivedAt).replace('T', ' ')}")
                Text("Fecha propuesta: ${c.occurredAt?.let { localDate(it).replace('T', ' ') } ?: "sin fecha inequívoca"}${if (c.estimatedDate) " · estimada" else ""}")
                Text("Comercio: ${c.merchant ?: "sin identificar"}")
                Text("Evidencia de la extracción (confianza ${(c.confidence * 100).toInt()}\u00a0%):", style = MaterialTheme.typography.labelLarge)
                Text(c.evidenceCodes.split(',').joinToString("\n") { evidenceReason(it) }, style = MaterialTheme.typography.bodySmall)
                Text(if (c.parserVersion.startsWith("outlook")) "Outlook experimental: solo extras visibles de la notificación." else "Cobertura preliminar; comprueba los datos en tu aplicación bancaria.")
                if (c.origin == "SYNTHETIC") Text("Prueba técnica: no se contabiliza ni altera saldos.")
                else Button(onClick = { candidate = c }, enabled = !s.busy) { Text("Completar operación o vincular") }
                TextButton(onClick = { vm.ignore(c.id) }, enabled = !s.busy) { Text("Ignorar candidato") }
            }
        }
    }
    if (pending.size > 30) Text("Se muestran los 30 más recientes. Al resolverlos aparecerán los anteriores.")
    BookCheck("Ver candidatos ignorados", showIgnored) { showIgnored = it }
    if (showIgnored) s.book?.ignored?.forEach { c ->
        Text("${c.provider ?: "Sin entidad"} · ${localDate(c.receivedAt).replace('T', ' ')}")
        OutlinedButton(onClick = { vm.reopen(c.id) }, enabled = !s.busy) { Text("Reabrir candidato") }
    }
    Text("Para separar un vínculo o deshacer una clasificación ya contabilizada, abre Movimientos y usa Deshacer última acción.")
    candidate?.let { c -> OperationEditor(s, vm, null, c) { candidate = null } }
}

@Composable
fun OperationEditor(s: BookUiState, vm: BookViewModel, old: TransactionEntity?, candidate: CandidateEntity?, dismiss: () -> Unit) {
    val book = s.book ?: return
    val c = candidate
    val id = rememberSaveable { old?.id ?: c?.let { "candidate:${it.id}" } ?: UUID.randomUUID().toString() }
    var account by rememberSaveable { mutableStateOf(old?.accountId ?: c?.accountId.orEmpty()) }
    var counter by rememberSaveable { mutableStateOf(old?.counterAccountId ?: c?.counterAccountId.orEmpty()) }
    var kind by rememberSaveable { mutableStateOf(old?.kind ?: c?.classification ?: "UNKNOWN") }
    var ownDirection by rememberSaveable { mutableStateOf(old?.direction ?: c?.direction ?: "OUTGOING") }
    var amount by rememberSaveable { mutableStateOf((old?.amountMinor ?: c?.amountMinor)?.let(::decimal).orEmpty()) }
    var occurred by rememberSaveable { mutableStateOf((old?.occurredAt ?: c?.occurredAt ?: if (c == null) System.currentTimeMillis() else null)?.let(::localDate).orEmpty()) }
    var estimated by rememberSaveable { mutableStateOf(old?.estimatedDate ?: c?.estimatedDate ?: false) }
    var merchant by rememberSaveable { mutableStateOf(old?.merchantAlias ?: c?.merchant.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(old?.note ?: c?.note.orEmpty()) }
    var original by rememberSaveable { mutableStateOf(old?.originalTransactionId ?: c?.originalTransactionId.orEmpty()) }
    var category by rememberSaveable { mutableStateOf(old?.categoryId ?: c?.categoryId.orEmpty()) }
    var categoryChanged by rememberSaveable { mutableStateOf(old != null || c?.categoryDecided == true) }
    var separate by rememberSaveable { mutableStateOf(false) }
    var correction by rememberSaveable { mutableStateOf(false) }
    var target by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("SAME") }
    var window by rememberSaveable { mutableStateOf("5") }
    val completed = remember { s.completed }
    LaunchedEffect(s.completed) { if (s.completed > completed) dismiss() }
    val primary = book.accounts.firstOrNull { it.id == account }
    val selectedKind = TransactionKind.valueOf(kind)
    val direction = LedgerRules.direction(selectedKind, primary?.let { accountType(it.kind) } ?: InstrumentType.BANK_ACCOUNT, MoneyDirection.valueOf(ownDirection))
    val money = runCatching { cents(amount) }.getOrNull()
    val at = runCatching { dateMillis(occurred) }.getOrNull()
    val interval = window.toIntOrNull()?.takeIf { it in 1..1440 }
    val source = book.observations.firstOrNull { it.id == c?.id }
    val primaryInstrument = primary?.let { accountType(it.kind).name }
    val mismatch = c != null && primary != null && ((c.provider != null && c.provider != primary.provider) ||
        (source?.direction != null && source.direction != direction.name) || (c.instrument != null && c.instrument != primaryInstrument && !(c.instrument == "DEBIT_CARD" && primaryInstrument == "BANK_ACCOUNT")))
    val targets = book.transactions.filter { included(it) && money != null && it.amountMinor == money && at != null && interval != null &&
        kotlin.math.abs(it.occurredAt - at) <= interval * 60_000L }
    val selectedTarget = targets.firstOrNull { it.id == target }
    BookDialog(if (old != null) "Editar movimiento" else if (c != null) "Revisar operación" else "Movimiento manual", s, dismiss) {
        if (c != null) Text("El texto original no se guardó. Comprueba los datos en la fuente; conserva la fecha desconocida hasta poder completarla.")
        BookChoice("Cuenta principal / del aviso", book.accounts.filter { it.active || it.id == old?.accountId }.map { it.id to it.alias }, account, !s.busy) { account = it }
        BookChoice("Clasificación", TransactionKind.entries.map { it.name to kindLabel(it) }, kind, !s.busy) { kind = it }
        if (primary?.kind == "CREDIT_CARD") Text("Usa Compra a crédito para consumos y Pago de tarjeta para reducir deuda.")
        BookText("Importe USD", amount, { amount = it.take(24) }, amount.isNotBlank() && money == null, "Positivo, sin separadores de miles; ejemplo 3,99.", !s.busy, true)
        BookText("Fecha de operación (Guayaquil)", occurred, { occurred = it.take(30) }, occurred.isNotBlank() && at == null,
            "AAAA-MM-DDTHH:MM:SS. La recepción no sustituye automáticamente a la operación.", !s.busy)
        if (c != null) TextButton(onClick = { occurred = localDate(c.receivedAt); estimated = true }, enabled = !s.busy) { Text("Usar recepción como fecha estimada") }
        BookCheck("Fecha estimada", estimated, !s.busy) { estimated = it }
        if (selectedKind == TransactionKind.OWN_TRANSFER) BookChoice("Sentido en la cuenta principal", listOf("OUTGOING" to "Salida", "INCOMING" to "Entrada"), ownDirection, !s.busy) { ownDirection = it }
        else Text("Sentido en la cuenta principal: ${if (direction == MoneyDirection.INCOMING) "Entrada" else "Salida"}")
        if (selectedKind in BookStore.INTERNAL_KINDS) BookChoice("Otra cuenta propia", book.accounts.filter { it.id != account && (it.active || it.id == old?.counterAccountId) }
            .map { it.id to it.alias }, counter, !s.busy) { counter = it }
        if (selectedKind == TransactionKind.REFUND) BookChoice("Compra original", book.transactions.filter { included(it) && it.accountId == account && it.kind in setOf("EXPENSE", "CREDIT_CARD_CHARGE") }
            .map { it.id to transactionLabel(it, book) }, original, !s.busy) { original = it }
        if (target.isBlank()) {
            BookText("Comercio (opcional)", merchant, { merchant = it.take(80) }, enabled = !s.busy)
            BookChoice("Categoría", listOf("" to "Sin categoría") + book.categories.filter { it.active || it.id == category }.map { it.id to it.name }, category, !s.busy && selectedKind != TransactionKind.REFUND) {
                category = it; categoryChanged = true
            }
            if (selectedKind == TransactionKind.REFUND) Text("La devolución usa la categoría de su compra original.")
            val suggestion = Insights.rule(merchant, book.rules.filter { rule -> rule.categoryId == null || book.categories.any { it.id == rule.categoryId && it.active } }.map(::ruleSpec))
            if (suggestion != null) {
                Text("Sugerencia de regla: ${suggestion.alias} · ${book.categories.firstOrNull { it.id == suggestion.categoryId }?.name ?: "Sin categoría"}")
                TextButton(onClick = { merchant = suggestion.alias; category = suggestion.categoryId.orEmpty(); categoryChanged = true }, enabled = !s.busy) { Text("Usar sugerencia") }
                if (!categoryChanged && old == null) Text("Al guardar se aplicará esta categoría. Puedes elegir otra o Sin categoría.")
            }
            BookText("Nota manual (opcional)", note, { note = it.take(500) }, enabled = !s.busy)
        } else Text("Al vincular se conservan fecha, comercio y nota de la operación elegida. Para corregirlos usa Editar movimiento.")
        if (c != null) {
            if (mismatch) BookCheck("Confirmo corregir cuenta, instrumento o sentido respecto del aviso", correction, !s.busy) { correction = it }
            BookText("Ventana de conciliación en minutos", window, { window = it.filter(Char::isDigit).take(4) }, interval == null, "Entre 1 y 1440; se aplica a esta decisión.", !s.busy, true)
            BookChoice("Ya estaba registrado", listOf("" to "Crear una nueva operación") + targets.map { it.id to transactionLabel(it, book) }, target, !s.busy) { target = it }
            Text("Las coincidencias por importe y fecha son sugerencias: revisa cuenta, finalidad y procedencia antes de vincular.")
            if (target.isNotBlank()) BookChoice("Relación del aviso", relations(), relation, !s.busy) { relation = it }
            else BookCheck("Confirmo que es una operación distinta de otras coincidencias cercanas", separate, !s.busy) { separate = it }
            if (target.isBlank()) OutlinedButton(onClick = { vm.draft(c.id, money, account.ifBlank { null }, selectedKind.takeIf { it != TransactionKind.UNKNOWN }, direction,
                at, counter.ifBlank { null }, original.ifBlank { null }, merchant.ifBlank { null }, note.ifBlank { null }, estimated, category.ifBlank { null }, categoryChanged) },
                enabled = !s.busy && (amount.isBlank() || money != null) && (occurred.isBlank() || at != null)) { Text("Guardar borrador") }
        }
        val valid = primary != null && money != null && at != null && at > 0 && (!mismatch || correction) &&
            (if (target.isNotBlank()) selectedTarget != null && interval != null else selectedKind != TransactionKind.UNKNOWN &&
                (selectedKind !in BookStore.INTERNAL_KINDS || counter.isNotBlank()) && (selectedKind != TransactionKind.REFUND || original.isNotBlank()))
        Button(onClick = {
            val draft = OperationDraft(id, account, money!!, selectedKind, at!!, direction,
                if (selectedKind in BookStore.INTERNAL_KINDS) counter.ifBlank { null } else null, merchant.ifBlank { null }, note.ifBlank { null },
                if (selectedKind == TransactionKind.REFUND) original.ifBlank { null } else null, c?.id, old?.revision, estimated, correction, category.ifBlank { null }, categoryChanged)
            if (selectedTarget != null) vm.link(draft, selectedTarget, if (relation == "SAME") null else TransactionKind.valueOf(relation), interval!!)
            else vm.save(draft, separate)
        }, enabled = !s.busy && valid) { Text(if (target.isNotBlank()) "Vincular aviso" else "Contabilizar operación") }
    }
}

@Composable
private fun ReconcileBookSection(s: BookUiState, vm: BookViewModel) {
    val book = s.book ?: return
    val live = book.transactions.filter(::included)
    if (live.size < 2) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }) { Text("Conciliar operaciones ya registradas") }
    if (!expanded) return
    var first by rememberSaveable { mutableStateOf("") }; var second by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("SAME") }; var window by rememberSaveable { mutableStateOf("5") }
    val a = live.firstOrNull { it.id == first }; val b = live.firstOrNull { it.id == second }
    BookChoice("Operación que se conservará", live.map { it.id to transactionLabel(it, book) }, first, !s.busy) { first = it }
    BookChoice("Otra operación", live.filter { it.id != first }.map { it.id to transactionLabel(it, book) }, second, !s.busy) { second = it }
    BookChoice("Relación entre operaciones", relations(), relation, !s.busy) { relation = it }
    BookText("Ventana de conciliación en minutos", window, { window = it.filter(Char::isDigit).take(4) }, numeric = true)
    Text("La otra operación conservará su procedencia, pero sus efectos se sustituirán por una sola operación. Deshacer restaura ambas.")
    Button(onClick = { vm.merge(a!!, b!!, if (relation == "SAME") null else TransactionKind.valueOf(relation), window.toInt()) },
        enabled = !s.busy && a != null && b != null && (window.toIntOrNull() ?: 0) in 1..1440) { Text("Confirmar conciliación") }
}

@Composable
fun BookDialog(title: String, s: BookUiState, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = { if (!s.busy) dismiss() }) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().heightIn(max = 700.dp).imePadding()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                s.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }
                content()
                TextButton(onClick = dismiss, enabled = !s.busy) { Text("Cerrar formulario") }
            }
        }
    }
}
@Composable
fun BookChoice(label: String, options: List<Pair<String, String>>, selected: String?, enabled: Boolean = true, choose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = label
                stateDescription = options.firstOrNull { it.first == selected }?.second ?: "Sin selección"
            }) {
                Text(options.firstOrNull { it.first == selected }?.second ?: "Seleccionar")
            }
            DropdownMenu(open, onDismissRequest = { open = false }) { options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { choose(id); open = false }) } }
        }
    }
}
@Composable
fun BookText(label: String, value: String, change: (String) -> Unit, error: Boolean = false, help: String = "", enabled: Boolean = true, numeric: Boolean = false) {
    OutlinedTextField(value, change, label = { Text(label) }, isError = error, supportingText = { if (help.isNotEmpty()) Text(help) }, enabled = enabled,
        singleLine = label != "Nota manual (opcional)", keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text, imeAction = ImeAction.Done), modifier = Modifier.fillMaxWidth())
}
@Composable
fun BookCheck(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)) {
        Checkbox(checked, null, enabled = enabled); Text(label, Modifier.weight(1f).padding(top = 10.dp))
    }
}
private fun instrumentLabel(kind: InstrumentType) = when (kind) {
    InstrumentType.BANK_ACCOUNT -> "Cuenta bancaria"
    InstrumentType.WALLET -> "Billetera"
    InstrumentType.DEBIT_CARD -> "Cuenta con tarjeta de débito"
    InstrumentType.CREDIT_CARD -> "Tarjeta de crédito"
}
private fun relations() = listOf("SAME" to "Misma operación (dos avisos)") + BookStore.INTERNAL_KINDS.map { it.name to kindLabel(it) }
fun displayMoney(value: Long) = "USD " + '$' + decimal(value)
private fun transactionLabel(t: TransactionEntity, book: BookSnapshot) = "${kindLabel(TransactionKind.valueOf(t.kind))} · ${book.accounts.firstOrNull { it.id == t.accountId }?.alias ?: "Sin cuenta"} · ${displayMoney(t.amountMinor)} · ${localDate(t.occurredAt).replace('T', ' ')}"
