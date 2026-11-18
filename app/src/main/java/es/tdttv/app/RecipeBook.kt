package es.tdttv.app

import org.json.JSONObject
import java.net.URI

/**
 * recipes.json:
 * {
 *   "default": { "css": "", "fullVideo": true, "desktop": true },
 *   "recipes": {
 *     "rtve.es": { "css": "...", "play": "selector", "cc": "selector", "ccJs": "...", "js": "...", "player": "selector",
 *                  "inheritJs": false }
 *   }
 * }
 * La receta se elige por x-recipe del canal o, si no, por el dominio de su URL, y se
 * superpone a "default" (css y js se concatenan, el resto de campos se sustituye).
 */
class RecipeBook private constructor(private val root: JSONObject) {

    fun forChannel(ch: Channel): JSONObject {
        val merged = JSONObject(root.optJSONObject("default")?.toString() ?: "{}")
        val sites = root.optJSONObject("recipes") ?: return merged
        val host = try {
            URI(ch.url).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val key = ch.recipeKey ?: sites.keys().asSequence().firstOrNull { host == it || host.endsWith(".$it") }
        val site = key?.let { sites.optJSONObject(it) } ?: return merged

        // inheritJs=false: el sitio no hereda el js de "default" (p. ej. el rechazo de cookies)
        if (!site.optBoolean("inheritJs", true)) merged.remove("js")

        for (k in site.keys()) {
            if (k == "inheritJs") continue
            if (k == "css" || k == "js") merged.put(k, merged.optString(k) + "\n" + site.optString(k))
            else merged.put(k, site.get(k))
        }
        return merged
    }

    companion object {
        val EMPTY = RecipeBook(JSONObject())

        fun parse(text: String): RecipeBook? =
            try {
                RecipeBook(JSONObject(text))
            } catch (_: Exception) {
                null
            }
    }
}
