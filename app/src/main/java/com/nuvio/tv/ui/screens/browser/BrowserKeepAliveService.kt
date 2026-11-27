package com.nuvio.tv.ui.screens.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nuvio.tv.R

/**
 * Keeps the main app process alive while the ported browser runs in the
 * CloudStream host process. Without this, opening the browser made the main
 * process a cached 450 MB target and the low-memory killer removed it, so
 * closing the browser looked like an app restart.
 *
 * The service is deliberately short-lived: MainActivity stops it as soon as
 * the browser returns, with a two-hour safety timeout for a forgotten tab.
 */
class BrowserKeepAliveService : Service() {

    companion object {
        private const val TAG = "BrowserKeepAlive"
        private const val CHANNEL_ID = "browser_keepalive"
        private const val NOTIFICATION_ID = 9530
        private const val MAX_ALIVE_MS = 2L * 60 * 60 * 1000

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BrowserKeepAliveService::class.java),
                )
            }.onFailure { Log.w(TAG, "Could not start: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, BrowserKeepAliveService::class.java))
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps the TV browser's parent app ready"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            startForeground(NOTIFICATION_ID, buildNotification())
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, MAX_ALIVE_MS)
            START_NOT_STICKY
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed: ${t.message}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("TV browser open")
            .setSmallIcon(R.drawable.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
