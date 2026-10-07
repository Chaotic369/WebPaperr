package com.webwallpaper.app

import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.webkit.*
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var pv: WebView? = null          // native preview of a website (no iframe restrictions)
    private var pvBox: FrameLayout? = null
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var pickVideo = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)   // dark only, no white flash while the page loads
        root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            textZoom = 100          // previews must match what the wallpaper / browser shows
            minimumFontSize = 1
            minimumLogicalFontSize = 1
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                if (request == null) null else LocalServer.intercept(this@MainActivity, request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = cb
                val accept = p?.acceptTypes?.joinToString(",") ?: ""
                pickVideo = accept.contains("video")
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (pickVideo) "video/*" else "*/*"
                }
                return try { startActivityForResult(i, 1); true } catch (e: Exception) {
                    fileCb = null; cb?.onReceiveValue(null); false
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        web.loadUrl("https://${Store.HOST}/assets/index.html")
    }

    // ---------------------------------------------------------------- native website preview

    private fun previewView(): WebView {
        pv?.let { return it }
        val box = FrameLayout(this)
        box.clipChildren = true
        box.visibility = View.GONE
        val v = WebView(this)
        v.setBackgroundColor(Color.BLACK)
        v.isVerticalScrollBarEnabled = false
        v.isHorizontalScrollBarEnabled = false
        v.overScrollMode = View.OVER_SCROLL_NEVER
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true   // keep the preview silent
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            textZoom = 100
            minimumFontSize = 1
            minimumLogicalFontSize = 1
        }
        v.webViewClient = WebViewClient()             // any http(s) site, follows redirects inside the preview
        box.addView(v, FrameLayout.LayoutParams(1, 1))
        root.addView(box, FrameLayout.LayoutParams(1, 1))
        pv = v
        pvBox = box
        return v
    }

    private fun applyPreviewLayout(j: JSONObject) {
        val box = pvBox ?: return
        if (!j.optBoolean("show")) { box.visibility = View.GONE; pv?.onPause(); return }
        val v = previewView()
        val d = resources.displayMetrics.density
        // snap every edge to whole device pixels so the scaled page and the clip line up exactly (no 1px bleed)
        val bL = (j.getDouble("l") * d).roundToInt(); val bT = (j.getDouble("t") * d).roundToInt()
        val bR = ((j.getDouble("l") + j.getDouble("w")) * d).roundToInt()
        val bB = ((j.getDouble("t") + j.getDouble("h")) * d).roundToInt()
        val vL = (j.getDouble("vl") * d).roundToInt(); val vT = (j.getDouble("vt") * d).roundToInt()
        val vR = (j.getDouble("vr") * d).roundToInt(); val vB = (j.getDouble("vb") * d).roundToInt()
        val bwI = (bR - bL).coerceAtLeast(1); val bhI = (bB - bT).coerceAtLeast(1)
        val zoom = j.optDouble("zoom", 1.0).coerceAtLeast(0.05)
        val dw = j.getDouble("dw"); val dh = j.getDouble("dh")
        val rad = (j.optDouble("rad", 0.0) * d).toFloat()

        // same maths as the wallpaper: page viewport = screen / zoom, then scaled to fill the preview box exactly
        val vw = (dw / zoom * d).toInt().coerceAtLeast(1)
        val vh = (dh / zoom * d).toInt().coerceAtLeast(1)
        val offL = bL - vL; val offT = bT - vT

        box.layoutParams = FrameLayout.LayoutParams((vR - vL).coerceAtLeast(1), (vB - vT).coerceAtLeast(1)).also {
            it.leftMargin = vL; it.topMargin = vT
        }
        v.layoutParams = FrameLayout.LayoutParams(vw, vh).also { it.leftMargin = offL; it.topMargin = offT }
        v.pivotX = 0f; v.pivotY = 0f
        v.scaleX = bwI.toFloat() / vw; v.scaleY = bhI.toFloat() / vh

        box.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // 1px inset hides the anti-aliased fringe of the scaled page
                outline.setRoundRect(offL + 1, offT + 1, offL + bwI - 1, offT + bhI - 1, (rad - 1f).coerceAtLeast(0f))
            }
        }
        box.clipToOutline = true
        box.invalidateOutline()
        if (box.visibility != View.VISIBLE) { box.visibility = View.VISIBLE; v.onResume() }
    }

    override fun onPause() { pv?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); if (pvBox?.visibility == View.VISIBLE) pv?.onResume() }
    override fun onDestroy() {
        pv?.let { try { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } catch (e: Throwable) { } }
        pv = null; pvBox = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val cb = fileCb ?: return
        fileCb = null
        val uri = if (resultCode == RESULT_OK) data?.data else null
        if (uri == null) { cb.onReceiveValue(null); return }
        importPicked(uri, cb, pickVideo)
    }

    private fun status(t: String) = runOnUiThread {
        web.evaluateJavascript("window.nativeStatus&&window.nativeStatus(${JSONObject.quote(t)})", null)
    }

    private fun reject(cb: ValueCallback<Array<Uri>>, why: String) = runOnUiThread {
        status("")
        Toast.makeText(this, why, Toast.LENGTH_LONG).show()
        cb.onReceiveValue(null)
    }

    private fun sizeOf(uri: Uri): Long {
        var n = -1L
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.SIZE)
                if (i >= 0 && !c.isNull(i)) n = c.getLong(i)
            }
        }
        return n
    }

    private fun copyTo(uri: Uri, dst: File) {
        contentResolver.openInputStream(uri)!!.use { ins -> dst.outputStream().use { ins.copyTo(it) } }
    }

    /** Copies the picked file into app storage. Videos are size/length limited and compressed when big. */
    private fun importPicked(uri: Uri, cb: ValueCallback<Array<Uri>>, isVideo: Boolean) {
        Thread {
            try {
                var name = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (!name.contains('.')) name += if (isVideo) ".mp4" else ".html"
                val stamp = System.currentTimeMillis()
                val dst = File(Store.filesDir(this), "${stamp}_$name")

                if (isVideo) {
                    val size = sizeOf(uri)
                    if (size > VideoCompressor.MAX_BYTES) { reject(cb, "Video is too large (max 1 GB)"); return@Thread }
                    val info = VideoCompressor.probe(this, uri)
                    if (info != null && info.durationMs > VideoCompressor.MAX_DURATION_MS) {
                        reject(cb, "Video is too long (max 3 minutes)"); return@Thread
                    }
                    if (VideoCompressor.needsCompress(info, size)) {
                        val out = File(Store.filesDir(this), "${stamp}_" + name.substringBeforeLast('.') + ".mp4")
                        runOnUiThread {
                            status("Compressing video… 0%")
                            VideoCompressor.compress(this, uri, out, info, { p -> status("Compressing video… $p%") }) { ok ->
                                if (ok && out.exists() && out.length() > 0) {
                                    finishImport(out, uri, cb)
                                } else if (size in 0..VideoCompressor.FALLBACK_RAW_MAX_BYTES) {
                                    Thread {   // compression failed but the original is small enough to keep
                                        try { copyTo(uri, dst); finishImport(dst, uri, cb) }
                                        catch (e: Exception) { reject(cb, "Could not import video") }
                                    }.start()
                                } else reject(cb, "Could not compress this video")
                            }
                        }
                        return@Thread
                    }
                }
                copyTo(uri, dst)
                finishImport(dst, uri, cb)
            } catch (e: Exception) {
                reject(cb, "Could not import file")
            }
        }.start()
    }

    private fun finishImport(file: File, uri: Uri, cb: ValueCallback<Array<Uri>>) {
        dropPending(file.name)
        val url = Store.fileToUrl(file)
        runOnUiThread {
            status("")
            web.evaluateJavascript("window.__pendingNative='$url';", null)
            cb.onReceiveValue(arrayOf(uri))
        }
    }

    // ---- files that were imported but never applied are removed when the next one is imported
    private fun activeNames(): List<String> {
        val p = Store.prefs(this)
        return listOf("home_src", "lock_src").mapNotNull { Store.urlToFile(this, p.getString(it, null))?.name }
    }

    private fun dropPending(newName: String) {
        val p = Store.prefs(this)
        val old = p.getString("pending_file", null)
        if (old != null && old != newName && old !in activeNames()) {
            try { File(Store.filesDir(this), old).delete() } catch (e: Exception) { }
        }
        p.edit().putString("pending_file", newName).apply()
    }

    private fun displayName(uri: Uri): String {
        var n = "file"
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) n = c.getString(i) ?: n
            }
        }
        return n
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.nativeBack && window.nativeBack()") { r ->
            if (r != "true") finish()
        }
    }

    private fun applyTheme(dark: Boolean) {
        window.statusBarColor = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
        val f = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility =
            if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
    }

    inner class Bridge {
        @JavascriptInterface fun getState(): String = Store.settingsJson(this@MainActivity)

        @JavascriptInterface fun saveSettings(json: String) {
            Store.prefs(this@MainActivity).edit().putString("settings", json).apply()
        }

        /** Range-capable loopback URL for a copied wallpaper file ("" if not applicable) – used for <video> previews. */
        @JavascriptInterface fun mediaUrl(url: String): String = try { MediaServer.urlFor(this@MainActivity, url) ?: "" } catch (e: Exception) { "" }

        /** Native website preview (shown above the preview box in the page, so X-Frame-Options / CSP can't block it). */
        @JavascriptInterface fun previewLoad(url: String) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) return
            runOnUiThread { previewView().loadUrl(url) }
        }

        @JavascriptInterface fun previewLayout(json: String) {
            runOnUiThread { try { applyPreviewLayout(JSONObject(json)) } catch (e: Exception) { } }
        }

        @JavascriptInterface fun previewHide() {
            runOnUiThread { pvBox?.visibility = View.GONE; pv?.loadUrl("about:blank") }
        }

        /** Removes a stored wallpaper file (history delete) unless it is the one currently applied. */
        @JavascriptInterface fun deleteFile(url: String) {
            val f = Store.urlToFile(this@MainActivity, url) ?: return
            if (f.name !in activeNames()) try { f.delete() } catch (e: Exception) { }
        }

        @JavascriptInterface fun setTheme(dark: Boolean) { runOnUiThread { applyTheme(dark) } }

        @JavascriptInterface fun clearCache() {
            val p = Store.prefs(this@MainActivity)
            val keep = listOf("home_src", "lock_src").mapNotNull { Store.urlToFile(this@MainActivity, p.getString(it, null))?.name }
            Store.filesDir(this@MainActivity).listFiles()?.forEach { if (it.name !in keep) it.delete() }
            runOnUiThread { web.clearCache(true) }
        }

        @JavascriptInterface fun applyWallpaper(type: String, url: String, target: String, settings: String): String {
            val e = Store.prefs(this@MainActivity).edit()
            e.putString("settings", settings)
            e.remove("hw_failed")   // let the fast renderer try again after every Apply
            val home = target == "Home Screen" || target == "Both"
            val lock = target == "Lock Screen" || target == "Both"
            if (home) e.putString("home_type", type).putString("home_src", url)
            if (lock) e.putString("lock_type", type).putString("lock_src", url)
            e.putLong("rev", System.currentTimeMillis())
            if (Store.urlToFile(this@MainActivity, url)?.name == Store.prefs(this@MainActivity).getString("pending_file", null)) e.remove("pending_file")
            e.apply()

            val wm = WallpaperManager.getInstance(this@MainActivity)
            val homeActive = wm.wallpaperInfo?.packageName == packageName
            val lockActive = if (Build.VERSION.SDK_INT >= 34)
                wm.getWallpaperInfo(WallpaperManager.FLAG_LOCK)?.packageName == packageName else homeActive
            val needSet = (home && !homeActive) || (lock && !lockActive)
            if (!needSet) return "Applied"
            runOnUiThread {
                val comp = ComponentName(this@MainActivity, LiveWallpaperService::class.java)
                try {
                    startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, comp))
                } catch (ex: Exception) {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
                }
            }
            return "Tap \"Set wallpaper\" to finish"
        }
    }
}
