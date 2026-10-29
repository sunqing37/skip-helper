package dev.skiphelper.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Region(
    @SerialName("left") val left: Float = 0f,
    @SerialName("top") val top: Float = 0f,
    @SerialName("right") val right: Float = 1f,
    @SerialName("bottom") val bottom: Float = 1f,
) {
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && left < right && top < bottom
}

@Serializable
data class AppRule(
    @SerialName("id") val id: String,
    @SerialName("view_id") val viewId: String = "",
    @SerialName("text") val text: String = "",
    @SerialName("page_marker") val pageMarker: String = "",
    @SerialName("region") val region: Region = Region(),
) {
    fun valid() = id.isNotBlank() && (viewId.isNotBlank() || text.isNotBlank()) &&
        pageMarker.isNotBlank() && region.valid()
}

@Serializable
data class AppConfig(
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("ocr_enabled") val ocrEnabled: Boolean = false,
    @SerialName("generic_enabled") val genericEnabled: Boolean = true,
    @SerialName("rules") val rules: List<AppRule> = emptyList(),
)

@Serializable
data class OperationRecord(
    @SerialName("time") val time: Long,
    @SerialName("package") val packageName: String,
    @SerialName("rule") val rule: String,
    @SerialName("result") val result: String,
)

@Serializable
data class Settings(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("consent") val consent: Boolean = false,
    @SerialName("enabled") val enabled: Boolean = false,
    @SerialName("keep_alive") val keepAlive: Boolean = false,
    @SerialName("apps") val apps: Map<String, AppConfig> = emptyMap(),
    @SerialName("records") val records: List<OperationRecord> = emptyList(),
)
