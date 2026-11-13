package ec.finanzas.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import ec.finanzas.security.DatabaseKeyVault
import ec.finanzas.model.CaptureEnvelope
import ec.finanzas.model.DataOrigin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Dos invocaciones separadas por force-stop prueban Keystore y BD entre procesos. */
class ProcessRestartProbe {
    @Test fun roundTripAcrossProcesses() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("restartPhase")
        assumeTrue("Se ejecuta mediante tooling/test-process-restart.ps1", phase in listOf("write", "read"))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "process_restart_probe"
        if (phase == "write") {
            context.deleteDatabase("$name.db")
            DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval()
        }
        val db = EncryptedDatabaseFactory.open(context, name)
        try {
            val capture = CaptureStore(db)
            if (phase == "write") {
                db.probe().save(BootstrapProbe(value = "restart-marker"))
                capture.initializeAfterProcessStart(1000)
                capture.consent(1000)
                capture.permission(true, 1100)
                capture.connection(true, 1100)
                capture.source(AllowedSourceEntity("ec.finanzas.fixture", "Fixture entre procesos", "DEUNA",
                    enabled = true, signingDigest = "synthetic-certificate"), 1500)
                capture.activate(2000)
                capture.enqueue(CaptureEnvelope("restart-queue", "ec.finanzas.fixture", "synthetic-key",
                    "synthetic-revision", db.capture().settings().currentSessionId, 2100, 2101, DataOrigin.NOTIFICATION))
                assertEquals(1L, db.capture().queueCount())
            } else {
                assertEquals("restart-marker", db.probe().read())
                capture.initializeAfterProcessStart(3000)
                capture.drain()
                capture.drain()
                assertEquals(0L, db.capture().queueCount())
                assertEquals(1L, db.capture().observationCount("NOTIFICATION"))
                assertEquals(0L, db.ledger().transactionCount())
            }
        } finally { db.close() }
        if (phase == "read") {
            context.deleteDatabase("$name.db")
            DatabaseKeyVault(context, name).deleteAfterDatabaseRemoval()
        }
    }
}

