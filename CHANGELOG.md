# Cambios de yotuve

## Pendiente de publicar

- La compilación ya no publica sola: las versiones se publican a mano cuando se decide.
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
