package es.tdttv.app

data class Channel(
    val id: String,
    val name: String,
    val group: String,
    val url: String,
    val logo: String?,
    /** El canal emite subtitulos: solo entonces se ofrece la opcion en el menu. */
    val hasSubs: Boolean,
    /** Canal que pide cuenta; la v1 los descarta. */
    val needsLogin: Boolean,
    /** Clave de receta explicita; si es null se busca por dominio. */
    val recipeKey: String?
) {
    /** La URL es un flujo HLS directo (.m3u8): lo reproduce el reproductor nativo en vez de abrir una web. */
    val isStream: Boolean
        get() = url.substringBefore('#').substringBefore('?').endsWith(".m3u8", ignoreCase = true)
}
