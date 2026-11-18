package es.tdttv.app

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Descarga canales.m3u y recipes.json. Orden: remoto -> cache en disco -> assets. */
class ChannelRepository(private val ctx: Context) {

    class Data(val channels: List<Channel>, val recipes: RecipeBook)

    /** Bloqueante: llamar fuera del hilo principal. */
    fun load(): Data {
        val m3u = fetch(LIST_FILE) { M3uParser.parse(it).isNotEmpty() }
        val rec = fetch(RECIPES_FILE) { RecipeBook.parse(it) != null }
        return Data(M3uParser.parse(m3u), RecipeBook.parse(rec) ?: RecipeBook.EMPTY)
    }

    private fun fetch(name: String, valid: (String) -> Boolean): String {
        val cache = File(ctx.filesDir, name)
        val base = BuildConfig.LIST_BASE_URL.trimEnd('/')
        if (base.startsWith("https://")) {
            try {
                val text = download("$base/$name")
                if (valid(text)) {
                    val tmp = File(ctx.filesDir, "$name.tmp")
                    tmp.writeText(text)
                    tmp.renameTo(cache)
                    return text
                }
            } catch (_: Exception) {
                // sin red o repo caido: se usa la cache
            }
        }
        if (cache.exists()) {
            val text = cache.readText()
            if (valid(text)) return text
        }
        return ctx.assets.open(name).bufferedReader().use { it.readText() }
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.useCaches = false
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")

            val buf = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val chunk = ByteArray(8192)
                var total = 0
                var n = input.read(chunk)
                while (n > 0) {
                    total += n
                    if (total > MAX_BYTES) throw IOException("fichero demasiado grande")
                    buf.write(chunk, 0, n)
                    n = input.read(chunk)
                }
            }
            return buf.toString("UTF-8")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val LIST_FILE = "canales.m3u"
        const val RECIPES_FILE = "recipes.json"
        private const val MAX_BYTES = 1_000_000
    }
}
