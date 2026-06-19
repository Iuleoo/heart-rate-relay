package com.hrrelay.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import android.util.Log

/**
 * 前台保活服务
 * 负责保持进程活跃，防止息屏后 CPU 休眠或系统杀掉后台进程。
 */
class KeepAliveService : Service() {

    companion object {
        private const val TAG = "KeepAliveService"
        private const val CHANNEL_ID = "HeartRateRelayChannel"
        private const val NOTIFICATION_ID = 8888

        @Volatile
        var instance: KeepAliveService? = null
            private set

        // 缓存当前的连接与测量状态以实现动态通知
        var currentHeartRate: Int = 0
        var isWatchConnected: Boolean = false
        var isPcConnected: Boolean = false
        var bleStatusText: String = "未连接"

        /**
         * 更新服务状态缓存，并触发前台通知刷新
         */
        fun updateState(hr: Int, watchConn: Boolean, pcConn: Boolean, statusText: String) {
            currentHeartRate = hr
            isWatchConnected = watchConn
            isPcConnected = pcConn
            bleStatusText = statusText

            instance?.refreshNotification()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "KeepAliveService Created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "KeepAliveService Started")

        // 1. 获取电源锁以防止 CPU 进入休眠模式
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HeartRateRelay::KeepAliveWakeLock").apply {
            acquire(12 * 60 * 60 * 1000L /* 最长锁定12小时 */)
        }

        // 2. 获取高精度 WiFi 锁以防止息屏后网络降级或超时断连
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HeartRateRelay::WifiLock").apply {
                acquire()
            }
            Log.i(TAG, "WifiLock acquired successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire WifiLock: ${e.message}")
        }

        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "KeepAliveService Destroyed")
        instance = null
        
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null

        wifiLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wifiLock = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * 重新构建并显示前台通知
     */
    fun refreshNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = createNotification()
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh notification: ${e.message}")
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // 动态构建内容
        val hr = currentHeartRate
        val isWatch = isWatchConnected
        val isPc = isPcConnected
        val statusText = bleStatusText

        val title = when {
            isWatch && hr > 0 -> "❤️ 心率中转中: $hr BPM"
            isWatch -> "⌚ 已连接手环 (等待数据...)"
            else -> "💔 蓝牙手环未连接"
        }

        val pcStatusStr = if (isPc) "💻 电脑: 已连接" else "💻 电脑: 未连接"
        val content = "$pcStatusStr | 蓝牙状态: $statusText"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "心率中转服务通道",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
