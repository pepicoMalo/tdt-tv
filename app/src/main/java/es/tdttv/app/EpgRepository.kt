package es.tdttv.app

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Descarga la guia (XMLTV, normalmente .xml.gz) y guarda en disco solo lo que necesitan los canales de la lista. */
class EpgRepository(private val ctx: Context) {

    private val cache get() = File(ctx.filesDir, CACHE_FILE)

    fun loadCache(): Epg =
        try {
            if (cache.exists()) Epg.fromJson(cache.readText()) ?: Epg.EMPTY else Epg.EMPTY
        } catch (_: Exception) {
            Epg.EMPTY
        }

    /**
     * Bloqueante: llamar fuera del hilo principal. Devuelve la guia nueva, o null si la actual todavia vale
     * (menos de [maxAgeMs]) o si la descarga falla (se sigue con la que hubiera).
     */
    fun refresh(url: String, wanted: Set<String>, current: Epg, maxAgeMs: Long): Epg? {
        val now = System.currentTimeMillis()
        if (current.fetchedAt > 0 && now - current.fetchedAt < maxAgeMs) return null
        if (!url.startsWith("https://") || wanted.isEmpty()) return null
        return try {
            val programmes = open(url) { XmltvParser.parse(it, wanted, now - KEEP_PAST_MS) }
            if (programmes.isEmpty()) return null
            val epg = Epg(now, programmes)
            val tmp = File(ctx.filesDir, "$CACHE_FILE.tmp")
            tmp.writeText(epg.toJson())
            tmp.renameTo(cache)
            epg
        } catch (_: Exception) {
            null
        }
    }

    private fun <T> open(url: String, read: (InputStream) -> T): T {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 20000
            c.useCaches = false
            if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
            val raw = BufferedInputStream(c.inputStream, 16 * 1024)
            raw.mark(2)
            val gzip = raw.read() == 0x1f && raw.read() == 0x8b
            raw.reset()
            val body = if (gzip) GZIPInputStream(raw) else raw
            return read(Limited(body, MAX_BYTES))
        } finally {
            c.disconnect()
        }
    }

    /** Corta la lectura si el XML (ya descomprimido) supera [max] bytes. */
    private class Limited(input: InputStream, private val max: Long) : FilterInputStream(input) {
        private var total = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0 && ++total > max) throw IOException("guia demasiado grande")
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                if (total > max) throw IOException("guia demasiado grande")
            }
            return n
        }
    }

    companion object {
        private const val CACHE_FILE = "epg.json"
        private const val MAX_BYTES = 40L * 1024 * 1024
        private const val KEEP_PAST_MS = 3L * 60 * 60 * 1000
    }
}
