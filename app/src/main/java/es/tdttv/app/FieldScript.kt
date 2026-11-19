package es.tdttv.app

import org.json.JSONObject

/**
 * Escribir texto en la pagina desde el mando. El teclado propio del WebView se abria y se cerraba solo en el
 * Chromecast, asi que el texto se pide en un dialogo nativo y se inyecta en el campo que tenia el foco.
 * Ojo: son raw strings de Kotlin, no usar "$" en el JS.
 */
object FieldScript {

    /**
     * Si el elemento con foco admite texto (input, textarea o contenteditable; tambien dentro de shadow roots y de
     * iframes del mismo origen) lo guarda en window.__tdtField y devuelve {type, label, value}; si no, null.
     */
    val DESCRIBE: String = """
(function () {
  function focused() {
    var a = document.activeElement;
    for (var n = 0; a && n < 8; n++) {
      if (a.shadowRoot && a.shadowRoot.activeElement) { a = a.shadowRoot.activeElement; continue; }
      var t = (a.tagName || '').toUpperCase();
      if (t === 'IFRAME' || t === 'FRAME') {
        var d = null;
        try { d = a.contentDocument; } catch (e) {}
        if (!d || !d.activeElement) return null;
        a = d.activeElement;
        continue;
      }
      break;
    }
    return a;
  }

  function kind(e) {
    if (!e) return null;
    var t = (e.tagName || '').toUpperCase();
    if (t === 'TEXTAREA') return (e.readOnly || e.disabled) ? null : 'textarea';
    if (t === 'INPUT') {
      if (e.readOnly || e.disabled) return null;
      var ty = (e.type || 'text').toLowerCase();
      var no = ['button', 'submit', 'reset', 'checkbox', 'radio', 'image', 'file', 'range', 'color', 'hidden'];
      return no.indexOf(ty) < 0 ? ty : null;
    }
    return e.isContentEditable ? 'textarea' : null;
  }

  function label(e) {
    var l = e.getAttribute && (e.getAttribute('aria-label') || e.getAttribute('placeholder'));
    if (!l && e.labels && e.labels.length) l = e.labels[0].innerText;
    if (!l) l = e.name || '';
    return String(l).replace(/\s+/g, ' ').trim().slice(0, 60);
  }

  var el = focused();
  var ty = kind(el);
  if (!ty) return null;
  window.__tdtField = el;
  var v = (el.value !== undefined) ? el.value : (el.innerText || '');
  return { type: ty, label: label(el), value: String(v) };
})()
""".trimIndent()

    /** Pone [text] en el campo que guardo [DESCRIBE] y avisa a la pagina (input y change) para que lo registre. */
    fun fill(text: String): String = """
(function (text) {
  var e = window.__tdtField;
  if (!e || !e.isConnected) return false;
  try {
    var w = (e.ownerDocument && e.ownerDocument.defaultView) || window;
    e.focus();
    var t = (e.tagName || '').toUpperCase();
    if (t === 'INPUT' || t === 'TEXTAREA') {
      var proto = t === 'INPUT' ? w.HTMLInputElement.prototype : w.HTMLTextAreaElement.prototype;
      var d = Object.getOwnPropertyDescriptor(proto, 'value');
      if (d && d.set) d.set.call(e, text); else e.value = text;
    } else {
      var done = false;
      try { w.document.execCommand('selectAll'); done = w.document.execCommand('insertText', false, text); } catch (x) {}
      if (!done) e.textContent = text;
    }
    var Ev = w.InputEvent || w.Event;
    e.dispatchEvent(new Ev('input', { bubbles: true, inputType: 'insertText', data: text }));
    e.dispatchEvent(new w.Event('change', { bubbles: true }));
    return true;
  } catch (x) { return false; }
})(__TEXT__)
""".trimIndent().replace(TEXT_TOKEN, JSONObject.quote(text))

    private const val TEXT_TOKEN = "__TEXT__"
}
