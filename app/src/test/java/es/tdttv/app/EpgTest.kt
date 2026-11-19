package es.tdttv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EpgTest {

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    private val xmltv = """
        <?xml version='1.0' encoding='utf-8'?>
        <tv>
          <channel id="La1.TV"><display-name>La1.TV</display-name></channel>
          <programme channel="La1.TV" start="20261119093500 +0000" stop="20261119100000 +0000">
            <title lang="es">Telediario &amp; Cia</title>
          </programme>
          <programme channel="La1.TV" start="20261119065000 +0000" stop="20261119093500 +0000">
            <title>La hora de La 1</title><desc>Resumen</desc>
          </programme>
          <programme channel="La1.TV" start="20261118000000 +0000" stop="20261118010000 +0000">
            <title>Programa de ayer</title>
          </programme>
          <programme channel="Otro.TV" start="20261119065000 +0000" stop="20261119093500 +0000">
            <title>De un canal que no esta en la lista</title>
          </programme>
          <programme channel="Zona.TV" start="20261119080000 +0100" stop="20261119090000 +0100">
            <title>Con hora local</title>
          </programme>
          <programme channel="Zona.TV" start="mal" stop="20261119100000 +0000">
            <title>Fecha rota</title>
          </programme>
          <programme channel="Zona.TV" start="20261119100000 +0000" stop="20261119110000 +0000">
          </programme>
        </tv>
    """.trimIndent()

    private fun parse(): Map<String, List<Programme>> =
        XmltvParser.parse(
            xmltv.byteInputStream(Charsets.UTF_8),
            setOf("La1.TV", "Zona.TV"),
            notBefore = ms("2026-11-19T00:00:00Z")
        )

    @Test
    fun se_queda_con_los_canales_pedidos_y_los_programas_que_no_acabaron_antes() {
        val parsed = parse()
        assertEquals(setOf("La1.TV", "Zona.TV"), parsed.keys)
        assertEquals(listOf("La hora de La 1", "Telediario & Cia"), parsed.getValue("La1.TV").map { it.title })
    }

    @Test
    fun respeta_la_zona_horaria_de_los_instantes() {
        val p = parse().getValue("Zona.TV").single()
        assertEquals("Con hora local", p.title)
        assertEquals(ms("2026-11-19T07:00:00Z"), p.start)
        assertEquals(ms("2026-11-19T08:00:00Z"), p.stop)
    }

    @Test
    fun descarta_los_programas_sin_titulo_o_con_fecha_rota() {
        val titles = parse().values.flatten().map { it.title }
        assertTrue("Fecha rota" !in titles)
        assertEquals(3, titles.size)
    }

    @Test
    fun current_incluye_el_inicio_y_excluye_el_final() {
        val epg = Epg(1L, parse())
        assertEquals("La hora de La 1", epg.current("La1.TV", ms("2026-11-19T06:50:00Z"))?.title)
        assertEquals("La hora de La 1", epg.current("La1.TV", ms("2026-11-19T09:34:59Z"))?.title)
        assertEquals("Telediario & Cia", epg.current("La1.TV", ms("2026-11-19T09:35:00Z"))?.title)
        assertNull(epg.current("La1.TV", ms("2026-11-19T10:00:00Z")))
        assertNull(epg.current("Desconocido.TV", ms("2026-11-19T09:00:00Z")))
    }

    @Test
    fun la_guia_sobrevive_a_guardarla_y_leerla() {
        val original = Epg(42L, parse())
        val back = Epg.fromJson(original.toJson())
        assertNotNull(back)
        assertEquals(42L, back!!.fetchedAt)
        val now = ms("2026-11-19T09:40:00Z")
        assertEquals(original.current("La1.TV", now), back.current("La1.TV", now))
    }

    @Test
    fun un_json_estropeado_no_rompe_nada() {
        assertNull(Epg.fromJson("esto no es json"))
        assertNull(Epg.fromJson("{}"))
        assertTrue(Epg.EMPTY.isEmpty())
    }
}
