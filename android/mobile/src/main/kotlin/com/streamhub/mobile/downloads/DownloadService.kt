package com.streamhub.mobile.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps the process alive while segments are still coming down.
 *
 * Without this, leaving the app hands the download's fate to the platform —
 * which is survivable, since the engine resumes from its record, but a download
 * that only progresses while the screen is on is not what "download" means to
 * anyone. The service runs exactly while at least one download is active; the
 * repository starts it on the first and stops it after the last.
 */
class DownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        val notification = build(count)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun build(count: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (count == 1) "Downloading 1 episode" else "Downloading $count episodes")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 41
        private const val EXTRA_COUNT = "count"

        /** Called by the repository whenever the number of active downloads changes. */
        fun update(context: Context, activeCount: Int) {
            val intent = Intent(context, DownloadService::class.java)
            if (activeCount > 0) {
                intent.putExtra(EXTRA_COUNT, activeCount)
                // A plain start is enough: the app is in the foreground when a
                // download begins, because beginning one takes a tap.
                runCatching { context.startService(intent) }
            } else {
                context.stopService(intent)
            }
        }
    }
}
