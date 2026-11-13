package ec.finanzas.locales

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import ec.finanzas.capture.CaptureEntryPoint
import ec.finanzas.capture.SourceDiscovery
import ec.finanzas.model.BankProvider
import ec.finanzas.model.CaptureState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CaptureListenerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val publisher = instrumentation.context
    private val manager = EntryPointAccessors.fromApplication(context.applicationContext, CaptureEntryPoint::class.java).captureManager()
    private val component = "${context.packageName}/ec.finanzas.capture.BankNotificationListenerService"
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun post(id: Int, text: String, grouped: Boolean = false, summary: Boolean = false) {
        // UiAutomation ejecuta argv, no un intérprete de shell: Base64 conserva espacios y acentos.
        val encoded = android.util.Base64.encodeToString(text.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            .ifEmpty { "AA==" }
        shell("am broadcast --include-stopped-packages -n ${publisher.packageName}/ec.finanzas.locales.TestSourceReceiver --ei event_id $id --es message_b64 $encoded --ez grouped $grouped --ez group_summary $summary")
    }
    private suspend fun awaitObserved(expected: Long) {
        withTimeout(60_000) {
            manager.ui.first { state ->
                check(!state.actionError) { manager.technicalStatus() }
                state.snapshot.observedCount == expected
            }
        }
    }

    @Test fun burstOfGroupedChildrenPersistsEachEventAndSummaryIsNeverAnOperation(): Unit = runBlocking {
        try { withTimeout(180_000) {
            manager.pause()
            shell("cmd notification disallow_listener $component")
            manager.refresh()
            manager.ui.first { !it.loading && !it.snapshot.permissionGranted }
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${publisher.packageName} android.permission.POST_NOTIFICATIONS")
            post(-1, "")
            manager.acceptConsent(); manager.ui.first { it.snapshot.consentAccepted }
            manager.refresh()
            val source = SourceDiscovery(context).discover().first { it.packageName == publisher.packageName }
            manager.configureSource(source, BankProvider.DEUNA, outlook = false, enabled = true)
            manager.ui.first { it.snapshot.sources.any { s -> s.packageName == source.packageName && s.enabled } }
            shell("cmd notification allow_listener $component")
            manager.refresh()
            manager.ui.first { it.snapshot.permissionGranted && it.snapshot.listenerConnected }
            manager.activate(); manager.ui.first { it.snapshot.state == CaptureState.ACTIVE }
            val base = manager.ui.value.snapshot.observedCount
            repeat(8) { index -> post(700+index,"Pagaste USD 0.01 [RAFAGA $index]",grouped=true); delay(250) }
            awaitObserved(base+8)
            post(799,"Pagaste USD 9.99 [RESUMEN]",summary=true)
            withTimeout(15000) { while (!manager.technicalStatus().contains("gate=GROUP_SUMMARY")) delay(100) }
            delay(300)
            assertEquals(base+8,manager.ui.value.snapshot.observedCount)
        } } finally {
            manager.pause()
            shell("cmd notification disallow_listener $component")
            manager.refresh(); post(-1, "")
        }
    }

    @Test fun actualListenerRejectsHistoryAndPauseThenDetectsRevocation() = runBlocking {
        try { withTimeout(180_000) {
            manager.pause()
            shell("cmd notification disallow_listener $component")
            manager.refresh()
            manager.ui.first { !it.loading && !it.snapshot.permissionGranted }
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${publisher.packageName} android.permission.POST_NOTIFICATIONS")
            post(-1, "")
            manager.acceptConsent()
            manager.ui.first { it.snapshot.consentAccepted }
            manager.refresh()
            val source = SourceDiscovery(context).discover().first { it.packageName == publisher.packageName }
            manager.configureSource(source, BankProvider.DEUNA, outlook = false, enabled = true)
            manager.ui.first { it.snapshot.sources.any { s -> s.packageName == source.packageName && s.enabled } }
            val base = manager.ui.value.snapshot.observedCount
            post(101, "Evento sintético anterior a la activación")
            shell("cmd notification allow_listener $component")
            manager.refresh()
            manager.ui.first { it.snapshot.permissionGranted && it.snapshot.listenerConnected }
            manager.activate()
            manager.ui.first { it.snapshot.state == CaptureState.ACTIVE }
            delay(800)
            assertEquals(base, manager.ui.value.snapshot.observedCount)
            post(102, "Pagaste USD 0.01 [SINTETICO]")
            awaitObserved(base + 1)
            val updateAt = System.currentTimeMillis()
            post(102, "Pagaste USD 0.01 [SINTETICO] confirmada")
            manager.ui.first { it.snapshot.sources.any { s -> s.packageName == source.packageName && (s.lastSeenAt ?: 0) >= updateAt } }
            awaitObserved(base + 2)
            assertEquals(base + 2, manager.ui.value.snapshot.observedCount)
            post(103, "Pagaste USD 0.01 [OTRO SINTETICO]")
            awaitObserved(base + 3)
            manager.pause()
            manager.ui.first { it.snapshot.state == CaptureState.PAUSED }
            post(104, "Pagaste USD 0.01 [DURANTE PAUSA]")
            delay(300)
            manager.activate()
            manager.ui.first { it.snapshot.state == CaptureState.ACTIVE }
            delay(500)
            assertEquals(base + 3, manager.ui.value.snapshot.observedCount)
            post(105, "Pagaste USD 0.01 [DESPUES DE PAUSA]")
            awaitObserved(base + 4)
            val priorSynthetic = manager.ui.value.snapshot.syntheticCount
            manager.syntheticTest()
            manager.ui.first { it.snapshot.syntheticCount > priorSynthetic }
            assertEquals(base + 4, manager.ui.value.snapshot.observedCount)
            shell("cmd notification disallow_listener $component")
            manager.refresh()
            manager.ui.first { it.snapshot.state == CaptureState.PERMISSION_REVOKED }
            assertFalse(manager.ui.value.snapshot.requestedActive)
            post(106, "Pagaste USD 0.01 [SIN PERMISO]")
            delay(500)
            assertEquals(base + 4, manager.ui.value.snapshot.observedCount)
            post(-1, "")
        } } finally {
            manager.pause()
            shell("cmd notification disallow_listener $component")
            manager.refresh()
            post(-1, "")
        }
    }
}
