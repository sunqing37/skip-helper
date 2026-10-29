package dev.skiphelper.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.skiphelper.SkipApplication
import dev.skiphelper.data.AppConfig
import dev.skiphelper.engine.Candidate
import dev.skiphelper.engine.Matcher
import dev.skiphelper.engine.Screen
import dev.skiphelper.engine.Source
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SkipAccessibilityService : AccessibilityService() {
    companion object {
        val connected = MutableStateFlow(false)
        private var instance: SkipAccessibilityService? = null
        fun refreshResidentMode() {
            instance?.let { it.resident.sync(it.store.state.value, retry = true) }
        }
    }
    private val resident by lazy { ResidentNotification(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store get() = (application as SkipApplication).store
    private val ocr = OcrReader()
    private var foreground: String? = null
    private var session: Session? = null
    private var worker: Job? = null
    private var deadline: Job? = null
    private var receiverRegistered = false
    private var gestureInFlight = false
    private var lastScreenshotAt = 0L
    private val screenDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var foregroundProbe: Job? = null
    private var lastProbeAt = 0L
    private var windowGeneration = 0L

    private class Session(val packageName: String, val started: Long, val config: AppConfig) {
        val startedUptime = SystemClock.uptimeMillis()
        var windowId = -1
        var windowRevision = 0
        var pendingWindow = false
        var pendingReason = ""
        val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()
        var ocrAt = 0L
        var ocrPrevious: Candidate? = null
        var ocrPreviousAt = 0L
        var ocrDisabled = false
        var lastAttempt: Candidate? = null
        var attempts = 0
        var sentAt = 0L
        var absentObservations = 0
        var width = 0
        var height = 0
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { stop("屏幕关闭或锁屏") }
    }

    override fun onServiceConnected() {
        instance = this
        connected.value = true
        resident.sync(store.state.value, retry = true)
        if (!receiverRegistered) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            receiverRegistered = true
        }
        scope.launch {
            store.state.collect { settings ->
                resident.sync(settings)
                val current = session
                if (!settings.enabled || !settings.consent ||
                    (current != null && settings.apps[current.packageName] != current.config)) stop("配置已变更")
            }
        }
        // Do not treat reconnecting the service while already inside an app as a launch.
        foreground = safeRead(includeElements = false)?.packageName
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val settings = store.state.value
        if (!settings.enabled || !settings.consent || store.error.value != null) return
        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (windowChanged) {
            windowGeneration++
            session?.let { pauseWindow(it, "窗口变化待复核") }
        }
        // Coalesce foreground probes; content changes in an active session are handled
        // by its bounded scan loop. Never retain the framework-owned AccessibilityEvent.
        if (windowChanged || session == null) requestForegroundProbe()
        val current = session
        if (current != null && event.eventTime >= current.startedUptime) {
            if (event.eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START) {
                if (!gestureInFlight) stop("检测到触摸交互")
                return
            }
            if (event.packageName?.toString() != current.packageName || event.windowId != current.windowId) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                    if (hasScrollMovement(event, current)) { stop("检测到当前页面滚动"); return }
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // A countdown TextView can emit this event without any user input.
                    @Suppress("DEPRECATION")
                    val source = event.source
                    val editable = try { source?.isEditable == true } finally {
                        @Suppress("DEPRECATION")
                        source?.recycle()
                    }
                    if (editable) { stop("检测到文字输入"); return }
                }
                AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                    if (!isOwnClick(event, current)) { stop("检测到点击交互"); return }
                }
            }
        }
    }

    private fun requestForegroundProbe() {
        if (foregroundProbe?.isActive == true) return
        foregroundProbe = scope.launch {
            delay((250L - (now() - lastProbeAt)).coerceAtLeast(0L))
            val settings = store.state.value
            if (!connected.value || !settings.enabled || !settings.consent || store.error.value != null ||
                settings.apps.values.none { it.enabled }) return@launch
            lastProbeAt = now()
            observeForeground()
        }
    }

    @Suppress("DEPRECATION")
    private fun hasScrollMovement(event: AccessibilityEvent, current: Session): Boolean {
        val source = event.source
        val key = try {
            val bounds = Rect()
            source?.getBoundsInScreen(bounds)
            "${event.windowId}:${source?.viewIdResourceName}:${event.className}:${bounds.toShortString()}"
        } finally { source?.recycle() }
        val position = event.scrollX to event.scrollY
        val previous = current.scrollPositions.put(key, position)
        val delta = Build.VERSION.SDK_INT >= 28 && (event.scrollDeltaX != 0 || event.scrollDeltaY != 0)
        val changed = previous != null && (
            (event.scrollX >= 0 && previous.first >= 0 && event.scrollX != previous.first) ||
                (event.scrollY >= 0 && previous.second >= 0 && event.scrollY != previous.second))
        val movedFromOrigin = previous == null && (event.scrollX > 0 || event.scrollY > 0)
        val moved = delta || changed || movedFromOrigin || event.action != 0
        if (!moved) trace(current, "忽略无位移的初始化滚动通知")
        return moved
    }

    @Suppress("DEPRECATION")
    private fun isOwnClick(event: AccessibilityEvent, current: Session): Boolean {
        val attempted = current.lastAttempt ?: return false
        if (now() - current.sentAt > 1000 || event.packageName?.toString() != current.packageName) return false
        val source = event.source ?: return false
        return try {
            val bounds = Rect().also(source::getBoundsInScreen)
            bounds.contains(attempted.element.bounds) && bounds.width() <= attempted.element.bounds.width() * 2 &&
                bounds.height() <= attempted.element.bounds.height() * 2
        } finally { source.recycle() }
    }

    private fun observeForeground() {
        if (locked()) { stop("锁屏"); return }
        val screen = when (val result = readSafely(includeElements = false)) {
            is ScreenReader.ReadResult.Pending -> { session?.let { pauseWindow(it, result.reason) }; return }
            is ScreenReader.ReadResult.Blocked -> { stop(result.reason); return }
            is ScreenReader.ReadResult.Available -> result.screen
        }
        if (screen.packageName == foreground) {
            session?.let { trackWindow(it, screen) }
            return
        }
        stop("前台应用已切换")
        foreground = screen.packageName
        val settings = store.state.value
        val config = settings.apps[screen.packageName] ?: return
        if (!store.ready.value || store.error.value != null || !settings.enabled || !settings.consent || !config.enabled ||
            screen.packageName == packageName) return
        val current = Session(screen.packageName, now(), config).also {
            it.width = screen.width; it.height = screen.height; it.windowId = screen.windowId
        }
        session = current
        trace(current, "开始识别，OCR=${config.ocrEnabled}")
        deadline = scope.launch { delay(15_000); if (session === current) stop("识别时段结束") }
        worker = scope.launch {
            while (isActive && valid(current)) {
                inspect(current)
                delay(250)
            }
        }
    }

    private fun valid(current: Session): Boolean {
        val settings = store.state.value
        return session === current && now() - current.started < 15_000 && !locked() &&
            settings.enabled && settings.consent && store.error.value == null &&
            settings.apps[current.packageName] == current.config && current.config.enabled
    }

    private suspend fun inspect(current: Session) {
        if (!valid(current)) return
        if (current.attempts > 0 && now() - current.sentAt < 800) return
        val result = readScreenAsync()
        if (!valid(current)) return
        val screen = when (result) {
            is ScreenReader.ReadResult.Pending -> { pauseWindow(current, result.reason); return }
            is ScreenReader.ReadResult.Blocked -> { stop(result.reason); return }
            is ScreenReader.ReadResult.Available -> result.screen
        }
        if (screen.packageName != current.packageName) {
            stop("前台应用已切换")
            observeForeground()
            return
        }
        if (!trackWindow(current, screen)) return
        val nodeCandidate = Matcher.find(screen, current.config, Source.NODE)
        val attempted = current.lastAttempt
        if (attempted != null && attempted.source == Source.NODE) {
            verifyOrRetry(current, nodeCandidate)
            return
        }
        if (nodeCandidate != null && attempted == null) {
            execute(current, nodeCandidate)
            return
        }
        if (!current.config.ocrEnabled || Build.VERSION.SDK_INT < 30 || current.ocrDisabled || ocr.busy) return
        if (now() - current.ocrAt < 1000 || now() - lastScreenshotAt < 1000) return
        current.ocrAt = now()
        lastScreenshotAt = current.ocrAt
        val captureAt = now()
        val revision = current.windowRevision
        val recognized = ocr.read(this, screen)
        if (!valid(current) || current.windowRevision != revision || current.pendingWindow) return
        val fresh = (readScreenAsync(includeElements = false) as? ScreenReader.ReadResult.Available)?.screen
        if (!valid(current) || current.windowRevision != revision) return
        if (fresh == null || fresh.packageName != screen.packageName || fresh.windowId != screen.windowId ||
            fresh.width != screen.width || fresh.height != screen.height) {
            pauseWindow(current, "截图后窗口已变化")
            observeForeground()
            return
        }
        if (recognized == null) {
            current.ocrDisabled = true
            current.ocrPrevious = null
            store.recordAsync(current.packageName, "OCR", "截图或识别不可用，本次仅使用控件识别")
            return
        }
        if (now() - captureAt > 1500) { current.ocrPrevious = null; return }
        val candidate = Matcher.find(recognized, current.config, Source.OCR)
        if (candidate != null) current.absentObservations = 0
        if (attempted != null) {
            if (candidate == null) verifyOrRetry(current, null)
            else if (!Matcher.sameTarget(attempted, candidate)) stop("点击后目标改变")
            else if (stableOcr(current, candidate)) verifyOrRetry(current, candidate)
        } else if (candidate != null && stableOcr(current, candidate)) {
            execute(current, candidate)
        }
        if (candidate == null) current.ocrPrevious = null
    }

    private fun stableOcr(current: Session, candidate: Candidate): Boolean {
        val previous = current.ocrPrevious
        val stable = previous != null && now() - current.ocrPreviousAt <= 3000 && Matcher.sameTarget(previous, candidate)
        current.ocrPrevious = candidate
        current.ocrPreviousAt = now()
        return stable
    }

    private suspend fun verifyOrRetry(current: Session, candidate: Candidate?) {
        val attempted = current.lastAttempt ?: return
        if (candidate == null) {
            if (++current.absentObservations >= 2) {
                store.recordAsync(current.packageName, attempted.rule, "目标消失（不等同于广告已成功跳过）")
                stop("目标消失", reportUnconfirmed = false)
            }
            return
        }
        current.absentObservations = 0
        if (!Matcher.sameTarget(attempted, candidate)) { stop("点击后目标改变"); return }
        if (current.attempts >= 2) { stop("已达到重试上限"); return }
        execute(current, candidate)
    }

    private suspend fun execute(current: Session, candidate: Candidate) {
        if (!valid(current) || current.attempts >= 2 || gestureInFlight) return
        val revision = current.windowRevision
        val fresh = (readScreenAsync() as? ScreenReader.ReadResult.Available)?.screen ?: return
        if (!valid(current) || current.windowRevision != revision || current.pendingWindow) return
        if (fresh.packageName != candidate.packageName || fresh.windowId != candidate.windowId ||
            fresh.width != current.width || fresh.height != current.height) return
        val target = if (candidate.source == Source.NODE) {
            Matcher.find(fresh, current.config, Source.NODE)?.takeIf { Matcher.sameTarget(candidate, it) } ?: return
        } else candidate
        if (target.source == Source.OCR && fresh.elements.any {
                (!it.enabled || Matcher.isWaiting(it.text)) && Rect.intersects(it.bounds, target.element.bounds)
            }) return
        current.lastAttempt = target
        current.attempts++
        current.sentAt = now()
        current.ocrPrevious = null
        val nodeClicked = target.source == Source.NODE && runCatching { ScreenReader.clickNode(this, fresh, target.element) }.getOrDefault(false)
        if (nodeClicked) {
            trace(current, "已发送控件点击，${target.rule}")
            store.recordAsync(current.packageName, target.rule, "已发送点击（控件，第 ${current.attempts} 次）")
            return
        }
        // A bounded tap only follows a freshly matched visible target, never a saved coordinate.
        val bounds = target.element.bounds
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        gestureInFlight = true
        val accepted = runCatching { dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { gestureInFlight = false }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                gestureInFlight = false
                if (session === current) stop("点击手势被取消")
            }
        }, null) }.getOrDefault(false)
        if (!accepted) { gestureInFlight = false; stop("点击手势未被系统接受") }
        else {
            trace(current, "已发送手势点击，${target.rule}")
            store.recordAsync(current.packageName, target.rule, "已发送点击（手势，第 ${current.attempts} 次）")
        }
    }

    private fun pauseWindow(current: Session, reason: String) {
        if (!current.pendingWindow) {
            current.windowRevision++
            trace(current, "暂缓识别：$reason")
        }
        current.pendingWindow = true
        current.pendingReason = reason
        current.ocrPrevious = null
        current.absentObservations = 0
    }

    private fun trackWindow(current: Session, screen: Screen): Boolean {
        if (screen.width != current.width || screen.height != current.height) {
            stop("屏幕方向已变化")
            return false
        }
        if (current.pendingWindow || current.windowId != screen.windowId) {
            current.windowRevision++
            current.ocrPrevious = null
            current.absentObservations = 0
            current.scrollPositions.clear()
            trace(current, "窗口已就绪，继续原识别时段")
        }
        current.pendingWindow = false
        current.windowId = screen.windowId
        return true
    }

    private fun trace(current: Session, message: String) {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("SkipHelper", "${current.packageName} +${now() - current.started}ms $message")
        }
    }

    private fun stop(reason: String, reportUnconfirmed: Boolean = true) {
        windowGeneration++
        val current = session
        session = null
        worker?.cancel(); worker = null
        deadline?.cancel(); deadline = null
        if (reportUnconfirmed && current?.lastAttempt != null) {
            store.recordAsync(current.packageName, current.lastAttempt!!.rule, "未确认结果：$reason")
        } else if (current != null && reportUnconfirmed) {
            val detail = if (current.pendingWindow) "$reason（${current.pendingReason}）" else reason
            store.recordAsync(current.packageName, "识别会话", "未点击：$detail")
        }
        if (current != null) trace(current, "结束：$reason")
    }

    private fun safeRead(includeElements: Boolean = true): Screen? =
        (readSafely(includeElements) as? ScreenReader.ReadResult.Available)?.screen

    private suspend fun readScreenAsync(includeElements: Boolean = true): ScreenReader.ReadResult {
        val generation = windowGeneration
        val result = withContext(screenDispatcher) { readSafely(includeElements) }
        return if (generation == windowGeneration) result else ScreenReader.ReadResult.Pending("读取期间窗口已变化")
    }

    private fun readSafely(includeElements: Boolean = true): ScreenReader.ReadResult =
        try { ScreenReader.read(this, includeElements) }
        catch (e: Exception) { ScreenReader.ReadResult.Pending("界面暂时不可读") }
    private fun locked(): Boolean = !(getSystemService(POWER_SERVICE) as PowerManager).isInteractive ||
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    private fun now() = SystemClock.elapsedRealtime()
    override fun onInterrupt() { stop("无障碍服务中断") }
    override fun onUnbind(intent: Intent?): Boolean {
        foregroundProbe?.cancel()
        connected.value = false
        if (instance === this) instance = null
        resident.stop()
        stop("权限撤销")
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        connected.value = false
        if (instance === this) instance = null
        resident.stop()
        stop("服务已停止")
        if (receiverRegistered) unregisterReceiver(screenOff)
        // Let already-running OCR tasks release their bitmaps before closing clients.
        ocr.close()
        scope.cancel()
        super.onDestroy()
    }
}
