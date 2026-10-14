package com.clipboardsync.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.clipboardsync.core.SyncEngine

/**
 * Фоновый сервис часов. При принятии буфера обмена с другого устройства:
 * пробуждает экран, вибрирует и принудительно показывает текст на экране.
 */
class WearSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startAsForeground()
        SyncEngine.init(this, "wear")
        SyncEngine.onClipboardReceived = { text, from -> showReceived(text, from) }
        SyncEngine.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        SyncEngine.init(this, "wear")
        SyncEngine.start()
        return START_STICKY
    }

    override fun onDestroy() {
        SyncEngine.onClipboardReceived = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- показ

    private fun showReceived(text: String, from: String) {
        wakeUpScreen()
        vibrate()
        notifyFullScreen(text, from)
        val i = Intent(this, ReceivedTextActivity::class.java).apply {
            putExtra(ReceivedTextActivity.EXTRA_TEXT, text)
            putExtra(ReceivedTextActivity.EXTRA_FROM, from)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            // Если система запретила запуск из фона — сработает full-screen intent.
            Log.w(TAG, "startActivity failed: ${e.message}")
        }
    }

    /** Пробуждение экрана (на части прошивок системное ограничение игнорирует — тогда сработает показ поверх блокировки). */
    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "ClipSync:screen"
            )
            wl.acquire(10_000)
        } catch (e: Exception) {
            Log.w(TAG, "wake lock failed: ${e.message}")
        }
    }

    private fun vibrate() {
        try {
            @Suppress("DEPRECATION")
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vib?.hasVibrator() == true) {
                vib.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (e: Exception) {
        }
    }

    private fun notifyFullScreen(text: String, from: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            val full = Intent(this, ReceivedTextActivity::class.java).apply {
                putExtra(ReceivedTextActivity.EXTRA_TEXT, text)
                putExtra(ReceivedTextActivity.EXTRA_FROM, from)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pi = PendingIntent.getActivity(
                this, 0, full,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, CHANNEL_RECEIVED)
                .setSmallIcon(com.clipboardsync.core.R.drawable.ic_notification)
                .setContentTitle(getString(R.string.notif_received_title))
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setFullScreenIntent(pi, true)
                .build()
            nm.notify(NOTIFICATION_RECEIVED_ID, n)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- служебное

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)

        val syncChannel = NotificationChannel(
            CHANNEL_SYNC,
            getString(R.string.notif_channel_sync),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(syncChannel)

        val receivedChannel = NotificationChannel(
            CHANNEL_RECEIVED,
            getString(R.string.notif_channel_received),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notif_channel_received_desc)
        }
        nm.createNotificationChannel(receivedChannel)
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, CHANNEL_SYNC)
            .setSmallIcon(com.clipboardsync.core.R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_SYNC_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_SYNC_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ClipSyncWear"
        private const val CHANNEL_SYNC = "sync"
        private const val CHANNEL_RECEIVED = "received"
        private const val NOTIFICATION_SYNC_ID = 1
        private const val NOTIFICATION_RECEIVED_ID = 101

        fun start(ctx: Context) {
            try {
                ctx.startForegroundService(Intent(ctx, WearSyncService::class.java))
            } catch (e: Exception) {
                ctx.startService(Intent(ctx, WearSyncService::class.java))
            }
        }
    }
}
