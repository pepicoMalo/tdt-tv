package es.tdttv.app

import org.json.JSONObject

/**
 * Script que se inyecta en la pagina oficial al terminar de cargar.
 * - Mete el css de la receta y, si fullVideo != false, deja el reproductor a pantalla completa.
 * - Cada segundo: elige el video principal, lo des-silencia y le da play (salvo autoplay == false, para paginas de
 *   catalogo como Netflix, donde hay videos de portada que no se deben tocar); pulsa "play" si hay selector.
 * - Expone window.__tdt.toggleSubs() para el menu del mando.
 * - Expone window.__tdt.setInteractive(on) para el modo cursor: con on=true quita el css inyectado y para el tick,
 *   de modo que la pagina se ve y se comporta como en un navegador normal (iniciar sesion, aceptar cookies).
 * Ojo: es una raw string de Kotlin, no usar "$" en el JS.
 */
object PageScript {

    fun build(recipe: JSONObject, interactive: Boolean = false): String = """
(function () {
  if (window.__tdt) return;
  var cfg = __RECIPE__;
  var T = window.__tdt = { cfg: cfg, interactive: __INTERACTIVE__ };

  var css = cfg.css || '';
  if (cfg.fullVideo !== false) {
    css += 'html,body{background:#000!important;overflow:hidden!important}' +
      '.tdt-main{position:fixed!important;left:0!important;top:0!important;width:100vw!important;' +
      'height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
      'padding:0!important;z-index:2147483647!important;background:#000!important;transform:none!important}' +
      'video.tdt-main{object-fit:contain!important}';
  }
  var st = document.createElement('style');
  st.textContent = css;
  if (!T.interactive) (document.head || document.documentElement).appendChild(st);

  function pickVideo() {
    var best = null, area = 0, vs = document.getElementsByTagName('video');
    for (var i = 0; i < vs.length; i++) {
      var v = vs[i], r = v.getBoundingClientRect();
      var a = Math.max(r.width * r.height, v.videoWidth * v.videoHeight);
      if (a > area) { area = a; best = v; }
    }
    return best;
  }

  function tick() {
    if (T.interactive) return;
    try {
      var v = pickVideo();
      if (cfg.fullVideo !== false) {
        var target = (cfg.player && document.querySelector(cfg.player)) || v;
        if (target && !target.classList.contains('tdt-main')) {
          var old = document.querySelectorAll('.tdt-main');
          for (var i = 0; i < old.length; i++) old[i].classList.remove('tdt-main');
          target.classList.add('tdt-main');
        }
      }
      if (v && cfg.autoplay !== false) {
        if (v.muted) v.muted = false;
        if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }
      }
      if (cfg.play) {
        var b = document.querySelector(cfg.play);
        if (b && !b.__tdtClick) { b.__tdtClick = true; b.click(); }
      }
      if (cfg.js) { (new Function(cfg.js))(); }
    } catch (e) {}
  }

  T.setInteractive = function (on) {
    T.interactive = !!on;
    try {
      if (T.interactive) {
        if (st.parentNode) st.parentNode.removeChild(st);
      } else {
        (document.head || document.documentElement).appendChild(st);
        tick();
      }
    } catch (e) {}
    return T.interactive;
  };

  T.toggleSubs = function () {
    try {
      if (cfg.ccJs) { return (new Function(cfg.ccJs))() || 'click'; }
      if (cfg.cc) {
        var b = document.querySelector(cfg.cc);
        if (b) { b.click(); return 'click'; }
      }
      var v = pickVideo();
      if (v && v.textTracks) {
        var tr = [], on = false, i;
        for (i = 0; i < v.textTracks.length; i++) {
          var t = v.textTracks[i];
          if (t.kind === 'subtitles' || t.kind === 'captions') tr.push(t);
        }
        if (tr.length) {
          for (i = 0; i < tr.length; i++) if (tr[i].mode === 'showing') on = true;
          for (i = 0; i < tr.length; i++) tr[i].mode = 'hidden';
          if (!on) tr[0].mode = 'showing';
          return on ? 'off' : 'on';
        }
      }
    } catch (e) {}
    return 'none';
  };

  tick();
  T.timer = setInterval(tick, 1000);
})();
""".trimIndent()
        .replace(RECIPE_TOKEN, recipe.toString())
        .replace(INTERACTIVE_TOKEN, interactive.toString())

    private const val RECIPE_TOKEN = "__RECIPE__"
    private const val INTERACTIVE_TOKEN = "__INTERACTIVE__"
}
