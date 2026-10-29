package dev.skiphelper.ui

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.skiphelper.data.AppConfig
import dev.skiphelper.data.OperationRecord
import dev.skiphelper.SkipApplication
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class InstalledApp(val packageName: String, val label: String, val icon: ImageBitmap? = null)

@Composable
fun AppsPage(apps: List<InstalledApp>, configs: Map<String, AppConfig>, loaded: Boolean, error: String?,
             onEnabled: (InstalledApp, Boolean) -> Unit, onOcr: (InstalledApp, Boolean) -> Unit, onRules: (InstalledApp) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var onlyEnabled by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered = remember(apps, configs, query, onlyEnabled) {
        apps.filter { (it.label.contains(query, true) || it.packageName.contains(query, true)) && (!onlyEnabled || configs[it.packageName]?.enabled == true) }
            .sortedByDescending { configs[it.packageName]?.enabled == true }
    }
    Column {
        TextField(query, { query = it }, placeholder = { Text("搜索应用") }, leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清除搜索") } },
            singleLine = true, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!onlyEnabled, { onlyEnabled = false }, label = { Text("全部 ${apps.size}") })
            FilterChip(onlyEnabled, { onlyEnabled = true }, label = { Text("已开启 ${configs.count { it.value.enabled }}") })
        }
        LazyColumn(contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (error != null) item { Text(error, color = MaterialTheme.colorScheme.error) }
            if (loaded && filtered.isEmpty()) item { EmptyState(Icons.Default.Search, "没有找到应用", "试试其他关键词，或切换到全部应用。") }
            items(filtered, key = { it.packageName }) { app ->
                val config = configs[app.packageName] ?: AppConfig()
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                    Column {
                        Surface(onClick = { expanded = if (expanded == app.packageName) null else app.packageName }, color = Color.Transparent) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AppIcon(app)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (config.enabled) if (config.ocrEnabled) "控件识别 + 图片识别" else "控件识别" else "未开启 · 点击查看设置",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(config.enabled, { onEnabled(app, it) })
                            }
                        }
                        AnimatedVisibility(expanded == app.packageName) {
                            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("图片文字识别", style = MaterialTheme.typography.titleSmall)
                                        Text(if (Build.VERSION.SDK_INT >= 30) "适用于画在图片中的跳过按钮" else "需要 Android 11 及以上", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(config.ocrEnabled, { onOcr(app, it) }, enabled = config.enabled && Build.VERSION.SDK_INT >= 30)
                                }
                                TextButton(onClick = { onRules(app) }, contentPadding = PaddingValues(0.dp)) {
                                    Text("专属规则 · ${config.rules.size} 条"); Icon(Icons.Default.KeyboardArrowRight, null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppIcon(app: InstalledApp) {
    val loader = (LocalContext.current.applicationContext as SkipApplication).appIcons
    val icon by produceState(app.icon, app.packageName, app.icon) {
        value = app.icon ?: loader.load(app.packageName)
    }
    val loaded = icon
    if (loaded != null) Image(loaded, null, Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)))
    else Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        Text(app.label.take(1), color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun RecordsPage(records: List<OperationRecord>, label: (String) -> String, onClear: () -> Unit) {
    var onlyClicks by rememberSaveable { mutableStateOf(false) }
    val locale = Locale.getDefault()
    val filtered = remember(records, onlyClicks) { records.reversed().filter { !onlyClicks || it.result.startsWith("已发送点击") } }
    val groups = remember(filtered, locale) {
        val formatter = SimpleDateFormat("yyyy.MM.dd", locale)
        filtered.groupBy { formatter.format(Date(it.time)) }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!onlyClicks, { onlyClicks = false }, label = { Text("全部动态") })
            FilterChip(onlyClicks, { onlyClicks = true }, label = { Text("已发送点击") })
            Spacer(Modifier.weight(1f))
            IconButton(onClear, enabled = records.isNotEmpty()) { Icon(Icons.Default.Delete, "清空记录") }
        }
        Text("最近 100 条记录 · 目标消失不代表已成功跳过", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (filtered.isEmpty()) item { EmptyState(Icons.Default.List, "这里还很安静", "识别、点击和结束原因都会记录在这里。") }
            groups.forEach { (day, entries) ->
                item { Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp)) }
                items(entries) { record -> Panel { RecordRow(record, label(record.packageName)) } }
            }
        }
    }
}

@Composable
fun HelpSection() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Panel {
        ActionRow(Icons.Default.Info, "使用指南", if (expanded) "点击收起" else "初次使用、图片按钮与常见问题", { expanded = !expanded })
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("01  开启无障碍服务", style = MaterialTheme.typography.titleSmall)
                Text("在首页开启自动跳过，再前往系统设置授权。如果系统提示受限设置，请按系统提示在应用信息页允许相关设置。", style = MaterialTheme.typography.bodyMedium)
                Text("02  选择你的应用", style = MaterialTheme.typography.titleSmall)
                Text("在应用页打开开关。点击应用可展开图片文字识别和专属规则。离开并重新打开目标应用后生效。", style = MaterialTheme.typography.bodyMedium)
                Text("03  了解识别边界", style = MaterialTheme.typography.titleSmall)
                Text("仅在进入前台后最多 15 秒内识别。不能确认按钮时不操作，不会绕过必须等待的倒计时。普通无障碍服务无法保证收到所有触摸事件。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
