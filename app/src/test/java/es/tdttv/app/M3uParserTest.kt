package es.tdttv.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class M3uParserTest {

    private val seed = File("src/main/assets/canales.m3u").readText()

    // La lista incluida es una copia de la del repo tdt-tv-lista, que el dueño edita a mano:
    // aqui solo se comprueban invariantes, no su contenido concreto.
    @Test
    fun la_lista_incluida_es_valida() {
        val channels = M3uParser.parse(seed)
        assertTrue(channels.isNotEmpty())
        assertEquals("ids repetidos", channels.size, channels.map { it.id }.toSet().size)
        assertTrue(channels.all { it.name.isNotBlank() && it.url.startsWith("https://") })
        assertTrue(channels.any { it.name == "Telecinco" })
    }

    @Test
    fun solo_tv3_marca_subtitulos_en_la_lista_incluida() {
        val subs = M3uParser.parse(seed).filter { it.hasSubs }.map { it.name }
        assertEquals(listOf("TV3"), subs)
    }

    @Test
    fun conserva_los_canales_con_login_y_los_marca() {
        val channels = M3uParser.parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\",Libre\nhttps://ejemplo.es/a\n" +
                "#EXTINF:-1 tvg-id=\"b\" x-login=\"1\",Con cuenta\nhttps://ejemplo.es/b\n"
        )
        assertEquals(listOf("Libre", "Con cuenta"), channels.map { it.name })
        assertFalse(channels[0].needsLogin)
        assertTrue(channels[1].needsLogin)
    }

    @Test
    fun descarta_los_desactivados() {
        val channels = M3uParser.parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\",Activo\nhttps://ejemplo.es/a\n" +
                "#EXTINF:-1 tvg-id=\"b\" x-disabled=\"1\",Apagado\nhttps://ejemplo.es/b\n"
        )
        assertEquals(listOf("Activo"), channels.map { it.name })
    }

    @Test
    fun una_linea_extinf_cortada_no_rompe_la_lista() {
        // Caso real: un commit dejo "#EXTINF:-1 tvg-id" suelto al final del fichero
        val channels = M3uParser.parse("#EXTM3U\n#EXTINF:-1 tvg-id=\"a\",Uno\nhttps://ejemplo.es/a\n#EXTINF:-1 tvg-id")
        assertEquals(listOf("Uno"), channels.map { it.name })
    }

    @Test
    fun la_coma_dentro_de_comillas_no_corta_el_nombre() {
        val ch = M3uParser.parse(
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"x\" group-title=\"A, B\",Mi canal\nhttps://ejemplo.es/directo\n"
        ).single()
        assertEquals("Mi canal", ch.name)
        assertEquals("A, B", ch.group)
    }

    @Test
    fun una_url_m3u8_es_un_flujo_directo_y_una_pagina_no() {
        val channels = M3uParser.parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\",Flujo\nhttps://cdn.ejemplo.es/live/master.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"b\",Con token\nhttps://cdn.ejemplo.es/live/index.M3U8?token=abc#x\n" +
                "#EXTINF:-1 tvg-id=\"c\",Pagina\nhttps://www.ejemplo.es/directo\n" +
                "#EXTINF:-1 tvg-id=\"d\",Pagina con m3u8 en la query\nhttps://www.ejemplo.es/player?src=a.m3u8\n"
        )
        assertEquals(listOf(true, true, false, false), channels.map { it.isStream })
    }

    @Test
    fun x_web_guarda_la_pagina_de_respaldo_solo_si_es_https() {
        val channels = M3uParser.parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\" x-web=\"https://www.ejemplo.es/directo\",Con web\nhttps://cdn.ejemplo.es/a.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"b\" x-web=\"http://www.ejemplo.es/directo\",Web insegura\nhttps://cdn.ejemplo.es/b.m3u8\n" +
                "#EXTINF:-1 tvg-id=\"c\",Sin web\nhttps://cdn.ejemplo.es/c.m3u8\n"
        )
        assertEquals(listOf("https://www.ejemplo.es/directo", null, null), channels.map { it.webUrl })
    }

    @Test
    fun la_cabecera_puede_dar_la_url_de_la_guia() {
        assertEquals(
            "https://guia.ejemplo.es/TV.xml.gz",
            M3uParser.epgUrl("#EXTM3U url-tvg=\"https://guia.ejemplo.es/TV.xml.gz\"\n#EXTINF:-1,A\nhttps://x/a.m3u8\n")
        )
        assertEquals(
            "https://guia.ejemplo.es/TV.xml",
            M3uParser.epgUrl("﻿#EXTM3U x-tvg-url=\"https://guia.ejemplo.es/TV.xml\"\n")
        )
        assertNull(M3uParser.epgUrl("#EXTM3U url-tvg=\"http://guia.ejemplo.es/TV.xml\"\n"))
        assertNull(M3uParser.epgUrl("#EXTM3U\n#EXTINF:-1,A\nhttps://x/a.m3u8\n"))
    }

    @Test
    fun solo_acepta_https_y_genera_id_si_falta() {
        val text = "#EXTM3U\n" +
            "#EXTINF:-1,Sin id\nhttps://ejemplo.es/a\n" +
            "#EXTINF:-1,Inseguro\nhttp://ejemplo.es/b\n" +
            "#EXTINF:-1,Script\njavascript:alert(1)\n"
        val channels = M3uParser.parse(text)
        assertEquals(1, channels.size)
        assertEquals("sin-id", channels[0].id)
        assertNull(channels[0].recipeKey)
        assertTrue(channels[0].url.startsWith("https://"))
    }
}
