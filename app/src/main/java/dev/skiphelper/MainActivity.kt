package dev.skiphelper

import android.content.Intent
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.skiphelper.data.AppConfig
import dev.skiphelper.data.AppRule
import dev.skiphelper.data.Region
import dev.skiphelper.service.SkipAccessibilityService
import dev.skiphelper.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SkipTheme { SkipHelper() } }
    }

    @Composable
    private fun SkipHelper() {
        val store = (application as SkipApplication).store
        val settings by store.state.collectAsStateWithLifecycle()
        val ready by store.ready.collectAsStateWithLifecycle()
        val error by store.error.collectAsStateWithLifecycle()
        val connected by SkipAccessibilityService.connected.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val pageState = rememberSaveableStateHolder()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
        var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
        var appsLoaded by remember { mutableStateOf(false) }
        var appsError by remember { mutableStateOf<String?>(null) }
        var consentAction by remember { mutableStateOf<(() -> Unit)?>(null) }
        var ocrApp by remember { mutableStateOf<InstalledApp?>(null) }
        var clearConfirm by remember { mutableStateOf(false) }
        var revokeConfirm by remember { mutableStateOf(false) }
        var notice by remember { mutableStateOf<String?>(null) }
        fun withConsent(action: () -> Unit) { if (settings.consent) action() else consentAction = action }
        fun openAccessibility() = withConsent {
            try { startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (e: Exception) { notice = "无法打开系统设置，请手动进入设置 → 无障碍。" }
        }
        val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }
        fun label(packageName: String) = appLabels[packageName] ?: packageName
        LaunchedEffect(Unit) {
            try {
                apps = withContext(Dispatchers.IO) {
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, 0)
                        .filter { it.activityInfo.packageName != packageName }.distinctBy { it.activityInfo.packageName }
                        .map { entry -> InstalledApp(entry.activityInfo.packageName, entry.loadLabel(packageManager).toString()) }
                        .sortedBy { it.label }
                }
            } catch (e: Exception) { appsError = "无法读取应用列表，请重新打开应用。" }
            appsLoaded = true
        }
        BackHandler(selectedPackage != null || tab != 0) { if (selectedPackage != null) selectedPackage = null else tab = 0 }
        val titles = listOf("概览", "应用", "记录", "设置")
        val icons = listOf(Icons.Default.Home, Icons.Default.List, Icons.Default.DateRange, Icons.Default.Settings)
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (selectedPackage != null) IconButton(onClick = { selectedPackage = null }) { Icon(Icons.Default.ArrowBack, "返回应用") }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("SKIP HELPER", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.8.sp)
                        Text(if (selectedPackage != null) "专属规则" else titles[tab], style = MaterialTheme.typography.headlineLarge)
                    }
                    if (selectedPackage == null && tab == 0) StatusPill("仅本机")
                }
            },
            bottomBar = {
                if (selectedPackage == null) Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        titles.forEachIndexed { index, title ->
                            NavigationBarItem(selected = tab == index, onClick = { tab = index },
                                icon = { Icon(icons[index], null) }, label = { Text(title) },
                                colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary))
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                if (!ready || error != null) {
                    Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (error == null) CircularProgressIndicator()
                        Text(error ?: "正在准备…", color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (selectedPackage != null) {
                    val selected = apps.find { it.packageName == selectedPackage } ?: InstalledApp(selectedPackage!!, selectedPackage!!)
                    RulePage(selected, settings.apps[selected.packageName] ?: AppConfig(),
                        onGeneric = { value -> scope.launch { store.app(selected.packageName) { it.copy(genericEnabled = value) } } },
                        onSave = { rule -> scope.launch { store.app(selected.packageName) { config ->
                            val existing = config.rules.indexOfFirst { it.id == rule.id }
                            config.copy(rules = if (existing < 0) config.rules + rule else config.rules.toMutableList().apply { set(existing, rule) })
                        } } },
                        onDelete = { id -> scope.launch { store.app(selected.packageName) { it.copy(rules = it.rules.filterNot { rule -> rule.id == id }) } } })
                } else pageState.SaveableStateProvider(tab) {
                    when (tab) {
                        0 -> Overview(settings, connected, ::label,
                            onToggle = { value ->
                                val action: () -> Unit = { scope.launch { store.update { it.copy(enabled = value) } }; Unit }
                                if (value) withConsent(action) else action()
                            }, onPermission = ::openAccessibility, onApps = { tab = 1 }, onRecords = { tab = 2 }, onSettings = { tab = 3 })
                        1 -> AppsPage(apps, settings.apps, appsLoaded, appsError,
                            onEnabled = { app, value ->
                                val action: () -> Unit = { scope.launch { store.app(app.packageName) { it.copy(enabled = value) } }; Unit }
                                if (value) withConsent(action) else action()
                            }, onOcr = { app, value ->
                                if (value) ocrApp = app else scope.launch { store.app(app.packageName) { it.copy(ocrEnabled = false) } }
                            }, onRules = { selectedPackage = it.packageName })
                        2 -> RecordsPage(settings.records, ::label, { clearConfirm = true })
                        3 -> LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            item { Panel { ActionRow(Icons.Default.CheckCircle, "无障碍权限", if (connected) "服务已连接" else "点击前往系统设置", ::openAccessibility) } }
                            item { BackgroundSettingsCard(settings.keepAlive, settings.enabled && settings.consent, connected) { value ->
                                scope.launch { store.update { it.copy(keepAlive = value && it.enabled && it.consent) } }
                            } }
                            item { HelpSection() }
                            item { Panel {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    IconTile(Icons.Default.CheckCircle)
                                    Text("隐私，由你掌握", style = MaterialTheme.typography.titleMedium)
                                }
                                Text("图片与界面内容只在本机处理，不保存截图、不上传。你可以随时撤回授权。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { revokeConfirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("撤回授权并关闭服务功能") }
                            } }
                            item { Text("Skip Helper  0.2.0  ·  简单一点，自在一点", Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
        }
        if (notice != null) AlertDialog(onDismissRequest = { notice = null }, title = { Text("暂时无法打开") }, text = { Text(notice!!) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } })
        if (consentAction != null) AlertDialog(
            onDismissRequest = { consentAction = null }, icon = { Icon(Icons.Default.CheckCircle, null) }, title = { Text("开启前，请了解") },
            text = { Text("Skip Helper 会读取所选应用的界面文字、控件和前台窗口信息，识别并代你点击开屏跳过按钮。\n\n所有识别在本机处理，不上传页面内容。图片识别需另行授权；你可以随时关闭功能或在系统设置中关闭无障碍服务。") },
            confirmButton = { TextButton(onClick = {
                val action = consentAction; consentAction = null
                scope.launch { store.update { it.copy(consent = true) }; if (store.error.value == null) action?.invoke() }
            }) { Text("同意并继续") } }, dismissButton = { TextButton(onClick = { consentAction = null }) { Text("暂不开启") } })
        if (ocrApp != null) AlertDialog(
            onDismissRequest = { ocrApp = null }, title = { Text("开启图片文字识别？") },
            text = { Text("为「${ocrApp!!.label}」开启后，控件无法识别时会在进入前台后的 15 秒内短暂截图，每秒最多一次。截图可能包含当前页面的个人信息。\n\n图片只在本机内存中处理，不保存、不上传。无法确认按钮时不点击，你可以随时关闭。") },
            confirmButton = { TextButton(onClick = {
                val app = ocrApp!!; ocrApp = null
                scope.launch { store.app(app.packageName) { it.copy(ocrEnabled = true) } }
            }) { Text("同意开启") } }, dismissButton = { TextButton(onClick = { ocrApp = null }) { Text("取消") } })
        if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false }, title = { Text("清空操作记录？") }, text = { Text("清空后无法恢复，不会影响应用与规则设置。") },
            confirmButton = { TextButton(onClick = { clearConfirm = false; scope.launch { store.update { it.copy(records = emptyList()) } } }) { Text("清空") } },
            dismissButton = { TextButton(onClick = { clearConfirm = false }) { Text("取消") } })
        if (revokeConfirm) AlertDialog(onDismissRequest = { revokeConfirm = false }, title = { Text("撤回授权？") },
            text = { Text("将关闭自动跳过、常驻通知和所有应用的图片识别。已保存的规则会保留，系统无障碍权限需在系统设置中关闭。") },
            confirmButton = { TextButton(onClick = { revokeConfirm = false; scope.launch { store.update { it.copy(enabled = false, consent = false, keepAlive = false,
                apps = it.apps.mapValues { (_, config) -> config.copy(enabled = false, ocrEnabled = false) }) } } }) { Text("确认撤回") } },
            dismissButton = { TextButton(onClick = { revokeConfirm = false }) { Text("取消") } })
    }
}

@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun RulePage(app: InstalledApp, config: AppConfig, onGeneric: (Boolean) -> Unit,
                     onSave: (AppRule) -> Unit, onDelete: (String) -> Unit) {
    var editing by remember { mutableStateOf<AppRule?>(null) }
    var deleting by remember { mutableStateOf<AppRule?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp, 4.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AppIcon(app)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Text("仅对这个应用生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ToggleRow("通用匹配", "专属规则优先；关闭后只使用下方规则。", config.genericEnabled, onChange = onGeneric)
        } }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("我的规则 · ${config.rules.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = { editing = AppRule(UUID.randomUUID().toString().take(8)) }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("新增")
                }
            }
        }
        if (config.rules.isEmpty()) item { EmptyState(Icons.Default.List, "还没有专属规则", "通用识别不够准确时，可以添加目标控件和页面特征。") }
        items(config.rules, key = { it.id }) { rule ->
            Panel {
                Text(rule.text.ifBlank { rule.viewId }, style = MaterialTheme.typography.titleMedium)
                Text("页面特征 · ${rule.pageMarker}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("识别区域  ${rule.region.left}, ${rule.region.top} → ${rule.region.right}, ${rule.region.bottom}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { deleting = rule }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除") }
                    FilledTonalButton(onClick = { editing = rule }) { Text("编辑规则") }
                }
            }
        }
        item { Text("按列表顺序匹配。必须有页面特征和明确目标，识别区域不能单独触发点击。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    editing?.let { rule -> key(rule.id) { RuleDialog(rule, { editing = null }, { onSave(it); editing = null }) } }
    deleting?.let { rule -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除此规则？") },
        confirmButton = { TextButton(onClick = { onDelete(rule.id); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun RuleDialog(rule: AppRule, onDismiss: () -> Unit, onSave: (AppRule) -> Unit) {
    var viewId by remember { mutableStateOf(rule.viewId) }
    var text by remember { mutableStateOf(rule.text) }
    var marker by remember { mutableStateOf(rule.pageMarker) }
    var left by remember { mutableStateOf(rule.region.left.toString()) }
    var top by remember { mutableStateOf(rule.region.top.toString()) }
    var right by remember { mutableStateOf(rule.region.right.toString()) }
    var bottom by remember { mutableStateOf(rule.region.bottom.toString()) }
    val region = Region(left.toFloatOrNull() ?: Float.NaN, top.toFloatOrNull() ?: Float.NaN, right.toFloatOrNull() ?: Float.NaN, bottom.toFloatOrNull() ?: Float.NaN)
    val draft = rule.copy(viewId = viewId.trim(), text = text.trim(), pageMarker = marker.trim(), region = region)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("专属跳过规则") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("控件 ID 和文字至少填一项；两项都填时必须同时匹配。页面特征填广告页独有的文字。控件 ID 规则不用于 OCR。")
                OutlinedTextField(viewId, { viewId = it }, label = { Text("控件 ID（完整包名:id/名称）") }, singleLine = true)
                OutlinedTextField(text, { text = it }, label = { Text("目标文字，例如：跳过") }, singleLine = true)
                OutlinedTextField(marker, { marker = it }, label = { Text("页面特征（必填）") }, singleLine = true)
                Text("识别区域：屏幕比例 0–1；左 < 右、上 < 下。必须包含整个按钮。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(left, { left = it }, label = { Text("左") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(top, { top = it }, label = { Text("上") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(right, { right = it }, label = { Text("右") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(bottom, { bottom = it }, label = { Text("下") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                if (!draft.valid()) Text("请填写页面特征、目标以及有效区域。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }, enabled = draft.valid()) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
