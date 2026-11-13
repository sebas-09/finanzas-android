package ec.finanzas.capture

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.work.*
import dagger.hilt.android.qualifiers.ApplicationContext
import ec.finanzas.database.*
import ec.finanzas.model.*
import ec.finanzas.parsers.NotificationParsers
import ec.finanzas.security.LocalFingerprinter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class CaptureManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: Provider<BootstrapDatabase>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    private var store: CaptureStore? = null
    private val mutableUi = MutableStateFlow(CaptureUiState())
    val ui: StateFlow<CaptureUiState> = mutableUi.asStateFlow()
    private val discovery = SourceDiscovery(context)
    private val fingerprint = LocalFingerprinter()
    private val blockedPackages = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var connected = false
    @Volatile private var suppressCapture = true
    @Volatile private var erasing = false
    @Volatile private var lastFailureType: String = "NONE"
    @Volatile private var lastGate: String = "NOT_STARTED"
    fun technicalStatus(): String = "state=${ui.value.snapshot.state} connected=$connected suppressed=$suppressCapture " +
        "gate=$lastGate failure=$lastFailureType observed=${ui.value.snapshot.observedCount} queued=${ui.value.snapshot.queuedCount} " +
        "sources=${ui.value.snapshot.sources.count { it.enabled && it.available }}"
    private sealed interface Callback {
        data class Posted(val input: NotificationInput, val sessionId: Long) : Callback
        data class Removed(val sourcePackage: String, val key: String) : Callback
    }
    private val inbox = Channel<Callback>(128)

    init {
        refresh()
        scope.launch {
            for (pending in inbox) performNow { repository ->
                if (pending is Callback.Removed) {
                    repository.notificationRemoved(fingerprint.hash("notification-key", pending.sourcePackage, pending.key))
                    return@performNow
                }
                pending as Callback.Posted
                if (!hasPermission()) { repository.permission(false, now()); return@performNow }
                val input = pending.input
                val source = ui.value.snapshot.sources.firstOrNull { it.packageName == input.sourcePackage }
                    ?: return@performNow
                if (discovery.signingDigest(source.packageName) != source.signingDigest) {
                    lastGate = "IDENTITY_REJECTED"
                    repository.missingSources(discovery.discover().associate { it.packageName to it.signingDigest }, now())
                    return@performNow
                }
                if (input.isGroupSummary) { lastGate = "GROUP_SUMMARY"; repository.discard("GROUP_SUMMARY"); return@performNow }
                val parts = listOfNotNull(input.title, input.text, input.bigText) + input.textLines
                val parsed = NotificationParsers.parse(input, source.provider, source.channel == "OUTLOOK")
                if (parsed is ParseResult.Rejected) {
                    lastGate = parsed.reason.name
                    repository.discard(parsed.reason.name); return@performNow
                }
                parsed as ParseResult.Candidate
                val keyHash = fingerprint.hash("notification-key", input.sourcePackage, input.key)
                val event = CaptureEnvelope(
                    fingerprint.hash("publication", input.sourcePackage, input.key, pending.sessionId.toString(), input.postedAt.toEpochMilli().toString(), fingerprint.hash("revision", *parts.toTypedArray())),
                    input.sourcePackage, keyHash, fingerprint.hash("revision", *parts.toTypedArray()),
                    pending.sessionId, input.postedAt.toEpochMilli(), input.receivedAt.toEpochMilli(), DataOrigin.NOTIFICATION,
                    parsed.copy(event = parsed.event.copy(notificationKeyHash = keyHash)),
                )
                if (repository.enqueue(event)) {
                    lastGate = "PERSISTED"
                    scheduleRecovery()
                    repository.drain()
                    lastGate = "PROCESSED"
                } else lastGate = "REJECTED_DURABLE"
            }
        }
    }

    fun hasPermission(): Boolean = context.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(component(context))

    fun refresh() = submit { repository ->
        val permission = hasPermission()
        if (!permission) suppressCapture = true
        repository.permission(permission, now())
        val sources = discovery.discover()
        repository.missingSources(sources.associate { it.packageName to it.signingDigest }, now())
        mutableUi.update { it.copy(discovered = sources, actionError = false) }
    }

    fun acceptConsent() = submit { it.consent(now()) }
    fun reportActionFailure() { mutableUi.update { it.copy(actionError = true) } }
    fun activate() = submit {
        it.permission(hasPermission(), now())
        it.activate(now())
        suppressCapture = false
        NotificationListenerService.requestRebind(component(context))
    }
    fun pause() {
        suppressCapture = true
        submit { it.pause(now()) }
    }
    fun configureSource(source: DiscoveredSource, provider: BankProvider?, outlook: Boolean, enabled: Boolean) {
        blockedPackages.add(source.packageName)
        submit {
            val actualDigest = discovery.signingDigest(source.packageName)
            check(actualDigest != null && actualDigest == source.signingDigest)
            it.source(AllowedSourceEntity(source.packageName, source.displayName, provider?.name,
                if (outlook) "OUTLOOK" else "BANK_APP", enabled = enabled, signingDigest = actualDigest), now())
            if (enabled) blockedPackages.remove(source.packageName)
        }
    }

    fun connection(value: Boolean) {
        connected = value
        if (!value) suppressCapture = true
        submit {
            it.permission(hasPermission(), now())
            it.connection(value, now())
            if (value) suppressCapture = false
        }
    }

    /** Se valida sin leer extras de aplicaciones no autorizadas. El callback nunca espera Room o Keystore. */
    fun receive(sbn: StatusBarNotification) {
        if (suppressCapture || !connected) { lastGate = "SUPPRESSED"; return }
        val snapshot = ui.value.snapshot
        val source = snapshot.sources.firstOrNull { it.packageName == sbn.packageName } ?: return
        if (source.packageName in blockedPackages || !source.available || !source.enabled || !hasPermission()) { lastGate = "SOURCE_DISABLED"; return }
        val start = snapshot.sessionStartedAt ?: return
        val sessionId = snapshot.sessionId ?: return
        val received = now()
        if (snapshot.state != CaptureState.ACTIVE || sbn.postTime < start ||
            sbn.postTime < (source.enabledSince ?: Long.MAX_VALUE) || sbn.postTime > received) { lastGate = "OUTSIDE_WINDOW"; return }
        val notification = sbn.notification
        val extras = notification.extras
        fun text(key: String, limit: Int): String? = extras.getCharSequence(key)?.let {
            it.subSequence(0, minOf(it.length, limit)).toString()
        }
        val input = NotificationInput(sbn.packageName, sbn.key, Instant.ofEpochMilli(sbn.postTime),
            Instant.ofEpochMilli(received), text(Notification.EXTRA_TITLE, 512), text(Notification.EXTRA_TEXT, 4096),
            text(Notification.EXTRA_BIG_TEXT, 8192),
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.take(20)?.map { it.take(1024).toString() }.orEmpty(),
            notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        lastGate = "QUEUED_RAM"
        if (!inbox.trySend(Callback.Posted(input, sessionId)).isSuccess) submit { it.discard("BUFFER_FULL") }
    }

    fun removed(sbn: StatusBarNotification) {
        if (ui.value.snapshot.sources.none { it.packageName == sbn.packageName }) return
        if (!inbox.trySend(Callback.Removed(sbn.packageName, sbn.key)).isSuccess) submit { it.discard("BUFFER_FULL") }
    }

    fun syntheticTest() = submit { repository ->
        val time = now()
        val token = UUID.randomUUID().toString()
        repository.enqueue(CaptureEnvelope(fingerprint.hash("synthetic", token), context.packageName,
            fingerprint.hash("synthetic-key", token), fingerprint.hash("synthetic-revision", token),
            null, time, time, DataOrigin.SYNTHETIC))
        scheduleRecovery()
        repository.drain()
    }

    suspend fun drainQueue(): Boolean = operations.withLock { if (erasing) false else ensureStore().drain() }

    /** El bloqueo precede al borrado del sistema y se mantiene si Android rechaza la petición. */
    fun blockForErase() { erasing = true; suppressCapture = true }

    suspend fun prepareForErase() = operations.withLock {
        // El borrado también debe poder solicitarse con una BD inaccesible: no intentar abrirla.
        try { store?.pause(now()) } catch (_: Exception) { /* Android eliminará el almacenamiento propio. */ }
        while (inbox.tryReceive().isSuccess) { /* Descartar texto efímero pendiente. */ }
    }

    private fun submit(action: suspend (CaptureStore) -> Unit) { scope.launch { performNow(action) } }
    private suspend fun performNow(action: suspend (CaptureStore) -> Unit) {
        operations.withLock {
            if (erasing) return@withLock
            try {
                val repository = ensureStore()
                action(repository)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                lastFailureType = failure.javaClass.simpleName
                suppressCapture = true
                try { store?.pause(now()) } catch (_: Exception) { /* No reset destructivo. */ }
                mutableUi.update { it.copy(loading = false, storageError = store == null, actionError = true,
                    snapshot = it.snapshot.copy(listenerConnected = false)) }
            }
        }
    }
    private suspend fun ensureStore(): CaptureStore {
        store?.let { return it }
        val repository = CaptureStore(database.get())
        repository.initializeAfterProcessStart(now())
        store = repository
        scope.launch {
            repository.observe().catch {
                suppressCapture = true
                mutableUi.update { it.copy(loading = false, storageError = true) }
            }.collect { snapshot -> mutableUi.update { it.copy(loading = false, storageError = false, snapshot = snapshot) } }
        }
        if (database.get().capture().queueCount() > 0) scheduleRecovery()
        return repository
    }
    private fun scheduleRecovery() {
        WorkManager.getInstance(context).enqueueUniqueWork("capture-metadata-drain", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CaptureWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build())
    }
    private fun now() = System.currentTimeMillis()
    companion object {
        fun component(context: Context) = ComponentName(context, BankNotificationListenerService::class.java)
    }
}
