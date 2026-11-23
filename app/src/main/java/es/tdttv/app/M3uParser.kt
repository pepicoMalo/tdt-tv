package es.tdttv.app

import java.text.Normalizer

/**
 * M3U extendido. Cada canal:
 *   #EXTINF:-1 tvg-id="la1" tvg-logo="..." group-title="Nacional" x-subs="1",La 1
 *   https://pagina-oficial-del-directo
 * Si la URL termina en .m3u8 es un flujo HLS directo y se reproduce con el reproductor nativo (sin pagina web, asi que
 * x-subs, x-login y x-recipe solo cuentan si el flujo falla y se abre x-web).
 *
 * tvg-id es el id del canal en la guia de programacion (XMLTV), p. ej. La1.TV. La cabecera puede llevar
 * url-tvg="https://.../guia.xml.gz" con la guia.
 *
 * Atributos propios (los reproductores M3U normales los ignoran):
 *   x-subs="1"      el canal emite subtitulos
 *   x-login="1"     pide cuenta: se queda en la lista y el usuario inicia sesion con el modo cursor
 *   x-recipe="clave" fuerza una receta de recipes.json
 *   x-web="https://..." pagina del canal a la que se vuelve si el flujo .m3u8 no arranca
 *   x-audio="1"     emisora de radio: la URL es un flujo de audio (mp3, aac, .m3u8...) y se reproduce sin pagina web
 *   x-disabled="1"  desactiva el canal sin borrarlo
 */
object M3uParser {
    private val ATTR = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var pending: Pair<Map<String, String>, String>? = null

        for (raw in text.lineSequence()) {
            val line = raw.removePrefix("﻿").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pending = parseExtinf(line)
                continue
            }
            if (line.startsWith("#")) continue

            val (attrs, name) = pending ?: continue
            pending = null

            // La lista es remota: solo se aceptan paginas https.
            if (!line.startsWith("https://")) continue
            if (attrs["x-disabled"] == "1") continue
            if (name.isBlank()) continue

            out += Channel(
                id = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: slug(name),
                name = name,
                group = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Otros",
                url = line,
                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                hasSubs = attrs["x-subs"] == "1",
                needsLogin = attrs["x-login"] == "1",
                recipeKey = attrs["x-recipe"]?.takeIf { it.isNotBlank() },
                webUrl = attrs["x-web"]?.takeIf { it.startsWith("https://") },
                audio = attrs["x-audio"] == "1"
            )
        }
        return out
    }

    /** url-tvg de la cabecera #EXTM3U: la guia de programacion (solo https), o null. */
    fun epgUrl(text: String): String? {
        val header = text.lineSequence()
            .map { it.removePrefix("﻿").trim() }
            .firstOrNull { it.startsWith("#EXTM3U", ignoreCase = true) }
            ?: return null
        val attrs = ATTR.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return (attrs["url-tvg"] ?: attrs["x-tvg-url"])?.takeIf { it.startsWith("https://") }
    }

    private fun parseExtinf(line: String): Pair<Map<String, String>, String> {
        var inQuotes = false
        var comma = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) {
                comma = i
                break
            }
        }
        val head = if (comma >= 0) line.substring(0, comma) else line
        val name = if (comma >= 0) line.substring(comma + 1).trim() else ""
        val attrs = ATTR.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return attrs to name
    }

    private fun slug(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
}
