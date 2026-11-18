package es.tdttv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerMathTest {

    private fun pointer() = PointerMath(1920, 1080, 1f)

    @Test
    fun empieza_en_el_centro() {
        val p = pointer()
        assertEquals(960f, p.x, 0.01f)
        assertEquals(540f, p.y, 0.01f)
    }

    @Test
    fun el_paso_crece_con_la_pulsacion_larga_y_tiene_tope() {
        val p = pointer()
        assertEquals(10f, p.step(0), 0.01f)
        assertTrue(p.step(5) > p.step(0))
        assertEquals(p.step(12), p.step(100), 0.01f)
    }

    @Test
    fun el_paso_escala_con_la_densidad() {
        assertEquals(2 * PointerMath(1920, 1080, 1f).step(0), PointerMath(1920, 1080, 2f).step(0), 0.01f)
    }

    @Test
    fun mueve_en_la_direccion_pedida() {
        val p = pointer()
        p.move(1, 0, 0)
        assertEquals(970f, p.x, 0.01f)
        assertEquals(540f, p.y, 0.01f)
        p.move(0, -1, 0)
        assertEquals(530f, p.y, 0.01f)
    }

    @Test
    fun no_se_sale_de_la_pantalla() {
        val p = pointer()
        repeat(200) { p.move(-1, -1, 12) }
        assertEquals(0f, p.x, 0.01f)
        assertEquals(0f, p.y, 0.01f)
        repeat(200) { p.move(1, 1, 12) }
        assertEquals(1919f, p.x, 0.01f)
        assertEquals(1079f, p.y, 0.01f)
    }

    @Test
    fun empujar_contra_el_borde_pide_desplazar_la_pagina() {
        val p = pointer()
        // Llegar al borde inferior no desplaza; la pulsacion siguiente si
        var last = 0
        repeat(200) { last = p.move(0, 1, 12) }
        assertEquals(1, last)
        assertEquals(1, p.move(0, 1, 0))
        assertEquals(0, p.move(0, -1, 0))
        repeat(200) { p.move(0, -1, 12) }
        assertEquals(-1, p.move(0, -1, 0))
    }

    @Test
    fun el_paso_que_llega_al_borde_no_desplaza() {
        val p = PointerMath(100, 100, 1f)
        // 58 px desde y=50 pasan del borde: se queda en 99 sin haber empujado todavia
        assertEquals(0, p.move(0, 1, 12))
        assertEquals(99f, p.y, 0.01f)
        assertEquals(1, p.move(0, 1, 12))
    }

    @Test
    fun moverse_en_horizontal_nunca_desplaza() {
        val p = pointer()
        repeat(200) { assertEquals(0, p.move(1, 0, 12)) }
        repeat(200) { assertEquals(0, p.move(-1, 0, 12)) }
    }

    @Test
    fun al_cambiar_el_tamano_el_cursor_se_queda_dentro() {
        val p = pointer()
        repeat(200) { p.move(1, 1, 12) }
        p.resize(1280, 720)
        assertEquals(1279f, p.x, 0.01f)
        assertEquals(719f, p.y, 0.01f)
    }
}
