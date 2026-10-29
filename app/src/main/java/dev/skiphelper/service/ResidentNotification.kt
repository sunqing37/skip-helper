package dev.skiphelper.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import dev.skiphelper.MainActivity
import dev.skiphelper.R
import dev.skiphelper.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps the already system-bound accessibility service visible; never self-starts it. */
class ResidentNotification(private val service: Service) {
    companion object {
        const val CHANNEL = "background_operation"
        private const val ID = 10
        private val mutableStatus = MutableStateFlow("未开启")
        val status = mutableStatus.asStateFlow()
    }
    private var lastRequested: Boolean? = null
    private var running = false

    fun sync(settings: Settings, retry: Boolean = false) {
        val desired = settings.enabled && settings.consent && settings.keepAlive
        if (!retry && desired == lastRequested) return
        lastRequested = desired
        if (!desired) { stop(); return }
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
            description = "显示自动跳过服务的后台运行状态"
            setShowBadge(false)
        })
        if ((Build.VERSION.SDK_INT >= 33 && service.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ||
            !manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
            stop()
            mutableStatus.value = "通知未允许，常驻保活未运行；请在通知设置中开启"
            return
        }
        if (running) return
        val openApp = PendingIntent.getActivity(service, 0,
            Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_skip_notification)
            .setContentTitle("Skip Helper 正在后台运行")
            .setContentText("只识别所选应用的开屏跳过按钮，点此管理或关闭")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) service.startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else service.startForeground(ID, notification)
            running = true
            mutableStatus.value = "常驻通知已开启"
        } catch (e: RuntimeException) {
            running = false
            mutableStatus.value = "系统暂未允许常驻运行，请打开本应用后重试"
        }
    }

    fun stop() {
        if (running) service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        running = false
        mutableStatus.value = "未开启"
    }
}
