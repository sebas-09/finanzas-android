package ec.finanzas.capture

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CaptureEntryPoint { fun captureManager(): CaptureManager }

class CaptureWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val manager = EntryPointAccessors.fromApplication(applicationContext, CaptureEntryPoint::class.java).captureManager()
        if (manager.drainQueue()) Result.retry() else Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Los reintentos conservan solo metadatos mínimos; no leer historial activo del sistema.
        Result.retry()
    }
}
