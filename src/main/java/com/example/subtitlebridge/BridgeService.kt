package com.example.subtitlebridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

object Bridge {
    var pages: PageServer? = null
    var socket: SubtitleSocket? = null

    @Synchronized fun start(ctx: Context) {
        if (pages == null) pages = PageServer(ctx.applicationContext).also { it.start() }
        if (socket == null) socket = SubtitleSocket().also { it.start(0, false) }
    }

    @Synchronized fun stop() {
        pages?.stop(); socket?.stop()
        pages = null; socket = null
    }
}

class BridgeService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            Bridge.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        Bridge.start(this)

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("bridge", "TransGame", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "bridge")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TransGame")
            .setContentText("خادم الترجمة يعمل")
            .setContentIntent(open)
            .addAction(0, "إيقاف", stop)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )

        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "transgame").apply {
                setReferenceCounted(false); acquire()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
