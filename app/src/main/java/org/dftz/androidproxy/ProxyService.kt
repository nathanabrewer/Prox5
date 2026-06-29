package org.dftz.androidproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ProxyService : Service() {

    private var server: ProxyServer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        val user = intent?.getStringExtra(EXTRA_USER)
        val pass = intent?.getStringExtra(EXTRA_PASS)
        authEnabled = !user.isNullOrEmpty()
        startForegroundCompat(port)
        try {
            server = ProxyServer(port, user, pass) { msg -> lastLog = msg }.also { it.start() }
            runningPort = port
            isRunning = true
        } catch (e: Exception) {
            lastLog = "failed to bind port $port: ${e.message}"
            isRunning = false
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        isRunning = false
        runningPort = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(port: Int) {
        val channelId = "proxy"
        val notif: Notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Proxy", NotificationManager.IMPORTANCE_LOW)
            )
            notif = Notification.Builder(this, channelId)
                .setContentTitle("Prox5 — Five is patrolling")
                .setContentText("Listening on :$port  ${if (authEnabled) "(auth on)" else "(OPEN — no auth)"}")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            notif = Notification.Builder(this)
                .setContentTitle("Prox5 — Five is patrolling")
                .setContentText("Listening on :$port  ${if (authEnabled) "(auth on)" else "(OPEN — no auth)"}")
                .setSmallIcon(R.drawable.ic_launcher)
                .setOngoing(true)
                .build()
        }

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val DEFAULT_PORT = 13178
        const val NOTIF_ID = 1

        @Volatile var isRunning = false
        @Volatile var runningPort = 0
        @Volatile var authEnabled = false
        @Volatile var lastLog = ""
    }
}
