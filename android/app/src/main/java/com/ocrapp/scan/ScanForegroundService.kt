package com.ocrapp.scan

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the app in the foreground while [ScanKeepAlive] has a scan, with that scan's
 * notification, and stops itself once the scan has ended.
 *
 * The scan itself runs in its screen's view model; this only keeps the process it runs
 * in from being killed, and relays the notification's Cancel button.
 */
@AndroidEntryPoint
class ScanForegroundService : Service() {

    @Inject
    lateinit var keepAlive: ScanKeepAlive

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updates: Job? = null
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        if (intent?.action == ACTION_CANCEL) {
            keepAlive.cancel()
            // Started by the button of a stale notification: there is no scan to hold.
            if (updates == null) stopSelf(startId)
            return START_NOT_STICKY
        }

        // Every other start came from startForegroundService(), which Android punishes
        // with a crash unless startForeground() follows — even if the scan has ended
        // in the meantime.
        ServiceCompat.startForeground(
            this,
            ScanNotifications.PROGRESS_ID,
            ScanNotifications.progress(this, keepAlive.active.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (updates == null) {
            updates = scope.launch {
                launch {
                    keepAlive.notificationRefreshes.collect {
                        keepAlive.active.value?.let { scan ->
                            ScanNotifications.updateProgress(this@ScanForegroundService, scan)
                        }
                    }
                }
                keepAlive.active.collect { scan ->
                    if (scan == null) {
                        ServiceCompat.stopForeground(this@ScanForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        // A scan started since then has its own start id, which keeps the
                        // service running for it.
                        stopSelf(lastStartId)
                    } else {
                        ScanNotifications.updateProgress(this@ScanForegroundService, scan)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        internal const val ACTION_CANCEL = "com.ocrapp.scan.action.CANCEL"
    }
}
