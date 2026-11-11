package com.armsone.nasfinder.smoke

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.armsone.nasfinder.ui.NasFinderViewModel
import com.armsone.nasfinder.ui.PhotoSelectionAnalysis
import com.armsone.nasfinder.ui.PhotoSelectionPhase
import com.armsone.nasfinder.ui.PhotoSelectionPolicy
import com.armsone.nasfinder.ui.PhotoSelectionSession
import com.armsone.nasfinder.ui.PhotoSelectionStaging
import com.armsone.nasfinder.ui.PhotoSelectionState
import com.hanai.core.ImageDiversitySelector
import com.hanai.core.ImageQualityCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One small native phone smoke: platform Instrumentation + UiAutomation (AccessibilityNodeInfo actions, takeScreenshot).
 * No adb input, no androidx.test. Result is the checks list (PASS / FAIL / UNVERIFIED), never the process exit code alone.
 */
class PhoneSmokeInstrumentation : Instrumentation() {
    companion object { var purgeBatchIds: List<String> = emptyList() }

    private var editsArgs: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        // mode=edits-proof: own-UUID Edits UI proof only. The default smoke (execute/purge) is never reachable from this branch.
        editsArgs = arguments?.takeIf { it.getString("mode") == "edits-proof" }
        if (editsArgs == null) purgeBatchIds = arguments?.getString("purge")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        start()
    }

    override fun onStart() {
        super.onStart()
        val runner = SmokeRun(this, editsArgs)
        val summary = try {
            if (editsArgs != null) runner.executeEdits() else runner.execute()
        } catch (error: Throwable) {
            runner.crash(error)
        }
        val result = Bundle().apply {
            putString("smoke_pass", summary.optInt("pass").toString())
            putString("smoke_fail", summary.optInt("fail").toString())
            putString("smoke_unverified", summary.optInt("unverified").toString())
            putString("smoke_out_dir", runner.outDir.absolutePath)
            putString("smoke_crash", summary.optString("crash"))
        }
        finish(if (summary.optInt("fail") == 0 && !summary.has("crash")) Activity.RESULT_OK else Activity.RESULT_FIRST_USER, result)
    }
}

private class SmokeRun(private val inst: Instrumentation, private val edits: Bundle? = null) {
    private val target = inst.targetContext
    private val pkg = target.packageName
    private val app = target.applicationContext as Application
    // edits-proof: no UiAutomation connection and no directory is created before the ownership guard passes
    private val ua: UiAutomation by lazy { inst.uiAutomation }
    private val runId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    private val editsUuid: String? = edits?.getString("ownUuid")
    private val editsBase: File? = editsUuid?.let { File(target.getExternalFilesDir(null) ?: target.filesDir, "nfa-proof-$it") }
    private val editsTag: String? = edits?.let { "${it.getString("profile")}-${it.getString("orientation")}" }
    private var editsGuardOk = false
    val outDir: File = if (edits != null) File(editsBase!!, "ui/$editsTag") else File(target.getExternalFilesDir(null) ?: target.filesDir, "phone-smoke-$runId").apply { mkdirs() }
    private val screens by lazy { File(outDir, "screens").apply { mkdirs() } }
    private val fixtureDir by lazy { File(outDir, "fixtures").apply { mkdirs() } }
    private val checks = JSONArray()
    private val ui = JSONObject()
    private val analysisJson = JSONObject()
    private val cleanup = JSONObject()
    private val fixtureManifest = JSONObject()
    private var activity: Activity? = null
    private var crashText: String? = null
    private var originalRotation = 0
    private var originalAutoRotation = 0
    private var rotationTouched = false
    private val actions = JSONArray()
    private val ownBatchIds = linkedSetOf<String>()
    private fun protectedSnapshot(): JSONObject {
        val result = JSONObject()
        listOf(target.filesDir, File(target.applicationInfo.dataDir, "shared_prefs")).forEach { dir ->
            dir.walkTopDown().filter { it.isFile }.forEach { f ->
                result.put(f.absolutePath.removePrefix(target.applicationInfo.dataDir), sha256(f))
            }
        }
        return result
    }

    // ---------------------------------------------------------------- bookkeeping

    private fun check(id: String, requirement: String, status: String, detail: String) {
        checks.put(JSONObject().put("id", id).put("requirement", requirement).put("status", status).put("detail", detail))
        inst.sendStatus(0, Bundle().apply { putString("check", "$id|$status"); putString("detail", detail.take(400)) })
    }

    private fun pass(id: String, req: String, ok: Boolean, detail: String) = check(id, req, if (ok) "PASS" else "FAIL", detail)

    fun crash(error: Throwable): JSONObject {
        crashText = error.stackTraceToString().take(3000)
        check("harness.crash", "instrumentation completed without an unexpected exception", "FAIL", crashText!!)
        return writeAll()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeJson(name: String, json: Any) {
        File(outDir, name).writeText(if (json is JSONObject) json.toString(2) else if (json is JSONArray) json.toString(2) else json.toString())
    }

    private fun writeAll(): JSONObject {
        var pass = 0; var fail = 0; var unverified = 0
        for (i in 0 until checks.length()) when (checks.getJSONObject(i).getString("status")) { "PASS" -> pass++; "FAIL" -> fail++; else -> unverified++ }
        val summary = JSONObject().put("runId", runId).put("pass", pass).put("fail", fail).put("unverified", unverified)
        crashText?.let { summary.put("crash", it) }
        if (edits != null && !editsGuardOk) return summary // nothing is written until the own-UUID guard has passed
        writeJson("checks.json", JSONObject().put("summary", summary).put("checks", checks))
        writeJson("ui-record.json", ui)
        writeJson("cleanup.json", cleanup)
        if (edits != null) writeJson("actions.json", actions)
        if (edits == null) {
            writeJson("analysis.json", analysisJson)
            writeJson("fixture-manifest.json", fixtureManifest)
        }
        return summary
    }

    // ---------------------------------------------------------------- ui helpers

    private fun idle() { try { ua.waitForIdle(500, 5000) } catch (_: TimeoutException) {}; Thread.sleep(800) }

    private fun root(): AccessibilityNodeInfo? {
        if (edits != null) return editsRoot()
        repeat(12) {
            // UiAutomation.clearCache() was added in API 34
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            val r = ua.rootInActiveWindow
            if (r != null && r.packageName?.toString() == pkg) return r
            Thread.sleep(250)
        }
        return null
    }

    /** edits-proof root: fails within ~10s (no blind retry) when there is no own root or the own MainActivity is not RESUMED. */
    private fun editsRoot(): AccessibilityNodeInfo? {
        val end = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            val resumed = resumedActivity
            if (resumed != null) {
                val r = ua.rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
                    ?: runCatching { ua.windows.firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == pkg } } }.getOrNull()
                if (r != null) return r
            } else if (android.os.SystemClock.elapsedRealtime() > end - 8_000) {
                // not resumed for ~2s: the activity is gone or backgrounded; waiting longer cannot help
                throw IllegalStateException(rootFailure("own MainActivity is not RESUMED"))
            }
            if (android.os.SystemClock.elapsedRealtime() >= end) throw IllegalStateException(rootFailure("no own root within 10s"))
            Thread.sleep(250)
        }
    }

    private fun rootFailure(why: String): String {
        val a = activity
        return "$why: activity=${a?.javaClass?.simpleName} isFinishing=${a?.isFinishing} isDestroyed=${a?.isDestroyed} resumed=${resumedActivity != null} lifecycleEvents=${lifecycleLog.length()}"
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        // edits-proof (API 29 clearCache is unavailable): refresh each node before its properties/children are read so a
        // stale cached hierarchy never reports old labels/state (e.g. a trim range already changed on screen).
        fun walk(n: AccessibilityNodeInfo) { if (edits != null) n.refresh(); out += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
        root()?.let(::walk)
        return out
    }

    private fun label(n: AccessibilityNodeInfo): String = (n.text?.toString()?.takeIf { it.isNotEmpty() } ?: n.contentDescription?.toString()).orEmpty()
    private fun bounds(n: AccessibilityNodeInfo): Rect = Rect().also { n.getBoundsInScreen(it) }
    private fun labels(): List<String> = nodes().map(::label).filter { it.isNotEmpty() }
    private fun rect(r: Rect) = JSONObject().put("l", r.left).put("t", r.top).put("r", r.right).put("b", r.bottom)

    private fun findAll(text: String, exact: Boolean = true) = nodes().filter { n -> label(n).let { if (exact) it == text else it.contains(text) } }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        return c
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        n.refresh()
        if (edits != null) bringIntoView(n)
        return clickableAncestor(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** The own app window's viewport (falls back to the display when the window root is unavailable). */
    private fun windowViewport(): Rect = root()?.let(::bounds)?.takeIf { it.width() > 0 && it.height() > 0 }
        ?: target.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    /** Node bounds clipped to the window viewport and to its scroll ancestor's viewport (empty when it is scrolled out). */
    private fun clippedBounds(n: AccessibilityNodeInfo): Rect {
        n.refresh()
        val clipped = Rect(bounds(n))
        if (!clipped.intersect(windowViewport())) return Rect()
        val pane = scrollableAncestor(n)
        if (pane != null && !clipped.intersect(bounds(pane))) return Rect()
        return clipped
    }

    /** Visible to the user AND at least half of the node lies inside the window viewport and its scroll ancestor's viewport. */
    private fun fullyShown(n: AccessibilityNodeInfo): Boolean {
        n.refresh()
        val b = bounds(n)
        if (!n.isVisibleToUser || b.width() <= 0 || b.height() <= 0) return false
        val c = clippedBounds(n)
        return c.width() * 2 >= b.width() && c.height() * 2 >= b.height()
    }

    /** Only when the node is off screen: SHOW_ON_SCREEN first, then scroll its own scroll ancestor (the pane that owns it) until it is shown. */
    private fun bringIntoView(n: AccessibilityNodeInfo): Boolean {
        if (fullyShown(n)) return true
        n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id); idle()
        if (fullyShown(n)) return true
        val pane = scrollableAncestor(n) ?: return false
        for (action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            for (i in 0 until 8) {
                pane.refresh()
                if (!pane.performAction(action)) break
                idle()
                if (fullyShown(n)) return true
            }
        }
        return fullyShown(n)
    }

    private fun scrollPaneTop(pane: AccessibilityNodeInfo?) {
        if (pane == null) return
        for (i in 0 until 10) {
            pane.refresh()
            if (!pane.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return
            idle()
        }
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (predicate()) return true; Thread.sleep(300) }
        return predicate()
    }

    /** Looks for [text] on screen; scrolls the first scrollable container forward (then back to top) when it is off screen. */
    private fun findScrolling(text: String, exact: Boolean = true): AccessibilityNodeInfo? {
        val dm = target.resources.displayMetrics
        fun visible(n: AccessibilityNodeInfo): Boolean = if (edits != null) fullyShown(n) else bounds(n).let { it.height() > 0 && it.top >= 0 && it.bottom <= dm.heightPixels }
        findAll(text, exact).firstOrNull(::visible)?.let { return it }
        // edits-proof: a node that exists but is off screen is brought in through its own scroll ancestor, not by scrolling an arbitrary container
        if (edits != null) findAll(text, exact).firstOrNull { bringIntoView(it) }?.let { return it }
        repeat(2) { direction ->
            val action = if (direction == 0) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            repeat(10) {
                val scroller = nodes().firstOrNull { it.isScrollable && it.isVisibleToUser } ?: return null
                if (!scroller.performAction(action)) return@repeat
                idle()
                findAll(text, exact).firstOrNull(::visible)?.let { return it }
            }
        }
        return null
    }

    private fun clickLabel(text: String, exact: Boolean = true): Boolean {
        var ok = false
        repeat(5) {
            if (ok) return@repeat
            val node = findScrolling(text, exact)
            ok = node != null && click(node)
            if (!ok) Thread.sleep(700)
        }
        idle()
        return ok
    }

    private fun back() {
        if (edits != null) {
            // global BACK only while the own app window is foreground/resumed right now AND the own project is the open context
            val a = activity
            check(resumedActivity != null && a != null && a === resumedActivity && !a.isFinishing && !a.isDestroyed) { rootFailure("global BACK refused: own app window is not foreground/resumed") }
            check(ua.rootInActiveWindow?.packageName?.toString() == pkg) { rootFailure("global BACK refused: active window root is not the own package") }
            requireOwn("global-back")
        }
        ua.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); idle() }

    private val tabLabels = listOf("도구", "파인더", "홈", "만들기", "설정")

    private fun tabNode(label: String): AccessibilityNodeInfo? = findAll(label).maxByOrNull { bounds(it).top }
        ?.takeIf { bounds(it).top > target.resources.displayMetrics.heightPixels * 0.7 }

    private fun ensureTabBar(): Boolean {
        repeat(8) {
            if (waitFor(3500) { tabLabels.all { tabNode(it) != null } }) return true
            back()
        }
        return tabLabels.all { tabNode(it) != null }
    }

    private fun launch(rotation: Int = UiAutomation.ROTATION_FREEZE_0) {
        // phone smoke is a portrait-phone check: record the starting rotation, then freeze portrait via UiAutomation (same locked state the device already had)
        if (!rotationTouched) {
            originalRotation = Settings.System.getInt(target.contentResolver, Settings.System.USER_ROTATION, 0)
            originalAutoRotation = Settings.System.getInt(target.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            // the tablet's observed original is user_rotation=1/auto=1; an earlier run's incomplete restore left 0, so reinstate the observed original
            if (edits != null && edits.getString("profile") == "tablet" && originalAutoRotation == 1 && originalRotation == 0) {
                originalRotation = 1; ui.put("rotationBeforeReadAs0", true)
            }
            ui.put("rotationBefore", originalRotation).put("accelerometerBefore", originalAutoRotation)
            rotationTouched = true
        }
        ua.setRotation(rotation)
        // a sleeping screen stops the activity within seconds; wake it with a plain key event (no keyguard/security change)
        val power = target.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        ui.put("interactiveBeforeLaunch", power.isInteractive)
        if (!power.isInteractive) {
            val t0 = android.os.SystemClock.uptimeMillis()
            ua.injectInputEvent(android.view.KeyEvent(t0, t0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_WAKEUP, 0), true)
            ua.injectInputEvent(android.view.KeyEvent(t0, t0, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_WAKEUP, 0), true)
            waitFor(3000) { power.isInteractive }
            ui.put("interactiveAfterWake", power.isInteractive)
        }
        val intent = Intent().setClassName(pkg, "com.armsone.nasfinder.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = inst.startActivitySync(intent)
        idle()
    }

    private fun insets(): JSONObject {
        val a = activity ?: return JSONObject().put("error", "no activity")
        val holder = arrayOfNulls<JSONObject>(1)
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            try {
                val i = ViewCompat.getRootWindowInsets(a.window.decorView)
                val dm = a.resources.displayMetrics
                fun ins(type: Int) = i?.getInsets(type)?.let { JSONObject().put("l", it.left).put("t", it.top).put("r", it.right).put("b", it.bottom) }
                holder[0] = JSONObject()
                    .put("statusBars", ins(WindowInsetsCompat.Type.statusBars()))
                    .put("navigationBars", ins(WindowInsetsCompat.Type.navigationBars()))
                    .put("ime", ins(WindowInsetsCompat.Type.ime())).put("imeVisible", i?.isVisible(WindowInsetsCompat.Type.ime()))
                    .put("systemBarsIgnoringVisibility", i?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())?.let { JSONObject().put("l", it.left).put("t", it.top).put("r", it.right).put("b", it.bottom) })
                    .put("displayCutout", i?.displayCutout?.let { JSONObject().put("l", it.safeInsetLeft).put("t", it.safeInsetTop).put("r", it.safeInsetRight).put("b", it.safeInsetBottom) })
                    .put("density", dm.density).put("densityDpi", dm.densityDpi)
                    .put("widthPx", dm.widthPixels).put("heightPx", dm.heightPixels)
                    .put("decorWidth", a.window.decorView.width).put("decorHeight", a.window.decorView.height)
                    .put("navigationMode", runCatching { Settings.Secure.getInt(a.contentResolver, "navigation_mode") }.getOrNull())
            } catch (e: Throwable) { holder[0] = JSONObject().put("error", e.toString()) }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return holder[0] ?: JSONObject().put("error", "insets timeout")
    }

    private fun pixel(bmp: Bitmap, x: Int, y: Int) = bmp.getPixel(x.coerceIn(0, bmp.width - 1), y.coerceIn(0, bmp.height - 1))
    private fun colorDistance(a: Int, b: Int): Int =
        abs(((a shr 16) and 255) - ((b shr 16) and 255)) + abs(((a shr 8) and 255) - ((b shr 8) and 255)) + abs((a and 255) - (b and 255))
    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    /** Takes the screenshot, saves the PNG and records size/hash/insets/control bounds/edge-pixel evidence for this state. */
    private fun capture(name: String, controls: List<String> = emptyList()): JSONObject {
        idle()
        if (edits != null) requireOwn("capture $name")
        // edits-proof: the foldable default display screenshot may be black for other surfaces, so use the public per-window overload (API 34+)
        // Dialog/popup windows are only in the display capture, so that wins whenever it is not blank.
        val displayShot = ua.takeScreenshot()
        fun blank(b: Bitmap?) = b == null || HashSet<Int>().also { s -> for (y in 0 until b.height step 48) for (x in 0 until b.width step 48) s += b.getPixel(x, y) }.size < 8
        val windowShot = if (edits != null && blank(displayShot) && Build.VERSION.SDK_INT >= 34) activity?.window?.let { runCatching { ua.takeScreenshot(it) }.getOrNull() } else null
        val bmp = windowShot ?: displayShot ?: throw IllegalStateException("takeScreenshot returned null for $name")
        val file = File(screens, "$name.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val inset = insets()
        val status = inset.optJSONObject("statusBars")?.optInt("t") ?: 0
        val nav = inset.optJSONObject("navigationBars")?.optInt("b") ?: 0
        val all = nodes()
        val clickable = all.filter { it.isClickable && it.isVisibleToUser && bounds(it).width() > 0 }
        val intrude = clickable.filter { val b = bounds(it); (status > 0 && b.top < status - 1) || (nav > 0 && b.bottom > bmp.height - nav + 1) }
        val named = JSONObject()
        controls.forEach { c -> findAll(c).maxByOrNull { bounds(it).top }?.let { named.put(c, rect(bounds(clickableAncestor(it) ?: it))) } }
        // Edge background continuity: a pixel on the very first/last row vs. one just inside the app area at the same x.
        val topEdge = pixel(bmp, 4, 1); val topInside = pixel(bmp, 4, status + 6)
        val botEdge = pixel(bmp, 4, bmp.height - 2); val botInside = pixel(bmp, 4, bmp.height - nav - 6)
        val unique = HashSet<Int>().also { s -> for (y in 0 until bmp.height step 24) for (x in 0 until bmp.width step 24) s += bmp.getPixel(x, y) }.size
        val record = JSONObject()
            .put("name", name).put("file", file.absolutePath).put("sha256", sha256(file)).put("bytes", file.length())
            .put("width", bmp.width).put("height", bmp.height).put("sampledUniqueColors", unique)
            .put("insets", inset)
            .put("statusPx", status).put("navPx", nav)
            .put("statusDp", status / (inset.optDouble("density", 1.0))).put("navDp", nav / (inset.optDouble("density", 1.0)))
            .put("controlBounds", named)
            .put("clickableCount", clickable.size)
            .put("clickableIntrudingSystemBars", JSONArray(intrude.map { JSONObject().put("label", label(it)).put("bounds", rect(bounds(it))) }))
            .put("edge", JSONObject()
                .put("topEdge", hex(topEdge)).put("topInside", hex(topInside)).put("topDist", colorDistance(topEdge, topInside))
                .put("bottomEdge", hex(botEdge)).put("bottomInside", hex(botInside)).put("bottomDist", colorDistance(botEdge, botInside)))
            .put("labels", JSONArray(labels().distinct()))
        ui.getJSONArray("screens").put(record)
        val serializer = android.util.Xml.newSerializer()
        val xml = java.io.StringWriter()
        serializer.setOutput(xml); serializer.startDocument("UTF-8", true); serializer.startTag(null, "hierarchy")
        fun dump(n: AccessibilityNodeInfo) {
            serializer.startTag(null, "node")
            serializer.attribute(null, "label", label(n)); serializer.attribute(null, "bounds", bounds(n).toShortString())
            serializer.attribute(null, "clickable", n.isClickable.toString()); serializer.attribute(null, "enabled", n.isEnabled.toString())
            for (idx in 0 until n.childCount) n.getChild(idx)?.let(::dump)
            serializer.endTag(null, "node")
        }
        root()?.let(::dump); serializer.endTag(null, "hierarchy"); serializer.endDocument()
        File(screens, "$name.xml").writeText(xml.toString())
        writeAll()
        return record
    }

    // ---------------------------------------------------------------- main flow

    fun execute(): JSONObject {
        ui.put("screens", JSONArray())
        environment()
        launch()
        try {
            homeRoots()
            settingsOrder()
            photoSelectionAnalysis()
        } finally {
            val rotationOk = ua.setRotation(if (originalAutoRotation == 1) UiAutomation.ROTATION_UNFREEZE else originalRotation)
            idle()
            val afterRotation = Settings.System.getInt(target.contentResolver, Settings.System.USER_ROTATION, -1)
            val afterAuto = Settings.System.getInt(target.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)
            cleanup.put("rotation", JSONObject().put("before", originalRotation).put("autoBefore", originalAutoRotation)
                .put("after", afterRotation).put("autoAfter", afterAuto).put("apiResult", rotationOk))
            pass("cleanup.rotation", "original rotation and accelerometer settings restored and reread",
                rotationOk && afterRotation == originalRotation && afterAuto == originalAutoRotation, cleanup.getJSONObject("rotation").toString())
        }
        return writeAll()
    }

    // ---------------------------------------------------------------- edits-proof (own UUID project only)

    private val ownPrefix get() = "QA Edits ${editsUuid!!.take(8)}"
    private var ownDraftId = ""
    private var ownDir: File? = null
    private var editsOrientation = "portrait"
    private val lifecycleLog = JSONArray()
    @Volatile private var resumedActivity: Activity? = null
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        private fun rec(event: String, a: Activity) { lifecycleLog.put(JSONObject().put("e", event).put("c", a.javaClass.simpleName).put("h", System.identityHashCode(a)).put("t", android.os.SystemClock.elapsedRealtime())) }
        override fun onActivityCreated(a: Activity, s: Bundle?) { rec("created", a) }
        override fun onActivityStarted(a: Activity) { rec("started", a) }
        override fun onActivityResumed(a: Activity) { rec("resumed", a); activity = a; resumedActivity = a }
        override fun onActivityPaused(a: Activity) { rec("paused", a); if (resumedActivity === a) resumedActivity = null }
        override fun onActivityStopped(a: Activity) { rec("stopped", a) }
        override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
        override fun onActivityDestroyed(a: Activity) { rec("destroyed", a) }
    }

    /** Ownership guard: runs before any write or UI action. Nothing but the own-UUID namespace/seed/project can pass. */
    private fun editsGuard() {
        val a = edits!!
        val uuid = a.getString("ownUuid") ?: error("ownUuid required")
        val nonce = a.getString("nonce") ?: error("nonce required")
        check(uuid == "3d17271c-72d6-41df-8e1f-41036394716d" && java.util.UUID.fromString(uuid).toString() == uuid) { "ownUuid is not the approved canonical uuid" }
        val profile = a.getString("profile"); editsOrientation = a.getString("orientation") ?: ""
        check(profile == "phone" || profile == "tablet") { "profile" }
        check(editsOrientation == "portrait" || editsOrientation == "landscape") { "orientation" }
        val base = editsBase!!
        check(File(base, ".owner").let { it.isFile && it.readText() == "$uuid:$nonce" }) { "owner mismatch" }
        val seed = JSONObject(File(base, "seed.json").readText())
        ownDraftId = seed.getString("draftId")
        val dir = File(seed.getString("dir")).canonicalFile
        check(dir.parentFile == File(target.filesDir, "trailer_projects").canonicalFile && dir.isDirectory) { "seed dir is not a canonical project dir" }
        check(dir.listFiles().orEmpty().any { it.isFile && it.length() > 0 && it.name.startsWith("own-$uuid-") }) { "synthetic own media missing" }
        val arr = JSONArray(File(target.filesDir, "trailer_drafts/drafts_meta.json").readText())
        val mine = (0 until arr.length()).mapNotNull { arr.opt(it) as? JSONObject }.filter { it.opt("id") == ownDraftId }
        check(mine.size == 1) { "own draft record count ${mine.size}" }
        check(mine[0].optString("customTitle").startsWith(ownPrefix)) { "own draft title guard" }
        check(mine[0].toString().contains(dir.name) && mine[0].toString().contains("own-$uuid-")) { "own draft dir/media guard" }
        ownDir = dir
        editsGuardOk = true
        outDir.mkdirs()
    }

    private fun act(name: String, ok: Boolean, startMs: Long, detail: String = "") {
        val ms = android.os.SystemClock.elapsedRealtime() - startMs
        actions.put(JSONObject().put("name", name).put("ok", ok).put("ms", ms).put("detail", detail.take(300)))
        check("act.$name", name, if (ok) "PASS" else "FAIL", "ms=$ms $detail")
    }

    /** Short digit-free labels only: enough to see which app chrome is on a list screen without echoing user titles. */
    private fun chromeLabels() = labels().distinct().filter { it.length <= 10 && it.none(Char::isDigit) }

    private fun editorOpen() = "편집 닫기 (편집본 자동 저장)" in labels()

    // ---- ownership identity (fail-closed): the open editor must be the own seed project in the real ViewModel, not just a matching header.

    /** Canonical dir of the editor session currently held by the ViewModel, only when the stored own draft record points at the same dir. */
    private fun ownSessionOpen(): Boolean = runCatching {
        val own = ownDir ?: return false
        val session = viewModel().state.value.trailer ?: return false
        if (session.draftId != null && session.draftId != ownDraftId) return false
        val open = session.directory.canonicalFile
        val stored = com.armsone.nasfinder.ui.TrailerDraftStore.getEveryDraft().firstOrNull { it.id == ownDraftId }?.directory?.canonicalFile
        open == own && stored == own
    }.getOrDefault(false)

    private fun headerOwned(): Boolean {
        val screenH = target.resources.displayMetrics.heightPixels
        return nodes().any { label(it).startsWith(ownPrefix) && bounds(it).top < screenH * 0.25 }
    }

    /** True for the own project's session whether the editor, one of its dialogs or its export view is on screen; false for anything else. */
    private fun ownContext(): Boolean = ownSessionOpen() && (!editorOpen() || headerOwned())

    /** The editor is open AND is the own project (header prefix + ViewModel session dir + stored own draft dir). */
    private fun ownedEditor(): Boolean = editorOpen() && ownContext()

    private fun requireOwn(what: String) { check(ownContext()) { "ownership guard refused '$what': open editor/session is not the own project" } }

    private fun ownClick(text: String, exact: Boolean = true): Boolean = ownContext() && clickLabel(text, exact)

    /** stage=remaining29: only the still-failed tool / low-landscape / rotation / native checks run (already-passed full proofs are not repeated). */
    private val remaining29 get() = edits?.getString("stage").let { it == "remaining29" || it == "toolsOnly" }

    /** stage=toolsOnly (same own mode/guard/entry/finally): only openTrimPanel + detailPanels + read-only protected state; no OS request, no rename/trim/split/rotate/export. */
    private val toolsOnly get() = edits?.getString("stage") == "toolsOnly"

    /** Set when the known OS permission dialog stayed pending (BACK did not dismiss it): own-root checks must not run behind it. */
    private var osPromptPending = false

    /** remaining29: a missing selector returns within [totalMs] overall (no clickLabel 5x findScrolling loops); the shown node comes from its own pane. */
    private fun ownClickFast(text: String, totalMs: Long = 10_000): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + totalMs
        // toolsOnly tablet: a pruned (off-screen) chip does not exist yet, so the SAME captured inspector is moved forward (bounded) until it appears
        var inspectorSteps = 0
        while (android.os.SystemClock.elapsedRealtime() < end) {
            if (!ownContext()) return false
            val node = findAll(text).firstOrNull { clickableAncestor(it) != null && (fullyShown(it) || (android.os.SystemClock.elapsedRealtime() < end && bringIntoView(it))) }
            if (node != null && click(node)) { idle(); return true }
            if (toolsOnly && inspectorRect != null) {
                if (inspectorSteps < 10 && inspectorScroll(true)) { inspectorSteps++; continue }
                if (inspectorSteps >= 10) return false
            }
            Thread.sleep(300)
        }
        return false
    }

    /** The actual '만들기' destination of the own MainActivity: bottom tab (phone) or side rail (tablet); no 70%-height condition, no back navigation. */
    private fun makeNode(): AccessibilityNodeInfo? {
        val screenH = target.resources.displayMetrics.heightPixels
        val candidates = findAll("만들기").filter { clickableAncestor(it) != null }
        return candidates.filter { bounds(it).top > screenH * 0.7 }.maxByOrNull { bounds(it).top } ?: candidates.minByOrNull { bounds(it).left }
    }

    /** Never touches a non-owned open editor: it throws instead of closing, saving or capturing it. */
    private fun openOwnProject(): Boolean {
        repeat(3) {
            if (editorOpen()) {
                check(waitFor(3000) { ownedEditor() }) { "ownership guard: an editor that is not the own project is open; refusing to touch it" }
                return true
            }
            makeNode()?.let { click(it) }; idle()
            val n = findScrolling(storedTitle()?.takeIf { it.startsWith(ownPrefix) } ?: ownPrefix, exact = false)
            if (n != null && click(n) && waitFor(8000) { editorOpen() }) {
                check(waitFor(3000) { ownedEditor() }) { "ownership guard: opened editor is not the own project; refusing to touch it" }
                return true
            }
        }
        if (editorOpen()) check(ownedEditor()) { "ownership guard: an editor that is not the own project is open; refusing to touch it" }
        return ownedEditor()
    }

    private fun closeEditor(): Boolean {
        requireOwn("close-editor")
        check(ownedEditor()) { "ownership guard refused 'close-editor': editor is not open for the own project" }
        val ok = clickLabel("편집 닫기 (편집본 자동 저장)")
        return ok && waitFor(8000) { !editorOpen() }
    }

    private fun setField(text: String): Boolean {
        val field = nodes().firstOrNull { it.isEditable } ?: return false
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) })
        idle(); return ok
    }

    private fun storedTitle(): String? = com.armsone.nasfinder.ui.TrailerDraftStore.getEveryDraft().firstOrNull { it.id == ownDraftId }?.customTitle

    /** The title row in the editor header (clickable, onClickLabel 프로젝트 이름 바꾸기); located by its own text. */
    private fun renameViaUi(newName: String): Boolean {
        val screenH = target.resources.displayMetrics.heightPixels
        requireOwn("rename-ui")
        val header = nodes().filter { label(it).startsWith(ownPrefix) && bounds(it).top < screenH * 0.25 }.minByOrNull { bounds(it).top } ?: return false
        if (!click(header)) return false
        if (!waitFor(5000) { "프로젝트 이름" in labels() }) return false
        capture("e02-rename-dialog", listOf("확인", "취소"))
        requireOwn("rename-field")
        if (!setField(newName)) return false
        if (!ownClick("확인")) return false
        return waitFor(6000) { "프로젝트 이름" !in labels() && nodes().any { label(it).contains(newName) && bounds(it).top < screenH * 0.25 } }
    }

    private fun rotationFor(orientation: String) = if (orientation == "landscape") UiAutomation.ROTATION_FREEZE_90 else UiAutomation.ROTATION_FREEZE_0

    private fun restoreRotation() {
        // freeze the original user rotation first (the freeze rewrites USER_ROTATION), then un-freeze when auto-rotate was on
        var rotationOk = ua.setRotation(originalRotation)
        idle()
        if (originalAutoRotation == 1) { rotationOk = ua.setRotation(UiAutomation.ROTATION_UNFREEZE) && rotationOk; idle() }
        val afterRotation = Settings.System.getInt(target.contentResolver, Settings.System.USER_ROTATION, -1)
        val afterAuto = Settings.System.getInt(target.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1)
        cleanup.put("rotation", JSONObject().put("before", originalRotation).put("autoBefore", originalAutoRotation)
            .put("after", afterRotation).put("autoAfter", afterAuto).put("apiResult", rotationOk))
        pass("cleanup.rotation", "original rotation and accelerometer settings restored and reread",
            rotationOk && afterRotation == originalRotation && afterAuto == originalAutoRotation, cleanup.getJSONObject("rotation").toString())
    }

    fun executeEdits(): JSONObject {
        ui.put("screens", JSONArray())
        editsGuard()
        app.registerActivityLifecycleCallbacks(lifecycle)
        try {
            editsEnvironment()
            launch(rotationFor(editsOrientation))
            editsFlow()
        } finally {
            runCatching { app.unregisterActivityLifecycleCallbacks(lifecycle) }
            ui.put("lifecycle", lifecycleLog)
            if (rotationTouched) restoreRotation()
        }
        return writeAll()
    }

    private fun editsEnvironment() {
        val info = target.packageManager.getPackageInfo(pkg, 0)
        val dm = target.resources.displayMetrics
        val env = JSONObject().put("pid", android.os.Process.myPid()).put("versionName", info.versionName).put("versionCode", info.longVersionCode)
            .put("model", Build.MODEL).put("device", Build.DEVICE).put("sdkInt", Build.VERSION.SDK_INT).put("profile", edits!!.getString("profile")).put("orientation", editsOrientation)
            .put("widthPx", dm.widthPixels).put("heightPx", dm.heightPixels).put("densityDpi", dm.densityDpi)
            .put("storeDrafts", com.armsone.nasfinder.ui.TrailerDraftStore.getEveryDraft().size)
            .put("metadataProblem", com.armsone.nasfinder.ui.TrailerDraftStore.metadataProblem() != null)
            .put("installedApkSha256", sha256(File(info.applicationInfo!!.sourceDir))) // the one app binary file only
        ui.put("environment", env)
        pass("env.version", "installed app is 2.40.0 / 452079", info.versionName == "2.40.0" && info.longVersionCode == 452079L, env.toString())
    }

    private fun editsFlow() {
        var t = android.os.SystemClock.elapsedRealtime()
        val opened = openOwnProject()
        val diag = if (opened) "" else "chrome=" + chromeLabels() + " activePkg=" + ua.rootInActiveWindow?.packageName +
            " windows=" + runCatching { ua.windows.map { "${it.type}:${it.root?.packageName}" } }.getOrNull() +
            " keyguardLocked=" + (target.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        act("open-own", opened, t, diag)
        if (!opened) return
        val dm = activity!!.resources.displayMetrics
        ui.put("orientationMetrics", JSONObject().put("w", dm.widthPixels).put("h", dm.heightPixels))
        pass("orientation.applied", "frozen rotation matches requested $editsOrientation", (dm.heightPixels > dm.widthPixels) == (editsOrientation == "portrait"), "w=${dm.widthPixels} h=${dm.heightPixels}")
        if (toolsOnly) {
            requireOwn("tools-only")
            visibilityRecord("toolsOnly-layout")
            if (openTrimPanel()) visibilityRecord("toolsOnly-trim")
            detailPanels()
            protectedStateProof()
            if (resumedActivity != null && ownedEditor()) closeEditor()
            return
        }
        if (remaining29) {
            remaining29Flow()
            // a pending OS dialog (or any non-own foreground window) hides the app: the own root cannot be read, so the editor close is skipped
            if (!osPromptPending && resumedActivity != null && ownedEditor()) closeEditor()
            return
        }
        capture("e01-editor-open", listOf("편집 닫기 (편집본 자동 저장)", "실행 취소", "다시 실행"))
        requireOwn("editor-scroll")
        repeat(3) { i ->
            nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); idle()
            capture("e03-scroll-$i")
        }
        repeat(6) { nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
        idle()
        // rename through the real header title row, close, reopen by the new name
        val newName = "$ownPrefix ${edits!!.getString("profile")}-$editsOrientation"
        t = android.os.SystemClock.elapsedRealtime()
        val renamed = renameViaUi(newName)
        act("rename-ui", renamed, t)
        capture("e04-renamed", listOf("편집 닫기 (편집본 자동 저장)"))
        t = android.os.SystemClock.elapsedRealtime()
        val closed = closeEditor()
        act("close-editor", closed, t)
        t = android.os.SystemClock.elapsedRealtime()
        val reopened = closed && openOwnProject()
        val shown = reopened && nodes().any { label(it).contains(newName) }
        act("reopen-renamed", shown, t)
        pass("rename.persisted", "renamed title is shown after close+reopen and is the stored title", shown && storedTitle() == newName, "stored=${storedTitle() == newName}")
        capture("e05-reopened", listOf("편집 닫기 (편집본 자동 저장)"))
        editsPhases()
        if (ownedEditor()) closeEditor()
    }

    // ---- touch + semantic helpers (own project only)

    // injectInputEvent results of the last gesture (DOWN / MOVE count ok of total / UP), reported in the action detail.
    private var injDown = false
    private var injMoves = 0
    private var injMovesOk = 0
    private var injUp = false

    private fun touch(action: Int, downTime: Long, x: Float, y: Float) {
        if (action == android.view.MotionEvent.ACTION_DOWN) requireOwn("touch")
        val ev = android.view.MotionEvent.obtain(downTime, android.os.SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        val ok = ua.injectInputEvent(ev, true); ev.recycle()
        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> injDown = ok
            android.view.MotionEvent.ACTION_MOVE -> { injMoves++; if (ok) injMovesOk++ }
            android.view.MotionEvent.ACTION_UP -> injUp = ok
        }
    }

    private fun gestureReset() { injDown = false; injMoves = 0; injMovesOk = 0; injUp = false }

    /** Injection results of the last gesture plus the target's bounds and the window clip it had to land in. */
    private fun gestureDetail(target: Rect?) =
        "inject(down=$injDown move=$injMovesOk/$injMoves up=$injUp) target=${target?.toShortString()} window=${windowViewport().toShortString()}"

    /** Real touch drag through UiAutomation.injectInputEvent: DOWN, interpolated MOVEs, UP. */
    private fun drag(x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 14) {
        gestureReset()
        val down = android.os.SystemClock.uptimeMillis()
        touch(android.view.MotionEvent.ACTION_DOWN, down, x0, y0)
        for (i in 1..steps) { Thread.sleep(16); touch(android.view.MotionEvent.ACTION_MOVE, down, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps) }
        touch(android.view.MotionEvent.ACTION_UP, down, x1, y1)
        idle()
    }

    private fun tap(x: Float, y: Float) {
        gestureReset()
        val down = android.os.SystemClock.uptimeMillis()
        touch(android.view.MotionEvent.ACTION_DOWN, down, x, y); Thread.sleep(40); touch(android.view.MotionEvent.ACTION_UP, down, x, y)
        idle()
    }

    private fun center(n: AccessibilityNodeInfo) = bounds(n).let { it.exactCenterX() to it.exactCenterY() }

    private fun rangeLabel(): Pair<Double, Double>? = labels().firstOrNull { it.startsWith("영상 구간:") }?.let {
        Regex("([0-9.]+)초 ~ ([0-9.]+)초").find(it)?.let { m -> m.groupValues[1].toDouble() to m.groupValues[2].toDouble() }
    }

    private fun clipCount(): Int? = labels().firstNotNullOfOrNull { Regex("클립 (\\d+)개").find(it)?.groupValues?.get(1)?.toInt() }

    private fun playheadSeconds(): Double? = labels().firstNotNullOfOrNull { Regex("타임라인 ([0-9.]+)초부터").find(it)?.groupValues?.get(1)?.toDouble() }

    /** Order + inclusion + protection of every scene, and the full manual fields (timing, adjustments) of the protected ones. */
    private fun manualState(): Pair<List<String>, List<String>> {
        val scenes = viewModel().state.value.trailer?.editor?.scenes.orEmpty()
        return scenes.map { "${it.key}|${it.excluded}|${it.automationProtected}" } to
            scenes.filter { it.automationProtected }.map { "${it.key}|${it.start}|${it.length}|${it.adjustments}" }
    }

    private fun sceneSnapshot(): List<String> = com.armsone.nasfinder.ui.TrailerDraftStore.getEveryDraft().firstOrNull { it.id == ownDraftId }?.editor?.scenes.orEmpty().map { it.toString() }

    /** Preview, timeline and the selected clip name/number must all be on screen together in the editor viewport. */
    private fun visibilityRecord(tag: String) {
        val dm = target.resources.displayMetrics
        val nav = insets().optJSONObject("navigationBars")?.optInt("b") ?: 0
        val all = nodes()
        fun ok(pred: (String) -> Boolean) = all.firstOrNull { pred(label(it)) && bounds(it).let { b -> b.height() > 0 && b.top >= 0 && b.bottom <= dm.heightPixels - nav } && it.isVisibleToUser }
        val preview = ok { it.endsWith("클립 화면") }
        val timeline = ok { it.startsWith("프로젝트 타임라인") }
        val clip = ok { Regex("^\\d+번 클립 [·/]").containsMatchIn(it) }
        pass("visible.$tag", "preview + timeline + selected clip name/number are all visible together ($tag)", preview != null && timeline != null && clip != null,
            "preview=${preview?.let(::bounds)} timeline=${timeline?.let(::bounds)} clip=${clip?.let { label(it).take(6) + bounds(it) }}")
    }

    // ---- own-metadata comparison: non-own records only, hashes/order/types/unknown keys (never values)

    private fun canon(v: Any?, sb: StringBuilder) {
        when (v) {
            null, JSONObject.NULL -> sb.append("null")
            is JSONObject -> { sb.append("{"); v.keys().asSequence().toList().sorted().forEachIndexed { i, k -> if (i > 0) sb.append(","); sb.append("s:").append(k.length).append(":").append(k).append("="); canon(v.get(k), sb) }; sb.append("}") }
            is JSONArray -> { sb.append("["); for (i in 0 until v.length()) { if (i > 0) sb.append(","); canon(v.get(i), sb) }; sb.append("]") }
            is String -> sb.append("s:").append(v.length).append(":").append(v)
            is Boolean -> sb.append("b:").append(v)
            is Number -> sb.append("nv:").append(runCatching { java.math.BigDecimal(v.toString()).stripTrailingZeros().let { if (it.signum() == 0) "0" else it.toPlainString() } }.getOrDefault(v.toString()))
            else -> sb.append("?")
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun nonOwnFingerprint(): JSONObject {
        val arr = JSONArray(File(target.filesDir, "trailer_drafts/drafts_meta.json").readText())
        val recs = JSONArray(); val digests = ArrayList<String>(); var own = 0
        for (i in 0 until arr.length()) {
            val v = arr.get(i)
            if (v is JSONObject && v.opt("id") == ownDraftId) { own++; continue }
            val sb = StringBuilder(); canon(v, sb)
            val d = sha(sb.toString().toByteArray()); digests += d
            val type = when (v) { is JSONObject -> "object"; is JSONArray -> "array"; is String -> "string"; is Boolean -> "boolean"; is Number -> "number"; else -> "null" }
            val unknown = (v as? JSONObject)?.keys()?.asSequence()?.filter { it !in setOf("id", "isMusicOverlayMode", "directory", "total", "clipLength", "photoClipLength", "format", "musicPolicy", "musicFitCount", "autoDucking", "output", "showingSettings", "orderMode", "timestamp", "customTitle", "isPrivate", "revision", "outputRevision", "musicFile", "musicName", "musicDuration", "editor", "sources", "voiceovers", "batchCaption") }?.toList()?.sorted().orEmpty()
            recs.put(JSONObject().put("i", i).put("type", type).put("digest", d).put("unknownKeysSha", sha(unknown.joinToString("\u0000").toByteArray())))
        }
        return JSONObject().put("total", arr.length()).put("own", own).put("nonOwn", digests.size).put("orderSha", sha(digests.joinToString(",").toByteArray())).put("records", recs)
    }

    private fun editsPhases() {
        val profile = edits!!.getString("profile")
        val metaBefore = nonOwnFingerprint()
        writeJson("meta-before-$editsTag.json", metaBefore)
        val scenesBefore = sceneSnapshot()
        ui.put("scenesBefore", JSONArray(scenesBefore))
        try {
            trimSplitProtect()
            detailPanels()
            rotationContinuity()
            exportOwn()
        } finally {
            val metaAfter = nonOwnFingerprint()
            writeJson("meta-after-$editsTag.json", metaAfter)
            val same = metaBefore.getInt("nonOwn") == metaAfter.getInt("nonOwn") && metaBefore.getString("orderSha") == metaAfter.getString("orderSha") &&
                metaBefore.getJSONArray("records").toString() == metaAfter.getJSONArray("records").toString()
            pass("meta.nonOwnUnchanged", "non-own metadata records unchanged (count/order/type/unknown keys/canonical digests) after the UI run", same && metaAfter.getInt("own") == 1,
                "before=${metaBefore.getInt("nonOwn")} after=${metaAfter.getInt("nonOwn")} order=${metaBefore.getString("orderSha") == metaAfter.getString("orderSha")} own=${metaAfter.getInt("own")}")
            ui.put("scenesAfter", JSONArray(sceneSnapshot()))
            ui.put("metadataProblemAfter", com.armsone.nasfinder.ui.TrailerDraftStore.metadataProblem() != null)
        }
        ui.put("profile", profile)
    }

    private fun trimPanelShown() = labels().any { it.startsWith("영상 구간:") }

    /**
     * Phone: the editor's own 설정 tab. Tablet: the 설정 chooser is a placement role (not a tab) and the sidebar 설정 is global, so neither is
     * clicked; the right inspector already shows its 자막·구간 tab (clicked only when another tab is active).
     */
    private fun openTrimPanel(): Boolean {
        val tablet = edits!!.getString("profile") == "tablet"
        if (!tablet && trimPanelShown()) return true
        if (!ownContext()) return trimPanelShown()
        if (tablet) {
            // the visible 자막·구간 tab of the right-hand inspector (rightmost match: never the sidebar or the top role chooser)
            fun inspectorTab() = findAll("자막·구간").filter { clickableAncestor(it) != null && fullyShown(it) }.maxByOrNull { bounds(it).left }
            if (remaining29) return openTrimTabletPane()
            if (!trimPanelShown()) inspectorTab()?.let { click(it) }
            val shown = waitFor(4000) { trimPanelShown() }
            // scroll the inspector pane that owns the tab (or the trim range) to its top, never another container
            val pane = inspectorTab()?.let(::scrollableAncestor) ?: findAll("영상 구간:", exact = false).firstOrNull()?.let(::scrollableAncestor)
            scrollPaneTop(pane)
            return shown && trimPanelShown()
        }
        val screenH = target.resources.displayMetrics.heightPixels
        findAll("설정").filter { bounds(it).top < screenH * 0.25 && clickableAncestor(it) != null && fullyShown(it) }.maxByOrNull { bounds(it).left }?.let { click(it) }
        return waitFor(4000) { trimPanelShown() }
    }

    private fun scrollableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n.parent
        while (c != null && !c.isScrollable) c = c.parent
        return c
    }

    /** The scroll pane that owns [n]: the nearest scrollable ancestor that is a real pane (>= 25% of the window height), never a small chip-row scroller. */
    private fun ownPane(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val minH = windowViewport().height() / 4
        var nearest: AccessibilityNodeInfo? = null
        var c: AccessibilityNodeInfo? = n.parent
        while (c != null) {
            if (c.isScrollable) {
                if (nearest == null) nearest = c
                if (bounds(c).height() >= minH) return c
            }
            c = c.parent
        }
        return nearest
    }

    private fun settle() { try { ua.waitForIdle(250, 1500) } catch (_: TimeoutException) {}; Thread.sleep(250) }

    /**
     * remaining29/toolsOnly tablet: one bounded (~12s) query. The inspector pane is captured ONCE (from the visible 자막·구간 tab, else from a visible own
     * inspector node / the right-hand scroller) and every scroll reuses it: BACKWARD until the tab is visible, click the tab, FORWARD until the range appears.
     * Never the global settings, the left clip board or any other container.
     */
    private fun openTrimTabletPane(): Boolean {
        val end = android.os.SystemClock.elapsedRealtime() + 12_000
        fun left() = end - android.os.SystemClock.elapsedRealtime()
        fun finish(): Boolean { findAll("영상 구간:", exact = false).firstOrNull()?.let { bringIntoView(it) }; return trimPanelShown() }
        // the visible tab label counts even when it is not clickable (the selected tab); the inspector viewport is captured once from it
        inspectorTabLabel()?.let(::captureInspector)
        if (inspectorRect == null) captureInspectorFallback()
        if (trimPanelShown()) return finish()
        var tabN = inspectorTabLabel()
        var i = 0
        while (tabN == null && i++ < 8 && left() > 0 && inspectorRect != null) {
            // the tab was scrolled out: move the SAME captured inspector toward the top until it is visible again
            if (!inspectorScroll(false)) break
            tabN = inspectorTabLabel()
        }
        if (tabN == null) return finish()
        captureInspector(tabN)
        // a non-clickable tab label is the already-selected tab: nothing to click
        val active = clickableAncestor(tabN) == null || generateSequence(tabN) { it.parent }.take(4).any { it.isSelected }
        if (!active) { click(tabN); settle() }
        var j = 0
        while (!trimPanelShown() && j++ < 10 && left() > 0) {
            if (!inspectorScroll(true)) break
        }
        return finish()
    }

    /** toolsOnly tablet: the own right inspector viewport, captured once (bounds from the exact own nodes, never the left board / global settings / full window). */
    private var inspectorRect: Rect? = null
    /** net forward steps of [inspectorScroll] since the pane was last at its top (reset walks back exactly this many). */
    private var inspectorFwd = 0

    private fun inspectorTabLabel(): AccessibilityNodeInfo? {
        val vp = windowViewport()
        return findAll("자막·구간").filter { fullyShown(it) && bounds(it).exactCenterX() > vp.exactCenterX() }.maxByOrNull { bounds(it).left }
    }

    /** Viewport from a node inside the inspector: the scrollable pane when the API reports one, else the nearest ancestor that is wide enough and lies in the right part of the window. */
    private fun captureInspector(from: AccessibilityNodeInfo) {
        if (inspectorRect != null) return
        val vp = windowViewport()
        fun rightPart(b: Rect) = b.width() > 0 && b.height() > 0 && b.left >= vp.left + vp.width() * 0.4
        ownPane(from)?.let { p -> bounds(p).takeIf(::rightPart)?.let { inspectorRect = Rect(it).also { r -> r.intersect(vp) }; return } }
        var c: AccessibilityNodeInfo? = from
        while (c != null) {
            val b = bounds(c)
            if (rightPart(b) && b.width() >= vp.width() * 0.3) { inspectorRect = Rect(b).also { it.intersect(vp) }; return }
            c = c.parent
        }
    }

    /** The tab is scrolled away: capture from any other visible inspector node (clip info, chips, trim range). */
    private fun captureInspectorFallback() {
        val vp = windowViewport()
        val names = setOf("추천 구간", "분할", "보호", "속도", "색상", "자막 타이밍", "움직임", "전환", "음악", "음성")
        nodes().firstOrNull { n -> n.isVisibleToUser && bounds(n).exactCenterX() > vp.exactCenterX() && label(n).let { it in names || it.startsWith("영상 구간:") } }?.let(::captureInspector)
    }

    /** One step of the captured inspector: the scroll action when a scrollable own node supports it, else a short real touch drag (~70% of the pane height) wholly inside it. */
    private fun inspectorScroll(forward: Boolean): Boolean {
        val r = inspectorRect ?: return false
        requireOwn("inspector-scroll")
        val scroller = nodes().filter { n -> n.isScrollable && n.isVisibleToUser && bounds(n).let { b -> b.width() > 0 && b.left >= r.left - 8 && b.right <= r.right + 8 && r.contains(b.centerX(), b.centerY()) } }
            .maxByOrNull { bounds(it).width() * bounds(it).height() }
        var mode = "action"
        var ok = scroller?.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        var detail = ""
        if (ok) settle() else {
            mode = "drag"
            val v = Rect(r)
            if (!v.intersect(windowViewport()) || v.height() < 40) return false
            val x = v.left + v.width() * 0.05f
            val hi = v.top + v.height() * 0.85f
            val lo = v.top + v.height() * 0.15f
            if (forward) drag(x, hi, x, lo) else drag(x, lo, x, hi)
            ok = injDown && injUp
            detail = gestureDetail(v)
        }
        if (ok) inspectorFwd = (inspectorFwd + if (forward) 1 else -1).coerceAtLeast(0)
        val log = ui.optJSONArray("inspectorScrolls") ?: JSONArray().also { ui.put("inspectorScrolls", it) }
        if (log.length() < 80) log.put(JSONObject().put("forward", forward).put("mode", mode).put("ok", ok).put("detail", detail))
        return ok
    }

    /** Back toward the inspector top by exactly the net forward steps taken (plus one), through the same captured pane. */
    private fun inspectorTop() {
        repeat(inspectorFwd + 1) { if (!inspectorScroll(false)) return }
        inspectorFwd = 0
    }

    private fun trimSplitProtect() {
        var t = android.os.SystemClock.elapsedRealtime()
        val settings = openTrimPanel()
        act("open-settings-trim", settings, t)
        if (!settings) { capture("t00-no-trim-panel"); return }
        visibilityRecord("trim-panel")
        capture("t01-trim-before", listOf("분할", "보호", "추천 구간", "영상에서 제외"))
        val clipsBefore = clipCount()
        val rangeBefore = rangeLabel()
        // touch-drag the end handle (observed node bounds) forward by roughly two seconds of source time
        var endHandle = findAll("범위 끝").firstOrNull()
        var track = findAll("영상 클립 시작과 끝").firstOrNull()
        // the handle and track must really be on screen (own pane, window viewport) before the physical drag; re-read after any scroll
        val handlesShown = endHandle != null && track != null && bringIntoView(endHandle) && bringIntoView(track)
        endHandle = findAll("범위 끝").firstOrNull(); track = findAll("영상 클립 시작과 끝").firstOrNull()
        if (endHandle == null || track == null || rangeBefore == null || !handlesShown || !fullyShown(endHandle)) {
            check("trim.handles", "trim handle nodes found and shown", "FAIL", "end=${endHandle != null} track=${track != null} shown=$handlesShown range=$rangeBefore"); return
        }
        val (hx, hy) = center(endHandle)
        val tb = bounds(track)
        val perSecond = (tb.width() - 36f) / 12.0f // source is 12.0s long in the own seed; the track keeps a small inset
        t = android.os.SystemClock.elapsedRealtime()
        drag(hx, hy, hx + perSecond * 2f, hy)
        val dragDetail = gestureDetail(bounds(endHandle))
        // fresh nodes only: poll until the range actually changed (or a short limit), never an old cached label
        waitFor(2500) { (rangeLabel()?.second ?: 0.0) > rangeBefore.second + 0.4 }
        val rangeAfter = rangeLabel()
        act("trim-handle-drag", rangeAfter != null && rangeAfter.second > rangeBefore.second + 0.4, t, "before=$rangeBefore after=$rangeAfter dragPx=${perSecond * 2f} $dragDetail")
        capture("t02-trim-after-drag", listOf("범위 시작", "범위 끝"))
        // playhead: a real tap on the project timeline inside clip 1, then split at that playhead
        val timeline = findAll("프로젝트 타임라인", exact = false).firstOrNull()?.also { bringIntoView(it) }?.let { findAll("프로젝트 타임라인", exact = false).firstOrNull() }
        val phBefore = playheadSeconds()
        if (timeline != null) {
            val b = clippedBounds(timeline)
            t = android.os.SystemClock.elapsedRealtime()
            tap(b.left + b.width() * 0.08f, b.exactCenterY())
            val tapDetail = gestureDetail(b)
            waitFor(2000) { playheadSeconds().let { it != null && it != phBefore } }
            val phAfter = playheadSeconds()
            act("playhead-tap", phAfter != null && phAfter != phBefore, t, "before=$phBefore after=$phAfter $tapDetail")
        } else check("playhead.timeline", "timeline node found", "FAIL", "missing")
        val splitBefore = clipCount()
        t = android.os.SystemClock.elapsedRealtime()
        val split = ownClick("분할") && waitFor(4000) { clipCount() == (splitBefore ?: 0) + 1 }
        act("split-at-playhead", split, t, "clips=$splitBefore->${clipCount()}")
        capture("t03-after-split")
        val scenesAfterSplit = sceneSnapshot()
        t = android.os.SystemClock.elapsedRealtime()
        val undone = ownClick("실행 취소") && waitFor(4000) { clipCount() == splitBefore }
        act("undo", undone, t, "clips=${clipCount()}")
        t = android.os.SystemClock.elapsedRealtime()
        val redone = ownClick("다시 실행") && waitFor(4000) { clipCount() == (splitBefore ?: 0) + 1 }
        act("redo", redone, t, "clips=${clipCount()}")
        pass("redo.sameScenes", "redo restores exactly the post-split scene list", sceneSnapshot() == scenesAfterSplit, "scenes=${sceneSnapshot().size}")
        // protection on the manually trimmed clip: the protect toggle, then the recommend action must not change the manual fields
        val protectLabelsBefore = chromeLabels().filter { it.contains("보호") }
        t = android.os.SystemClock.elapsedRealtime()
        val protectClicked = ownClick("보호")
        idle()
        act("protect-toggle", protectClicked, t, "before=$protectLabelsBefore after=${chromeLabels().filter { it.contains("보호") }}")
        capture("t04-protected", listOf("보호", "추천 구간"))
        val manual = sceneSnapshot()
        val manualBefore = manualState()
        // the real 추천 구간 button: brought into view through its own pane, clicked only when it is actually enabled (a protected clip disables it)
        requireOwn("recommend")
        val recNode = findScrolling("추천 구간")
        val recButton = recNode?.let { n -> generateSequence(n) { it.parent }.take(3).firstOrNull { it.isClickable } }
        val recEnabled = recNode != null && recNode.isEnabled && recButton != null && recButton.isEnabled
        val recClicked = recEnabled && ownContext() && click(recButton!!)
        idle()
        val afterRec = sceneSnapshot()
        val manualAfter = manualState()
        pass("recommend.keepsManual", "추천 구간 keeps the manual protected fields, order and inclusion (a click is claimed only when the real button was enabled; a disabled button is a recorded no-op)",
            recNode != null && (!recEnabled || recClicked) && manualAfter == manualBefore,
            "found=${recNode != null} enabled=$recEnabled clickExecuted=$recClicked scenes=${manualBefore.first.size}->${manualAfter.first.size} protected=${manualBefore.second.size} manualEqual=${manualAfter == manualBefore} fullSceneEqual=${afterRec == manual}")
        capture("t05-after-recommend")
        // save + reopen: close, reopen, settings tab, the same manual state is shown
        val expectedClips = clipCount()
        t = android.os.SystemClock.elapsedRealtime()
        val closed = closeEditor()
        val reopened = closed && openOwnProject() && openTrimPanel()
        act("save-reopen", reopened && clipCount() == expectedClips, t, "clips=${clipCount()} expected=$expectedClips range=${rangeLabel()}")
        capture("t06-reopened-settings")
        pass("reopen.sameScenes", "reopened project keeps the exact scene list (order/timing) and manual-edit state", sceneSnapshot() == afterRec, "scenes=${sceneSnapshot().size}")
    }

    private fun detailPanels() {
        if (!ownedEditor() && !openOwnProject()) return
        openTrimPanel()
        val tablet = edits!!.getString("profile") == "tablet"
        for (chip in listOf("속도", "색상", "자막 타이밍", "움직임", "전환")) {
            if (toolsOnly && inspectorRect != null) inspectorTop()
            val t = android.os.SystemClock.elapsedRealtime()
            val opened = if (remaining29) ownClickFast(chip) else ownClick(chip)
            idle()
            act("chip-$chip", opened, t)
            // a click that lands is not proof of the tool: the clicked chip must be on screen and the controls of exactly that tool visible
            // (EditorAdvancedControls: 속도 = 재생 속도 text, 색상 = 밝기/대비/채도 sliders, 움직임/자막 타이밍 = their add buttons, 전환 = dissolve text)
            val required: List<(String) -> Boolean> = when (chip) {
                "속도" -> listOf({ l -> l.startsWith("재생 속도") })
                "색상" -> listOf({ l -> l == "밝기" }, { l -> l == "대비" }, { l -> l == "채도" })
                "움직임" -> listOf({ l -> l == "현재 위치에 지점 추가" })
                "자막 타이밍" -> listOf({ l -> l == "현재 위치에 자막 추가" })
                else -> listOf({ l -> l.startsWith("다음 클립으로 넘어갈 때") })
            }
            val chipNode = findAll(chip).firstOrNull { fullyShown(it) }
            val chipSelectedSemantics = chipNode != null && generateSequence(chipNode) { it.parent }.take(4).any { it.isSelected || it.isChecked }
            // each expected control is brought into its own inspector viewport and recorded while it is shown (a later control may scroll an earlier one out)
            val shownControls = HashSet<Int>()
            // remaining29/toolsOnly: the inspector pane is captured ONCE from the visible clicked chip, before any scroll, and reused for chip scroll and control search
            val chipPane = if (remaining29) chipNode?.let(::ownPane) else null
            if (toolsOnly && chipNode != null) captureInspector(chipNode)
            val useInspector = toolsOnly && inspectorRect != null
            var forwardSteps = 0
            var exhausted = false
            waitFor(if (remaining29) 14_000 else 3000) {
                required.forEachIndexed { i, p ->
                    if (i !in shownControls && nodes().filter { p(label(it)) }.any { bringIntoView(it) && fullyShown(it) }) shownControls += i
                }
                if (shownControls.size == required.size) true
                else {
                    // Compose prunes off-screen controls, so nothing exists to bring into view: move THAT same pane forward and look again
                    if (useInspector) { if (forwardSteps < 10 && inspectorScroll(true)) forwardSteps++ else exhausted = true }
                    else if (chipPane != null && chipPane.refresh() && chipPane.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) settle()
                    exhausted
                }
            }
            val controlsShown = shownControls.size == required.size
            pass("chip-$chip.panel", "chip '$chip' is the shown tool chip and its own controls are visible", opened && chipNode != null && controlsShown,
                "chipShown=${chipNode != null} chipSelectedSemantics=$chipSelectedSemantics controlsShown=$controlsShown")
            capture("d-$chip")
            visibilityRecord("chip-$chip-top")
            requireOwn("chip-scroll")
            // tablet: scroll the pane that actually owns the chip (never the clip grid); phone: the single visible scroller
            fun toolScroller() = if (remaining29) chipPane?.takeIf { it.refresh() }
            else if (tablet) findAll(chip).firstOrNull { it.isVisibleToUser }?.let(::scrollableAncestor)
            else nodes().filter { it.isScrollable && it.isVisibleToUser }.maxByOrNull { bounds(it).left }
            if (useInspector) {
                // same captured inspector: one more forward step for the scrolled capture, then back to its top for the next chip
                if (inspectorScroll(true)) { capture("d-$chip-scrolled"); visibilityRecord("chip-$chip-scrolled") }
                inspectorTop()
                idle()
            } else {
            val scroller = toolScroller()
            if (scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true) {
                idle(); capture("d-$chip-scrolled"); visibilityRecord("chip-$chip-scrolled")
                if (remaining29) scrollPaneTop(scroller) else repeat(3) { scroller.refresh(); scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) }
                idle()
            } else if (remaining29) scrollPaneTop(scroller)
            }
            if (!ownedEditor()) openOwnProject()
        }
    }

    // The visible selected-clip header only ("N번 클립 · name" portrait / "N번 클립 / name" landscape), never the "N번 클립 메뉴" buttons.
    private fun selectedClipNumber(): Int? = labels().firstNotNullOfOrNull { Regex("^(\\d+)번 클립 [·/]").find(it)?.groupValues?.get(1)?.toInt() }

    /** One editor process: pick a non-first clip with a non-zero playhead via the real timeline, rotate both ways, compare the same clip/playhead. */
    private fun rotationContinuity() {
        if (!ownedEditor() && !openOwnProject()) return
        val timeline = findAll("프로젝트 타임라인", exact = false).firstOrNull()?.also { bringIntoView(it) } ?: run { check("rotation.timeline", "timeline node found", "FAIL", "missing"); return }
        // real timeline taps (canonical output position) until a non-first clip is selected at a non-zero playhead; fresh bounds, clipped to the window/pane
        for (fraction in listOf(0.62f, 0.8f, 0.45f, 0.92f)) {
            val b = clippedBounds(timeline)
            tap(b.left + b.width() * fraction, b.exactCenterY())
            if (waitFor(1500) { (selectedClipNumber() ?: 0) >= 2 && (playheadSeconds() ?: 0.0) > 0.0 }) break
        }
        val clipBefore = selectedClipNumber(); val headBefore = playheadSeconds()
        val clipLineBefore = labels().firstOrNull { Regex("^\\d+번 클립 [·/]").containsMatchIn(it) }
        // actual scene key of the selected UI index (existing public editor data), plus the project identity, read before rotating
        fun selectedKey(index: Int?): Any? = index?.let { viewModel().state.value.trailer?.editor?.scenes?.getOrNull(it - 1)?.key }
        fun projectDir() = viewModel().state.value.trailer?.directory?.canonicalFile
        val keyBefore = selectedKey(clipBefore); val dirBefore = projectDir()
        pass("rotation.pre", "a non-first clip is selected with a non-zero playhead and a known scene key before rotating", (clipBefore ?: 0) >= 2 && (headBefore ?: 0.0) > 0.0 && keyBefore != null,
            "clip=$clipBefore playhead=$headBefore key=$keyBefore")
        if ((clipBefore ?: 0) < 2 || (headBefore ?: 0.0) <= 0.0 || keyBefore == null) return // a fresh first-clip/0s start proves nothing
        val other = if (editsOrientation == "portrait") "landscape" else "portrait"
        val pidBefore = android.os.Process.myPid()
        for ((step, orientation) in listOf("rotated" to other, "restored" to editsOrientation)) {
            val t = android.os.SystemClock.elapsedRealtime()
            ua.setRotation(rotationFor(orientation)); idle()
            val dm = activity!!.resources.displayMetrics
            // ready = recreated activity in the new orientation, own editor open, selected clip + playhead labels back on screen
            val applied = waitFor(6000) { activity!!.resources.displayMetrics.let { (it.heightPixels > it.widthPixels) == (orientation == "portrait") } } && ownContext() && editorOpen() &&
                waitFor(6000) { selectedClipNumber() != null && playheadSeconds() != null }
            val clip = selectedClipNumber(); val head = playheadSeconds()
            val clipLine = labels().firstOrNull { Regex("^\\d+번 클립 [·/]").containsMatchIn(it) }
            // same selected clip (number + source name) and the same output position within the label's 0.1s rounding
            val keyAfter = selectedKey(clip)
            val sameKey = keyAfter != null && keyAfter == keyBefore
            val sameProject = dirBefore != null && projectDir() == dirBefore
            // identity = index + scene key + project + canonical head; the display line differs by design ('·' portrait, '/' landscape)
            val same = clip == clipBefore && sameKey && sameProject && head != null && abs(head - headBefore!!) <= 0.11
            act("rotate-$step", applied && same, t, "orientation=$orientation w=${dm.widthPixels} h=${dm.heightPixels} clip=$clip/$clipBefore playhead=$head/$headBefore sameLine=${clipLine == clipLineBefore} key=$keyAfter/$keyBefore sameProject=$sameProject")
            capture("r-$step-$orientation")
        }
        pass("rotation.sameProcess", "rotation continuity ran inside one app process", android.os.Process.myPid() == pidBefore, "pid=$pidBefore")
    }

    /** stage=remaining29 (own project, same guard/entry/finally): only the previously failed checks. Final source hash is env.installedApkSha256. */
    private fun remaining29Flow() {
        requireOwn("remaining29")
        var t = android.os.SystemClock.elapsedRealtime()
        val settings = openTrimPanel()
        act("open-settings-panel", settings, t)
        if (settings) {
            visibilityRecord("remaining29-layout")
            footerClearance("remaining29-layout")
            capture("m01-layout", listOf("내보내기"))
        }
        detailPanels()
        protectedStateProof()
        rotationContinuity()
        footerClearance("remaining29-after-rotation")
        nativeAvailabilityProof()
    }

    /** Fixed picture/timeline/selected-clip line must end above the fixed footer's primary (no content behind the footer). */
    private fun footerClearance(tag: String) {
        val all = nodes()
        val footer = all.firstOrNull { label(it) == "내보내기" && clickableAncestor(it) != null && it.isVisibleToUser }?.let(::bounds)
        val timeline = all.firstOrNull { label(it).startsWith("프로젝트 타임라인") && it.isVisibleToUser }?.let(::bounds)
        val clip = all.firstOrNull { Regex("^\\d+번 클립 [·/]").containsMatchIn(label(it)) && it.isVisibleToUser }?.let(::bounds)
        val ok = footer != null && timeline != null && clip != null && timeline.bottom <= footer.top + 1 && clip.bottom <= footer.top + 1
        pass("footer.clear.$tag", "timeline and selected-clip line end above the fixed footer ($tag)", ok, "footer=$footer timeline=$timeline clip=$clip")
    }

    /** Reads the already-protected own clip's manual mask/fields; never toggles protection and never clicks the (disabled) 추천 구간. */
    private fun protectedStateProof() {
        val (mask, fields) = manualState()
        val rec = findAll("추천 구간").firstOrNull()
        val recEnabled = rec != null && rec.isEnabled && generateSequence(rec) { it.parent }.take(3).firstOrNull { it.isClickable }?.isEnabled == true
        val status = if (fields.isNotEmpty()) "PASS" else "UNVERIFIED"
        check("protected.state", "own clip is protected with its manual fields intact; 추천 구간 disabled and not executed", status,
            "scenes=${mask.size} protected=${fields.size} recommendEnabled=$recEnabled recommendExecuted=false fields=${sha(fields.joinToString("\n").toByteArray()).take(12)}")
    }

    /** Reads the current RECORD_AUDIO permission and the on-device speech availability through the normal API; never grants, requests, downloads or records. */
    private fun nativeAvailabilityProof() {
        val granted = target.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val availability = runCatching {
            runBlocking { kotlinx.coroutines.withTimeoutOrNull(10_000) { com.armsone.nasfinder.ui.OnDeviceSpeech.availability(target) } }
        }
        val value = availability.getOrNull()
        ui.put("native", JSONObject().put("recordAudioGranted", granted).put("speechState", value?.state?.name ?: JSONObject.NULL).put("speechTag", value?.languageTag ?: JSONObject.NULL))
        pass("native.availability", "RECORD_AUDIO state and on-device speech availability read through the normal API (no grant/download)", value != null,
            "granted=$granted state=${value?.state} error=${availability.exceptionOrNull()?.javaClass?.simpleName}")
        nativeRecordPrompt(granted)
    }

    /**
     * Own 음악 inspector -> the visible '녹음' button, clicked once and only while RECORD_AUDIO is denied, to observe the OS prompt.
     * Never Allow/Deny, never grants, never records. The human/OS grant stays PENDING. Only the recognised own permission prompt is dismissed (BACK); an unknown window is left alone.
     */
    private fun nativeRecordPrompt(grantedBefore: Boolean) {
        fun recordGranted() = target.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (grantedBefore) {
            check("native.recordPrompt", "OS record prompt via the audio inspector's record button", "UNVERIFIED",
                "RECORD_AUDIO already granted: no prompt to observe; ambient recording not run; a real record test stays pending")
            return
        }
        val tabOpened = ownClickFast("음악")
        val shownBefore = tabOpened && findAll("녹음").any { clickableAncestor(it) != null }
        val clicked = shownBefore && ownClickFast("녹음")
        val permissionPackages = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
        val appLabel = target.applicationInfo.loadLabel(target.packageManager).toString()
        // Owned-OS-dialog exception: the permission controller is not the own package, so the own-filtered root()/labels() cannot see it.
        // Read the active window root directly (public UiAutomation API), only its package name, and only read-only labels when that package is exactly the permission controller.
        fun activePackage(): String? = runCatching {
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            ua.rootInActiveWindow?.packageName?.toString()
        }.getOrNull()
        fun permissionRoot(): AccessibilityNodeInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            ua.rootInActiveWindow?.takeIf { it.packageName?.toString() in permissionPackages }
        }.getOrNull()
        fun readLabels(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
            if (depth > 30 || out.size >= 60) return
            (n.text?.toString()?.takeIf { it.isNotEmpty() } ?: n.contentDescription?.toString())?.takeIf { it.isNotEmpty() }?.let { out += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { readLabels(it, out, depth + 1) }
        }
        val promptShown = clicked && waitFor(6000) { activePackage() in permissionPackages }
        val promptLabels = if (promptShown) {
            val collected = ArrayList<String>()
            permissionRoot()?.let { readLabels(it, collected, 0) }
            collected.distinct().filter { it.length <= 80 }.take(12)
        } else emptyList()
        val ownPrompt = promptShown && promptLabels.any { it.contains(appLabel) || it.contains(pkg) }
        var dismissed = false
        if (ownPrompt && activePackage() in permissionPackages) {
            ua.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            dismissed = waitFor(5000) { activePackage() == pkg && resumedActivity != null }
            if (dismissed) requireOwn("native-record-prompt-dismissed")
        }
        // BACK was sent once and never repeated: a known OS dialog that stays (API 29 BACK=false) is a real system condition, reported PENDING
        osPromptPending = ownPrompt && !dismissed && activePackage() in permissionPackages
        val grantedAfter = recordGranted()
        ui.put("nativeRecordPrompt", JSONObject().put("tabOpened", tabOpened).put("recordButtonShown", shownBefore).put("clickedOnce", clicked)
            .put("promptShown", promptShown).put("ownPrompt", ownPrompt).put("dismissedByBack", dismissed).put("grantedAfter", grantedAfter).put("promptLabels", JSONArray(promptLabels)))
        val status = when {
            grantedAfter -> "UNVERIFIED" // surprisingly granted: reported, ambient recording not run
            ownPrompt && dismissed -> "PASS"
            else -> "UNVERIFIED"
        }
        check("native.recordPrompt", "OS record prompt appears after the app's own request from the 음악 inspector's 녹음 button (no Allow/Deny)", status,
            "tab=$tabOpened button=$shownBefore clickedOnce=$clicked prompt=$promptShown own=$ownPrompt dismissed=$dismissed grantedAfter=$grantedAfter osDialogPending=$osPromptPending${if (osPromptPending) " PENDING: OS dialog observed, BACK did not dismiss it (system condition, not an app failure); not repeated, own-root/close checks skipped" else ""}; grant/record stay pending human approval")
    }

    private fun exportOwn() {
        if (!ownedEditor() && !openOwnProject()) return
        val seed = JSONObject(File(editsBase!!, "seed.json").readText())
        val ledger = File(outDir, "export-ledger.json")
        ledger.writeText(JSONObject().put("projectId", ownDraftId).put("dir", seed.getString("dir")).put("status", "before-export").toString(2))
        // the committed output of the own draft, only when it is a non-empty file inside the own project dir (the owned-artifact guard)
        fun ownOutput(): File? = runCatching {
            com.armsone.nasfinder.ui.TrailerDraftStore.getEveryDraft().firstOrNull { it.id == ownDraftId }?.output
                ?.takeIf { it.isFile && it.length() > 0 && it.canonicalFile.parentFile == ownDir }
        }.getOrNull()
        // the result view that already exists in the app: 편집으로 돌아가기 plus 재생 or 저장·공유 (a missing expected word is a host-selector issue)
        fun previewReady() = labels().let { l -> "편집으로 돌아가기" in l && ("저장·공유" in l || "재생" in l) }
        fun storeDraft() = com.armsone.nasfinder.ui.TrailerDraftStore.getEveryDraft().firstOrNull { it.id == ownDraftId }
        // the committed output belongs to the current revision (or the store has no revision numbers, then nothing newer can exist)
        fun committedCurrent() = storeDraft().let { d -> d != null && (d.revision == null || d.outputRevision == d.revision) }
        fun proof(f: File?) = JSONObject().put("revision", storeDraft()?.revision ?: JSONObject.NULL).put("outputRevision", storeDraft()?.outputRevision ?: JSONObject.NULL)
            .put("file", f?.absolutePath ?: JSONObject.NULL).put("bytes", f?.length() ?: 0).put("lastModified", f?.lastModified() ?: 0L)
            .put("sha256", if (f != null) sha256(f) else JSONObject.NULL)
        val outputBefore = ownOutput()
        val reusedBefore = outputBefore
        val proofBefore = proof(outputBefore)
        var t = android.os.SystemClock.elapsedRealtime()
        val clicked = ownClick("내보내기")
        act("export-click", clicked, t)
        // ready = own output file exists, committed for the current revision, and the result view is on screen
        val done = waitFor(240_000) { ownOutput() != null && committedCurrent() && previewReady() }
        act("export-ready", done, t, "output=${ownOutput()?.length()} reusedExisting=${reusedBefore != null} chrome=" + chromeLabels().take(30))
        capture("x01-export-result")
        val out = ownOutput()
        val proofAfter = proof(out)
        val newOutput = proofBefore.toString() != proofAfter.toString()
        ledger.writeText(JSONObject().put("projectId", ownDraftId).put("dir", seed.getString("dir")).put("status", if (out != null) "exported" else "no-output")
            .put("reusedExisting", reusedBefore != null).put("outputChanged", newOutput).put("committedCurrent", committedCurrent()).put("uiReady", previewReady())
            .put("before", proofBefore).put("after", proofAfter)
            .put("file", out?.absolutePath ?: JSONObject.NULL).put("uri", out?.let { Uri.fromFile(it).toString() } ?: JSONObject.NULL)
            .put("bytes", out?.length() ?: 0).put("sha256", if (out != null) sha256(out) else JSONObject.NULL).toString(2))
        pass("export.file", "own UI export produced a real output file inside the own project", out != null && out.isFile && out.length() > 0, "bytes=${out?.length()} outputChanged=$newOutput reusedExisting=${reusedBefore != null} committedCurrent=${committedCurrent()}")
        if (!ownedEditor()) {
            requireOwn("export-return")
            if ("편집으로 돌아가기" in labels()) ownClick("편집으로 돌아가기")
            if (!ownedEditor()) { requireOwn("export-back"); back(); if (!ownedEditor()) openOwnProject() }
        }
    }

    private fun environment() {
        val info = target.packageManager.getPackageInfo(pkg, 0)
        val apk = File(info.applicationInfo!!.sourceDir)
        val env = JSONObject()
            .put("package", pkg).put("versionName", info.versionName).put("versionCode", info.longVersionCode)
            .put("buildNumber", runCatching { Class.forName("$pkg.BuildConfig").getField("BUILD_NUMBER").get(null) }.getOrNull())
            .put("installedApk", apk.absolutePath).put("installedApkBytes", apk.length()).put("installedApkSha256", sha256(apk))
            .put("lastUpdateTime", info.lastUpdateTime)
            .put("sdkInt", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE).put("model", Build.MODEL).put("device", Build.DEVICE)
            .put("locale", target.resources.configuration.locales.toLanguageTags())
            .put("uiModeNight", (target.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            .put("fontScale", target.resources.configuration.fontScale)
            .put("runId", runId).put("outDir", outDir.absolutePath)
        ui.put("environment", env)
        pass("env.version", "installed app is 2.38.1 / 439238 / 202611020936",
            info.versionName == "2.38.1" && info.longVersionCode == 439238L && env.optString("buildNumber") == "202611020936", env.toString())
    }

    private fun homeRoots() {
        check("ui.tabbar", "5 root tab bar is reachable", if (ensureTabBar()) "PASS" else "FAIL", "tabs=" + tabLabels.joinToString { it + "=" + (tabNode(it) != null) })
        val signatures = LinkedHashMap<String, Set<String>>()
        val themeNode = nodes().firstOrNull { label(it).startsWith("병기스타일 테마:") }
        ui.put("themeDescription", themeNode?.let(::label))
        tabNode("도구")?.let { click(it) }; idle()
        for (tab in tabLabels) {
            val node = tabNode(tab)
            val before = labels().toSet()
            val clicked = node != null && click(node)
            idle()
            if (tab != tabLabels.first() || clicked) waitFor(3000) { labels().toSet() != before }
            idle()
            fun chainSelected(n: AccessibilityNodeInfo): Boolean {
                var c: AccessibilityNodeInfo? = n
                while (c != null) { if (c.isSelected) return true; c = c.parent }
                fun down(x: AccessibilityNodeInfo): Boolean = x.isSelected || (0 until x.childCount).any { i -> x.getChild(i)?.let(::down) == true }
                return down(n)
            }
            val selected = tabNode(tab)?.let(::chainSelected) == true
            val rec = capture("tab-${tabLabels.indexOf(tab) + 1}-$tab", controls = tabLabels + listOf("설정", "네트워크 추가"))
            signatures[tab] = labels().toSet() - tabLabels.toSet()
            pass("tab.$tab.click", "tapping the $tab tab works and selects it", clicked && selected, "clicked=$clicked selected=$selected")
            // the tab bar must stay clear of the OS navigation bar and every tab must be a usable-size target
            val b = rec.getJSONObject("controlBounds").optJSONObject(tab)
            val h = rec.getInt("height"); val nav = rec.getInt("navPx")
            pass("safearea.$tab.tabbar", "bottom tab bar controls sit above the OS navigation bar ($tab)",
                b != null && b.getInt("b") <= h - nav + 1, "tabBounds=$b height=$h navPx=$nav")
        }
        val names = signatures.keys.toList()
        var distinct = true
        val detail = StringBuilder()
        for (i in names.indices) for (j in i + 1 until names.size) {
            val a = signatures.getValue(names[i]); val b = signatures.getValue(names[j])
            val jaccard = if (a.isEmpty() && b.isEmpty()) 1.0 else a.intersect(b).size.toDouble() / a.union(b).size
            detail.append("${names[i]}~${names[j]}=${"%.2f".format(jaccard)} ")
            if (a == b || jaccard > 0.8) distinct = false
        }
        pass("roots.distinct", "도구/파인더/홈/만들기/설정 taps open distinct screens (visible-label Jaccard <= 0.8 for every pair)", distinct, detail.toString())
        // Finder: formal 즐겨찾기 section first, before the connection list. (Wide-window sidebar route is not a phone surface.)
        tabNode("파인더")?.let { click(it) }; idle(); waitFor(4000) { "내 기기" in labels() }
        val fav = findAll("즐겨찾기").minByOrNull { bounds(it).top }
        val other = nodes().filter { label(it) in setOf("내 기기", "네트워크", "저장된 연결", "NAS", "네트워크 추가") }.minByOrNull { bounds(it).top }
        pass("finder.favorites", "Finder root shows the formal 즐겨찾기 section above the source/connection list",
            fav != null && (other == null || bounds(fav).top <= bounds(other).top), "fav=${fav?.let { bounds(it) }} next=${other?.let { label(it) + bounds(it) }}")
        ui.put("finderFavoritesNote", "phone surface only; the sidebar 즐겨찾기 route belongs to wide windows (tablet slot, not run)")
        // Tools: each row opens its own destination; the 도구 tab itself never lands on 설정.
        tabNode("도구")?.let { click(it) }; idle(); waitFor(4000) { "사진 선별" in labels() }
        val toolsLabels = labels()
        pass("tools.root", "도구 tab root lists 사진 선별 / 미디어 전송 / 수퍼 썸네일 / 썸네일 캐시 rows and is not the settings screen",
            listOf("사진 선별", "미디어 전송", "수퍼 썸네일", "썸네일 캐시").all { it in toolsLabels } && "테마" !in toolsLabels, toolsLabels.distinct().joinToString("|").take(600))
        val toolsRoot = labels().toSet()
        for ((row, marker) in listOf("미디어 전송" to "보내기", "수퍼 썸네일" to "선택한 폴더의 영상과 사진 썸네일을 미리 만듭니다.", "썸네일 캐시" to "자동 정리", "사진 선별" to "사진 보관함에서 고르기")) {
            val clicked = clickLabel(row)
            waitFor(4000) { labels().toSet() != toolsRoot }
            val screen = labels().toSet()
            val changed = screen != toolsRoot
            val markerOk = marker == null || waitFor(4000) { marker in labels() }
            capture("tools-$row".replace('·', '-').replace(' ', '_'), controls = listOf("뒤로"))
            pass("tools.$row", "도구 → '$row' opens its own screen (marker=${marker ?: "n/a"}) distinct from the tools root and from settings",
                clicked && changed && markerOk && "테마" !in screen, "clicked=$clicked changed=$changed marker=$markerOk")
            if (clicked) { if (!clickLabel("뒤로")) back() }
            if (!ensureTabBar()) check("tools.$row.return", "returned to a tab root", "FAIL", "tab bar missing after back")
            tabNode("도구")?.let { click(it) }; idle(); waitFor(4000) { "사진 선별" in labels() }
        }
    }

    private fun settingsOrder() {
        tabNode("설정")?.let { click(it) }; idle(); waitFor(4000) { "저장공간·캐시" in labels() }
        val wanted = listOf("저장공간·캐시", "화면 켜짐", "앱 아이콘", "프라이버시", "화면·테마", "앱 정보·업데이트")
        val firstSeen = LinkedHashMap<String, Int>()
        val initial = ArrayList<Pair<String, Int>>()
        fun scan(initialScan: Boolean) {
            nodes().forEach { n ->
                val l = label(n)
                if (l in wanted && bounds(n).height() > 0) {
                    firstSeen.getOrPut(l) { firstSeen.size }
                    if (initialScan && initial.none { it.first == l }) initial += l to bounds(n).top
                }
            }
        }
        scan(true)
        capture("settings-initial", controls = listOf("뒤로"))
        repeat(12) {
            val scroller = nodes().firstOrNull { it.isScrollable && it.isVisibleToUser } ?: return@repeat
            if (!scroller.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return@repeat
            idle(); scan(false)
        }
        val order = firstSeen.keys.toList()
        val initialOrder = initial.sortedBy { it.second }.map { it.first }
        ui.put("settingsInitialVisible", JSONArray(initial.map { JSONObject().put("title", it.first).put("top", it.second) }))
        ui.put("settingsFullOrder", JSONArray(order))
        pass("settings.initial", "Settings root initial placement starts 저장공간·캐시 (visible prefix in the requested order)",
            initialOrder.isNotEmpty() && initialOrder.first() == "저장공간·캐시" && initialOrder == wanted.filter { it in initialOrder }, "initial=$initialOrder")
        pass("settings.order", "Settings sections follow 저장공간·캐시 → 화면 켜짐 → 앱 아이콘 → 프라이버시 → 화면·테마 → 앱 정보·업데이트 (scrolled)",
            order == wanted, "seen=$order")
        capture("settings-scrolled", controls = listOf("뒤로"))
        repeat(12) { nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); }
        idle()
        if (activity?.isFinishing == false) { ensureTabBar(); tabNode("홈")?.let { click(it) }; idle() }
    }

    // ---------------------------------------------------------------- photo selection

    private data class Fix(val label: String, val kind: String, val file: File, val derivedFrom: String?)

    private fun saveJpeg(bitmap: Bitmap, name: String, kind: String, derived: String?, extra: JSONObject = JSONObject()): Fix {
        val file = File(fixtureDir, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val fix = Fix(name.substringBeforeLast('.'), kind, file, derived)
        fixtureManifest.getJSONArray("fixtures").put(
            JSONObject().put("name", name).put("kind", kind).put("derivedFrom", derived).put("path", file.absolutePath)
                .put("bytes", file.length()).put("sha256", sha256(file)).put("width", bitmap.width).put("height", bitmap.height).put("params", extra),
        )
        return fix
    }

    private fun waitPhase(session: PhotoSelectionSession, phases: Set<PhotoSelectionPhase>, timeoutMs: Long = 420_000): PhotoSelectionState {
        waitFor(timeoutMs) { session.state.value.phase in phases }
        return session.state.value
    }

    private fun stateJson(s: PhotoSelectionState): JSONObject = JSONObject()
        .put("phase", s.phase.name).put("countText", s.countText).put("countEdited", s.countEdited).put("countError", s.countError)
        .put("confirmed", s.confirmed).put("error", s.error).put("message", s.message)
        .put("selected", s.outcome?.selectedIndices?.let { JSONArray(it) })
        .put("roles", s.outcome?.roleByIndex?.let { m -> JSONObject().also { j -> m.forEach { (k, v) -> j.put(k.toString(), v.name) } } })

    private val devMemo = Regex("DINO|dino|provisional|fallback|TODO|FIXME|debug|HanAI|HanYang|threshold|cosine|임계")

    private fun describeAnalysis(tag: String, analysis: PhotoSelectionAnalysis): JSONObject {
        val names = analysis.batch.photos.associate { it.id to it.displayName }
        val recommendation = PhotoSelectionPolicy.recommend(analysis)
        val candidates = analysis.analyzed.map { ImageQualityCandidate(it.photo.id, it.sharpness, it.exposure, it.pixelCount, aestheticsScore = null) }
        val selector = if (candidates.isEmpty()) null else ImageDiversitySelector.selectDiverse(candidates, analysis.distances, PhotoSelectionPolicy.GROUP_THRESHOLD, candidates.size)
        val photos = JSONArray()
        analysis.analyzed.forEach { a ->
            photos.put(JSONObject().put("id", a.photo.id).put("name", a.photo.displayName).put("sharpness", a.sharpness).put("exposure", a.exposure)
                .put("pixels", a.pixelCount).put("aspect", a.aspectRatio).put("hasEmbedding", a.embedding != null)
                .put("embeddingDim", a.embedding?.size).put("embeddingNorm", a.embedding?.let { e -> sqrt(e.fold(0.0) { s, v -> s + v * v }) })
                .put("score", selector?.scoreByIndex?.get(a.photo.id)))
        }
        val pairGate = JSONArray(analysis.distances.map { JSONObject().put("a", names[it.firstIndex]).put("b", names[it.secondIndex]).put("distance", it.distance) })
        val dinoPairs = JSONArray()
        if (analysis.usedDino) {
            for (i in analysis.analyzed.indices) for (j in i + 1 until analysis.analyzed.size) {
                val a = analysis.analyzed[i]; val b = analysis.analyzed[j]
                val dot = a.embedding!!.indices.fold(0.0) { s, k -> s + a.embedding!![k] * b.embedding!![k] }
                dinoPairs.put(JSONObject().put("a", a.photo.displayName).put("b", b.photo.displayName).put("cosineDistance", 1.0 - dot))
            }
        }
        val groups = JSONArray(selector?.groups.orEmpty().map { g ->
            JSONObject().put("representative", names[g.representativeIndex]).put("members", JSONArray(g.memberIndices.map { names[it] }))
        })
        val json = JSONObject().put("tag", tag).put("usedDino", analysis.usedDino).put("analyzedCount", analysis.analyzed.size)
            .put("photos", photos).put("closePairsPassingFingerprintAndDinoGates", pairGate).put("dinoCosineDistancesAllPairs", dinoPairs)
            .put("groups", groups).put("groupThreshold", PhotoSelectionPolicy.GROUP_THRESHOLD)
            .put("floors", JSONObject().put("minSharpness", PhotoSelectionPolicy.MIN_SHARPNESS).put("minExposure", PhotoSelectionPolicy.MIN_EXPOSURE))
            .put("failures", JSONArray(analysis.failures.map { JSONObject().put("name", it.name).put("reason", it.reason) }))
        if (recommendation != null) {
            json.put("recommendation", JSONObject().put("eligible", recommendation.eligible).put("groups", recommendation.groups)
                .put("lowQualityRepresentatives", recommendation.lowQualityRepresentatives).put("recommended", recommendation.recommended)
                .put("allLowQuality", recommendation.allLowQuality)
                .put("lowQualityNames", JSONArray(recommendation.lowQualityIndices.map { names[it] }))
                .put("rejected", JSONArray(recommendation.rejected.map { JSONObject().put("name", it.name).put("reason", it.reason) }))
                .put("reasons", JSONArray(PhotoSelectionPolicy.explanation(recommendation, analysis))))
        } else json.put("recommendation", JSONObject.NULL)
        return json
    }

    private fun photoSelectionAnalysis() {
        if (activity?.let { it.isFinishing || it.isDestroyed } != false) launch()
        fixtureManifest.put("fixtures", JSONArray())
        fixtureManifest.put("provenance", "Procedurally generated in-process by PhotoLikeFixtures (texture/lighting/objects/grain). No user photos, attachments, " +
            "external images or OS stock images were read. They are boundary-behaviour inputs only and do not prove quality ranking on real photographs.")
        val stagingRoot = PhotoSelectionStaging.root(app)
        PhoneSmokeInstrumentation.purgeBatchIds.forEach { id ->
            require(id.matches(Regex("[0-9a-f-]{36}")))
            val existed = File(stagingRoot, id).exists()
            viewModel().photoSelection.discard(id)
            val absent = !File(stagingRoot, id).exists() && !File(app.cacheDir, "shares/photo-selection-$id").exists()
            cleanup.put("purgedOwnPreviousBatch-$id", JSONObject().put("existed", existed).put("absentAfter", absent))
            pass("cleanup.previous.$id", "exact previous owned batch removed through app API", absent, "existed=$existed absent=$absent")
        }
        val protectedBefore = protectedSnapshot()
        cleanup.put("protectedBefore", protectedBefore)
        val before = stagingRoot.list()?.sorted().orEmpty()
        cleanup.put("stagingBatchesBefore", JSONArray(before))

        // --- fixtures
        val forest = PhotoLikeFixtures.scene(0)
        val f01 = saveJpeg(forest, "f01-forest-sharp.jpg", "sharp", null)
        val f02 = saveJpeg(PhotoLikeFixtures.scene(0, dx = 14f, dy = -9f, gain = 1.05f, grainSeed = 77), "f02-forest-near-duplicate.jpg", "near-duplicate", f01.label,
            JSONObject().put("dx", 14).put("dy", -9).put("gain", 1.05).put("grainSeed", 77))
        val cityBmp = PhotoLikeFixtures.scene(1)
        val f03 = saveJpeg(cityBmp, "f03-city-sharp.jpg", "sharp", null)
        val f04 = saveJpeg(PhotoLikeFixtures.scene(2), "f04-beach-sharp.jpg", "sharp", null)
        val f05 = saveJpeg(PhotoLikeFixtures.scene(3), "f05-interior-sharp.jpg", "sharp", null)
        val f06 = saveJpeg(PhotoLikeFixtures.blurred(PhotoLikeFixtures.scene(4)), "f06-mountain-blur.jpg", "blur", "scene-4", JSONObject().put("method", "downscale 1/24 + bilinear upscale"))
        val f07 = saveJpeg(PhotoLikeFixtures.darkened(PhotoLikeFixtures.scene(5), 0.10f), "f07-field-dark.jpg", "dark", "scene-5", JSONObject().put("factor", 0.10))
        val f08 = saveJpeg(PhotoLikeFixtures.blurred(cityBmp), "f08-city-blur-derivative.jpg", "blur", f03.label, JSONObject().put("method", "downscale 1/24 + bilinear upscale"))
        val bad = File(fixtureDir, "u01-unreadable.jpg").apply { writeBytes(ByteArray(2048) { (it * 31).toByte() }) }
        val missing = File(fixtureDir, "u02-missing.jpg")
        val lone = f03
        fixtureManifest.put("unreadable", JSONArray().put(bad.absolutePath).put(missing.absolutePath + " (never created)"))
        fixtureManifest.put("fixtureDir", fixtureDir.absolutePath)
        fixtureManifest.put("userMedia", "none read; no personal photos, no seven user attachments, no MediaStore access")
        val mainFiles = listOf(f01, f02, f03, f04, f05, f06, f07, f08)
        val mainSources = mainFiles.map { it.file } + bad
        fun uris(files: List<File>) = files.map { Uri.fromFile(it) }

        analysisJson.put("modelAsset", JSONObject().put("note", "hanai/dinov2-small.onnx published to noBackupFilesDir/hanai on first use (SHA-256 verified by the app)"))

        // --- 0 eligible (all unreadable): independent session, no UI
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val zero = PhotoSelectionSession(app, scope) { _, _, _ -> Result.failure(IllegalStateException("not used in smoke")) }
            zero.start(uris(listOf(bad, missing)))
            val zs = waitPhase(zero, setOf(PhotoSelectionPhase.BLOCKED, PhotoSelectionPhase.SETUP, PhotoSelectionPhase.PREVIEW))
            analysisJson.put("zeroEligible", JSONObject().put("state", stateJson(zs)).put("failures", JSONArray(zs.analysis?.failures.orEmpty().map { it.name + ": " + it.reason })))
            pass("sel.zero.blocked", "0 eligible (all unreadable) is BLOCKED with item errors; no recommendation, no result, no full-set fallback",
                zs.phase == PhotoSelectionPhase.BLOCKED && zs.recommendation == null && zs.outcome == null && !zs.error.isNullOrEmpty() && (zs.analysis?.failures?.size ?: 0) >= 2,
                "phase=${zs.phase} error=${zs.error} failures=${zs.analysis?.failures?.map { it.name }}")
            zero.retryAnalysis()
            val zr = waitPhase(zero, setOf(PhotoSelectionPhase.BLOCKED, PhotoSelectionPhase.SETUP, PhotoSelectionPhase.PREVIEW))
            zero.showPreview()
            val shareFiles = runBlocking { zero.prepareShareFiles() }
            pass("sel.zero.retry", "retry on 0 eligible stays BLOCKED (retry offered, never fabricates a result); preview/share stay unavailable",
                zr.phase == PhotoSelectionPhase.BLOCKED && zero.state.value.phase == PhotoSelectionPhase.BLOCKED && shareFiles == null && zero.state.value.outcome == null,
                "afterRetry=${zr.phase} afterPreview=${zero.state.value.phase} share=$shareFiles")
            val videoFixture = File(fixtureDir, "v01-synthetic-video.mp4").apply { writeBytes(ByteArray(1024)) }
            val zeroVideo = PhotoSelectionSession(app, scope) { _, _, _ -> Result.failure(IllegalStateException("not used in smoke")) }
            zeroVideo.start(listOf(com.armsone.nasfinder.ui.PhotoSelectionSource(Uri.fromFile(videoFixture), "v01-synthetic-video.mp4")))
            val zvs = waitPhase(zeroVideo, setOf(PhotoSelectionPhase.BLOCKED, PhotoSelectionPhase.SETUP, PhotoSelectionPhase.PREVIEW))
            zeroVideo.retryAnalysis()
            val zvr = waitPhase(zeroVideo, setOf(PhotoSelectionPhase.BLOCKED, PhotoSelectionPhase.SETUP, PhotoSelectionPhase.PREVIEW))
            zeroVideo.showPreview()
            val videoShare = runBlocking { zeroVideo.prepareShareFiles() }
            val videoBatch = zeroVideo.state.value.batch?.id
            cleanup.put("zeroVideoBatchId", videoBatch); videoBatch?.let { ownBatchIds += it }
            val videoSkipped = zvs.batch?.skippedVideos ?: 0
            val videoBlocked = zvs.phase == PhotoSelectionPhase.BLOCKED && zvs.recommendation == null && zvs.outcome == null &&
                zvr.phase == PhotoSelectionPhase.BLOCKED && zeroVideo.state.value.phase == PhotoSelectionPhase.BLOCKED &&
                videoShare == null && videoSkipped == 1
            pass("sel.zero.videoOnly", "video-only batch behaves as 0 eligible (video excluded, recommendation and preview blocked, skippedVideos observed)",
                videoBlocked, "phase=${zvs.phase} skippedVideos=$videoSkipped afterRetry=${zvr.phase} share=$videoShare")
            zeroVideo.finish()
            val zeroBatch = zero.state.value.batch?.id
            zero.finish()
            cleanup.put("zeroBatchId", zeroBatch); zeroBatch?.let { ownBatchIds += it }

            // --- 1 photo
            val one = PhotoSelectionSession(app, scope) { _, _, _ -> Result.failure(IllegalStateException("not used in smoke")) }
            one.start(uris(listOf(lone.file)))
            val os = waitPhase(one, setOf(PhotoSelectionPhase.BLOCKED, PhotoSelectionPhase.SETUP))
            analysisJson.put("onePhoto", JSONObject().put("state", stateJson(os)).put("analysis", os.analysis?.let { describeAnalysis("one", it) }))
            val rec1 = os.recommendation
            pass("sel.one.recommend", "1 photo: eligible=1, recommended=1", rec1 != null && rec1.eligible == 1 && rec1.recommended == 1, "rec=${rec1}")
            one.setCountText("2")
            val over = one.state.value
            one.showPreview()
            pass("sel.one.over", "1 photo: explicit N=2 (M+1) is an error and keeps SETUP (no silent clamp)",
                over.countError != null && one.state.value.phase == PhotoSelectionPhase.SETUP && one.state.value.countText == "2", "err=${one.state.value.countError} phase=${one.state.value.phase}")
            one.setCountText("1"); one.showPreview()
            val op = waitPhase(one, setOf(PhotoSelectionPhase.PREVIEW, PhotoSelectionPhase.SETUP), 60_000)
            pass("sel.one.preview", "1 photo: N=1 reaches preview with 1 selected, unconfirmed", op.phase == PhotoSelectionPhase.PREVIEW && op.outcome?.selectedIndices?.size == 1 && !op.confirmed, stateJson(op).toString())
            val oneBatch = one.state.value.batch?.id
            one.finish()
            cleanup.put("oneBatchId", oneBatch); oneBatch?.let { ownBatchIds += it }

            // --- N photos, driven through the real screen
            val vm = viewModel()
            val session = vm.photoSelection
            pass("sel.main.idle", "app photo-selection session is idle before the test batch", session.state.value.phase == PhotoSelectionPhase.IDLE, "phase=${session.state.value.phase}")
            if (session.state.value.phase != PhotoSelectionPhase.IDLE) return
            tabNode("도구")?.let { click(it) }; idle(); waitFor(4000) { "사진 선별" in labels() }
            val opened = clickLabel("사진 선별") && waitFor(4000) { "사진 보관함에서 고르기" in labels() }
            capture("photosel-1-idle", controls = listOf("뒤로"))
            pass("sel.screen.open", "도구 → 사진 선별 opens the selection screen", opened, "opened=$opened")
            session.start(uris(mainFiles.map { it.file } + bad).map { com.armsone.nasfinder.ui.PhotoSelectionSource(it) })
            val st = waitPhase(session, setOf(PhotoSelectionPhase.SETUP, PhotoSelectionPhase.BLOCKED))
            val analysis = st.analysis
            analysisJson.put("main", analysis?.let { describeAnalysis("main", it) })
            val rec = st.recommendation
            val batchId = st.batch?.id
            cleanup.put("mainBatchId", batchId); batchId?.let { ownBatchIds += it }; writeAll()
            capture("photosel-2-setup")
            analysisJson.put("mainStateAtSetup", stateJson(st))
            val modelFile = File(app.noBackupFilesDir, "hanai/dinov2-small.onnx")
            analysisJson.put("model", JSONObject().put("usedDino", analysis?.usedDino).put("modelFileExists", modelFile.isFile).put("modelFileBytes", if (modelFile.isFile) modelFile.length() else 0))
            check("sel.model", "bundled DINO model loads and embeds every analysed photo (else fallback is recorded)",
                if (analysis?.usedDino == true && modelFile.isFile) "PASS" else "FAIL",
                "usedDino=${analysis?.usedDino} modelFile=${modelFile.isFile}/${modelFile.length()} (false means fingerprint-only fallback or model failure)")
            if (analysis == null || rec == null) { check("sel.main.setup", "analysis produced a recommendation", "FAIL", "phase=${st.phase} error=${st.error}"); return }
            val m = rec.eligible
            val names = analysis.batch.photos.associate { it.id to it.displayName }
            pass("sel.main.counts", "8 readable photos analysed, unreadable one reported as an item error (not counted, not selected)",
                analysis.analyzed.size == 8 && m == 8 && analysis.failures.any { it.name.contains("u01") }, "analysed=${analysis.analyzed.size} eligible=$m failures=${analysis.failures.map { it.name }}")
            val expectedRec = if (rec.allLowQuality) 1 else maxOf(1, rec.groups - rec.lowQualityRepresentatives)
            pass("sel.main.recommend", "recommended N is 1..M and equals max(1, groups - low-quality representatives) from the actual analysis",
                rec.recommended in 1..m && rec.recommended == expectedRec, "M=$m groups=${rec.groups} low=${rec.lowQualityRepresentatives} rec=${rec.recommended}")
            val reasons = PhotoSelectionPolicy.explanation(rec, analysis)
            pass("sel.main.reasons", "recommendation reasons are present and contain no developer memo wording", reasons.isNotEmpty() && reasons.none { devMemo.containsMatchIn(it) }, reasons.joinToString(" / "))
            val screenText = labels().joinToString(" ")
            pass("sel.main.screenText", "rendered setup screen shows the recommendation numbers and no developer memo wording",
                screenText.contains("추천 ${rec.recommended}장") && !devMemo.containsMatchIn(screenText), screenText.take(500))
            // grouping boundary behaviour of this synthetic input
            val groups = analysisJson.getJSONObject("main").getJSONArray("groups")
            fun groupOf(label: String): Int { for (i in 0 until groups.length()) { val ms = groups.getJSONObject(i).getJSONArray("members"); for (j in 0 until ms.length()) if (ms.getString(j).startsWith(label)) return i }; return -1 }
            pass("sel.main.nearDuplicate", "synthetic near-duplicate pair (f01,f02) lands in one similar-scene group", groupOf("f01") >= 0 && groupOf("f01") == groupOf("f02"), "g01=${groupOf("f01")} g02=${groupOf("f02")}")
            pass("sel.main.distinct", "distinct scenes (f03,f04,f05) are in three different groups, separate from f01",
                setOf(groupOf("f03"), groupOf("f04"), groupOf("f05"), groupOf("f01")).size == 4 && groupOf("f03") >= 0, "g=${listOf("f01", "f03", "f04", "f05").map(::groupOf)}")
            val lowNames = analysisJson.getJSONObject("main").getJSONObject("recommendation").getJSONArray("lowQualityNames")
            val lowSet = (0 until lowNames.length()).map { lowNames.getString(it) }
            pass("sel.main.blurLow", "blurred scene f06 is flagged as a markedly-low-quality group representative", lowSet.any { it.startsWith("f06") }, "low=$lowSet")
            pass("sel.main.darkLow", "dark scene f07 is flagged as a markedly-low-quality group representative", lowSet.any { it.startsWith("f07") }, "low=$lowSet")

            // explicit N through the real text field + buttons
            val explicit = JSONArray()
            fun setCount(n: String): Boolean {
                val field = findScrolling("선별할 사진 수", exact = false)?.let { nodes().firstOrNull { x -> x.isEditable } } ?: nodes().firstOrNull { it.isEditable } ?: return false
                field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, n) })
                idle(); return ok
            }
            // Exercise the actual numeric IME Done action and visible footer before changing N.
            findScrolling("선별할 사진 수", exact = false)
            val imeField = nodes().firstOrNull { it.isEditable }
            val imeOpened = imeField?.let { click(it) } == true && waitFor(5000) { insets().optBoolean("imeVisible") }
            capture("photosel-ime-open", listOf("결과 미리보기", "취소"))
            val imeDone = nodes().firstOrNull { it.isEditable }?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
            val imeClosed = waitFor(5000) { !insets().optBoolean("imeVisible") }
            val footer = findScrolling("결과 미리보기")?.let { clickableAncestor(it) }
            val footerInsets = insets()
            val footerBounds = footer?.let(::bounds)
            capture("photosel-ime-done", listOf("결과 미리보기", "취소"))
            pass("sel.main.imeDoneFooter", "numeric IME opens, Done dismisses it and preview footer is reachable above navigation bar",
                imeOpened && imeDone && imeClosed && footerBounds != null && footerBounds.bottom <= footerInsets.optInt("heightPx") - (footerInsets.optJSONObject("navigationBars")?.optInt("b") ?: 0),
                "opened=$imeOpened done=$imeDone closed=$imeClosed footer=$footerBounds")
            // M+1
            val setOver = setCount((m + 1).toString())
            val overButton = findScrolling("결과 미리보기")?.let { clickableAncestor(it) }
            capture("photosel-3-setup-over")
            val sOver = session.state.value
            val overOk = setOver && sOver.countError != null && sOver.phase == PhotoSelectionPhase.SETUP && overButton?.isEnabled == false && sOver.countText == (m + 1).toString()
            explicit.put(stateJson(sOver).put("n", m + 1).put("expect", "error"))
            pass("sel.main.nPlusOne", "explicit N=M+1 shows an error, disables 결과 미리보기, keeps the typed value (no clamp)", overOk,
                "set=$setOver err=${sOver.countError} previewEnabled=${overButton?.isEnabled} phase=${sOver.phase}")
            setCount("0")
            explicit.put(stateJson(session.state.value).put("n", 0).put("expect", "error"))
            pass("sel.main.nZero", "explicit N=0 is an error (1 or more required)", session.state.value.countError != null && session.state.value.phase == PhotoSelectionPhase.SETUP, "err=${session.state.value.countError}")
            // N=1, N=rec, N=M
            val sizes = linkedSetOf(1, rec.recommended, m)
            var confirmedFiles = -1
            for (n in sizes) {
                val setupReady = waitFor(8000) { session.state.value.phase == PhotoSelectionPhase.SETUP && nodes().any { it.isEditable } }
                pass("sel.main.setup.n$n", "each explicit N starts in fresh SETUP UI", setupReady, stateJson(session.state.value).toString())
                if (!setupReady) break
                val typed = setCount(n.toString())
                val clicked = clickLabel("결과 미리보기")
                val ps = waitPhase(session, setOf(PhotoSelectionPhase.PREVIEW), 60_000)
                val selectedNames = ps.outcome?.selectedIndices?.map { names[it] }
                capture("photosel-4-preview-n$n")
                val unconfirmedShare = runBlocking { session.prepareShareFiles() }
                explicit.put(stateJson(ps).put("n", n).put("selectedNames", JSONArray(selectedNames.orEmpty())).put("expect", "ok"))
                pass("sel.main.preview.n$n", "explicit N=$n (rec=${rec.recommended}, M=$m) previews exactly N unconfirmed; typed N is preserved; nothing exposed before confirm",
                    typed && clicked && ps.phase == PhotoSelectionPhase.PREVIEW && ps.outcome?.selectedIndices?.size == n && !ps.confirmed && unconfirmedShare == null && ps.countText == n.toString(),
                    "typed=$typed clicked=$clicked phase=${ps.phase} selected=$selectedNames confirmed=${ps.confirmed} shareBeforeConfirm=$unconfirmedShare")
                val selected = ps.outcome?.selectedIndices.orEmpty()
                pass("sel.main.order.n$n", "selected photos preserve input order", selected == selected.sorted() && selected.distinct().size == n, "indices=$selected")
                if (n == 4) {
                    val noLowSelected = selected.none { it in rec.lowQualityIndices }
                    val forestSelected = selectedNames?.any { it?.startsWith("f02") == true } == true
                    pass("sel.main.n4.priority", "explicit N=4 selects normal representatives including forest and excludes low-quality f08",
                        noLowSelected && forestSelected, "selected=$selectedNames lowIndices=${rec.lowQualityIndices}")
                }
                if (n == m) {
                    clickLabel("이 결과로 확정")
                    waitFor(8000) { session.state.value.confirmed && "확정했습니다. 무엇을 할까요?" in labels() }
                    val cs = session.state.value
                    capture("photosel-5-confirmed")
                    val files = runBlocking { session.prepareShareFiles() }
                    confirmedFiles = files?.size ?: -1
                    pass("sel.main.confirm", "confirm after preview unlocks exactly the N selected photos (app-private copies only; no save/share run)", cs.confirmed && confirmedFiles == n, "confirmed=${cs.confirmed} files=$confirmedFiles")
                    clickLabel("개수 다시 정하기")
                    pass("sel.main.backKeepsN", "going back to the count step keeps the typed N and requires a new preview/confirm",
                        session.state.value.phase == PhotoSelectionPhase.SETUP && session.state.value.countText == n.toString() && !session.state.value.confirmed, stateJson(session.state.value).toString())
                } else {
                    repeat(3) { if (session.state.value.phase != PhotoSelectionPhase.SETUP) { clickLabel("개수 다시 정하기"); waitFor(4000) { session.state.value.phase == PhotoSelectionPhase.SETUP } } }
                }
            }
            analysisJson.put("explicitN", explicit)
            // finish through the real button path: preview M -> confirm -> 완료
            setCount(m.toString()); clickLabel("결과 미리보기"); waitPhase(session, setOf(PhotoSelectionPhase.PREVIEW), 60_000)
            clickLabel("이 결과로 확정")
            waitFor(8000) { session.state.value.confirmed }

            // Verify bottom 4 buttons on confirmed screen are reachable and sit above OS nav bar after scroll
            val confirmedButtons = listOf("사진 보관함에 저장", "공유", "영상 재료에 추가", "완료 · 임시 사본 정리")
            val buttonDetails = ArrayList<String>()
            var allButtonsReachable = true
            val dm = target.resources.displayMetrics
            val inset = insets()
            val navPx = inset.optJSONObject("navigationBars")?.optInt("b") ?: 0
            val viewportBottom = dm.heightPixels - navPx
            for (btnText in confirmedButtons) {
                val node = findScrolling(btnText, exact = true)
                val hitNode = node?.let { clickableAncestor(it) ?: it }
                val b = hitNode?.let(::bounds)
                val visible = hitNode != null && hitNode.isVisibleToUser && (b?.let { it.height() > 0 && it.top >= 0 && it.bottom <= dm.heightPixels } == true)
                val enabled = hitNode?.isEnabled == true
                val aboveNav = b != null && b.bottom <= viewportBottom + 1
                val reachable = visible && enabled && aboveNav
                if (!reachable) allButtonsReachable = false
                buttonDetails += "$btnText: reachable=$reachable visible=$visible enabled=$enabled bounds=$b viewportBottom=$viewportBottom"
            }
            pass("sel.confirmed.buttons.reachable", "confirmed screen bottom 4 buttons (저장/공유/영상재료추가/완료) are reachable and sit above OS navigation bar after scroll",
                allButtonsReachable, buttonDetails.joinToString("; "))
            capture("photosel-5b-confirmed-buttons", confirmedButtons)

            clickLabel("완료 · 임시 사본 정리")
            val finished = waitFor(10_000) { session.state.value.phase == PhotoSelectionPhase.IDLE }
            capture("photosel-6-after-finish")
            val dirGone = batchId != null && !File(stagingRoot, batchId).exists()
            pass("sel.main.cleanup", "완료 returns to idle and removes exactly this batch's staging copy", finished && dirGone, "idle=$finished dirGone=$dirGone")
            // An independent owned pending batch must survive cancellation of another owned batch.
            val pending = runBlocking { PhotoSelectionStaging.stage(app, uris(listOf(f03.file)).map { com.armsone.nasfinder.ui.PhotoSelectionSource(it) }, 0, onProgress = { _, _ -> }) }
            ownBatchIds += pending.id
            val pendingHash = pending.photos.associate { it.file.absolutePath to sha256(it.file) }
            session.start(uris(listOf(f01.file)).map { com.armsone.nasfinder.ui.PhotoSelectionSource(it) })
            val cancelState = waitPhase(session, setOf(PhotoSelectionPhase.SETUP, PhotoSelectionPhase.BLOCKED))
            cancelState.batch?.id?.let { ownBatchIds += it }
            val cancelClicked = clickLabel("취소") && clickLabel("취소하고 정리")
            val cancelled = waitFor(8000) { session.state.value.phase == PhotoSelectionPhase.IDLE }
            val preserved = pendingHash.all { (path, hash) -> File(path).isFile && sha256(File(path)) == hash }
            pass("sel.cancel.otherPending", "UI cancellation removes only active batch and preserves independent pending bytes",
                cancelClicked && cancelled && preserved && cancelState.batch?.id?.let { !File(stagingRoot, it).exists() } == true,
                "clicked=$cancelClicked idle=$cancelled pending=${pending.id} preserved=$preserved")
            capture("photosel-cancel-preserved")
            session.discard(pending.id)
            back(); ensureTabBar()
        } finally {
            scope.cancel()
            ownBatchIds.forEach { id ->
                val sess = viewModel().photoSelection
                if (sess.state.value.batch?.id == id) sess.cancel() else sess.discard(id)
            }
            val protectedAfter = protectedSnapshot()
            cleanup.put("protectedAfter", protectedAfter)
            val changed = (protectedBefore.keys().asSequence().toSet() + protectedAfter.keys().asSequence().toSet())
                .filter { protectedBefore.optString(it) != protectedAfter.optString(it) }
            cleanup.put("protectedChangedPaths", JSONArray(changed))
            pass("cleanup.dataPreserved", "existing files and preferences unchanged after owned cleanup", changed.isEmpty(), "changed=$changed")
            val after = stagingRoot.list()?.sorted().orEmpty()
            cleanup.put("stagingBatchesAfter", JSONArray(after))
            pass("cleanup.stagingUntouched", "pre-existing staging batches are unchanged and none of the test batches remain", after == before, "before=$before after=$after")
            cleanup.put("fixtureDirToRemoveByHost", fixtureDir.absolutePath)
            cleanup.put("note", "fixture JPEGs stay in the run dir until the host pulls them, then the host removes exactly this run dir")
        }
    }

    private fun viewModel(): NasFinderViewModel {
        val a = activity as androidx.activity.ComponentActivity
        val holder = arrayOfNulls<NasFinderViewModel>(1)
        val latch = CountDownLatch(1)
        a.runOnUiThread {
            holder[0] = ViewModelProvider(a.viewModelStore, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = throw IllegalStateException("view model must already exist")
            })[NasFinderViewModel::class.java]
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return checkNotNull(holder[0]) { "NasFinderViewModel not available" }
    }
}
