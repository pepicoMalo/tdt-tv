package es.tdttv.app

import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/** Un programa de la guia; los instantes son milisegundos desde 1970. */
data class Programme(val start: Long, val stop: Long, val title: String)

/** Guia de programacion: para cada id de canal (tvg-id), sus programas ordenados por inicio. */
class Epg(val fetchedAt: Long, private val byChannel: Map<String, List<Programme>>) {

    fun current(channelId: String, now: Long): Programme? =
        byChannel[channelId]?.firstOrNull { it.start <= now && now < it.stop }

    fun isEmpty(): Boolean = byChannel.isEmpty()

    fun toJson(): String {
        val channels = JSONObject()
        for ((id, list) in byChannel) {
            val arr = JSONArray()
            for (p in list) arr.put(JSONArray().put(p.start).put(p.stop).put(p.title))
            channels.put(id, arr)
        }
        return JSONObject().put("fetched", fetchedAt).put("channels", channels).toString()
    }

    companion object {
        val EMPTY = Epg(0L, emptyMap())

        fun fromJson(text: String): Epg? =
            try {
                val root = JSONObject(text)
                val channels = root.getJSONObject("channels")
                val map = HashMap<String, List<Programme>>()
                for (id in channels.keys()) {
                    val arr = channels.getJSONArray(id)
                    map[id] = (0 until arr.length()).map {
                        val p = arr.getJSONArray(it)
                        Programme(p.getLong(0), p.getLong(1), p.getString(2))
                    }
                }
                Epg(root.getLong("fetched"), map)
            } catch (_: Exception) {
                null
            }
    }
}

/** Lee un XMLTV (como el de TDTChannels) en streaming, sin cargarlo entero en memoria. */
object XmltvParser {

    /**
     * Se queda solo con los canales de [wanted] y con los programas que terminan despues de [notBefore].
     * Los instantes del XMLTV llevan zona horaria: "20261119065000 +0000".
     */
    fun parse(input: InputStream, wanted: Set<String>, notBefore: Long): Map<String, List<Programme>> {
        val out = HashMap<String, ArrayList<Programme>>()
        val factory = SAXParserFactory.newInstance()
        try {
            // El XML viene de fuera: ni DTD ni entidades externas
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // algunas implementaciones (Android) no conocen la opcion
        }
        val fmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).apply { isLenient = false }

        fun time(s: String?): Long? =
            if (s == null) null else try {
                fmt.parse(s)?.time
            } catch (_: Exception) {
                null
            }

        factory.newSAXParser().parse(InputSource(input), object : DefaultHandler() {
            private var channel: String? = null
            private var start = 0L
            private var stop = 0L
            private var title: StringBuilder? = null
            private var titleText = ""

            override fun startElement(uri: String?, localName: String?, qName: String?, atts: Attributes) {
                when (qName) {
                    "programme" -> {
                        channel = null
                        titleText = ""
                        val id = atts.getValue("channel")
                        if (id != null && id in wanted) {
                            val s = time(atts.getValue("start"))
                            val e = time(atts.getValue("stop"))
                            if (s != null && e != null && e > notBefore) {
                                channel = id
                                start = s
                                stop = e
                            }
                        }
                    }

                    "title" -> if (channel != null && titleText.isEmpty()) title = StringBuilder()
                }
            }

            override fun characters(ch: CharArray, offset: Int, length: Int) {
                title?.append(ch, offset, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (qName) {
                    "title" -> {
                        title?.let { titleText = it.toString().trim() }
                        title = null
                    }

                    "programme" -> {
                        val id = channel
                        if (id != null && titleText.isNotEmpty()) {
                            out.getOrPut(id) { ArrayList() } += Programme(start, stop, titleText)
                        }
                        channel = null
                    }
                }
            }
        })
        return out.mapValues { (_, list) -> list.sortedBy { it.start } }
    }
}
