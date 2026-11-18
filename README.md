# TDT TV

App Android TV (Chromecast con Google TV) para ver canales de la TDT española desde la **web oficial** de cada canal.
Un único canal a pantalla completa; se cambia de canal con el anillo del mando.

## Cómo funciona

- `MainActivity`: un `WebView` a pantalla completa. Al sintonizar un canal carga su URL oficial e inyecta un script
  (`PageScript.kt`) que deja el reproductor a pantalla completa, lo des-silencia y le da play.
- La lista de canales (`canales.m3u`) y las recetas por sitio (`recipes.json`) se descargan al arrancar de una URL base
  (`tdttv.listBaseUrl` en `gradle.properties`). Si no hay red se usa la última copia en caché y, en el primer arranque
  sin red, la copia incluida en `app/src/main/assets/`.
- La lista y las recetas viven en otro repositorio (`tdt-tv-lista`, público). Solo su dueño puede publicar cambios.

## Mando

| Botón | Acción |
|---|---|
| Anillo arriba / CH+ | Canal siguiente |
| Anillo abajo / CH- | Canal anterior |
| OK (pulsación corta) | Entra en el **modo cursor y teclado** (ver abajo) |
| OK (pulsación larga) / Menú | Menú: subtítulos, elegir canal, recargar, actualizar lista, cursor y teclado, salir |
| Info | Muestra el canal actual |
| Atrás | Pulsar dos veces para salir |

### Modo cursor y teclado

Sirve para iniciar sesión en los canales con cuenta (`x-login`), aceptar cookies o pulsar cualquier cosa de la web.

| Botón | Acción |
|---|---|
| Anillo | Mueve el cursor (más rápido si se mantiene pulsado). Empujar contra el borde superior o inferior desplaza la página |
| OK | Pulsa donde está el cursor. Si es un campo de texto abre el teclado en pantalla |
| CH+ / CH- (o Re Pág / Av Pág) | Desplaza la página |
| OK largo / Menú | Menú |
| Atrás | Sale del modo cursor y vuelve al zapping (con el teclado abierto, el primer Atrás lo cierra) |

Mientras está activo, la página se ve sin el CSS de pantalla completa y sin los clics automáticos de las recetas, como en un navegador normal.
Al salir, vuelve a la pantalla completa. Cambiar de canal o recargarlo desde el menú también sale del modo.
Tras iniciar sesión conviene marcar «recordarme» si la web lo ofrece. La sesión (cookies) se guarda en el almacenamiento de la app y se vuelca a disco al pausarla.
No hay inicio de sesión con ventanas emergentes (Google, Apple…): usa correo y contraseña. También se puede escribir desde el móvil con la app Google TV.

## Formato de `canales.m3u`

M3U extendido; la URL de cada canal es la **página oficial del directo** (solo `https`).

```
#EXTM3U
#EXTINF:-1 tvg-id="tv3" tvg-logo="" group-title="Autonómico" x-subs="1",TV3
https://www.3cat.cat/3cat/directes/tv3/
```

Atributos propios: `x-subs="1"` (el canal emite subtítulos: solo entonces aparece la opción), `x-login="1"` (pide cuenta:
se queda en la lista, avisa al sintonizarlo y se inicia sesión con el modo cursor; la 0.1.0 lo descartaba),
`x-recipe="clave"` (fuerza una receta), `x-disabled="1"` (lo desactiva sin borrarlo).
El orden de la lista es el orden de canales.

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

- `dist/tdt-tv-0.2.0-debug.apk`: la APK ya compilada, lista para `adb install -r`.
- `signing/debug.keystore`: el keystore de depuración con el que se firmó esa APK (alias `androiddebugkey`, contraseñas `android`,
  huella SHA-256 `79:D2:F6:AB:3A:7B:AE:D8:0B:9F:FC:8D:20:1A:CE:B7:3A:F3:41:9B:B9:CD:97:BD:DE:67:D9:A4:6C:6C:AC:49`). Es el de
  depuración estándar de Android, no un secreto: sirve para que otra máquina firme igual y la APK se instale encima de la anterior.
  Para usarlo, copiarlo a `%USERPROFILE%\.android\debug.keystore` antes de compilar. No es apto para publicar en una tienda.
