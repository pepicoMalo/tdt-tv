# TDT TV

App Android TV (Chromecast con Google TV) para ver canales de la TDT española desde la **web oficial** de cada canal.
Un único canal a pantalla completa; se cambia de canal con el anillo del mando.

## Cómo funciona

- `MainActivity`: un `WebView` a pantalla completa. Al sintonizar un canal carga su URL oficial e inyecta un script
  (`PageScript.kt`) que deja el reproductor a pantalla completa, lo des-silencia y le da play.
- Si la URL del canal termina en `.m3u8` (0.3.0 o posterior) es un flujo HLS directo: se reproduce con un `VideoView` nativo
  (reproductor de Android, sin dependencias), sin página web ni modo cursor. Si el flujo se corta, reintenta 3 veces; si no
  llega a arrancar (error, o sin imagen a los 20 s) y el canal tiene `x-web`, abre esa página (0.4.0 o posterior).
- Si el canal lleva `x-audio="1"` (0.5.0 o posterior) es una emisora de radio: la URL es un flujo de audio (mp3, aac…, sin
  importar la extensión) que reproduce ese mismo `VideoView`, y en pantalla se deja el nombre de la emisora. Una radio se da por
  arrancada en cuanto el reproductor está preparado (no hay imagen que esperar).
- La guía de programación (0.4.0 o posterior) se baja en segundo plano de la `url-tvg` de la cabecera de la lista (XMLTV, p. ej.
  el de TDTChannels), se queda en disco solo con los canales de la lista (`tvg-id` = id de la guía) y se refresca cada 6 horas.
  Solo se usa en la lista de canales (`Epg.kt`, `EpgRepository.kt`).
- La lista de canales (`canales.m3u`) y las recetas por sitio (`recipes.json`) se descargan al arrancar de una URL base
  (`tdttv.listBaseUrl` en `gradle.properties`). Si no hay red se usa la última copia en caché y, en el primer arranque
  sin red, la copia incluida en `app/src/main/assets/`.
- La lista y las recetas viven en otro repositorio (`tdt-tv-lista`, público). Solo su dueño puede publicar cambios.

## Mando

| Botón | Acción |
|---|---|
| Anillo arriba / CH+ | Canal siguiente |
| Anillo abajo / CH- | Canal anterior |
| OK (un toque) | Entra en el **modo cursor y teclado** (ver abajo); en un flujo directo solo muestra el rótulo del canal |
| OK (dos toques seguidos) | **Lista de todos los canales**, con el programa que emiten ahora si la guía lo tiene; OK en uno lo sintoniza, Atrás cierra |
| OK (pulsación larga) / Menú | Menú: subtítulos, elegir canal (la misma lista), recargar, actualizar lista, cursor y teclado, salir |
| Info | Muestra el canal actual |
| Atrás | Pulsar dos veces para salir |

### Modo cursor y teclado

Sirve para iniciar sesión en los canales con cuenta (`x-login`), aceptar cookies o pulsar cualquier cosa de la web.

| Botón | Acción |
|---|---|
| Anillo | Mueve el cursor (más rápido si se mantiene pulsado). Empujar contra el borde superior o inferior desplaza la página |
| OK | Pulsa donde está el cursor. Si deja el foco en un campo de texto abre un cuadro para escribir (ver abajo) |
| CH+ / CH- (o Re Pág / Av Pág) | Desplaza la página |
| OK largo / Menú | Menú |
| Atrás | Sale del modo cursor y vuelve al zapping |

Mientras está activo, la página se ve sin el CSS de pantalla completa y sin los clics automáticos de las recetas, como en un navegador normal.
Al salir, vuelve a la pantalla completa. Cambiar de canal o recargarlo desde el menú también sale del modo.
Tras iniciar sesión conviene marcar «recordarme» si la web lo ofrece. La sesión (cookies) se guarda en el almacenamiento de la app y se vuelca a disco al pausarla.
No hay inicio de sesión con ventanas emergentes (Google, Apple…): usa correo y contraseña. También se puede escribir desde el móvil con la app Google TV.

El texto se escribe en un cuadro nativo de Android (el WebView no abre su propio teclado: en el Chromecast se abría y se cerraba solo, versión 0.2.0).
Al pulsar OK sobre un campo sale el cuadro con el valor actual; con **Aceptar** (o «Hecho» en el teclado) se pone en el campo y se avisa a la página
(`input` y `change`). Las contraseñas salen con puntos y el botón **Ver** las muestra. Tras escribir, mueve el cursor al botón de enviar y pulsa OK.
Solo funciona con campos de la propia página, de un iframe del mismo origen o dentro de un shadow DOM; un campo en un iframe de otro origen no se puede rellenar.

## Formato de `canales.m3u`

M3U extendido; la URL de cada canal es la **página oficial del directo** o, si termina en `.m3u8`, un **flujo HLS directo**
(solo `https`).

```
#EXTM3U
#EXTINF:-1 tvg-id="tv3" tvg-logo="" group-title="Autonómico" x-subs="1",TV3
https://www.3cat.cat/3cat/directes/tv3/
```

Atributos propios: `x-subs="1"` (el canal emite subtítulos: solo entonces aparece la opción), `x-login="1"` (pide cuenta:
se queda en la lista, avisa al sintonizarlo y se inicia sesión con el modo cursor; la 0.1.0 lo descartaba),
`x-recipe="clave"` (fuerza una receta), `x-disabled="1"` (lo desactiva sin borrarlo), `x-web="https://…"` (página de un canal
con flujo `.m3u8`, a la que se vuelve si el flujo no arranca; 0.4.0), `x-audio="1"` (emisora de radio: flujo de audio sin
imagen; 0.5.0).
En los flujos `.m3u8` no hay página, así que `x-subs`, `x-login` y `x-recipe` solo cuentan si se abre `x-web`.
`tvg-id` es el id del canal en la guía de programación, y la cabecera `#EXTM3U url-tvg="https://…/guia.xml.gz"` da la guía
(XMLTV, `.xml` o `.xml.gz`; 0.4.0). Un canal sin id en la guía simplemente no muestra programa.
El orden de la lista es el orden de canales.

Ejemplo de flujo directo:

```
#EXTINF:-1 tvg-id="realmadridtv" group-title="Nacional",Real Madrid TV
https://rmtv.akamaized.net/hls/live/2043153/rmtv-es-web/master.m3u8
```

El reproductor nativo de Android reproduce HLS con segmentos MPEG-TS (y de forma más limitada fMP4/CMAF); los flujos con DRM o con
tokens que caducan no valen. Comprueba con `curl` que la URL responde sin cuenta y mira `#EXT-X-MAP` en la lista de medios: si aparece, es fMP4.

## Formato de `recipes.json`

```json
{
  "default": { "css": "", "fullVideo": true, "desktop": true },
  "recipes": {
    "3cat.cat": {
      "css": "#onetrust-banner-sdk{display:none!important}",
      "play": "button.play",
      "cc": "button.subtitles",
      "player": ".player-container",
      "js": "codigo JS idempotente que se ejecuta cada segundo",
      "ccJs": "codigo JS que activa subtítulos y devuelve 'on' | 'off' | 'click'"
    }
  }
}
```

La receta se elige por `x-recipe` o por el dominio de la URL, y se superpone a `default` (`css` y `js` se concatenan).
Con `"inheritJs": false` el sitio no hereda el `js` de `default` (el de `default` rechaza los banners de cookies; Atresplayer y
Mediaset lo desactivan porque su receta los acepta, ya que sin aceptar no dan el directo).
Si el reproductor vive en un `iframe`, pon como URL del canal la del propio iframe.
Con `"fullVideo": false` la receta no deja el vídeo a pantalla completa y con `"autoplay": false` el script no des-silencia ni da play a los
vídeos de la página (0.5.0). La receta `plataforma` de la lista usa las dos para Netflix, Prime Video, Disney+ y HBO Max, que son catálogos y
no un directo: se entra con OK (modo cursor), se inicia sesión y se navega como en un navegador. Sin probar la reproducción (DRM) en el Chromecast.

## Compilar

Herramientas portables en `D:\Android` (JDK 17, Android SDK 34, Gradle 8.7).

```powershell
$env:JAVA_HOME='D:\Android\jdk-17'; $env:ANDROID_HOME='D:\Android\Sdk'
.\gradlew.bat assembleDebug
# APK: app\build\outputs\apk\debug\app-debug.apk
```

## Instalar en el Chromecast

1. En el Chromecast: Ajustes > Sistema > Acerca de > pulsar 7 veces en «Compilación» > Opciones de desarrollador > Depuración USB / por red.
2. Anota su IP (Ajustes > Red).
3. Desde el PC:

```powershell
D:\Android\Sdk\platform-tools\adb.exe connect <ip>:5555
D:\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

La build de debug activa la depuración remota del WebView: con `chrome://inspect` se ven los selectores del reproductor para afinar recetas.

## APK y clave de firma

- `dist/`: las APK ya compiladas, listas para `adb install -r`.
- `signing/debug.keystore`: el keystore de depuración con el que se firman (alias `androiddebugkey`, contraseñas `android`,
  huella SHA-256 `79:D2:F6:AB:3A:7B:AE:D8:0B:9F:FC:8D:20:1A:CE:B7:3A:F3:41:9B:B9:CD:97:BD:DE:67:D9:A4:6C:6C:AC:49`). Es el de
  depuración estándar de Android, no un secreto. `app/build.gradle.kts` lo usa en `signingConfigs.debug`, así que cualquier máquina
  firma igual y la APK se instala encima de la anterior sin desinstalar (`gradle :app:signingReport` muestra la ruta que usa).
  No es apto para publicar en una tienda.
