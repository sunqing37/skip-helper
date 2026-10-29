package dev.skiphelper.engine

import android.graphics.Rect
import dev.skiphelper.data.AppConfig
import dev.skiphelper.data.Region
import kotlin.math.abs

enum class Source { NODE, OCR }

data class Element(
    val text: String,
    val viewId: String,
    val bounds: Rect,
    val enabled: Boolean = true,
    val path: List<Int>? = null,
)

data class Screen(
    val packageName: String,
    val windowId: Int,
    val width: Int,
    val height: Int,
    val elements: List<Element>,
)

data class Candidate(
    val packageName: String,
    val windowId: Int,
    val element: Element,
    val source: Source,
    val rule: String,
    val evidence: String,
)

object Matcher {
    private val whitespace = Regex("[\\s|｜·]")
    private val number = Regex("^\\d{1,2}$")
    private val adLabels = setOf("广告", "ad", "advertisement", "sponsored")
    private val skip = Regex("^(?:跳过(?:广告)?|skip(?:ad)?)(?:[（(]?\\d{1,2}(?:s|秒)?[）)]?)?$", RegexOption.IGNORE_CASE)
    private val reverseSkip = Regex("^\\d{1,2}(?:s|秒)?(?:跳过(?:广告)?|skip(?:ad)?)$", RegexOption.IGNORE_CASE)
    private val timer = Regex("^(?:\\d{1,2}(?:s|秒)|(?:跳过(?:广告)?|skip(?:ad)?)\\d{1,2}(?:s|秒)?)$", RegexOption.IGNORE_CASE)
    private val waiting = Regex("(?:\\d+\\s*(?:秒|s)?\\s*后|后可跳过|skip\\s+(?:in|after)|available\\s+in)", RegexOption.IGNORE_CASE)
    fun normalized(text: String) = text.replace(whitespace, "").lowercase()
    fun isWaiting(text: String) = waiting.containsMatchIn(text)
    private fun isSkip(text: String) = normalized(text).let { skip.matches(it) || reverseSkip.matches(it) }

    fun find(screen: Screen, config: AppConfig, source: Source): Candidate? {
        val elements = screen.elements
        fun candidate(element: Element, rule: String, evidence: String) = Candidate(
            screen.packageName, screen.windowId, element, source, rule, evidence,
        )
        for (rule in config.rules.filter { it.valid() }) {
            if (source == Source.OCR && rule.viewId.isNotBlank()) continue
            if (elements.none { it.text.contains(rule.pageMarker, ignoreCase = true) }) continue
            val matched = elements.firstOrNull { element ->
                eligible(element, screen) && !waitingNearby(element, screen) && inside(element.bounds, rule.region, screen) &&
                    (rule.viewId.isBlank() || rule.viewId == element.viewId) &&
                    (rule.text.isBlank() || normalized(rule.text) == normalized(element.text) ||
                        (isSkip(rule.text) && isSkip(element.text)))
            }
            if (matched != null) return candidate(matched, "专属规则 ${rule.id}", "页面特征及目标匹配")
        }
        if (!config.genericEnabled) return null
        val adMarker = elements.any { normalized(it.text) in adLabels }
        return elements.firstNotNullOfOrNull { element ->
            if (!eligible(element, screen) || waitingNearby(element, screen) || !isSkip(element.text) || !corner(element.bounds, screen)) return@firstNotNullOfOrNull null
            val adjacentTimer = elements.any { other ->
                (timer.matches(normalized(other.text)) || number.matches(normalized(other.text)) ||
                    (isSkip(other.text) && other.text.any { it.isDigit() })) &&
                    abs(other.bounds.centerX() - element.bounds.centerX()) < screen.width * .2f &&
                    abs(other.bounds.centerY() - element.bounds.centerY()) < screen.height * .08f
            }
            if (adMarker || adjacentTimer) candidate(element, "通用${if (source == Source.OCR) " OCR" else "控件"}", "角落跳过 + ${if (adMarker) "广告标识" else "相邻倒计时"}") else null
        }
    }

    private fun eligible(element: Element, screen: Screen): Boolean = element.enabled &&
        !isWaiting(element.text) && !element.bounds.isEmpty && element.bounds.left >= 0 &&
        element.bounds.top >= 0 && element.bounds.right <= screen.width && element.bounds.bottom <= screen.height &&
        element.bounds.width() < screen.width * .5f && element.bounds.height() < screen.height * .18f

    private fun waitingNearby(element: Element, screen: Screen) = screen.elements.any {
        isWaiting(it.text) && abs(it.bounds.centerX() - element.bounds.centerX()) < screen.width * .25f &&
            abs(it.bounds.centerY() - element.bounds.centerY()) < screen.height * .1f
    }

    private fun inside(bounds: Rect, region: Region, screen: Screen): Boolean =
        bounds.left >= region.left * screen.width && bounds.right <= region.right * screen.width &&
            bounds.top >= region.top * screen.height && bounds.bottom <= region.bottom * screen.height

    private fun corner(bounds: Rect, screen: Screen): Boolean =
        (bounds.centerX() > screen.width * .65f || bounds.centerX() < screen.width * .35f) &&
            (bounds.centerY() < screen.height * .3f || bounds.centerY() > screen.height * .7f)

    fun sameTarget(a: Candidate, b: Candidate): Boolean = a.packageName == b.packageName &&
        a.windowId == b.windowId && a.rule == b.rule && a.source == b.source &&
        a.element.viewId == b.element.viewId &&
        (normalized(a.element.text) == normalized(b.element.text) || (isSkip(a.element.text) && isSkip(b.element.text))) &&
        abs(a.element.bounds.centerX() - b.element.bounds.centerX()) <= 20 &&
        abs(a.element.bounds.centerY() - b.element.bounds.centerY()) <= 20
}
