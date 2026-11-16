# Cambios de yotuve

La sección de arriba es la de la próxima versión: se usa como notas al publicar.

## 16/11/2026 · Canciones «auto» como un DJ

- Estilo parecido pero variando de artista (penaliza repetir los artistas de las últimas canciones).
- Prioriza las canciones más populares y las grabaciones de estudio (audio oficial y canales «- Topic»).
- Descarta directos, conciertos, sesiones, karaoke, covers, versiones aceleradas/8D y recopilatorios.

## 16/11/2026 · Me gusta / No me gusta y radio infinita en el modo música

- Botones **Me gusta** y **No me gusta** en la canción que suena; se guarda un registro en el móvil.
- «No me gusta» salta la canción en el momento y no vuelve a sonar ni a añadirse sola.
- Al acabarse la lista se va añadiendo una canción **relacionada**, una tras otra y sin repetir, elegida a partir
  de la que suena y de las que te gustan, con prioridad para tus artistas favoritos (marcadas como «auto»).

## 16/11/2026 · Modo música, menú con versión e inicio según tus 50 últimos vídeos

- **Modo música** (icono de nota arriba o menú): solo sonido, con lista que se puede importar de Spotify
  (lista, álbum o canción; se leen los nombres y el sonido sale de YouTube) o de YouTube (lista o vídeo),
  añadir canciones buscando, y editar (subir, bajar, quitar). Controles tipo Spotify (aleatorio, anterior,
  play/pausa, siguiente, repetir), barra de progreso, fundido entre canciones, sigue sonando con la pantalla
  bloqueada y barra pequeña en las demás pantallas. Botón «A música» en cada vídeo.
- **Menú** (⋮ o mantener pulsado el logo): versión actual con enlace a sus novedades y a todas las versiones.
- **Inicio**: analiza tus últimos 50 vídeos, saca los temas y canales que se repiten y muestra novedades
  del último mes sobre ellos con bastantes visualizaciones; descarta lo poco visto y lo ya visto.

## 16/11/2026 · Inicio basado en lo último que has visto

- El inicio mezcla lo que YouTube recomienda para tus 3 últimos vídeos (el más reciente pesa más) con lo más
  visto esta semana sobre sus temas: tendencias sobre lo que ves.
- Se carga por peticiones directas a YouTube (más rápido, sin Python); sin repetir lo visto y con variedad de canales.

## 16/11/2026 · Calidad adaptativa (arranque casi inmediato)

- El reproductor recibe todas las calidades a la vez (DASH, como NewPipe): empieza en una calidad baja para
  arrancar al instante y sube sola, sin cortes, según la conexión y el tamaño en pantalla.
- Búfer mínimo para empezar a reproducir: 0,4 s (antes 0,7 s).
- Al abrir la app se deja preparada la conexión con YouTube: el primer vídeo arranca antes.

## 16/11/2026 · Vídeos que arrancan mucho antes

- El vídeo es lo primero: los enlaces se piden directamente a YouTube (una petición, sin arrancar Python/yt-dlp).
  yt-dlp queda de respaldo si un vídeo no los da o fallan.
- La información del vídeo se carga después, en paralelo, sin retrasar la reproducción.
- Los recomendados se cargan los últimos, cuando el vídeo ya suena, y salen de la propia respuesta de YouTube
  (sin Python); siguen con variedad de canales y priorizando lo nuevo.

## 16/11/2026 · Minirreproductor y carga rápida

- Vídeo minimizado abajo a la derecha (por encima del teclado) al buscar, ir al inicio o abrir un canal;
  tocarlo lo vuelve a abrir y la X lo cierra.
- Fundido de sonido al cambiar de vídeo: el anterior baja poco a poco y el nuevo entra subiendo.
- Carga de vídeos más rápida: modo rápido de yt-dlp (un solo cliente, sin descargar la página web) y los datos
  del vídeo (descripción, fecha, canal, suscriptores, «me gusta») directamente de YouTube en paralelo.
- Si el modo rápido solo da 360p, la calidad sube sola en segundo plano sin cortar el vídeo.
- Precarga al tocar un vídeo (antes de soltar el dedo).

## 1.202
- Repositorio reorganizado (código en la raíz, README, gradlew).
- QuickJS incluido para vídeos «para niños» o con restricciones.
- Búsqueda directa en YouTube (más rápida y con fecha de subida).
- Historial para el botón atrás, comentarios y canal paginados, tiempo máximo en las cargas.
- Siguiente vídeo con cuenta atrás, «Volver a ver», recuperación de enlaces caducados.
