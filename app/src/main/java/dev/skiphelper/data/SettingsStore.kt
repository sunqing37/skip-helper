package dev.skiphelper.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.preferences by preferencesDataStore("skip_helper")

class SettingsStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val key = stringPreferencesKey("settings_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(Settings())
    val state = mutableState.asStateFlow()
    val error = MutableStateFlow<String?>(null)
    val ready = MutableStateFlow(false)

    init {
        scope.launch {
            try {
                context.preferences.data.collect {
                    mutableState.value = decode(it[key])
                    ready.value = true
                }
            } catch (e: Exception) {
                mutableState.value = Settings()
                error.value = "配置无法读取，自动操作已关闭。请清除应用数据后重新配置。"
            }
        }
    }

    private fun decode(raw: String?): Settings {
        val value = raw?.let { json.decodeFromString<Settings>(it) } ?: Settings()
        require(value.schemaVersion == 1) { "Unsupported settings version" }
        require(value.apps.values.all { app -> app.rules.all { it.valid() } })
        return value.copy(records = value.records.takeLast(100))
    }

    suspend fun update(transform: (Settings) -> Settings) {
        try {
            check(ready.value && error.value == null)
            context.preferences.edit { prefs ->
                val updated = transform(decode(prefs[key]))
                prefs[key] = json.encodeToString(updated.copy(records = updated.records.takeLast(100)))
            }
        } catch (e: Exception) {
            mutableState.value = Settings()
            error.value = "配置保存失败，自动操作已关闭。请重新打开应用检查存储状态。"
        }
    }

    suspend fun app(packageName: String, transform: (AppConfig) -> AppConfig) = update {
        it.copy(apps = it.apps + (packageName to transform(it.apps[packageName] ?: AppConfig())))
    }

    suspend fun record(packageName: String, rule: String, result: String) = update {
        it.copy(records = (it.records + OperationRecord(System.currentTimeMillis(), packageName, rule, result)).takeLast(100))
    }

    fun recordAsync(packageName: String, rule: String, result: String) {
        scope.launch { record(packageName, rule, result) }
    }
}
