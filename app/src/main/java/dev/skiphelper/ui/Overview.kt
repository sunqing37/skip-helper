package dev.skiphelper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.skiphelper.data.OperationRecord
import dev.skiphelper.data.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun Overview(settings: Settings, connected: Boolean, label: (String) -> String,
             onToggle: (Boolean) -> Unit, onPermission: () -> Unit, onApps: () -> Unit, onRecords: () -> Unit, onSettings: () -> Unit) {
    val selected = settings.apps.count { it.value.enabled }
    val active = settings.enabled && settings.consent && connected && selected > 0
    LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF123F36), Color(0xFF147967))))) {
                Canvas(Modifier.matchParentSize()) {
                    drawCircle(Color.White.copy(alpha = .04f), size.width * .5f, Offset(size.width * .92f, size.height * .14f))
                    drawCircle(Color.White.copy(alpha = .10f), size.width * .32f, Offset(size.width * .92f, size.height * .14f), style = Stroke(1.dp.toPx()))
                    val x = size.width * .82f; val y = size.height * .18f; val d = 15.dp.toPx()
                    drawPath(Path().apply { moveTo(x - d, y - d); lineTo(x + d * .45f, y); lineTo(x - d, y + d); close() }, Color(0xFFB6EBCF).copy(alpha = .7f))
                    drawLine(Color(0xFFB6EBCF).copy(alpha = .7f), Offset(x + d * .75f, y - d), Offset(x + d * .75f, y + d), 4.dp.toPx())
                }
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(7.dp).background(if (active) Color(0xFFADEAB9) else Color(0xFFB7CAC3), CircleShape))
                        Text(if (active) "服务已就绪" else "等待开启", color = Color(0xFFCFEBE1), style = MaterialTheme.typography.labelLarge)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when { !settings.enabled -> "少一点等待。"; !connected -> "还差一步，\n即刻开启。"; selected == 0 -> "选好应用，\n轻松出发。"; else -> "开屏等待，\n交给我就好。" },
                            color = Color.White, fontSize = 28.sp, lineHeight = 37.sp, fontWeight = FontWeight.Bold)
                        Text(if (active) "在所选应用打开时，寻找可跳过的广告。" else "开启服务，选择需要自动跳过的应用。",
                            color = Color(0xFFD0E6DD), style = MaterialTheme.typography.bodyMedium)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .14f))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动跳过", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(if (settings.enabled) "已开启 · 随时可以暂停" else "点击开关开始", color = Color(0xFFBCD8CE), style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(settings.enabled, onToggle, colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF123F36), checkedTrackColor = Color(0xFFBAEDC7),
                            uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF48695E), uncheckedBorderColor = Color.Transparent))
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("已选应用", selected.toString(), "按你的选择运行", Modifier.weight(1f), onApps)
                Metric("最近点击", settings.records.count { it.result.startsWith("已发送点击") }.toString(), "基于最近 100 条记录", Modifier.weight(1f), onRecords)
            }
        }
        item {
            Panel {
                ActionRow(Icons.Default.CheckCircle, "无障碍服务", if (connected) "已连接 · 自动识别已准备好" else "未连接 · 前往系统设置开启", onPermission)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionRow(Icons.Default.Notifications, "后台运行", if (settings.keepAlive) "已启用保活偏好 · 查看运行状态" else "设置常驻通知与电池优化", onSettings)
            }
        }
        item { SectionTitle("最近动态", "查看全部", onRecords) }
        item {
            Panel {
                if (settings.records.isEmpty()) EmptyState(Icons.Default.List, "还没有操作记录", "选择应用后重新打开它，识别结果会显示在这里。")
                settings.records.takeLast(3).reversed().forEachIndexed { index, record ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecordRow(record, label(record.packageName))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text("本地识别 · 不保存截图 · 不上传页面内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Metric(title: String, value: String, note: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RecordRow(record: OperationRecord, appName: String) {
    val locale = Locale.getDefault()
    val displayTime = remember(record.time, locale) { SimpleDateFormat("HH:mm", locale).format(Date(record.time)) }
    val clicked = record.result.startsWith("已发送点击")
    val disappeared = record.result.startsWith("目标消失")
    val icon = if (clicked || disappeared) Icons.Default.CheckCircle else Icons.Default.Info
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        IconTile(icon, tone = if (clicked || disappeared) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(displayTime,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(record.result, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(record.rule, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
