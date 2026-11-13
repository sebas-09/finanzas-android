package ec.finanzas.locales

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import ec.finanzas.capture.CaptureManager
import ec.finanzas.database.*
import ec.finanzas.settings.ExportMovement
import ec.finanzas.settings.MovementCsv
import java.io.OutputStreamWriter
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DataSettingsState(val busy: Boolean = false, val erasing: Boolean = false,
    val message: String? = null, val error: String? = null)

/** Solo exporta la instantánea confirmada. Ni texto de notificaciones ni ficheros temporales en claro. */
fun BookSnapshot.exportMovements(): List<ExportMovement> {
    val accountsById = accounts.associateBy { it.id }
    val categoriesById = categories.associateBy { it.id }
    val transactionsById = transactions.associateBy { it.id }
    return transactions.filter(::included).sortedWith(compareBy<TransactionEntity> { it.occurredAt }.thenBy { it.id }).map { t ->
        val account = accountsById.getValue(requireNotNull(t.accountId))
        val categoryId = if (t.kind == "REFUND") transactionsById[t.originalTransactionId]?.categoryId else t.categoryId
        ExportMovement(t.id, t.occurredAt, account.alias, account.provider, accountType(account.kind).name,
            t.counterAccountId?.let { accountsById[it]?.alias }, t.amountMinor, t.currency, t.kind, t.direction,
            categoriesById[categoryId]?.name, t.merchantAlias, t.note, t.origin, t.status,
            t.originalTransactionId, t.estimatedDate)
    }
}

@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: Provider<BootstrapDatabase>,
    private val capture: CaptureManager,
) : ViewModel() {
    private val mutable = MutableStateFlow(DataSettingsState())
    val state = mutable.asStateFlow()

    fun export(uri: Uri?) {
        if (uri == null) { mutable.update { it.copy(message = "Exportación cancelada.", error = null) }; return }
        if (mutable.value.busy) return
        mutable.value = DataSettingsState(busy = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val records = BookStore(database.get()).observe().first().exportMovements()
                val stream = context.contentResolver.openOutputStream(uri, "wt")
                    ?: throw java.io.IOException("No output stream")
                val count = OutputStreamWriter(stream, Charsets.UTF_8).use { MovementCsv.write(it, records) }
                mutable.value = DataSettingsState(message = "CSV guardado: $count movimientos vigentes. El archivo está en claro.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = DataSettingsState(error = "No se completó la exportación. Tus datos locales se conservan. El destino puede contener un CSV parcial; elimínalo o vuelve a exportar.")
            }
        }
    }

    fun erase() {
        if (mutable.value.busy) return
        mutable.value = DataSettingsState(busy = true, erasing = true)
        // Bloquea callbacks inmediatamente, antes de esperar tareas o IPC.
        capture.blockForErase()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                capture.prepareForErase()
                // Si la BD interna de WorkManager falla (por ejemplo, almacenamiento lleno),
                // su cancelación no debe impedir liberar los datos mediante Android.
                try { WorkManager.getInstance(context).cancelAllWork() }
                catch (_: Exception) { /* El borrado del sistema termina los trabajos y elimina su BD. */ }
                try { context.getSystemService(NotificationManager::class.java).cancelAll() }
                catch (_: Exception) { /* Android retira los avisos al limpiar el paquete. */ }
                // Android cierra el proceso antes de eliminar BD/WAL/SHM, preferencias, cachés,
                // tareas y claves Keystore de este UID. No afecta a documentos guardados por SAF.
                check(context.getSystemService(ActivityManager::class.java).clearApplicationUserData())
                // La petición fue aceptada; no declarar éxito antes de que Android cierre el proceso.
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = DataSettingsState(error = "Android no pudo completar el borrado. La captura permanece bloqueada. Cierra y vuelve a abrir la app para reintentar; no se declara eliminado ningún dato.")
            }
        }
    }
}
