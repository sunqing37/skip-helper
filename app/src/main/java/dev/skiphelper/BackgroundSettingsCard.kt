package dev.skiphelper

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.skiphelper.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skiphelper.service.ResidentNotification
import dev.skiphelper.service.SkipAccessibilityService

@Composable
fun BackgroundSettingsCard(requested: Boolean, automationEnabled: Boolean, serviceConnected: Boolean,
                           onRequestedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = remember { context.getSystemService(PowerManager::class.java) }
    val activity = remember { context.getSystemService(ActivityManager::class.java) }
    val manager = remember { context.getSystemService(NotificationManager::class.java) }
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    var powerSaving by remember { mutableStateOf(power.isPowerSaveMode) }
    var restricted by remember { mutableStateOf(Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted) }
    var notificationAllowed by remember { mutableStateOf(manager.areNotificationsEnabled()) }
    var message by remember { mutableStateOf<String?>(null) }
    val residentStatus by ResidentNotification.status.collectAsStateWithLifecycle()
    val latestChange by rememberUpdatedState(onRequestedChange)
    val latestEnabled by rememberUpdatedState(automationEnabled && serviceConnected)

    fun refresh() {
        exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        powerSaving = power.isPowerSaveMode
        restricted = Build.VERSION.SDK_INT >= 28 && activity.isBackgroundRestricted
        notificationAllowed = manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(ResidentNotification.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        SkipAccessibilityService.refreshResidentMode()
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && latestEnabled) {
            message = null
            latestChange(true)
        } else if (!granted) message = "通知权限未允许，未开启常驻保活。你仍可使用普通自动跳过。"
        refresh()
    }
    fun openSettings(intent: Intent) {
        message = null
        try { context.startActivity(intent) }
        catch (e: RuntimeException) {
            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }
            catch (e: RuntimeException) { message = "无法打开系统设置，请手动进入设置中的 Skip Helper 应用信息页。" }
        }
    }

    var details by rememberSaveable { mutableStateOf(false) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(Icons.Default.Notifications)
            Column(Modifier.weight(1f)) {
                Text("后台保活", style = MaterialTheme.typography.titleMedium)
                Text("更稳定地待命", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("常驻通知", style = MaterialTheme.typography.titleMedium)
                Text("减少被回收的概率，可能增加耗电", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = requested, enabled = requested || (automationEnabled && serviceConnected), onCheckedChange = { value ->
                if (!value) { message = null; onRequestedChange(false) }
                else if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else if (!notificationAllowed) message = "请先在通知设置中允许后台运行通知。"
                else { message = null; onRequestedChange(true) }
            })
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
            Text(when {
                !serviceConnected -> "请先连接无障碍服务，必要时在系统设置中关闭后重新开启。"
                !automationEnabled -> "自动跳过已关闭，常驻保活未运行。"
                !requested -> "开启后会显示一条后台运行通知。"
                else -> residentStatus
            }, modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (requested && automationEnabled && serviceConnected) {
            TextButton(onClick = { refresh() }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("刷新运行状态")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ActionRow(Icons.Default.Settings, "电池优化", if (exempt) "已豁免系统电池优化" else "未豁免 · 建议调整", {
            openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        })
        ActionRow(Icons.Default.Info, "应用后台设置", if (restricted) "检测到后台限制" else "未检测到系统后台限制", {
            openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        })
        ActionRow(Icons.Default.Notifications, "通知权限", if (notificationAllowed) "已允许通知" else "尚未允许通知", {
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        })
        if (powerSaving) Text("省电模式已开启，后台运行可能受影响。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "收起说明" else "关于后台运行") }
        AnimatedVisibility(details) {
            Text("返回应用后会自动刷新状态。厂商的休眠名单、自启动和后台限制仍需手动检查。\n\n强制停止应用或关闭无障碍权限后，需要手动恢复；常驻通知不能保证服务永不被停止。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (message != null) Text(message!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
