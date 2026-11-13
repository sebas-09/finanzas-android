package ec.finanzas.locales

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import ec.finanzas.database.*
import ec.finanzas.model.*
import ec.finanzas.parsers.MoneyNormalizer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ReviewUiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val candidates: List<CandidateEntity> = emptyList(),
    val accounts: List<FinancialAccountEntity> = emptyList(),
)

@HiltViewModel
class ReviewViewModel @Inject constructor(private val database: Provider<BootstrapDatabase>) : ViewModel() {
    private val mutable = MutableStateFlow(ReviewUiState())
    val state = mutable.asStateFlow()
    private var observing: kotlinx.coroutines.Job? = null
    init { retry() }
    fun retry() {
        observing?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        observing = viewModelScope.launch(Dispatchers.IO) {
            try {
                val dao = database.get().capture()
                combine(dao.candidates(), dao.accounts()) { candidates, accounts -> candidates to accounts }
                    .collect { (candidates, accounts) -> mutable.update { it.copy(loading = false, candidates = candidates, accounts = accounts) } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = "No se pudo abrir la bandeja. Pulsa Reintentar.") } }
        }
    }
    fun save(id: String, amount: String, accountId: String?, classification: TransactionKind?, direction: MoneyDirection?, confirm: Boolean) = action {
        val db = database.get()
        db.withTransaction {
            val dao = db.capture()
            val old = requireNotNull(dao.candidate(id))
            require(old.status == "NEEDS_REVIEW")
            val cents = if (amount.isBlank()) null else MoneyNormalizer.cents(amount)
            require(amount.isBlank() || cents != null) { "Escribe un importe positivo con hasta dos decimales (por ejemplo, 3,99)." }
            val account = accountId?.let { dao.accountById(it) }
            require(accountId == null || account != null) { "Selecciona una cuenta activa." }
            if (confirm) {
                require(cents != null && account != null && direction != null && classification != null && classification != TransactionKind.UNKNOWN) {
                    "Para confirmar, completa importe, cuenta, sentido y clasificación."
                }
                require(account.currency == "USD")
                require(old.provider == null || account.provider == old.provider) { "La cuenta debe pertenecer a la fuente del candidato." }
                require(old.instrument == null || old.instrument == account.kind ||
                    (old.instrument == "DEBIT_CARD" && account.kind == "BANK_ACCOUNT")) { "El instrumento de la cuenta no coincide con el candidato." }
                val expected = when (classification) {
                    TransactionKind.EXPENSE, TransactionKind.CREDIT_CARD_PAYMENT, TransactionKind.CREDIT_CARD_CHARGE -> MoneyDirection.OUTGOING
                    TransactionKind.INCOME, TransactionKind.REFUND, TransactionKind.WALLET_TOP_UP -> MoneyDirection.INCOMING
                    else -> direction
                }
                require(direction == expected) { "La clasificación y el sentido no coinciden." }
            }
            val status = if (confirm) "MANUAL_CONFIRMED" else "NEEDS_REVIEW"
            dao.candidate(old.copy(amountMinor = cents, accountId = accountId, classification = classification?.name,
                direction = direction?.name, status = status))
            val at = System.currentTimeMillis()
            if (confirm) {
                dao.resolve(id, at, status)
                dao.observation(id)?.let { dao.observation(it.copy(state = status)) }
            }
            dao.audit(ChangeAuditEntity(UUID.randomUUID().toString(), id, if (confirm) "CANDIDATE_CONFIRMED" else "CANDIDATE_COMPLETED",
                at, "amountMinor,accountId,classification,direction,status"))
        }
        if (confirm) "Candidato confirmado. La contabilización aún no está disponible." else "Cambios guardados."
    }
    fun ignore(id: String) = action {
        val db = database.get()
        db.withTransaction {
            val dao = db.capture()
            val old = requireNotNull(dao.candidate(id))
            require(old.status == "NEEDS_REVIEW")
            dao.candidate(old.copy(status = "IGNORED"))
            val at = System.currentTimeMillis()
            dao.resolve(id, at, "IGNORED")
            dao.observation(id)?.let { dao.observation(it.copy(state = "IGNORED")) }
            dao.audit(ChangeAuditEntity(UUID.randomUUID().toString(), id, "CANDIDATE_IGNORED", at, "status"))
        }
        "Candidato ignorado."
    }
    fun createAccount(alias: String, bank: BankProvider, instrument: InstrumentType) = action {
        require(alias.trim().length in 1..40 && alias.none { it.isISOControl() }) { "Escribe un alias de 1 a 40 caracteres." }
        database.get().ledger().account(FinancialAccountEntity(UUID.randomUUID().toString(), alias.trim(), bank.name,
            instrument.name, createdAt = System.currentTimeMillis()))
        "Cuenta creada. Ya puedes seleccionarla."
    }
    private fun action(block: suspend () -> String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { val message = block(); mutable.update { it.copy(busy = false, message = message) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: IllegalArgumentException) { mutable.update { it.copy(busy = false, error = e.message ?: "Revisa los campos.") } }
            catch (_: Exception) { mutable.update { it.copy(busy = false, error = "No se guardó la acción. Inténtalo de nuevo.") } }
        }
    }
}
