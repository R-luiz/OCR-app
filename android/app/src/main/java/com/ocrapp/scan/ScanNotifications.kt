package com.ocrapp.scan

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ocrapp.MainActivity
import com.ocrapp.R
import com.ocrapp.ui.capture.progressLabel

/**
 * The notifications of a Document-parsing scan: an ongoing one while it runs, and one
 * that says how it ended when the app was out of sight by then.
 */
internal object ScanNotifications {

    const val PROGRESS_ID = 1
    private const val OUTCOME_ID = 2
    const val EXTRA_SCAN_ID = "com.ocrapp.extra.SCAN_ID"

    private const val CHANNEL_PROGRESS = "scan_progress"
    private const val CHANNEL_OUTCOME = "scan_outcome"

    /** The ongoing notification of [scan]; a bare one when it has already ended. */
    fun progress(context: Context, scan: ActiveScan?): Notification {
        createChannels(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(context.getString(R.string.mode_document))
            .setContentText(scan?.let { context.resources.progressLabel(it.progress) })
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setContentIntent(openApp(context))
            .addAction(0, context.getString(R.string.action_cancel), cancelScan(context))
        scan?.waitStartedAt?.let { startedAt ->
            // The wait is timed on the monotonic clock, the notification's on wall time.
            builder
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt))
                .setShowWhen(true)
                .setUsesChronometer(true)
        }
        return builder.build()
    }

    fun updateProgress(context: Context, scan: ActiveScan) {
        post(context, PROGRESS_ID, progress(context, scan))
    }

    fun postOutcome(context: Context, outcome: ScanOutcome) {
        createChannels(context)
        val (title, text) = when (outcome) {
            is ScanOutcome.Ready ->
                context.getString(R.string.scan_ready_title) to context.getString(R.string.scan_ready_body)

            is ScanOutcome.Failed ->
                context.getString(R.string.scan_failed_title) to context.getString(outcome.messageRes)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_OUTCOME)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, (outcome as? ScanOutcome.Ready)?.scanId))
            .build()
        post(context, OUTCOME_ID, notification)
    }

    fun cancelOutcome(context: Context) {
        NotificationManagerCompat.from(context).cancel(OUTCOME_ID)
    }

    /** Declared on every use, so their names follow the device's language. */
    private fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.scan_channel_progress))
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_OUTCOME, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.scan_channel_outcome))
                    .build(),
            ),
        )
    }

    /** Posts [notification], unless the user has not allowed notifications (Android 13+). */
    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openApp(context: Context, scanId: Long? = null): PendingIntent {
        val intent = Intent.makeMainActivity(ComponentName(context, MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        scanId?.let { intent.putExtra(EXTRA_SCAN_ID, it) }
        return PendingIntent.getActivity(
            context,
            if (scanId == null) 0 else OUTCOME_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun cancelScan(context: Context): PendingIntent = PendingIntent.getService(
        context,
        0,
        Intent(context, ScanForegroundService::class.java).setAction(ScanForegroundService.ACTION_CANCEL),
        PendingIntent.FLAG_IMMUTABLE,
    )
}
