# Análisis técnico de Kotmpeg Core — base de la 3.0.0

Revisión del código completo (muxers y demuxers MKV/MP4/fMP4, EBML, E/S, configuración de códecs,
audio PCM y fachada) con tres objetivos: que no quede ningún fallo conocido sin cubrir, que rinda
con grabaciones y archivos largos, y que se pueda usar en aplicaciones móviles sin romper la
compatibilidad con la 2.x. Cada hallazgo se verificó con un test que **falla con el código anterior** y
pasa con el de la 3.0.0, salvo donde se indica.

## 1. Punto de partida

La base era sólida y se ha respetado su diseño:

- Arquitectura clara: fachada (`MkvKotlin`), interfaces `Muxer`/`Demuxer`, un modelo canónico
  (`MediaPacket`, `TrackInfo`) y un paquete por formato. Remux MKV ↔ MP4 bit a bit.
- Sin dependencias ni API de plataforma, con `explicitApi()`.
- Política de robustez ante archivos hostiles ya trabajada (tamaños de tabla acotados, `largesize`
  con `addExact`, cabeceras EBML validadas).
- Disciplina de API pública (`PublicApiTest`) y de documentación (`CommentPolicyTest`).

Lo que faltaba no era arquitectura, sino casos concretos: la integración real con codificadores
móviles, el rendimiento con archivos largos y la compatibilidad efectiva con Android.

## 2. Alcance: el audio de grabación es parte del producto

En una primera pasada se planteó retirar `PcmMixer` y `PcmResampler` por no ser trabajo de un
contenedor. **Se descartó**, y con razón: grabar el audio del sistema y el del micrófono combinados
en una pista o cada uno en la suya es lo que distingue a esta librería para móviles, y retirarlos
habría roto la API pública de la 2.x. Queda fijado como **regla inmutable del proyecto**:

| Montaje | Pistas de audio |
|---|---|
| Mezcla | `Mezcla` |
| Solo micrófono | `Micrófono` |
| Solo sistema | `Audio del sistema` |
| Separadas | `Micrófono` + `Audio del sistema` |
| Mezcla y separadas | `Mezcla` + `Micrófono` + `Audio del sistema` |

Se protege como las demás políticas del proyecto, con un test propio —`RecordingAudioLayoutsTest`—
que recorre los cinco montajes en MKV, MP4 y fMP4 y tras convertir entre ellos, y comprueba la
mezcla muestra a muestra con micrófono mono a 44,1 kHz y sistema estéreo a 48 kHz.

Se optimizó por dentro **sin tocar su API**: la mezcla da exactamente los mismos bytes que antes
(lo comprueba `PcmFastPathEquivalenceTest` contra una copia literal de la 2.1.1) y el remuestreador
da la misma señal, pero exacta en sesiones largas.

## 3. Hallazgos y correcciones

Severidad: **Crítica** = archivo inutilizable o caída en producción; **Alta** = resultado
incorrecto sin aviso; **Media** = robustez o conformidad; **Rendimiento**.

| # | Severidad | Hallazgo | Corrección | Test |
|---|---|---|---|---|
| 1 | Crítica | `OpusConfig` usaba `ByteBuffer.position(int)`: compilado con JDK 17 enlaza con una sobrecarga covariante inexistente en Android (al menos hasta API 30) → `NoSuchMethodError` al abrir Opus. | Lecturas absolutas. | `AndroidApiCompatibilityTest` |
| 2 | Crítica | Abrir un MP4 largo escrito por la propia librería tardaba >10 s: recorrido cuadrático de `stsc` por chunk. | Recorrido lineal en paralelo. | medición (§4) |
| 3 | Alta | Audio con `isKeyFrame=false` (valor por defecto) → `stss` vacío en MP4, muestras no sync en fMP4, MKV de audio sin cues. | El audio es siempre sync en los tres muxers. | `SyncSamplesAndTimelineTest` |
| 4 | Alta | `MkvMuxer` escribía marcas absolutas: con relojes de `MediaCodec` un archivo de 1 s declaraba días. | Origen en el paquete más temprano de todas las pistas, como `Mp4Muxer` (ver #28). | `SyncSamplesAndTimelineTest`, `TrackStartOrderTest` |
| 5 | Alta | fMP4 con vídeo de frecuencia variable (captura de pantalla): cada pausa desalineaba DTS y PTS (2 s de pausa → 2 s de desfase acumulado). | Duración del último fotograma tomada del keyframe siguiente. | `SyncSamplesAndTimelineTest` |
| 6 | Alta | `PcmResampler` acumulaba la fase en `double`: con trozos de 960 frames se desviaba a los 6,6 min y llegaba a 5 frames en 3 h. | Fase exacta en enteros. | `PcmFastPathEquivalenceTest` |
| 7 | Alta | MKV cortado dentro de un bloque lanzaba `EbmlException`, contra la política documentada. | Fin de stream limpio con aviso; los errores de E/S reales se propagan. | `MatroskaContentEncodingTest` |
| 8 | Alta | `ContentEncoding` de Matroska ignorado: header stripping o zlib entregaban fotogramas corruptos sin aviso. | Se deshacen ambos; cifrado/bzlib/lzo → pista descartada con aviso; techo anti-bomba de 64 MiB. | `MatroskaContentEncodingTest` |
| 9 | Alta | MP4 perdía idioma (`mdhd` = `und`), nombre y pista predeterminada al releer: un archivo con mezcla, micrófono y sistema volvía con las tres pistas «predeterminadas». | Se escriben y se leen los tres. | `TrackLanguageRoundTripTest`, `RecordingAudioLayoutsTest` |
| 10 | Media | `elst.entry_count` sin acotar; `mvhd` con escala 0 descartaba pistas; `TimestampScale` 0 anulaba marcas. | Cotas y validación. | `Mp4HeaderHardeningTest`, `MatroskaContentEncodingTest` |
| 11 | Media | `SeekableOutput.close()` filtraba el descriptor si fallaba el último `flush`. | `try/finally`. | `FastPathEquivalenceTest` |
| 12 | Media | `mp4FastStart` sustituía el original sin `force` del temporal (riesgo ante corte de batería). | `force(true)` antes del movimiento atómico. | revisión |
| 13 | Media | Conversiones de escala `valor * escala` desbordaban en silencio. | `Timestamps` con aritmética exacta y saturación. | `FastPathEquivalenceTest` |
| 14 | Media | El MKV declaraba un fotograma menos de duración que el MP4 sin duraciones de paquete. | Diferencia entre los dos mayores PTS (exacta también con B-frames) o `DefaultDuration`. | `SyncSamplesAndTimelineTest` |
| 15 | Media | Parámetros inválidos (`fragmentDurationUs <= 0`…) producían archivos rotos. | Validación antes de abrir el archivo (sin fugas). | `SyncSamplesAndTimelineTest` |
| 16 | Media | HE-AAC en MKV sin `OutputSamplingFrequency` (no conforme). | Núcleo + salida, como FFmpeg. | `HeAacConfigTest` |
| 17 | Rendimiento | Un objeto por muestra en muxer y demuxer MP4. | Columnas primitivas paginadas (`SampleTable`). | medición |
| 18 | Rendimiento | `BoxBuilder` copiaba cada caja al padre por nivel y escribía byte a byte en un `ByteArrayOutputStream` sincronizado. | Un solo array, tamaños rellenados al cerrar. | `FastPathEquivalenceTest` |
| 19 | Rendimiento | `seekTo` recorría todas las muestras / todas las cues. | Bisección. | `FastPathEquivalenceTest` |
| 20 | Rendimiento | Doble copia de cada muestra al leer (buffer → array; bloque → fotograma). | Lectura directa del canal y de la carga. | `FastPathEquivalenceTest` |
| 21 | Rendimiento | Annex-B ↔ ISO creaba un array por NAL y otro para unirlos, por fotograma. | Una pasada al array final. | `FastPathEquivalenceTest` |
| 22 | Rendimiento | `PcmMixer` pasaba cada muestra por `double` y desempaquetaba un `Float` por muestra y fuente. | Ruta entera (idéntica bit a bit) y caso de dos fuentes sin bucle interno. | `PcmFastPathEquivalenceTest` |
| 23 | Móvil | Uso desde dos hilos (callbacks de vídeo y audio) exigía sincronización manual. | `MkvKotlin.synchronizedMuxer`. | `SynchronizedMuxerTest` |
| 24 | Crítica | `FragmentedMp4Muxer` retenía una referencia al array de cada paquete hasta cerrar el fragmento: una app que reutiliza su buffer obtenía 49 de 50 paquetes dañados, sin error. | Copia al recibir el paquete; contrato escrito en `MediaPacket`. | `PacketBufferReuseTest` |
| 25 | Alta | El MKV escribía pistas sin `CodecPrivate` (audio sin configuración, vídeo sin `avcC`): FFmpeg lo tolera, Media3/ExoPlayer y el extractor de Android no. | Audio: la misma configuración por defecto que el MP4. Vídeo: rechazo en `addTrack`, como en MP4. | `MissingCodecPrivateTest` |
| 26 | Alta | Compilado en nivel Kotlin 2.2, el artefacto exigía Kotlin 2.1+ a la app (el mismo fallo que obligó a la 1.0.1). | Nivel 2.0 y `kotlin-stdlib:2.0.21`: apps con Kotlin 1.9+. | `compat/kotlin-1.9` en el CI |
| 27 | Móvil | Los límites de memoria del fMP4 (64 MB y 10 s por fragmento) no se podían ajustar desde la fachada. | `MkvKotlin.createFragmentedMp4Muxer` y constructor con `File`, sin tocar firmas existentes. | `PacketBufferReuseTest` |
| 28 | Alta | MKV y fMP4 fijaban el origen con el **primer paquete que llegaba**, no con el más temprano. Con dos codificadores no coinciden: el vídeo tarda más que el audio. Con pantalla desde 0 y micrófono desde +64 ms, el fMP4 adelantaba el audio 64 ms (ya en la 2.1.1) y el MKV del #4 dejaba el keyframe inicial con marca negativa. | Retención de arranque (`TimelineGate`): se espera al primer paquete de cada pista, con copia y acotada en tiempo y memoria. | `TrackStartOrderTest`, `FfmpegIntegrationTest` |

Los hallazgos 3, 4, 5, 6, 7, 14, 15, 24, 25, 26, 28 y los casos de `elst` y `mvhd` del 10 se
comprobaron contra el código anterior: fallan allí y pasan ahora. El 26 con el propio proyecto
consumidor: con el nivel anterior no compila («Module was compiled with an incompatible version of
Kotlin»), con el nuevo compila y ejecuta.

## 4. Mediciones

Misma máquina de desarrollo, tres ejecuciones; en un móvil los tiempos absolutos son mayores, pero
la proporción se mantiene.

MP4 de dos horas a 60 fps con AAC (~770 000 muestras):

| | 2.1.1 | 3.0.0 | Mejora |
|---|---|---|---|
| Abrir con `Mp4Demuxer` | ~10,6 s | ~0,27 s | ~40× |
| Heap del demuxer abierto | ~47,5 MB | ~29,3 MB | −38 % |
| `seekTo` | ~0,9 ms | ~5 µs | ~180× |
| Heap de `Mp4Muxer` antes de `stop()` | ~54 MB | ~34 MB | −37 % |
| `Mp4Muxer.stop()` | ~0,9 s | ~0,4 s | ~2,3× |

Audio de grabación, una hora en bloques de 10 ms:

| | 2.1.1 | 3.0.0 | Mejora |
|---|---|---|---|
| Mezcla micrófono + sistema, estéreo 48 kHz, sin ganancias | ~1,8 s | ~0,3 s | ~6× |
| La misma con ganancias | ~2,6 s | ~2,3 s | ~1,1× |
| Remuestreo 44,1 → 48 kHz (3 h, mono) | ~4,9 s | ~3,7 s | ~1,3× |
| Desviación del remuestreo en 3 h | hasta 5 frames | 0 | exacto |

## 5. Uso en móviles

- **`minSdk` 26 (Android 8.0)** en lugar de 34, verificado por `AndroidApiCompatibilityTest`
  contra la firma oficial de la API 26. La cota la pone `java.nio.file` (sustitución atómica de
  `mp4FastStart`).
- **Kotlin 1.9 o superior en la app**, verificado en el CI con una app mínima que graba con la
  librería.
- La app puede reutilizar su buffer entre paquetes, y el fMP4 permite acotar su memoria con
  `createFragmentedMp4Muxer(maxFragmentBytes = ...)`.
- Marcas de `MediaCodec` aceptadas tal cual en los tres contenedores, y en el orden en que lleguen
  los paquetes de cada codificador; audio sin marcar correcto; vídeo de frecuencia variable sin
  desfases en fMP4.
- `synchronizedMuxer` para los hilos de los codificadores y `SeekableOutput.sync()` para quien
  necesite durabilidad ante cortes de batería.
- Menos memoria y sin picos de copia al crecer las tablas, que es lo que provoca un
  `OutOfMemoryError` al final de una grabación larga en un heap limitado.
- No se añadió nada de Android: la librería sigue siendo Kotlin/JVM puro y sin dependencias.

## 6. Compatibilidad y migración desde la 2.x

**No hay que cambiar código ni recompilar.** `public-api.txt` frente al de la 2.1.1 solo gana
líneas: ninguna firma desaparece ni cambia. La versión es mayor por cambios de comportamiento, todos
correcciones; conviene revisarlos solo si la app dependía del comportamiento anterior:

1. Los MKV de `MkvMuxer` empiezan en el paquete más temprano de todas las pistas (antes, en la
   marca absoluta recibida).
2. `Mp4Demuxer` informa del idioma, el nombre y la pista predeterminada reales (antes `und`,
   `null` y `true` siempre).
3. `MkvDemuxer` termina el stream ante un bloque cortado en vez de lanzar.
4. Los constructores rechazan parámetros no positivos.
5. `PcmResampler` puede entregar un frame en la llamada siguiente cuando cae en el borde de un
   trozo; el flujo concatenado es el mismo.
6. `MkvMuxer.addTrack` rechaza el vídeo sin `codecPrivate`, como ya hacía el MP4.

## 7. Recomendaciones pendientes

Fuera de esta versión por alcance, en orden de valor:

1. **Índice de muestras bajo demanda en `Mp4Demuxer`** para archivos de muchas horas: hoy se
   construye entero al abrir (~37 bytes por muestra).
2. **Un mezclador en streaming** que alinee por sí mismo micrófono y sistema cuando llegan en
   bloques de tamaños distintos (hoy la app entrega bloques del mismo tiempo a `PcmMixer.mix`),
   como adición sin tocar la API actual.
3. **Segmentos CMAF con `sidx`** para empaquetado DASH/HLS directo.
4. **Fuzzing continuo** (p. ej. Jazzer) sobre los dos demuxers en el CI.
5. **Benchmarks reproducibles** (JMH) en el CI para que las cifras del §4 no retrocedan.

Descartado por decisión del proyecto:

- **VP9, AV1 y la escritura de WebM.** Los códecs se quedan en H.264 (AVC) y H.265 (HEVC) para
  vídeo, y AAC y Opus para audio: son los de soporte universal en MKV y MP4 y con codificación por
  hardware en cualquier Android. Añadir más amplía la superficie que hay que mantener y verificar
  sin cubrir ningún caso que estos no cubran.
- **Publicar en Maven Central.** La librería se distribuye por JitPack, que la compila a partir de
  cada tag del repositorio.

## 8. Cómo se verificó

- Suite completa: 211 tests, incluida la integración con FFmpeg real (decodificación completa sin
  errores y `ffprobe`), en Linux y en Windows. Los tres tests de archivos abiertos miden en los dos
  sistemas (en Windows, comprobado inyectando una fuga en `remux`); solo los dos de HE-AAC se
  omiten donde no hay un FFmpeg con codificador HE.
- El techo de descompresión de Matroska, por los dos lados del límite; el test se comprobó
  subiendo el techo en el demuxer, con lo que falla.
- Paquetes reales de libx264 (con B-frames) y AAC escritos como lo haría una app con `MediaCodec`
  —marcas de reloj absolutas, sin DTS, audio sin `isKeyFrame`, buffer reutilizado y
  `synchronizedMuxer`— en MKV, MP4 y fMP4: duración correcta, todos los paquetes, decodificación
  sin errores y, en fMP4, DTS alineado tras una pausa de vídeo de un segundo.
- El mismo material con las pistas llegando al muxer en otro orden que el de captura (vídeo con
  150 ms de latencia, audio con 20 ms, micrófono 64 ms después de la pantalla), comparado con la
  2.1.1 publicada: `ffprobe` da el vídeo en 0 y el audio en +0,064 s en los tres contenedores, y
  la decodificación es limpia. Con la 2.1.1 el fMP4 ya adelantaba el audio.
- Una app mínima compilada con Kotlin 1.9.25 contra el artefacto publicado, que graba pantalla con
  mezcla, micrófono y sistema en MKV, MP4 y fMP4 y lo relee.
- Tests nuevos ejecutados contra el código anterior para confirmar que reproducen cada fallo.
- `PcmMixer` comparado byte a byte, y `PcmResampler` muestra a muestra, contra una copia literal de
  la 2.1.1.
- Comprobación de firmas con animal-sniffer contra Android API 26 (y, para la sobrecarga de
  `ByteBuffer`, también contra la API 30).
- `public-api.txt` regenerado y comparado con el de la 2.1.1: ninguna línea eliminada.
