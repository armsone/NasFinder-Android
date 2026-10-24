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

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        purgeBatchIds = arguments?.getString("purge")?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        start()
    }

    override fun onStart() {
        super.onStart()
        val runner = SmokeRun(this)
        val summary = try {
            runner.execute()
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

private class SmokeRun(private val inst: Instrumentation) {
    private val target = inst.targetContext
    private val pkg = target.packageName
    private val app = target.applicationContext as Application
    private val ua: UiAutomation = inst.uiAutomation
    private val runId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
    val outDir: File = File(target.getExternalFilesDir(null) ?: target.filesDir, "phone-smoke-$runId").apply { mkdirs() }
    private val screens = File(outDir, "screens").apply { mkdirs() }
    private val fixtureDir = File(outDir, "fixtures").apply { mkdirs() }
    private val checks = JSONArray()
    private val ui = JSONObject()
    private val analysisJson = JSONObject()
    private val cleanup = JSONObject()
    private val fixtureManifest = JSONObject()
    private var activity: Activity? = null
    private var crashText: String? = null
    private var originalRotation = 0
    private var originalAutoRotation = 0
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
        writeJson("checks.json", JSONObject().put("summary", summary).put("checks", checks))
        writeJson("ui-record.json", ui)
        writeJson("analysis.json", analysisJson)
        writeJson("fixture-manifest.json", fixtureManifest)
        writeJson("cleanup.json", cleanup)
        return summary
    }

    // ---------------------------------------------------------------- ui helpers

    private fun idle() { try { ua.waitForIdle(500, 5000) } catch (_: TimeoutException) {}; Thread.sleep(800) }

    private fun root(): AccessibilityNodeInfo? {
        repeat(12) {
            ua.clearCache()
            val r = ua.rootInActiveWindow
            if (r != null && r.packageName?.toString() == pkg) return r
            Thread.sleep(250)
        }
        return null
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) { out += n; for (i in 0 until n.childCount) n.getChild(i)?.let(::walk) }
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
        return clickableAncestor(n)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (predicate()) return true; Thread.sleep(300) }
        return predicate()
    }

    /** Looks for [text] on screen; scrolls the first scrollable container forward (then back to top) when it is off screen. */
    private fun findScrolling(text: String, exact: Boolean = true): AccessibilityNodeInfo? {
        val dm = target.resources.displayMetrics
        fun visible(n: AccessibilityNodeInfo): Boolean = bounds(n).let { it.height() > 0 && it.top >= 0 && it.bottom <= dm.heightPixels }
        findAll(text, exact).firstOrNull(::visible)?.let { return it }
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

    private fun back() { ua.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); idle() }

    private val tabLabels = listOf("홈", "파인더", "영상", "도구")

    private fun tabNode(label: String): AccessibilityNodeInfo? = findAll(label).maxByOrNull { bounds(it).top }
        ?.takeIf { bounds(it).top > target.resources.displayMetrics.heightPixels * 0.7 }

    private fun ensureTabBar(): Boolean {
        repeat(8) {
            if (waitFor(3500) { tabLabels.all { tabNode(it) != null } }) return true
            back()
        }
        return tabLabels.all { tabNode(it) != null }
    }

    private fun launch() {
        // phone smoke is a portrait-phone check: record the starting rotation, then freeze portrait via UiAutomation (same locked state the device already had)
        originalRotation = Settings.System.getInt(target.contentResolver, Settings.System.USER_ROTATION, 0)
        originalAutoRotation = Settings.System.getInt(target.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        ui.put("rotationBefore", originalRotation).put("accelerometerBefore", originalAutoRotation)
        ua.setRotation(UiAutomation.ROTATION_FREEZE_0)
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
        val bmp = ua.takeScreenshot() ?: throw IllegalStateException("takeScreenshot returned null for $name")
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
        pass("env.version", "installed app is 2.23.0 / 423001 / 202610211443",
            info.versionName == "2.23.0" && info.longVersionCode == 423001L && env.optString("buildNumber") == "202610211443", env.toString())
    }

    private fun homeRoots() {
        check("ui.tabbar", "4 root tab bar is reachable", if (ensureTabBar()) "PASS" else "FAIL", "tabs=" + tabLabels.joinToString { it + "=" + (tabNode(it) != null) })
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
        pass("roots.distinct", "홈/파인더/영상/도구 taps open distinct screens (visible-label Jaccard <= 0.8 for every pair)", distinct, detail.toString())
        // Finder: formal 즐겨찾기 section first, before the connection list. (Wide-window sidebar route is not a phone surface.)
        tabNode("파인더")?.let { click(it) }; idle(); waitFor(4000) { "파일 열기" in labels() }
        val fav = findAll("즐겨찾기").minByOrNull { bounds(it).top }
        val other = nodes().filter { label(it) in setOf("파일 열기", "저장된 연결", "NAS", "네트워크 추가") }.minByOrNull { bounds(it).top }
        pass("finder.favorites", "Finder root shows the formal 즐겨찾기 section above the source/connection list",
            fav != null && (other == null || bounds(fav).top <= bounds(other).top), "fav=${fav?.let { bounds(it) }} next=${other?.let { label(it) + bounds(it) }}")
        ui.put("finderFavoritesNote", "phone surface only; the sidebar 즐겨찾기 route belongs to wide windows (tablet slot, not run)")
        // Tools: each row opens its own destination; the 도구 tab itself never lands on 설정.
        tabNode("도구")?.let { click(it) }; idle(); waitFor(4000) { "사진 선별" in labels() }
        val toolsLabels = labels()
        pass("tools.root", "도구 tab root lists 사진 선별 / 사진·영상 전송 / 설정 rows and is not the settings screen",
            listOf("사진 선별", "사진·영상 전송", "설정").all { it in toolsLabels } && "테마" !in toolsLabels, toolsLabels.distinct().joinToString("|").take(600))
        val toolsRoot = labels().toSet()
        for ((row, marker) in listOf("사진·영상 전송" to "보내기", "설정" to "테마", "사진 선별" to "사진 보관함에서 고르기")) {
            val clicked = clickLabel(row)
            waitFor(4000) { labels().toSet() != toolsRoot }
            val screen = labels().toSet()
            val changed = screen != toolsRoot
            val markerOk = marker == null || waitFor(4000) { marker in labels() }
            capture("tools-$row".replace('·', '-').replace(' ', '_'), controls = listOf("뒤로"))
            pass("tools.$row", "도구 → '$row' opens its own screen (marker=${marker ?: "n/a"}) distinct from the tools root and from settings unless it is 설정",
                clicked && changed && markerOk && (row == "설정" || "테마" !in screen), "clicked=$clicked changed=$changed marker=$markerOk")
            if (clicked) { if (!clickLabel("뒤로")) back() }
            if (!ensureTabBar()) check("tools.$row.return", "returned to a tab root", "FAIL", "tab bar missing after back")
            tabNode("도구")?.let { click(it) }; idle(); waitFor(4000) { "사진 선별" in labels() }
        }
    }

    private fun settingsOrder() {
        tabNode("도구")?.let { click(it) }; idle(); waitFor(4000) { "사진 선별" in labels() }
        clickLabel("설정")
        waitFor(4000) { "테마" in labels() }
        val wanted = listOf("테마", "아이콘 스타일", "화면 켜짐", "앱 아이콘", "저장공간 관리", "파일 선택기 연동", "앱 정보")
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
        pass("settings.initial", "Settings initial placement starts 테마 → 아이콘 스타일 → 화면 켜짐 (visible prefix in the requested order)",
            initialOrder.isNotEmpty() && initialOrder.first() == "테마" && initialOrder == wanted.filter { it in initialOrder }, "initial=$initialOrder")
        pass("settings.order", "Settings sections follow 테마 → 아이콘 스타일 → 화면 켜짐 → 앱 아이콘 → 저장공간 관리 → 파일 연동 → 정보 (scrolled)",
            order == wanted, "seen=$order")
        capture("settings-scrolled", controls = listOf("뒤로"))
        repeat(12) { nodes().firstOrNull { it.isScrollable && it.isVisibleToUser }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD); }
        idle()
        if (activity?.isFinishing == false) { back(); ensureTabBar() }
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
            val zero = PhotoSelectionSession(app, scope) { _, _ -> Result.failure(IllegalStateException("not used in smoke")) }
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
            val zeroVideo = PhotoSelectionSession(app, scope) { _, _ -> Result.failure(IllegalStateException("not used in smoke")) }
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
            val one = PhotoSelectionSession(app, scope) { _, _ -> Result.failure(IllegalStateException("not used in smoke")) }
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
                    val forestSelected = selectedNames?.any { it.startsWith("f02") } == true
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
