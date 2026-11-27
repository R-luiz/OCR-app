package com.ocrapp.scan

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.ocrapp.ocr.OcrProgress
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The scan [ScanForegroundService] is keeping alive, as its notification shows it. */
data class ActiveScan(
    val progress: OcrProgress,
    /**
     * [android.os.SystemClock.elapsedRealtime] when the scan started waiting on the
     * server, or null before it does.
     */
    val waitStartedAt: Long?,
)

/** How a scan that ended while the app was out of sight turned out. */
sealed interface ScanOutcome {
    data class Ready(val scanId: Long) : ScanOutcome

    data class Failed(@StringRes val messageRes: Int) : ScanOutcome
}

/**
 * Keeps the process alive, through [ScanForegroundService], while a Document-parsing
 * scan waits on the server.
 *
 * A cold start keeps that scan waiting for up to 10 minutes, long enough for the user to
 * switch apps, and Android may kill a process in the background at any time: the paid
 * job's result would be lost with it. While the service runs, Android treats the app as
 * in use; its notification shows where the scan is and can stop it.
 *
 * One scan at a time. Calls name their owner, so a screen that is torn down late cannot
 * end the scan of the screen that replaced it.
 */
@Singleton
class ScanKeepAlive @Inject constructor(@ApplicationContext private val context: Context) {

    private val _active = MutableStateFlow<ActiveScan?>(null)
    val active: StateFlow<ActiveScan?> = _active.asStateFlow()

    private val _notificationRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val notificationRefreshes = _notificationRefreshes.asSharedFlow()

    @Volatile
    private var owner: Any? = null

    @Volatile
    private var onCancel: (() -> Unit)? = null

    /**
     * Holds the process for [owner]'s scan until [stop]; [onCancel] stops that scan from
     * the notification.
     *
     * Call it while the app is on screen: Android refuses to start a foreground service
     * from the background.
     */
    @MainThread
    fun start(owner: Any, progress: OcrProgress, onCancel: () -> Unit) {
        this.owner = owner
        this.onCancel = onCancel
        _active.value = ActiveScan(progress, waitStartedAt = null)
        ScanNotifications.cancelOutcome(context)
        try {
            ContextCompat.startForegroundService(context, Intent(context, ScanForegroundService::class.java))
        } catch (error: IllegalStateException) {
            // The app left the screen in the meantime. The scan runs on, unprotected.
            Log.w(TAG, "Could not start the scan service", error)
        }
    }

    /** Shows how far [owner]'s scan has got. Callable from any thread. */
    fun update(owner: Any, progress: OcrProgress, waitStartedAt: Long?) {
        if (owner === this.owner) _active.value = ActiveScan(progress, waitStartedAt)
    }

    @MainThread
    internal fun refreshNotification(owner: Any) {
        if (owner === this.owner) _notificationRefreshes.tryEmit(Unit)
    }

    /**
     * Lets the process go once [owner]'s scan has ended. [outcome], when there is one, is
     * posted as a notification for the user to come back to.
     */
    @MainThread
    fun stop(owner: Any, outcome: ScanOutcome?) {
        if (owner !== this.owner) return
        this.owner = null
        onCancel = null
        _active.value = null
        outcome?.let { ScanNotifications.postOutcome(context, it) }
    }

    /** Removes the notification of a finished scan, once the user is back to see it. */
    fun dismissOutcome() {
        ScanNotifications.cancelOutcome(context)
    }

    /** Stops the running scan, at the request of its notification. */
    @MainThread
    internal fun cancel() {
        onCancel?.invoke()
    }

    private companion object {
        const val TAG = "ScanKeepAlive"
    }
}
