package es.tdttv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RecipeBookTest {

    private val book = RecipeBook.parse(File("src/main/assets/recipes.json").readText())!!
    private val channels = M3uParser.parse(File("src/main/assets/canales.m3u").readText()).associateBy { it.name }

    private fun recipe(name: String) = book.forChannel(channels.getValue(name))

    @Test
    fun por_defecto_se_rechazan_las_cookies() {
        val r = recipe("Canal Sur")
        assertTrue(r.getString("js").contains("didomi-notice-disagree-button"))
        assertTrue(r.getBoolean("fullVideo"))
    }

    @Test
    fun atresplayer_acepta_cookies_y_no_hereda_el_rechazo() {
        val r = recipe("Antena 3")
        assertTrue(r.getString("js").contains("didomi-notice-agree-button"))
        assertFalse(r.getString("js").contains("disagree"))
        assertEquals("button[aria-label=\"Directo\"]", r.getString("play"))
        assertEquals(r.toString(), recipe("laSexta").toString())
    }

    @Test
    fun mediaset_acepta_cookies_y_no_hereda_el_rechazo() {
        val r = recipe("Telecinco")
        assertTrue(r.getString("js").contains("aceptar y navegar"))
        assertFalse(r.getString("js").contains("disagree"))
        assertEquals(r.toString(), recipe("Cuatro").toString())
    }

    @Test
    fun los_canales_hermanos_de_atres_y_mediaset_usan_la_misma_receta_que_aceptan_cookies() {
        val atres = recipe("Antena 3").toString()
        val mediaset = recipe("Telecinco").toString()
        val all = M3uParser.parse(File("src/main/assets/canales.m3u").readText())
        val atresChannels = all.filter { "atresplayer.com" in it.url }
        val mediasetChannels = all.filter { "mediasetinfinity.es" in it.url }
        assertTrue(atresChannels.size > 1 && mediasetChannels.size > 1)
        atresChannels.forEach { assertEquals(it.name, atres, book.forChannel(it).toString()) }
        mediasetChannels.forEach { assertEquals(it.name, mediaset, book.forChannel(it).toString()) }
    }

    @Test
    fun tres_cat_hereda_el_rechazo_y_suma_css_propio() {
        val r = recipe("TV3")
        assertTrue(r.getString("js").contains("didomi-notice-disagree-button"))
        assertEquals(".bitmovinplayer-container", r.getString("player"))
        assertNotNull(r.optString("ccJs", null))
    }

    @Test
    fun cope_pulsa_el_boton_de_video_y_hereda_el_rechazo_de_cookies() {
        // Canal propio (no el de la lista): la lista la edita su dueno y puede quitar TRECE
        val trece = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"t\",TRECE\nhttps://www.cope.es/directos/trece\n"
        ).single()
        val r = book.forChannel(trece)
        assertEquals(".c-card--claim__toggle__btn.toggle-btn-video", r.getString("play"))
        assertTrue(r.getString("js").contains("didomi-notice-disagree-button"))
        assertTrue(r.getBoolean("fullVideo"))
    }

    @Test
    fun inheritJs_no_se_filtra_a_la_receta_final() {
        assertFalse(recipe("Antena 3").has("inheritJs"))
    }
}
