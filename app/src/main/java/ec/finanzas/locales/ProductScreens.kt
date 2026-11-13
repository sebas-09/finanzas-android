package ec.finanzas.locales

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import ec.finanzas.database.*
import ec.finanzas.dashboard.*
import ec.finanzas.model.*
import java.time.*
import java.math.BigInteger
import java.util.UUID

@Composable
fun SourceActivity(s: BookUiState, capture: CaptureUiState) {
    Text("Actividad financiera por fuente", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (capture.snapshot.sources.isEmpty()) Text("Todavía no hay fuentes seleccionadas.")
    capture.snapshot.sources.forEach { source ->
        val last = s.book?.observations?.filter { it.sourcePackage == source.packageName && it.origin == "NOTIFICATION" && !it.parserVersion.startsWith("capture-") }?.maxOfOrNull { it.receivedAt }
        Text("${source.displayName} · ${if (source.enabled) "Habilitada" else "Deshabilitada"}${if (!source.available) " · No disponible" else ""}")
        Text("Último candidato financiero procesado ${last?.let(::localDate) ?: "sin actividad registrada"}")
    }
    Text("Motivos técnicos de descarte o incidencia", style = MaterialTheme.typography.titleMedium)
    val diagnostics = s.book?.diagnostics.orEmpty()
    if (diagnostics.isEmpty()) Text("Sin incidencias registradas.")
    diagnostics.forEach { Text("${it.reason} · ${it.count}") }
    Text("Los mensajes bancarios se procesan temporalmente. Se conservan metadatos cifrados y decisiones locales. Exportación y eliminación completa estarán disponibles en la fase de privacidad y entrega.")
}

@Composable
fun monthField(value: String, change: (String) -> Unit): YearMonth? {
    val month = runCatching { YearMonth.parse(value) }.getOrNull()
    BookText("Mes (AAAA-MM)", value, change, month == null, if (month == null) "Escribe un mes válido como 2026-11." else "Fechas de America/Guayaquil.")
    return month
}

@Composable
fun DashboardScreen(s: BookUiState, vm: BookViewModel) {
    BookFeedback(s, vm)
    var value by rememberSaveable { mutableStateOf(YearMonth.now(FINANCIAL_ZONE).toString()) }
    val month = monthField(value) { value = it.take(7) } ?: return
    val book = s.book ?: return
    val records = remember(book) { book.insightRecords() }
    val summary = remember(records, month) { runCatching { Insights.month(month, records) } }
    val totals = summary.getOrNull()
    if (totals == null) { Text("El resumen supera el rango permitido. Revisa los importes registrados."); return }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Resumen de $month", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Gasto de consumo neto ${displayMoney(totals.expense)}")
            Text("Ingresos externos ${displayMoney(totals.income)}")
            Text("Diferencia ingresos menos gastos ${displayMoney(totals.net)}")
            Text("Las devoluciones reducen consumo en su mes de registro. Transferencias propias, recargas y pagos de tarjeta se excluyen de consumo e ingresos.")
            if (totals.estimatedDates > 0) Text("${totals.estimatedDates} operaciones del mes usan fecha estimada.")
            Text("${book.pending.count { it.origin != "SYNTHETIC" }} avisos pendientes de revisión; excluidos de estos totales.")
        }
    }
    Text("Consumo por categoría", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    if (totals.categories.isEmpty()) Text("Sin consumo contabilizado en este mes.")
    totals.categories.entries.sortedByDescending { it.value }.forEach { (id, amount) ->
        Text("${book.categories.firstOrNull { it.id == id }?.name ?: "Sin categoría"} · ${displayMoney(amount)}")
    }
    Text("Saldos al cierre del mes", style = MaterialTheme.typography.titleMedium)
    Text("Estimaciones de las operaciones registradas hasta el cierre seleccionado. La captura puede tener huecos.")
    val end = month.plusMonths(1).atDay(1).atStartOfDay(FINANCIAL_ZONE).toInstant().toEpochMilli()
    book.accounts.forEach { account ->
        val changes = records.filter { it.operation.included && it.occurredAt < end }.flatMap { it.operation.effects }
            .filter { it.accountId == account.id }.fold(BigInteger.ZERO) { total, effect -> total + effect.delta.toBigInteger() }
        val balance = runCatching { Insights.exact(changes + (account.openingBalanceMinor ?: 0).toBigInteger()) }.getOrNull()
        val label = if (account.kind == "CREDIT_CARD") "deuda" else "saldo"
        Text("${account.alias} · ${if (account.openingBalanceMinor == null) "Variación registrada de $label" else "$label estimado"} ${balance?.let(::displayMoney) ?: "fuera de rango"}")
    }
    Text("Movimientos recientes del mes", style = MaterialTheme.typography.titleMedium)
    val recent = Insights.filter(records.filter { it.operation.included }, MovementFilter(from = month.atDay(1), through = month.atEndOfMonth()), book.accounts.associate { it.id to it.alias }).take(5)
    if (recent.isEmpty()) Text("Todavía no hay movimientos en este mes.")
    recent.forEach { record -> Text("${localDate(record.occurredAt).replace('T', ' ')} · ${kindLabel(record.operation.kind)} · ${displayMoney(record.operation.amount)}") }
}

@Composable
fun filteredMovements(book: BookSnapshot?, showRemoved: Boolean): List<TransactionEntity> {
    var search by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable { mutableStateOf("") }; var through by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("") }; var account by rememberSaveable { mutableStateOf("") }
    var instrument by rememberSaveable { mutableStateOf("") }; var category by rememberSaveable { mutableStateOf("ALL") }
    var origin by rememberSaveable { mutableStateOf("") }; var status by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("") }
    BookText("Buscar comercio o alias de cuenta", search, { search = it.take(80) })
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar filtros" else "Filtrar movimientos") }
    val first = runCatching { LocalDate.parse(from) }.getOrNull()
    val last = runCatching { LocalDate.parse(through) }.getOrNull()
    val valid = (from.isBlank() || first != null) && (through.isBlank() || last != null) && (first == null || last == null || first <= last)
    if (expanded) {
        BookText("Desde (AAAA-MM-DD)", from, { from = it.take(10) }, from.isNotBlank() && first == null)
        BookText("Hasta inclusive (AAAA-MM-DD)", through, { through = it.take(10) }, through.isNotBlank() && last == null)
        BookChoice("Banco o entidad", listOf("" to "Todos") + BankProvider.entries.map { it.name to it.name }, provider) { provider = it }
        BookChoice("Cuenta o tarjeta", listOf("" to "Todas") + book?.accounts.orEmpty().map { it.id to it.alias }, account) { account = it }
        BookChoice("Instrumento", listOf("" to "Todos", "BANK_ACCOUNT" to "Cuenta bancaria", "DEBIT_CARD" to "Tarjeta de débito", "CREDIT_CARD" to "Tarjeta de crédito", "WALLET" to "Billetera"), instrument) { instrument = it }
        BookChoice("Filtrar categoría", listOf("ALL" to "Todas", "" to "Sin categoría") + book?.categories.orEmpty().map { it.id to it.name }, category) { category = it }
        BookChoice("Origen", listOf("" to "Todos", "MANUAL" to "Manual", "NOTIFICATION" to "Notificación"), origin) { origin = it }
        BookChoice("Estado", listOf("" to "Todos") + (book?.transactions.orEmpty().map { if (it.deletedAt != null) "DELETED" else it.status }.distinct().map { it to stateLabel(it) }), status) { status = it }
        BookChoice("Tipo de movimiento", listOf("" to "Todos") + TransactionKind.entries.map { it.name to kindLabel(it) }, kind) { kind = it }
        Text("Para consultar borrados y vínculos anteriores, activa también su casilla sobre la lista.")
        TextButton(onClick = { from = ""; through = ""; provider = ""; account = ""; instrument = ""; category = "ALL"; origin = ""; status = ""; kind = ""; search = "" }) { Text("Limpiar filtros") }
    }
    if (!valid) { Text("Revisa el rango de fechas. La fecha inicial debe ser anterior o igual a la final.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }); return emptyList() }
    if (book == null) return emptyList()
    val filter = MovementFilter(first, last, provider.ifBlank { null }, account.ifBlank { null }, instrument.ifBlank { null }, category.takeIf { it != "ALL" },
        origin.ifBlank { null }, status.ifBlank { null }, kind.takeIf { it.isNotBlank() }?.let(TransactionKind::valueOf), search)
    val ids = Insights.filter(book.insightRecords().filter { it.origin != "SYNTHETIC" && (showRemoved || it.operation.included) }, filter, book.accounts.associate { it.id to it.alias }).map { it.operation.id }
    val byId = book.transactions.associateBy { it.id }
    Text("${ids.size} movimientos coinciden")
    return ids.mapNotNull(byId::get)
}

private fun stateLabel(value: String) = when (value) {
    "MANUAL_CONFIRMED" -> "Confirmado manualmente"
    "AUTO_CONFIRMED" -> "Confirmado automáticamente"
    "DELETED" -> "Borrado o vinculado"
    "MERGED" -> "Vinculado"
    "NEEDS_REVIEW" -> "Por revisar"
    else -> value
}

@Composable
fun MovementDetail(initial: TransactionEntity, s: BookUiState, vm: BookViewModel, dismiss: () -> Unit) {
    val book = s.book ?: return
    val t = book.transactions.firstOrNull { it.id == initial.id } ?: initial
    val before = remember { s.completed }
    BookDialog("Detalle del movimiento", s, dismiss) {
        if (s.completed > before) s.message?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Text("${kindLabel(TransactionKind.valueOf(t.kind))} · ${displayMoney(t.amountMinor)}")
        Text("${if (t.direction == "INCOMING") "Entrada" else "Salida"} · ${book.accounts.firstOrNull { it.id == t.accountId }?.alias ?: "Sin cuenta"}")
        Text("Fecha de operación ${localDate(t.occurredAt)}${if (t.estimatedDate) " · estimada" else ""}")
        Text("Recibido ${localDate(t.observedAt)}")
        Text("Estado ${stateLabel(if (t.deletedAt != null) "DELETED" else t.status)}")
        Text("Comercio ${t.merchantAlias ?: "sin identificar"}")
        Text("Categoría ${book.categories.firstOrNull { it.id == t.categoryId }?.name ?: "Sin categoría"}")
        t.note?.let { Text(it) }
        t.counterAccountId?.let { counter -> Text("Otra cuenta propia ${book.accounts.firstOrNull { it.id == counter }?.alias ?: "Sin alias"}") }
        val observations = book.observations.filter { it.transactionId == t.id }
        if (observations.isEmpty()) Text(if (t.origin == "MANUAL") "Registro manual. No tiene avisos vinculados." else "No se conservan avisos vinculados a este registro.")
        observations.forEach { source ->
            Text("Fuente ${source.sourcePackage} · ${source.provider ?: "Entidad sin identificar"}", style = MaterialTheme.typography.titleSmall)
            Text("Recibido ${localDate(source.receivedAt)} · Adaptador ${source.parserVersion}")
            Text("Confianza de extracción ${(source.confidence * 100).toInt()} %. Cobertura preliminar.")
            Text(source.evidenceCodes.split(',').joinToString("\n") { evidenceReason(it) })
        }
        if (included(t) && Insights.rule(t.merchantAlias, book.rules.map(::ruleSpec)) != null) {
            Text("Aplicar una regla cambia el alias y la categoría de esta operación. Puedes deshacerlo desde la lista.")
            OutlinedButton(onClick = { vm.applyRule(t) }, enabled = !s.busy) { Text("Aplicar regla a este movimiento") }
        }
    }
}

@Composable
fun CreditSummary(account: FinancialAccountEntity, book: BookSnapshot, s: BookUiState) {
    val card = book.cards.firstOrNull { it.accountId == account.id && it.type == "CREDIT" } ?: return
    Text("El saldo estimado puede diferir del estado de cuenta por intereses, comisiones y movimientos omitidos.")
    val balance = s.totals?.balances?.get(account.id)
    val creditLimit = card.creditLimitMinor
    if (creditLimit != null) {
        Text("Límite configurado ${displayMoney(creditLimit)}")
        if (balance != null) {
            val available = runCatching { Insights.exact(creditLimit.toBigInteger() - balance.toBigInteger()) }.getOrNull()
            Text("Disponible estimado ${available?.let(::displayMoney) ?: "fuera de rango"}")
        } else Text("Configura deuda inicial para calcular disponible estimado.")
    }
    val billingDay = card.billingDay
    if (billingDay == null) { Text("Configura el día de corte para consultar el ciclo."); return }
    val cycle = runCatching { Insights.credit(LocalDate.now(FINANCIAL_ZONE), billingDay, card.dueDay, account.id, book.insightRecords()) }.getOrNull()
    if (cycle == null) { Text("No se pudo calcular el ciclo. Revisa días e importes."); return }
    Text("Ciclo actual ${cycle.start} a ${cycle.endExclusive.minusDays(1)}")
    Text("Consumo neto del ciclo ${displayMoney(cycle.consumption)}")
    Text("Pagos registrados en el ciclo ${displayMoney(cycle.payments)}")
    Text("Vencimiento estimado ${cycle.due ?: "sin configurar"}")
    Text("El corte incluye el día indicado; días inexistentes se ajustan al último día del mes. El vencimiento es el primer día configurado posterior al corte.")
}

@Composable
fun BudgetsScreen(s: BookUiState, vm: BookViewModel) {
    BookFeedback(s, vm)
    val book = s.book ?: return
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now(FINANCIAL_ZONE).toString()) }
    val month = monthField(monthText) { monthText = it.take(7) }
    var editor by remember { mutableStateOf<BudgetEntity?>(null) }; var creating by rememberSaveable { mutableStateOf(false) }
    Button(onClick = { creating = true }, enabled = !s.busy && month != null) { Text("Añadir presupuesto mensual") }
    val spent = month?.let { runCatching { Insights.month(it, book.insightRecords()).categories }.getOrNull() }
    val budgets = book.budgets.filter { it.month == monthText }
    if (month != null && budgets.isEmpty()) Text("No hay presupuestos para este mes.")
    budgets.forEach { budget ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(book.categories.firstOrNull { it.id == budget.categoryId }?.name ?: "Categoría archivada", style = MaterialTheme.typography.titleMedium)
                if (spent == null) {
                    Text("No se pudo calcular el consumo de este mes. Revisa el rango de los importes.", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                } else {
                    val consumed = spent[budget.categoryId] ?: 0L
                    val remaining = runCatching { Insights.exact(budget.limitMinor.toBigInteger() - consumed.toBigInteger()) }.getOrNull()
                    Text("Consumo neto ${displayMoney(consumed)} de ${displayMoney(budget.limitMinor)}")
                    Text("Disponible ${remaining?.let(::displayMoney) ?: "fuera de rango"}")
                }
                Text("${if (budget.alertsEnabled) "Alertas activadas" else "Alertas desactivadas"} · umbral ${budget.threshold} % y límite 100 %")
                OutlinedButton(onClick = { editor = budget }, enabled = !s.busy) { Text("Editar presupuesto") }
                TextButton(onClick = { vm.removeBudget(budget.id) }, enabled = !s.busy) { Text("Eliminar presupuesto") }
            }
        }
    }
    if (book.budgets.any { it.alertsEnabled }) NotificationPermissionControls()
    Text("Categorías", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    CategorySettings(s, vm)
    Text("Reglas por comercio", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    RuleSettings(s, vm)
    if (creating || editor != null) BudgetEditor(s, vm, editor, monthText) { creating = false; editor = null }
}

@Composable
private fun CategorySettings(s: BookUiState, vm: BookViewModel) {
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }; var creating by rememberSaveable { mutableStateOf(false) }
    OutlinedButton(onClick = { creating = true }, enabled = !s.busy) { Text("Añadir categoría") }
    s.book?.categories?.forEach { category ->
        Text("${category.name}${if (!category.active) " · Archivada" else ""}")
        TextButton(onClick = { editing = category }, enabled = !s.busy) { Text("Editar categoría ${category.name}") }
    }
    if (creating || editing != null) {
        val id = remember { editing?.id ?: UUID.randomUUID().toString() }
        var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
        var active by rememberSaveable { mutableStateOf(editing?.active ?: true) }
        val before = remember { s.completed }
        val dismiss = { creating = false; editing = null }
        LaunchedEffect(s.completed) { if (s.completed > before) dismiss() }
        BookDialog("Categoría", s, dismiss) {
            BookText("Nombre de categoría", name, { name = it.take(40) })
            BookCheck("Categoría activa", active) { active = it }
            Text("Archivar conserva las operaciones y sus presupuestos.")
            Button(onClick = { vm.category(id, name, active) }, enabled = !s.busy && name.isNotBlank()) { Text("Guardar categoría") }
        }
    }
}

@Composable
private fun RuleSettings(s: BookUiState, vm: BookViewModel) {
    var editing by remember { mutableStateOf<MerchantRuleEntity?>(null) }; var creating by rememberSaveable { mutableStateOf(false) }
    Text("Se aplica primero la mayor prioridad. En empate se usa el identificador estable de la regla. Las reglas nuevas sugieren categoría en operaciones nuevas; los registros existentes se reclasifican desde su detalle.")
    OutlinedButton(onClick = { creating = true }, enabled = !s.busy) { Text("Añadir regla") }
    s.book?.rules?.forEach { rule ->
        Text("${rule.pattern} → ${rule.merchantAlias} · prioridad ${rule.priority}${if (!rule.enabled) " · Desactivada" else ""}")
        TextButton(onClick = { editing = rule }, enabled = !s.busy) { Text("Editar regla ${rule.pattern}") }
    }
    if (creating || editing != null) {
        val id = remember { editing?.id ?: UUID.randomUUID().toString() }
        var pattern by rememberSaveable { mutableStateOf(editing?.pattern.orEmpty()) }
        var alias by rememberSaveable { mutableStateOf(editing?.merchantAlias.orEmpty()) }
        var match by rememberSaveable { mutableStateOf(editing?.matchType ?: "CONTAINS") }
        var category by rememberSaveable { mutableStateOf(editing?.categoryId.orEmpty()) }
        var priority by rememberSaveable { mutableStateOf(editing?.priority?.toString() ?: "0") }
        var enabled by rememberSaveable { mutableStateOf(editing?.enabled ?: true) }
        val before = remember { s.completed }; val dismiss = { creating = false; editing = null }
        LaunchedEffect(s.completed) { if (s.completed > before) dismiss() }
        BookDialog("Regla por comercio", s, dismiss) {
            BookChoice("Coincidencia", listOf("EXACT" to "Exacta", "CONTAINS" to "Contiene"), match) { match = it }
            BookText("Patrón de comercio", pattern, { pattern = it.take(80) })
            BookText("Alias de comercio", alias, { alias = it.take(80) })
            BookChoice("Categoría de la regla", listOf("" to "Sin categoría") + s.book?.categories.orEmpty().filter { it.active }.map { it.id to it.name }, category) { category = it }
            BookText("Prioridad", priority, { priority = it.take(11) }, priority.toIntOrNull() == null, "Mayor número tiene preferencia.", numeric = true)
            BookCheck("Regla habilitada", enabled) { enabled = it }
            Button(onClick = { vm.rule(MerchantRuleEntity(id, match, pattern, alias, category.ifBlank { null }, priority.toInt(), enabled)) },
                enabled = !s.busy && pattern.isNotBlank() && alias.isNotBlank() && priority.toIntOrNull() != null) { Text("Guardar regla") }
        }
    }
}

@Composable
private fun BudgetEditor(s: BookUiState, vm: BookViewModel, old: BudgetEntity?, initialMonth: String, dismiss: () -> Unit) {
    var category by rememberSaveable { mutableStateOf(old?.categoryId.orEmpty()) }
    var month by rememberSaveable { mutableStateOf(old?.month ?: initialMonth) }
    var limit by rememberSaveable { mutableStateOf(old?.limitMinor?.let(::decimal).orEmpty()) }
    var threshold by rememberSaveable { mutableStateOf(old?.threshold?.toString() ?: "80") }
    var alerts by rememberSaveable { mutableStateOf(old?.alertsEnabled ?: false) }
    val before = remember { s.completed }
    LaunchedEffect(s.completed) { if (s.completed > before) dismiss() }
    BookDialog("Presupuesto mensual", s, dismiss) {
        BookChoice("Categoría del presupuesto", s.book?.categories.orEmpty().filter { it.active || it.id == old?.categoryId }.map { it.id to it.name }, category, enabled = old == null) { category = it }
        BookText("Mes del presupuesto (AAAA-MM)", month, { month = it.take(7) }, error = runCatching { YearMonth.parse(month) }.isFailure,
            help = "Escribe un mes válido como 2026-11.", enabled = old == null)
        BookText("Límite mensual USD", limit, { limit = it.take(24) }, limit.isNotBlank() && runCatching { cents(limit) }.isFailure,
            help = "Importe positivo con hasta dos decimales; ejemplo 10,00.", numeric = true)
        BookText("Umbral de alerta (%)", threshold, { threshold = it.filter(Char::isDigit).take(3) }, (threshold.toIntOrNull() ?: 0) !in 1..100, "Entre 1 y 100. También se avisa al alcanzar el límite.", numeric = true)
        BookCheck("Activar alertas locales para este presupuesto", alerts) { alerts = it }
        if (alerts) NotificationPermissionControls()
        Text("El consumo usa la categoría de la compra, con devoluciones en el mes de su registro. Las alertas son orientativas y pueden demorarse según Android.")
        Button(onClick = { vm.budget(category, month, limit, alerts, threshold) }, enabled = !s.busy && category.isNotBlank() &&
            runCatching { YearMonth.parse(month); cents(limit) }.isSuccess && (threshold.toIntOrNull() ?: 0) in 1..100) { Text("Guardar presupuesto") }
    }
}
