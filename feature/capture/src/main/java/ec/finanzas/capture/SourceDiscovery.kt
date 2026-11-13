package ec.finanzas.capture

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import ec.finanzas.model.DiscoveredSource
import java.security.MessageDigest

/** No se infieren identidades bancarias por el nombre visible. El usuario vincula cada paquete real. */
class SourceDiscovery(private val context: Context) {
    fun signingDigest(packageName: String): String? = try {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signing = info.signingInfo
        // El certificado inicial permanece estable en rotaciones firmadas del mismo desarrollador.
        val signatures = if (signing?.hasMultipleSigners() == true) signing.apkContentsSigners
            else signing?.signingCertificateHistory?.take(1)?.toTypedArray()
        signatures?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }?.sorted()?.joinToString(":")?.takeIf { it.isNotBlank() }
    } catch (_: PackageManager.NameNotFoundException) { null }

    fun discover(): List<DiscoveredSource> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = context.packageManager.queryIntentActivities(intent, 0)
        return activities.distinctBy { it.activityInfo.packageName }.mapNotNull { activity ->
            val name = activity.activityInfo.packageName
            if (name == context.packageName) null else signingDigest(name)?.let { digest ->
                DiscoveredSource(name, activity.loadLabel(context.packageManager).toString().take(100), digest)
            }
        }.sortedBy { it.displayName.lowercase() }
    }
}
