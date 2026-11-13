package ec.finanzas.locales

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.work.*
import ec.finanzas.database.*
import ec.finanzas.model.FINANCIAL_ZONE
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

object BudgetNotifications {
    const val CHANNEL = "budget-thresholds"
    private const val PERIODIC = "budget-periodic"
    private const val IMMEDIATE = "budget-refresh"

    fun allowed(context: Context): Boolean = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    fun schedule(context: Context, enabled: Boolean) {
        val work = WorkManager.getInstance(context)
        if (enabled) {
            work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BudgetAlertWorker>(15, TimeUnit.MINUTES).build())
            work.enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<BudgetAlertWorker>().build())
        } else {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(IMMEDIATE)
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun post(context: Context, id: String, level: Int): Boolean {
        if (!allowed(context)) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Umbrales de presupuesto", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = Intent(context, MainActivity::class.java).putExtra("openBudgets", true)
        val pending = PendingIntent.getActivity(context, 41, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Revisa tus presupuestos")
            .setContentText(if (level == 2) "Un presupuesto alcanzó su límite mensual registrado." else "Un presupuesto alcanzó el umbral que configuraste.")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build()
        return try { manager.notify("budget:$id", 41, notification); true } catch (_: SecurityException) { false }
    }
}

class BudgetAlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!BudgetNotifications.allowed(applicationContext)) return Result.success()
        return try {
            val db = EncryptedDatabaseFactory.open(applicationContext)
            try {
                ProductStore(db).deliverAlerts(YearMonth.now(FINANCIAL_ZONE)) { id, level -> BudgetNotifications.post(applicationContext, id, level) }
            } finally { db.close() }
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

@Composable
fun NotificationPermissionControls() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(BudgetNotifications.allowed(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = BudgetNotifications.allowed(context) }
    Text(if (allowed) "Android permite avisos propios. Se usarán únicamente en presupuestos con alertas activadas." else "Android tiene los avisos propios desactivados. La captura y los presupuestos siguen disponibles.")
    if (!allowed && Build.VERSION.SDK_INT >= 33) OutlinedButton(onClick = { request.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir avisos de presupuesto") }
    OutlinedButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }) { Text("Ajustes de avisos de presupuesto") }
    TextButton(onClick = { allowed = BudgetNotifications.allowed(context) }) { Text("Actualizar permiso de avisos") }
}
