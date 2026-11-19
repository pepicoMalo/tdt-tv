package es.tdttv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldScriptTest {

    @Test
    fun el_texto_va_como_literal_js_escapado() {
        val js = FieldScript.fill("a\"b\\c\nd")
        assertTrue(js.contains("\"a\\\"b\\\\c\\nd\""))
        assertFalse(js.contains("__TEXT__"))
    }

    @Test
    fun un_texto_con_script_no_cierra_el_literal() {
        val js = FieldScript.fill("</script><b>")
        assertFalse(js.contains("</script>"))
    }

    @Test
    fun el_texto_se_inserta_una_sola_vez_aunque_contenga_el_marcador() {
        val js = FieldScript.fill("__TEXT__")
        assertEquals(1, Regex("\"__TEXT__\"").findAll(js).count())
    }

    @Test
    fun describe_guarda_el_campo_para_rellenarlo_despues() {
        assertTrue(FieldScript.DESCRIBE.contains("window.__tdtField = el"))
        assertTrue(FieldScript.fill("x").contains("window.__tdtField"))
    }
}
