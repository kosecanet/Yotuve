# Cambios de yotuve

La sección de arriba es la de la próxima versión: se usa como notas al publicar.

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
