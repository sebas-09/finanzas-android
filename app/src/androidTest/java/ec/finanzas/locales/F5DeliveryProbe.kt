package ec.finanzas.locales

import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import ec.finanzas.database.*
import ec.finanzas.ledger.OperationDraft
import ec.finanzas.model.*
import ec.finanzas.security.LocalFingerprinter
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Probe destructivo solo en AVD F5 dedicado, invocado por tooling/test-f5-delivery.py. */
class F5DeliveryProbe {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun sha(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun report(label: String, value: String) { println("F5_$label=$value") }

    @Test fun deliveryAcrossProcesses(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val phase=args.getString("f5Phase")
        assumeTrue("Run explicitly on dedicated F5 AVD",phase!=null && args.getString("allowF5DeliveryProbe")=="yes")
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (phase=="reset") {
            assertFalse(store.containsAlias("ec.finanzas.locales.db.ledger.v1"))
            assertFalse(store.containsAlias("ec.finanzas.locales.fingerprint.v1"))
            assertFalse(File(context.noBackupFilesDir,"ledger.key").exists())
            assertFalse(context.getDatabasePath("ledger.db").exists())
            for (dir in listOf(context.filesDir,context.cacheDir,context.noBackupFilesDir)) assertFalse(File(dir,"f5-marker").exists())
            assertFalse(context.getSharedPreferences("f5-probe",Context.MODE_PRIVATE).contains("marker"))
        }
        val db=EncryptedDatabaseFactory.open(context)
        try {
            val book=BookStore(db)
            val product=ProductStore(db)
            val capture=CaptureStore(db)
            if (phase=="seed") {
                assertTrue(db.book().accounts().isEmpty())
                assertEquals(5L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
                product.initialize()
                book.saveAccount("f5-bank","F5 Banco",BankProvider.PICHINCHA,InstrumentType.BANK_ACCOUNT,10000)
                product.category("f5-custom","F5 categoría",true)
                product.rule(MerchantRuleEntity("f5-custom-rule","EXACT","F5 Tienda","F5 Tienda","f5-custom",5))
                book.save(OperationDraft("f5-buy","f5-bank",399,TransactionKind.EXPENSE,1000,MoneyDirection.OUTGOING,
                    categoryId="f5-custom",categoryChanged=true,note="f5-upgrade-marker"))
                product.budget("f5-custom","2026-11",1000,false,80)
                capture.initializeAfterProcessStart(System.currentTimeMillis())
                capture.consent(System.currentTimeMillis())
                capture.syntheticTestMarker()
                db.probe().save(BootstrapProbe(value="f5-upgrade-marker"))
                for (dir in listOf(context.filesDir,context.cacheDir,context.noBackupFilesDir)) File(dir,"f5-marker").writeText("synthetic")
                assertTrue(context.getSharedPreferences("f5-probe",Context.MODE_PRIVATE).edit().putString("marker","synthetic").commit())
            } else if (phase=="upgrade") {
                assertEquals(6L,context.packageManager.getPackageInfo(context.packageName,0).longVersionCode)
                val snapshot=book.observe().first()
                assertEquals("f5-upgrade-marker",db.probe().read())
                assertEquals(9601L,snapshot.totals().balances["f5-bank"])
                assertEquals("f5-upgrade-marker",snapshot.transactions.single().note)
                assertTrue(snapshot.rules.any { it.id=="f5-custom-rule" })
                assertEquals(1,snapshot.budgets.size)
                assertTrue(capture.observe().first().consentAccepted)
                assertEquals(1L,db.capture().queueCount())
            } else if (phase=="reset") {
                capture.initializeAfterProcessStart(System.currentTimeMillis())
                assertFalse(capture.observe().first().consentAccepted)
                assertNull(capture.observe().first().captureStartedAt)
                val snapshot=book.observe().first()
                assertTrue(snapshot.accounts.isEmpty()); assertTrue(snapshot.transactions.isEmpty())
                assertTrue(snapshot.rules.isEmpty()); assertTrue(snapshot.budgets.isEmpty())
                assertTrue(snapshot.observations.isEmpty()); assertTrue(snapshot.pending.isEmpty())
                assertEquals(0L,db.capture().queueCount())
                assertTrue(capture.observe().first().sources.isEmpty())
                product.initialize()
                assertTrue(db.product().rules().none { it.id=="f5-custom-rule" })
                // Una nueva activación tiene frontera propia; una publicación vieja sigue rechazada.
                val at=System.currentTimeMillis()
                capture.consent(at); capture.permission(true,at); capture.connection(true,at)
                capture.source(AllowedSourceEntity("fixture.f5","Prueba F5",provider="DEUNA",enabled=true,signingDigest="synthetic"),at)
                capture.activate(at)
                assertTrue(requireNotNull(capture.observe().first().captureStartedAt)>=at)
                assertFalse(capture.enqueue(CaptureEnvelope("old","fixture.f5","old-key","old-rev",
                    capture.observe().first().sessionId,at-1,at,DataOrigin.NOTIFICATION)))
                capture.pause(at+1)
                // Solo limpieza del fixture NUEVO tras verificar el borrado real del sistema.
                // No dejar su sesión o fuente ficticia en la instalación recién reiniciada.
                db.clearAllTables()
                capture.initializeAfterProcessStart(at+2)
            } else error("Unknown phase")
            // Solo hashes de datos sintéticos: nunca se imprime un secreto SQLCipher.
            report("WRAPPED_SHA256",sha(File(context.noBackupFilesDir,"ledger.key").readBytes()))
            report("FINGERPRINT",LocalFingerprinter().hash("f5-delivery-probe"))
            report("PHASE_OK",requireNotNull(phase))
        } finally { db.close() }
    }

    private suspend fun CaptureStore.syntheticTestMarker() {
        val at=System.currentTimeMillis()
        assertTrue(enqueue(CaptureEnvelope("f5-queue",context.packageName,"f5-key","f5-revision",null,at,at,DataOrigin.SYNTHETIC)))
    }
}
