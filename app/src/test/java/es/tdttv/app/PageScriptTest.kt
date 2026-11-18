package es.tdttv.app

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageScriptTest {

    private val recipe = JSONObject("""{"fullVideo":true,"js":"var x = 1;"}""")

    @Test
    fun no_deja_marcadores_sin_sustituir() {
        val js = PageScript.build(recipe)
        assertFalse(js.contains("__RECIPE__"))
        assertFalse(js.contains("__INTERACTIVE__"))
    }

    @Test
    fun por_defecto_no_arranca_en_modo_interactivo() {
        assertTrue(PageScript.build(recipe).contains("interactive: false"))
    }

    @Test
    fun puede_arrancar_ya_en_modo_interactivo() {
        assertTrue(PageScript.build(recipe, interactive = true).contains("interactive: true"))
    }

    @Test
    fun expone_el_cambio_de_modo_interactivo() {
        assertTrue(PageScript.build(recipe).contains("T.setInteractive = function"))
    }
}
