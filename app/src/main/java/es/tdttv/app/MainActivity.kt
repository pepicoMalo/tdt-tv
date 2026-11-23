package es.tdttv.app

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val LONG_PRESS_MS = 600L
private const val OVERLAY_MS = 4000L
private const val EXIT_WINDOW_MS = 2000L
private const val TAP_MS = 60L
private const val FIELD_DELAY_MS = 250L
private const val PAGE_SCROLL_NOTCHES = 6f
private const val STREAM_RETRY_MS = 4000L
private const val STREAM_MAX_RETRIES = 3
private const val STREAM_START_TIMEOUT_MS = 20000L
private const val DOUBLE_TAP_MS = 350L
private const val EPG_MAX_AGE_MS = 6L * 60 * 60 * 1000

/**
 * WebView que nunca abre el teclado del sistema: en el Chromecast se abria y se cerraba solo, asi que el texto se
 * pide en un dialogo nativo (ver MainActivity.showTextDialog). Un teclado fisico sigue llegando como teclas.
 */
private class TvWebView(context: Context) : WebView(context) {
    override fun onCheckIsTextEditor(): Boolean = false
    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? = null
}

class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private lateinit var video: VideoView
    private lateinit var radioCard: LinearLayout
    private lateinit var radioName: TextView
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
    private var textDialog: AlertDialog? = null

    // true mientras suena un flujo HLS en el reproductor nativo (false = se muestra una pagina en el WebView)
    private var streaming = false
    private var streamRetries = 0
    private var rendered = false
    private val retryStream = Runnable {
        val ch = channels.getOrNull(index)
        if (ch != null && ch.isStream) startStream(ch)
    }

    // Si pasado un rato no hay imagen ni avance, el flujo no sirve: se vuelve a la web del canal (si la hay)
    private val streamWatchdog = Runnable {
        val ch = channels.getOrNull(index) ?: return@Runnable
        val moving = video.isPlaying && video.currentPosition > 1000
        if (streaming && !rendered && !moving) streamGaveUp(ch, 0, 0)
    }

    private lateinit var epgRepo: EpgRepository
    @Volatile private var epg: Epg = Epg.EMPTY
    private var epgUrl: String? = null
    private var epgBusy = false
    private var channelDialog: AlertDialog? = null
    private var channelAdapter: BaseAdapter? = null

    private var lastBack = 0L
    private var okDownAt = 0L
    private var okLongDone = false
    private var lastOkTap = 0L
    private val singleOk = Runnable {
        lastOkTap = 0L
        // Un flujo directo no tiene pagina: no hay nada que pulsar con el cursor
        val ch = channels.getOrNull(index)
        if (ch != null && streaming) showOverlay(channelLabel(ch)) else setPointerMode(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        repo = ChannelRepository(applicationContext)
        epgRepo = EpgRepository(applicationContext)
        io.execute { epg = epgRepo.loadCache() }

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        web = buildWebView()
        root.addView(web, FrameLayout.LayoutParams(MATCH, MATCH))

        // Reproductor nativo para los canales cuya URL es un flujo HLS directo (.m3u8); por encima del WebView.
        video = VideoView(this).apply {
            visibility = View.GONE
            isFocusable = false
            setOnPreparedListener {
                streamRetries = 0
                // Una radio no tiene imagen: estar preparada ya es "arrancada" (si no, el watchdog la daria por muerta)
                if (channels.getOrNull(index)?.audio == true) {
                    rendered = true
                    ui.removeCallbacks(streamWatchdog)
                }
            }
            setOnErrorListener { _, what, extra ->
                streamFailed(what, extra)
                true
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    rendered = true
                    ui.removeCallbacks(streamWatchdog)
                }
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    showOverlay("Cargando…", sticky = true)
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END ||
                    what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START
                ) {
                    channels.getOrNull(index)?.let { showOverlay(channelLabel(it)) }
                }
                true
            }
        }
        root.addView(video, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))

        // Emisoras de radio (x-audio): el reproductor solo da sonido, asi que se deja el nombre de la emisora en pantalla.
        radioName = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 48f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        radioCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(radioName, LinearLayout.LayoutParams(WRAP, WRAP))
            addView(TextView(context).apply {
                setTextColor(Color.WHITE)
                alpha = 0.6f
                textSize = 22f
                gravity = Gravity.CENTER
                text = "Radio en directo"
            }, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        root.addView(radioCard, FrameLayout.LayoutParams(MATCH, MATCH))

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
        textDialog?.dismiss()
        channelDialog?.dismiss()
        ui.removeCallbacks(singleOk)
        ui.removeCallbacks(retryStream)
        ui.removeCallbacks(streamWatchdog)
        video.stopPlayback()
        web.loadUrl("about:blank")
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        video.stopPlayback()
        io.shutdownNow()
        root.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val w = TvWebView(this)
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
                epgUrl = data.epgUrl
                index = channels.indexOfFirst { it.id == wanted }.coerceAtLeast(0)
                tune(index)
                refreshEpg()
            }
        }
    }

    /** Baja la guia de programacion en segundo plano si la que hay (en disco) tiene mas de EPG_MAX_AGE_MS. */
    private fun refreshEpg() {
        val url = epgUrl ?: return
        if (epgBusy) return
        epgBusy = true
        val wanted = channels.map { it.id }.toSet()
        io.execute {
            val current = epg
            val fresh = try {
                epgRepo.refresh(url, wanted, current, EPG_MAX_AGE_MS)
            } catch (e: Exception) {
                null
            }
            ui.post {
                epgBusy = false
                if (!isDestroyed && fresh != null) {
                    epg = fresh
                    channelAdapter?.notifyDataSetChanged()
                }
            }
        }
    }

    private fun tune(i: Int) {
        if (channels.isEmpty()) return
        if (pointerMode) setPointerMode(false, announce = false)
        index = i.mod(channels.size)
        val ch = channels[index]
        ui.removeCallbacks(singleOk)
        lastOkTap = 0L
        streamRetries = 0
        hideCustomView()
        if (ch.isStream) startStream(ch) else openPage(ch, ch.url)
        prefs.edit().putString("last", ch.id).apply()
        tuned = true
        showOverlay(channelLabel(ch))
    }

    private fun startStream(ch: Channel) {
        streaming = true
        rendered = false
        ui.removeCallbacks(retryStream)
        ui.removeCallbacks(streamWatchdog)
        web.loadUrl("about:blank")
        radioName.text = ch.name
        radioCard.visibility = if (ch.audio) View.VISIBLE else View.GONE
        video.visibility = View.VISIBLE
        video.setVideoURI(Uri.parse(ch.url))
        video.start()
        ui.postDelayed(streamWatchdog, STREAM_START_TIMEOUT_MS)
    }

    /** Muestra [url] en el WebView con la receta del canal (la pagina oficial, o la de respaldo si el flujo falla). */
    private fun openPage(ch: Channel, url: String) {
        streaming = false
        ui.removeCallbacks(retryStream)
        ui.removeCallbacks(streamWatchdog)
        video.stopPlayback()
        video.visibility = View.GONE
        radioCard.visibility = View.GONE
        recipe = recipes.forChannel(ch.copy(url = url))
        web.settings.userAgentString = if (recipe.optBoolean("desktop", true)) desktopUa else baseUa
        web.loadUrl(url)
    }

    /** El flujo dio error: reintenta unas veces (los directos se cortan al cambiar de red) y despues se rinde. */
    private fun streamFailed(what: Int, extra: Int) {
        val ch = channels.getOrNull(index) ?: return
        if (!streaming) return
        if (streamRetries < STREAM_MAX_RETRIES) {
            streamRetries++
            showOverlay("Reconectando ${ch.name}… ($streamRetries/$STREAM_MAX_RETRIES)", sticky = true)
            ui.removeCallbacks(retryStream)
            ui.postDelayed(retryStream, STREAM_RETRY_MS)
        } else {
            streamGaveUp(ch, what, extra)
        }
    }

    /** El flujo no arranca: se abre la web del canal si la lista la da (x-web); si no, se avisa. */
    private fun streamGaveUp(ch: Channel, what: Int, extra: Int) {
        val fallback = ch.webUrl
        if (fallback != null) {
            showOverlay("El directo de ${ch.name} no arranca: abro su web")
            openPage(ch, fallback)
        } else {
            val code = if (what != 0 || extra != 0) " (error $what/$extra)" else ""
            showOverlay("No se pudo reproducir ${ch.name}$code. Menú > Recargar canal", sticky = true)
        }
    }

    private fun channelLabel(ch: Channel): String {
        val cc = if (ch.hasSubs) "  ·  CC" else ""
        val account = if (ch.needsLogin && !streaming) "  ·  pide cuenta: OK = cursor y teclado" else ""
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
        labels += "Elegir canal… (también: OK dos veces)"
        actions += { showChannelList() }
        labels += "Recargar canal"
        actions += { tune(index) }
        labels += "Actualizar lista de canales"
        actions += { loadLists() }
        if (!streaming) {
            labels += "Cursor y teclado (iniciar sesión, cookies)"
            actions += { setPointerMode(true) }
        }
        labels += "Salir"
        actions += { finish() }

        AlertDialog.Builder(this)
            .setTitle(ch?.name ?: "TDT TV")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    /** Lista de todos los canales, con el programa que emiten ahora si la guia lo tiene; OK en uno lo sintoniza. */
    private fun showChannelList() {
        if (channels.isEmpty() || channelDialog != null) return
        val density = resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (10 * density).toInt()
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

        val adapter = object : BaseAdapter() {
            override fun getCount() = channels.size
            override fun getItem(position: Int) = channels[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padH, padV, padH, padV)
                    addView(TextView(context).apply { textSize = 20f })
                    addView(TextView(context).apply {
                        textSize = 15f
                        alpha = 0.7f
                    })
                }
                val ch = channels[position]
                val title = row.getChildAt(0) as TextView
                val sub = row.getChildAt(1) as TextView
                val here = position == index
                title.text = (if (here) "▶ " else "") + "${position + 1}  ${ch.name}"
                title.typeface = if (here) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                val now = epg.current(ch.id, System.currentTimeMillis())
                sub.text = if (now != null) "${clock.format(now.start)}–${clock.format(now.stop)}  ${now.title}" else ch.group
                return row
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Canales")
            .setAdapter(adapter) { d, which ->
                d.dismiss()
                tune(which)
            }
            .create()
        dialog.setOnDismissListener {
            channelDialog = null
            channelAdapter = null
        }
        channelDialog = dialog
        channelAdapter = adapter
        dialog.show()
        dialog.listView.setSelection(index)
        refreshEpg()
    }

    // ---------------------------------------------------------------- Cursor y teclado

    /**
     * Modo cursor: las flechas mueven un cursor, OK pulsa (un toque en el WebView; si deja el foco en un campo de
     * texto se abre un dialogo para escribir) y CH+/CH- desplazan la pagina. La pagina se muestra sin el css de
     * pantalla completa.
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
                if (on) "Cursor: flechas mueven · OK pulsa (en un campo, escribe) · CH+/CH- desplazan · Atrás sale"
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
            ui.postDelayed({ editFocusedField() }, FIELD_DELAY_MS)
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

    /** Tras pulsar con el cursor: si la pagina dejo el foco en un campo de texto, pide el texto en un dialogo. */
    private fun editFocusedField() {
        if (!pointerMode || textDialog != null) return
        web.evaluateJavascript(FieldScript.DESCRIBE) { r ->
            if (!pointerMode || textDialog != null || r == null || r == "null") return@evaluateJavascript
            val field = try {
                JSONObject(r)
            } catch (e: Exception) {
                return@evaluateJavascript
            }
            showTextDialog(field.optString("label"), field.optString("type"), field.optString("value"))
        }
    }

    private fun showTextDialog(label: String, type: String, current: String) {
        val password = type == "password"
        val multiline = type == "textarea"
        val edit = EditText(this).apply {
            inputType = when (type) {
                "password" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "email" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "url" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                "number" -> InputType.TYPE_CLASS_NUMBER
                "tel" -> InputType.TYPE_CLASS_PHONE
                "textarea" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT
            }
            if (!multiline) imeOptions = EditorInfo.IME_ACTION_DONE
            setText(current)
            setSelection(text.length)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (label.isBlank()) "Escribir" else "Escribir: $label")
            .setView(box)
            .setPositiveButton("Aceptar") { _, _ -> fillField(edit.text.toString()) }
            .setNegativeButton("Cancelar", null)
        if (password) builder.setNeutralButton("Ver", null)
        val dialog = builder.create()
        dialog.setOnDismissListener {
            textDialog = null
            web.requestFocus()
        }
        textDialog = dialog
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        if (password) {
            // El boton "Ver" alterna entre puntos y texto sin cerrar el dialogo.
            val toggle = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            toggle.setOnClickListener {
                val hidden = edit.transformationMethod != null
                edit.transformationMethod = if (hidden) null else PasswordTransformationMethod.getInstance()
                toggle.text = if (hidden) "Ocultar" else "Ver"
                edit.setSelection(edit.text.length)
            }
        }
        if (!multiline) {
            edit.setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    true
                } else {
                    false
                }
            }
        }
        edit.requestFocus()
    }

    private fun fillField(text: String) {
        web.evaluateJavascript(FieldScript.fill(text)) { r ->
            if (r != "true") Toast.makeText(this, "No se pudo escribir en el campo", Toast.LENGTH_SHORT).show()
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

    /**
     * OK corto en el zapping: un toque abre el modo cursor (en un flujo directo, el rotulo del canal) y dos toques
     * seguidos la lista de canales. El primer toque espera DOUBLE_TAP_MS por si llega el segundo.
     */
    private fun onOkTap() {
        val now = SystemClock.uptimeMillis()
        if (lastOkTap != 0L && now - lastOkTap <= DOUBLE_TAP_MS) {
            lastOkTap = 0L
            ui.removeCallbacks(singleOk)
            showChannelList()
        } else {
            lastOkTap = now
            ui.removeCallbacks(singleOk)
            ui.postDelayed(singleOk, DOUBLE_TAP_MS)
        }
    }

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
                    onOkTap()
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
