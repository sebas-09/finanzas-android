package ec.finanzas.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import java.io.FileDescriptor
import java.io.PrintWriter

@AndroidEntryPoint
class BankNotificationListenerService : NotificationListenerService() {
    @Inject lateinit var capture: CaptureManager
    override fun onListenerConnected() { capture.connection(true) }
    override fun onListenerDisconnected() { capture.connection(false) }
    override fun onNotificationPosted(sbn: StatusBarNotification) { capture.receive(sbn) }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { capture.removed(sbn) }
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.println(capture.technicalStatus())
    }
    override fun onDestroy() {
        capture.connection(false)
        super.onDestroy()
    }
}
