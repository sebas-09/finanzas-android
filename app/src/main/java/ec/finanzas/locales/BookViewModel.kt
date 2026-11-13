package ec.finanzas.locales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.finanzas.database.*
import ec.finanzas.ledger.*
import ec.finanzas.model.*
import ec.finanzas.dashboard.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class BookUiState(val loading: Boolean = true, val busy: Boolean = false, val book: BookSnapshot? = null,
    val totals: BookTotals? = null, val error: String? = null, val message: String? = null, val completed: Long = 0)

@HiltViewModel
class BookViewModel @Inject constructor(private val database: Provider<BootstrapDatabase>) : ViewModel() {
    private val mutable = MutableStateFlow(BookUiState())
    val state = mutable.asStateFlow()
    private var observing: Job? = null
    init { retry() }
    fun retry() {
        observing?.cancel(); mutable.update { it.copy(loading = true, error = null) }
        observing = viewModelScope.launch(Dispatchers.IO) {
            try { ProductStore(database.get()).initialize(); BookStore(database.get()).observe().collect { book ->
                val totals = book.totals(); mutable.update { it.copy(loading = false, book = book, totals = totals) }
            } } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = "No se pudo cargar el libro. Reintenta; tus datos se conservan.") } }
        }
    }
    fun save(draft: OperationDraft, separate: Boolean) = action("Operación guardada; saldos y totales recalculados.") { it.save(draft, separate) }
    fun delete(record: TransactionEntity) = action("Operación borrada del cómputo. Puedes deshacerla.") { it.softDelete(record.id, record.revision) }
    fun undo(record: TransactionEntity) = action("Última acción deshecha.") { it.undo(record.mergedIntoId ?: record.id) }
    fun ignore(id: String) = action("Candidato ignorado.") { it.ignoreCandidate(id) }
    fun reopen(id: String) = action("Candidato devuelto a revisión.") { it.reopenCandidate(id) }
    fun archive(id: String, active: Boolean) = action(if (active) "Cuenta reactivada." else "Cuenta archivada; su historia se conserva.") { it.archiveAccount(id, active) }
    fun category(id: String, name: String, active: Boolean) = action("Categoría guardada.") { ProductStore(database.get()).category(id, name, active) }
    fun rule(value: MerchantRuleEntity) = action("Regla guardada. Se aplicará a registros nuevos; puedes revisar los existentes.") { ProductStore(database.get()).rule(value) }
    fun budget(category: String, month: String, limit: String, alerts: Boolean, threshold: String) = action("Presupuesto guardado.") {
        ProductStore(database.get()).budget(category, month, cents(limit), alerts, threshold.toIntOrNull() ?: 0)
    }
    fun removeBudget(id: String) = action("Presupuesto eliminado.") { ProductStore(database.get()).removeBudget(id) }
    fun applyRule(record: TransactionEntity) = action("Regla aplicada. Puedes deshacer la clasificación.") { store ->
        val book = requireNotNull(mutable.value.book)
        val rule = requireNotNull(Insights.rule(record.merchantAlias, book.rules.filter { rule -> rule.categoryId == null || book.categories.any { it.id == rule.categoryId && it.active } }.map(::ruleSpec))) { "No hay una regla aplicable." }
        store.save(operationDraft(record).copy(merchant = rule.alias, categoryId = rule.categoryId, categoryChanged = true))
    }
    fun account(id: String, alias: String, provider: BankProvider, instrument: InstrumentType, opening: String,
        active: Boolean, billing: String, due: String, limit: String) = action("Cuenta guardada.") { store ->
        fun day(value: String): Int? = if (value.isBlank()) null else value.toIntOrNull()?.takeIf { it in 1..31 }
            ?: throw IllegalArgumentException("Los días de corte y vencimiento deben estar entre 1 y 31.")
        store.saveAccount(id, alias, provider, instrument, if (opening.isBlank()) null else cents(opening, false), active,
            day(billing), day(due), if (limit.isBlank()) null else cents(limit))
    }
    fun merge(a: TransactionEntity, b: TransactionEntity, kind: TransactionKind?, window: Int) = action("Operaciones conciliadas. Puedes separarlas con Deshacer.") {
        it.merge(a.id, b.id, kind, window, a.revision, b.revision)
    }
    fun link(d: OperationDraft, target: TransactionEntity, kind: TransactionKind?, window: Int) = action("Aviso vinculado; no se duplicó la operación.") {
        it.linkCandidate(requireNotNull(d.candidateId), target.id, d.accountId, d.direction, d.amount, d.occurredAt, kind, window, target.revision)
    }
    fun draft(id: String, amount: Long?, account: String?, kind: TransactionKind?, direction: MoneyDirection, date: Long?,
        counter: String?, original: String?, merchant: String?, note: String?, estimated: Boolean, category: String? = null, categoryDecided: Boolean = false) = action("Borrador guardado; no afecta totales.") {
        it.saveCandidateDraft(id, amount, account, kind, direction, date, counter, original, merchant, note, estimated, category, categoryDecided)
    }
    private fun action(message: String, block: suspend (BookStore) -> Any?) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { block(BookStore(database.get())); mutable.update { it.copy(busy = false, message = message, completed = it.completed + 1) } }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { mutable.update { it.copy(busy = false, error = e.message ?: "Revisa los datos de la operación.") } }
            catch (_: Exception) { mutable.update { it.copy(busy = false, error = "No se guardó la acción. Tus datos se conservaron; vuelve a intentarlo.") } }
        }
    }
}

fun cents(text: String, positive: Boolean = true): Long {
    val value = text.trim()
    require(Regex("-?[0-9]+(?:[.,][0-9]{1,2})?").matches(value)) { "Escribe un importe con hasta dos decimales y sin separadores de miles." }
    val result = try { MoneyMinor.fromDecimal(BigDecimal(value.replace(',', '.'))).value } catch (_: ArithmeticException) {
        throw IllegalArgumentException("El importe supera el rango de centavos permitido.")
    }
    require(!positive || result > 0) { "El importe debe ser positivo." }; return result
}
fun decimal(value: Long) = BigDecimal.valueOf(value, 2).toPlainString()
fun localDate(value: Long): String = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").format(Instant.ofEpochMilli(value).atZone(FINANCIAL_ZONE))
fun dateMillis(value: String): Long = try { LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(FINANCIAL_ZONE).toInstant().toEpochMilli() }
    catch (_: java.time.DateTimeException) { throw IllegalArgumentException("Escribe la fecha como AAAA-MM-DDTHH:MM:SS, hora de Guayaquil.") }
fun kindLabel(kind: TransactionKind) = when (kind) {
    TransactionKind.EXPENSE -> "Gasto"
    TransactionKind.INCOME -> "Ingreso externo"
    TransactionKind.OWN_TRANSFER -> "Transferencia propia"
    TransactionKind.CREDIT_CARD_PAYMENT -> "Pago de tarjeta"
    TransactionKind.REFUND -> "Devolución"
    TransactionKind.CREDIT_CARD_CHARGE -> "Compra a crédito"
    TransactionKind.WALLET_TOP_UP -> "Recarga de billetera"
    TransactionKind.UNKNOWN -> "Sin clasificar"
}
