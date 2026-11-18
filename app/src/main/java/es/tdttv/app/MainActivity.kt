package es.tdttv.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val LONG_PRESS_MS = 600L
private const val OVERLAY_MS = 4000L
private const val EXIT_WINDOW_MS = 2000L
private const val TAP_MS = 60L
private const val KEYBOARD_DELAY_MS = 250L
private const val PAGE_SCROLL_NOTCHES = 6f

// true si el elemento con foco admite texto (para forzar el teclado en pantalla tras pulsar con el cursor)
private const val EDITING_JS = "(function(){var a=document.activeElement;if(!a)return false;" +
    "if(a.isContentEditable)return true;var t=(a.tagName||'').toUpperCase();" +
    "if(t==='TEXTAREA')return true;if(t!=='INPUT')return false;" +
    "return !/^(button|submit|reset|checkbox|radio|image|file|range|color)\$/i.test(a.type||'');})()"

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var overlay: TextView
    private lateinit var repo: ChannelRepository
    private lateinit var baseUa: String
    private lateinit var desktopUa: String

    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("tdttv", Context.MODE_PRIVATE) }
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private var channels: List<Channel> = emptyList()
    private var recipes: RecipeBook = RecipeBook.EMPTY
    private var recipe = JSONObject()
    private var index = 0
    private var tuned = false

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private lateinit var pointerView: PointerView
    private val pointer by lazy { PointerMath(0, 0, resources.displayMetrics.density) }
    private var pointerMode = false
    private var lastBack = 0L
    private var okDownAt = 0L
    private var okLongDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = ChannelRepository(applicationContext)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = buildWebView()
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))

        pointerView = PointerView(this).apply { visibility = View.GONE }
        root.addView(pointerView, FrameLayout.LayoutParams(MATCH, MATCH))

        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC000000.toInt())
            setPadding(32, 16, 32, 16)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        root.addView(overlay, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
            setMargins(48, 36, 0, 0)
        })

        setContentView(root)
        loadLists()
    }

    override fun onStart() {
        super.onStart()
        // Al volver de Home se recarga el canal (onStop lo dejo en blanco para cortar el audio).
        if (tuned && channels.isNotEmpty()) tune(index)
    }

    override fun onPause() {
        super.onPause()
        // La sesion iniciada (cookies) tiene que llegar a disco aunque el sistema mate la app.
        CookieManager.getInstance().flush()
    }

    override fun onStop() {
        super.onStop()
        web.loadUrl("about:blank")
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val w = WebView(this)
        w.setBackgroundColor(Color.BLACK)
        w.isFocusable = true
        w.isFocusableInTouchMode = true
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        baseUa = w.settings.userAgentString
        val chrome = Regex("Chrome/([\\d.]+)").find(baseUa)?.groupValues?.get(1) ?: "120.0.0.0"
        desktopUa = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                return !(scheme == "https" || scheme == "http")
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url == "about:blank") return
                view.evaluateJavascript(PageScript.build(recipe, interactive = pointerMode), null)
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val name = channels.getOrNull(index)?.name ?: ""
                    showOverlay("No se pudo cargar $name: ${error.description}", sticky = true)
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreate()
                return true
            }
        }

        w.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                // indice 1: por encima del WebView y por debajo del overlay
                root.addView(view, 1, FrameLayout.LayoutParams(MATCH, MATCH))
            }

            override fun onHideCustomView() = hideCustomView()

            override fun onPermissionRequest(request: PermissionRequest) {
                // Solo DRM (Widevine); camara, micro, etc. fuera.
                val drm = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                if (drm.isNotEmpty()) request.grant(drm.toTypedArray()) else request.deny()
            }

            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
        return w
    }

    private fun hideCustomView() {
        customView?.let { root.removeView(it) }
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    // ---------------------------------------------------------------- Canales

    private fun loadLists() {
        showOverlay("Cargando lista de canales…", sticky = true)
        io.execute {
            val data = try {
                repo.load()
            } catch (e: Exception) {
                null
            }
            ui.post {
                if (isDestroyed) return@post
                if (data == null || data.channels.isEmpty()) {
                    showOverlay("No hay lista de canales disponible", sticky = true)
                    return@post
                }
                val wanted = channels.getOrNull(index)?.id ?: prefs.getString("last", null)
                channels = data.channels
                recipes = data.recipes
                index = channels.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
                tune(index)
            }
        }
    }

    private fun tune(i: Int) {
        if (channels.isEmpty()) return
        if (pointerMode) setPointerMode(false, announce = false)
        index = i.mod(channels.size)
        val ch = channels[index]
        recipe = recipes.forChannel(ch)
        web.settings.userAgentString = if (recipe.optBoolean("desktop", true)) desktopUa else baseUa
        hideCustomView()
        web.loadUrl(ch.url)
        prefs.edit().putString("last", ch.id).apply()
        tuned = true
        showOverlay(channelLabel(ch))
    }

    private fun channelLabel(ch: Channel): String {
        val cc = if (ch.hasSubs) "  ·  CC" else ""
        val account = if (ch.needsLogin) "  ·  pide cuenta: OK = cursor y teclado" else ""
        return "${index + 1}  ${ch.name}  ·  ${ch.group}$cc$account"
    }

    private fun showOverlay(text: String, sticky: Boolean = false) {
        overlay.text = text
        overlay.visibility = View.VISIBLE
        ui.removeCallbacks(hideOverlay)
        if (!sticky) ui.postDelayed(hideOverlay, OVERLAY_MS)
    }

    // ---------------------------------------------------------------- Subtitulos

    private fun toggleSubs() {
        val ch = channels.getOrNull(index) ?: return
        if (!ch.hasSubs) return
        web.evaluateJavascript("(window.__tdt && window.__tdt.toggleSubs && window.__tdt.toggleSubs()) || 'none'") { r ->
            val msg = when (r?.trim('"')) {
                "on" -> "Subtítulos activados"
                "off" -> "Subtítulos desactivados"
                "click" -> "Subtítulos: botón CC pulsado"
                else -> "Ahora mismo el canal no ofrece subtítulos"
            }
            showOverlay(msg)
        }
    }

    // ---------------------------------------------------------------- Menu

    private fun showMenu() {
        val ch = channels.getOrNull(index)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (ch?.hasSubs == true) {
            labels += "Subtítulos: activar / desactivar"
            actions += { toggleSubs() }
        }
        labels += "Elegir canal…"
        actions += { showChannelPicker() }
        labels += "Recargar canal"
        actions += { tune(index) }
        labels += "Actualizar lista de canales"
        actions += { loadLists() }
        labels += "Cursor y teclado (iniciar sesión, cookies)"
        actions += { setPointerMode(true) }
        labels += "Salir"
        actions += { finish() }

        AlertDialog.Builder(this)
            .setTitle(ch?.name ?: "TDT TV")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun showChannelPicker() {
        val names = channels.mapIndexed { i, c -> "${i + 1}  ${c.name}  ·  ${c.group}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Canales")
            .setSingleChoiceItems(names, index) { dialog, which ->
                dialog.dismiss()
                tune(which)
            }
            .show()
    }

    // ---------------------------------------------------------------- Cursor y teclado

    /**
     * Modo cursor: las flechas mueven un cursor, OK pulsa (un toque en el WebView, asi que los campos de texto abren
     * el teclado en pantalla) y CH+/CH- desplazan la pagina. La pagina se muestra sin el css de pantalla completa.
     */
    private fun setPointerMode(on: Boolean, announce: Boolean = true) {
        pointerMode = on
        pointerView.visibility = if (on) View.VISIBLE else View.GONE
        if (on) {
            pointer.resize(root.width, root.height)
            pointerView.moveTo(pointer.x, pointer.y)
            web.requestFocus()
        }
        web.evaluateJavascript("window.__tdt && window.__tdt.setInteractive && window.__tdt.setInteractive($on)", null)
        if (announce) {
            showOverlay(
                if (on) "Cursor: flechas mueven · OK pulsa · CH+/CH- desplazan · Atrás sale"
                else "Zapping activo"
            )
        }
    }

    private fun movePointer(dx: Int, dy: Int, repeat: Int) {
        val edge = pointer.move(dx, dy, repeat)
        pointerView.moveTo(pointer.x, pointer.y)
        // Empujar contra el borde superior / inferior desplaza la pagina
        if (edge != 0) scrollWheel(edge * (1f + repeat / 5f))
    }

    private fun tapPointer() {
        val x = pointer.x
        val y = pointer.y
        val downTime = SystemClock.uptimeMillis()
        web.requestFocus()
        sendTouch(MotionEvent.ACTION_DOWN, downTime, downTime, x, y)
        ui.postDelayed({
            sendTouch(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), x, y)
            ui.postDelayed({ showKeyboardIfEditing() }, KEYBOARD_DELAY_MS)
        }, TAP_MS)
    }

    private fun sendTouch(action: Int, downTime: Long, time: Long, x: Float, y: Float) {
        val ev = MotionEvent.obtain(downTime, time, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Rueda de raton en la posicion del cursor; notches > 0 baja la pagina. */
    private fun scrollWheel(notches: Float) {
        val t = SystemClock.uptimeMillis()
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            x = pointer.x
            y = pointer.y
            setAxisValue(MotionEvent.AXIS_VSCROLL, -notches)
        })
        val ev = MotionEvent.obtain(
            t, t, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0
        )
        web.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private fun showKeyboardIfEditing() {
        if (!pointerMode) return
        web.evaluateJavascript(EDITING_JS) { r ->
            if (r == "true" && pointerMode) {
                web.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(web, 0)
            }
        }
    }

    private fun pointerKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        val firstDown = down && e.repeatCount == 0
        when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (e.action == KeyEvent.ACTION_UP) setPointerMode(false)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (down) movePointer(0, -1, e.repeatCount)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (down) movePointer(0, 1, e.repeatCount)
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (down) movePointer(-1, 0, e.repeatCount)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (down) movePointer(1, 0, e.repeatCount)
                return true
            }

            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) scrollWheel(-PAGE_SCROLL_NOTCHES)
                return true
            }

            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) scrollWheel(PAGE_SCROLL_NOTCHES)
                return true
            }

            // Solo el OK del mando pulsa; ENTER (teclado) sigue a la pagina para enviar formularios.
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (down) {
                    if (e.repeatCount == 0) {
                        okDownAt = e.eventTime
                        okLongDone = false
                    } else if (!okLongDone && e.eventTime - okDownAt >= LONG_PRESS_MS) {
                        okLongDone = true
                        showMenu()
                    }
                } else if (!okLongDone) {
                    tapPointer()
                }
                return true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                if (firstDown) showMenu()
                return true
            }
        }
        return false
    }

    // ---------------------------------------------------------------- Mando

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (pointerMode) {
            return pointerKey(e) || super.dispatchKeyEvent(e)
        }

        val down = e.action == KeyEvent.ACTION_DOWN
        val firstDown = down && e.repeatCount == 0

        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_PAGE_UP -> {
                if (firstDown) tune(index + 1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (firstDown) tune(index - 1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> return true

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (down) {
                    if (e.repeatCount == 0) {
                        okDownAt = e.eventTime
                        okLongDone = false
                    } else if (!okLongDone && e.eventTime - okDownAt >= LONG_PRESS_MS) {
                        okLongDone = true
                        showMenu()
                    }
                } else if (!okLongDone) {
                    setPointerMode(true)
                }
                return true
            }

            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_SETTINGS -> {
                if (firstDown) showMenu()
                return true
            }

            KeyEvent.KEYCODE_INFO -> {
                if (firstDown) channels.getOrNull(index)?.let { showOverlay(channelLabel(it)) }
                return true
            }

            KeyEvent.KEYCODE_CAPTIONS -> {
                if (firstDown) toggleSubs()
                return true
            }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) {
            hideCustomView()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastBack < EXIT_WINDOW_MS) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            lastBack = now
            Toast.makeText(this, "Pulsa Atrás otra vez para salir", Toast.LENGTH_SHORT).show()
        }
    }
}
