# yotuve ▶️

YouTube simplificado para Android: **sin anuncios**, rápido y ligero.
Usa [yt-dlp](https://github.com/yt-dlp/yt-dlp) y ffmpeg (vía [youtubedl-android](https://github.com/JunkFood02/youtubedl-android)) para reproducir y descargar.

## Funciones
- **Inicio** con recomendaciones según lo que ves (variadas y priorizando lo más nuevo), **Seguir viendo** y **Shorts**.
- **Buscador** con las sugerencias de YouTube y resultados paginados.
- **Reproductor** sin anuncios: calidad automática según el tamaño, pantalla completa, ventana flotante,
  sigue sonando con el móvil bloqueado, recuerda por dónde ibas y pasa al siguiente vídeo con cuenta atrás.
- **Bloqueo para niños** en pantalla completa (mantener 5 s el candado).
- **Descargar** (MP4 hasta 1080p en `Descargas/yotuve`) y **compartir** el archivo o el enlace.
- **Enviar a la TV**: Chromecast / Google TV o duplicar pantalla.
- **Comentarios**, descripción y página del **canal**.
- Botón **Atrás** con historial completo.
- **Se actualiza sola**: yt-dlp en segundo plano y la propia app desde las Releases de este repositorio.

## Compilar con GitHub
1. Cada `push` a `main` lanza **Actions → "Compilar APK y publicar Release"** (también a mano con *Run workflow*).
2. Al terminar, el APK aparece en **Releases** como `yotuve-arm64-v8a-1.N.apk` (casi todos los móviles)
   y `yotuve-universal-1.N.apk`.
3. La versión sube sola en cada compilación y la app avisa de que hay una nueva para instalarla encima.

Para compilar en local: `./gradlew assembleRelease`.

### Firma
El repo incluye `app/yotuve.jks` (contraseña `yotuve123`) para que las actualizaciones lleven siempre la misma
firma. La autoactualización necesita que el repositorio sea **público** para consultar las Releases;
ten en cuenta que entonces la clave también es pública.

### QuickJS
El flujo de GitHub compila [QuickJS-ng](https://github.com/quickjs-ng/quickjs) para Android (arm64) y lo incluye
como `libqjs.so`. yt-dlp lo usa solo cuando un vídeo falla (p. ej. vídeos «para niños»). Si su compilación falla,
el APK se genera igualmente sin él.

## Estructura
- `app/src/main/java/com/jose/yotuve/`
  - `MainActivity.kt` → pantallas (inicio, búsqueda, reproductor) y lógica de navegación.
  - `Repo.kt` / `Details.kt` → llamadas a yt-dlp (vídeo, relacionados, comentarios, canal, descargas).
  - `Innertube.kt` → búsqueda directa en YouTube (rápida y con fecha de subida).
  - `PlayerCache.kt` / `PlaybackService.kt` / `Quality.kt` → reproductor, caché, segundo plano y calidad.
  - `Shorts.kt`, `Social.kt`, `Cast.kt`, `Updater.kt`, `History.kt` → Shorts, comentarios/canal, TV, actualizaciones, historial.
- `app/src/main/res/` → icono (círculo coral con «play»).
- `.github/workflows/build.yml` → compilación y publicación.
