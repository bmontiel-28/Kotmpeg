# Análisis técnico de Kotmpeg Core

Las secciones 1 a 8 son la revisión que dio lugar a la **3.0.0**. La [sección 9](#9-la-310) es la
**3.1.0**, que atiende las recomendaciones del §7, con su propio histórico, mediciones y
observaciones.

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

Las que quedaron fuera de la 3.0.0 por alcance, en orden de valor, con su estado:

1. ~~**Índice de muestras bajo demanda en `Mp4Demuxer`** para archivos de muchas horas~~. **Hecho
   en la 3.1.0** ([§9.2](#92-índice-de-muestras-bajo-demanda)): ya no se construye el mapa al
   abrir; la memoria pasa de ~28 MB a ~0,5 MB en un archivo de dos horas.
2. ~~**Un mezclador en streaming** que alinee por sí mismo micrófono y sistema cuando llegan en
   bloques de tamaños distintos~~. **Hecho en la 3.1.0**: `PcmStreamMixer`, como adición sin tocar
   la API de `PcmMixer` ni `PcmResampler`.
3. ~~**Segmentos CMAF con `sidx`** para empaquetado DASH/HLS directo~~. **Hecho en la 3.1.0**
   ([§9.5](#95-segmentos-indexados-con-sidx)), con la forma de `-movflags dash` de FFmpeg: un `sidx`
   por pista delante de cada fragmento, sin dejar de ser un único archivo solo-añadir, y un aviso
   por segmento para empaquetar en vivo.
4. ~~**Fuzzing continuo** sobre los dos demuxers en el CI~~. **Hecho en la 3.1.0**
   ([§9.3](#93-fuzzing-de-los-demuxers)): con un fuzzer propio en lugar de Jazzer, en cada
   ejecución de los tests y en una campaña semanal.
5. ~~**Benchmarks reproducibles** (JMH) en el CI para que las cifras del §4 no retrocedan~~.
   **Hecho en la 3.1.0** ([§9.4](#94-benchmarks)): vigilando la memoria por operación, que no
   depende de la máquina.

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

## 9. La 3.1.0

Atiende las cinco recomendaciones del §7: mezclador en streaming, índice bajo demanda, fuzzing,
benchmarks y segmentos con `sidx`. Es una versión **menor**: `public-api.txt` frente al de la 3.0.0
gana 82 líneas y no pierde ninguna, y ningún comportamiento del que alguien pueda depender cambia (el
criterio de CONTRIBUTING): el `sidx` y el aviso de segmentos solo actúan si se piden.

### 9.1 Mezclador en streaming

`PcmStreamMixer` alinea por sí mismo micrófono y sistema cuando llegan en formatos, hilos y tamaños
de bloque distintos, y da exactamente lo mismo que convertir y mezclar a mano los flujos enteros.
Las decisiones de diseño —espera de 500 ms, descarte del audio tardío para no desincronizar, sin
corrección de deriva de reloj— están en el CHANGELOG y en el KDoc de la clase; las fija
`PcmStreamMixerTest`, incluida una prueba de mutación del descarte.

### 9.2 Índice de muestras bajo demanda

**Por qué.** `Mp4Demuxer` construía al abrir un mapa de 37 bytes por muestra. Con dos horas a 60 fps
y AAC eran ~28 MB; con diez, más de 140 MB, que en el heap de un móvil es un `OutOfMemoryError`.

**Cómo.** Las tablas del propio archivo ya son un índice compacto, así que no se copian: al abrir se
recorren una vez —para validarlas, contar las muestras utilizables y saber si los DTS avanzan— y se
guarda un **punto de control** cada 256 muestras con el estado del recorrido. Después, un cursor por
pista lee lo que necesita.

- **MP4 plano**: el cursor son dos máquinas de estado sobre las tablas. La de tiempos (`stts`,
  `ctts`) avanza en cada paso; la de posiciones (`stsc`, `stco`, `stsz`) solo se pone al día cuando
  se pide el offset de una muestra, al leer su paquete. Las tablas se leen por páginas con lecturas
  posicionales que no tocan el buffer compartido de `SeekableInput`. Los keyframes van en un
  `BitSet`: un bit por muestra.
- **fMP4**: los puntos de control guardan dónde retomar el recorrido de fragmentos y el tiempo de
  decodificación acumulado de cada pista, y el cursor vuelve a parsear el `moof` que necesita con el
  mismo código que la pasada al abrir.
- La lógica de parseo es la misma de la 3.0.0, incluidas sus reglas para archivos dañados; lo que
  cambia es dónde se guardan las muestras. Las búsquedas reproducen el algoritmo anterior entre
  puntos de control.

**Decisiones.**

| Decisión | Por qué |
|---|---|
| Un punto de control cada **256** muestras | Con 1024 la memoria era la cuarta parte, pero `seekTo` pasaba de 100 µs. Con 256 cuesta menos de medio byte por muestra y `seekTo` se queda en unos 7 µs. |
| Separar tiempos y posiciones | Una búsqueda al azar no encuentra en caché las páginas de `stsz`/`stco`/`stsc`, y leerlas del disco era la mayor parte de su coste. Buscar solo necesita tiempos. |
| Propiedades calculadas en el cursor | Al buscar se recorren cientos de muestras mirando solo el DTS; resolver en cada paso PTS, duración y tamaño era la mitad del coste. |
| `SampleTable` conserva tres métodos sin uso | La clase es `internal` pero figura en `public-api.txt`, y quitar líneas del volcado exige la mayor según CONTRIBUTING. Se retiran en ella. |

**Mediciones.** El MP4 de dos horas a 60 fps con AAC del §4 (~770 000 muestras), las dos versiones
en la misma máquina, en la misma sesión y con la JVM ya caliente (por eso los tiempos de apertura
de la 3.0.0 salen más bajos que en el §4, que medía otra cosa):

| | 3.0.0 | 3.1.0 |
|---|---|---|
| Heap del demuxer abierto, MP4 | 28,0 MB | 0,49 MB |
| Heap del demuxer abierto, fMP4 | 27,9 MB | 0,13 MB |
| Abrir, MP4 | 37,6 ms | 22,8 ms |
| `seekTo`, MP4 / fMP4 | 0,7 µs / 0,7 µs | 7,6 µs / 52 µs |
| Leer el archivo entero, MP4 (770 000 paquetes de pocos bytes) | 44 ms | 77 ms |

Buscar y leer cuestan más, a cambio de no retener el mapa: siguen en microsegundos por operación y
muy por debajo de lo que cuesta leer los datos de un vídeo real, donde cada paquete son KB.

**Equivalencia.** Lo que había que garantizar es que el resultado no cambiara. Además de la suite y
de `Mp4SampleIndexTest` —que compara cada `seekTo` con el algoritmo documentado aplicado a la pista
entera, también justo en los bordes de los puntos de control—, durante el desarrollo se comparó el
`Mp4Demuxer` nuevo con el de la 3.0.0 publicada, cada uno en su classloader, sobre las semillas del
fuzzing y sobre archivos mutados: pistas, duración, avisos, todos los paquetes con un CRC de sus
datos, y el destino de nueve `seekTo` seguidos de cuarenta paquetes. Sobre el código final,
600 000 variantes con dos semillas distintas y las seis semillas MP4: **cero diferencias**. Sumando
las versiones intermedias, la comparación cubrió más de 1,2 millones de variantes.

Esa comparación encontró **un fallo del código nuevo antes de publicarlo**: los puntos de control de
fMP4 marcaban «DTS todavía desconocido» con `Long.MIN_VALUE`, y un `tfdt` dañado puede dar
exactamente ese DTS a la primera muestra de una pista; `seekTo(0)` la entregaba en vez de saltarla.
Ahora lo pendiente se sigue por posición, sin valor centinela, y lo fija un test que falla con el
código anterior.

### 9.3 Fuzzing de los demuxers

**Por qué un fuzzer propio y no Jazzer.** Jazzer guía las mutaciones por cobertura, que encuentra
más con el mismo tiempo, pero trae un agente nativo y su modo de fuzzing solo corre bien en Linux:
en la máquina de desarrollo, Windows, se quedaría en reproducir un corpus. El de `DemuxerFuzzTest`
no tiene dependencias, corre igual en los dos sistemas y es **determinista**: una semilla y una
iteración reproducen cualquier hallazgo. Lo que pierde por no ver la cobertura lo compensa en parte
mutando con conocimiento de la estructura.

**Qué hace.**

- **Semillas**: seis archivos de FFmpeg —B-frames con `ctts`, `edts` de AAC, fMP4 con y sin
  `default-base-is-moof`, HEVC con Opus fragmentado, los `sidx` de `-movflags dash`, MKV con
  `SeekHead`, `Tags` y `Cues`— y seis de la librería, entre ellos un fMP4 con `sidx` y un MKV a
  mano con los tres tipos de lacing, zlib, eliminación de cabecera y `BlockGroup`. **Todas se
  generan en cada ejecución**; las de FFmpeg solo si está instalado, como en los tests de
  integración. Al principio las de FFmpeg iban versionadas (unos 38 KB), y se retiraron para no
  guardar archivos de vídeo en el repositorio: el precio es que dependen de la versión de FFmpeg
  instalada, que no importa, porque cada hallazgo se guarda con la entrada exacta que lo provocó.
- **Mutaciones**: a ciegas (bits, bytes y enteros «interesantes», borrar, duplicar, truncar, cruzar
  semillas) y conscientes de la estructura: tamaños, tipos, versiones y contadores de las cajas MP4;
  tamaños e IDs de los elementos EBML; cabeceras de bloque y lacing; duplicar, quitar o intercambiar
  nodos corrigiendo el tamaño de sus ancestros.
- **Oráculos**: cada variante se abre, se lee entera y se busca en seis puntos, en un hilo con tiempo
  límite, sobre un canal en memoria. Es violación un cuelgue, un `Error`, una excepción fuera de las
  tipadas, más de 96 MB reservados, más paquetes de los que caben, un paquete de una pista no
  anunciada, o el archivo abierto tras fallar el constructor. Un archivo **solo truncado** además
  tiene que leerse hasta donde llegue sin lanzar.

**Resultados.** A unas 4000–7000 variantes por segundo:

| Campaña | Variantes | Hallazgos |
|---|---|---|
| Semilla 1, 2 min | 468 065 | 1: memoria desproporcionada en MKV (#29) |
| Semilla 2, 5 min, tras corregirlo | 2 191 500 | 0 |
| Semilla 3, 10 min, código final | 3 501 262 | 0 |

En las campañas, en torno al 87 % de las variantes abre y se lee entera, y el resto falla al abrir con
una excepción tipada: las mutaciones llegan al parser profundo y no se quedan en la primera
cabecera. Ninguna variante hizo fallar la lectura una vez abierta: los dos demuxers convierten todo
daño posterior en avisos y fin de stream, como promete la política.

**Hallazgos**, con la numeración del §3:

| # | Severidad | Hallazgo | Corrección | Test |
|---|---|---|---|---|
| 29 | Media | `MkvDemuxer` creaba un `Inflater` y un buffer de 64 KB por fotograma comprimido. Con lacing fijo un bloque declara 256 fotogramas con un byte: un MKV de 953 bytes reservaba 110 MB en una lectura. | Descompresor y buffer reutilizados, liberados en `close`. | `MatroskaContentEncodingTest` |
| 30 | Media | Con lacing, un fotograma comprimido ilegible se descartaba y los siguientes del bloque heredaban su marca de tiempo; además se avisaba una vez por fotograma. | El hueco se mantiene y se avisa una vez por bloque. | `MatroskaContentEncodingTest` |

El #30 no lo detectaba ningún oráculo: apareció al analizar el #29, y es un ejemplo de por qué cada
hallazgo se revisa a mano en vez de solo hacer pasar el caso. Los dos tests fallan con el
`MkvDemuxer` de la 3.0.0 y pasan con el nuevo.

### 9.4 Benchmarks

`benchmarks/` es un build JMH aparte —como `compat/kotlin-1.9`, para no meter el plugin en el
build que publica JitPack— que compila contra el código del árbol por *composite build*. Mide lo que
mide el §4: abrir, buscar y leer el MP4 de dos horas, escribirlo con `Mp4Muxer`, y mezcla,
remuestreo y `PcmStreamMixer` en bloques de 10 ms.

El CI vigila los **bytes reservados por operación**, no el tiempo: el perfilador de GC de JMH los da
exactos y son los mismos en cualquier máquina, mientras que los tiempos de un runner compartido
varían de una ejecución a otra más que cualquier regresión que importe (en la máquina de desarrollo,
escribir el MP4 de dos horas, sin cambiar una línea de su código, midió 70 ms en una ejecución y
342 ms en otra). Es
además la medida que retrocede cuando algo que se leía bajo demanda vuelve a cargarse entero:
abrir el MP4 de dos horas reserva ~1,5 MB, con un límite de 3 MB, donde la 3.0.0 reservaba unos 30.
La tarea se comprobó en los dos sentidos: pasa con los límites y falla al bajar uno por debajo de lo
medido.

### 9.5 Segmentos indexados con `sidx`

**Qué.** Con `FragmentedMp4Muxer.segmentIndex`, cada fragmento lleva delante un `sidx` (ISO/IEC
14496-12 §8.16.3) por cada pista que tiene muestras en él: versión 1, una referencia al
`moof`+`mdat` que sigue, el tiempo de presentación más temprano y la duración de la pista en su
escala, y si empieza en un punto de acceso. El `first_offset` de cada uno salta los que van detrás,
para que su referencia empiece justo en el `moof`. Es exactamente la forma de `-movflags dash` de
FFmpeg, que se tomó como referencia generando un archivo con él y comparando caja a caja.

**Por qué así.**

| Decisión | Por qué |
|---|---|
| Un `sidx` por fragmento, no uno global | Un índice global va delante de todos los fragmentos, que solo se conocen al cerrar: habría que reescribir el archivo y dejaría de ser solo-añadir y a prueba de cortes. Por fragmento se escribe hacia delante, porque el fragmento entero está en memoria antes de escribirse. |
| Uno por pista, como FFmpeg | El muxer intercala todas las pistas en el mismo `moof`, y cada pista tiene su escala y sus tiempos. Es la forma que los reproductores ya conocen. |
| Sin `styp` ni marca CMAF | FFmpeg tampoco los escribe en este modo. CMAF exige una pista por archivo, y declararlo sería falso; quien lo necesite puede escribir cada pista con su propio muxer. |
| `SAP_type` 0 | Igual que FFmpeg: el muxer sabe que el fragmento empieza en un keyframe, pero no si el GOP es cerrado, que es lo que distingue los tipos 1 y 2. |
| Una propiedad, no un parámetro del constructor | Añadir un parámetro a un constructor público cambia su firma JVM y quitaría una línea del volcado, que exige la mayor. Es el mismo criterio que ya seguía `creationTimeMillis`. |
| `maxFragmentBytes` de 1 GiB como mucho | `referenced_size` tiene 31 bits, y un fragmento puede pasar del límite en lo que ocupe la muestra que lo hace cortarse. Se comprueba en `start()`. |
| Un inicio negativo se escribe 0 | `earliest_presentation_time` no tiene signo, y el primer fragmento puede empezar antes del origen: un B-frame que se muestra antes que el keyframe que abrió la línea de tiempo, como al remuxear desde un punto de búsqueda con GOP abierto. Es lo que ya hacía el `tfra`. Lo encontró la revisión final: escrito tal cual, el negativo se leía como un inicio a miles de millones de años. |

**Para empaquetar en vivo**, `onSegment` avisa de cada fragmento en cuanto está en disco —número de
secuencia, posición, tamaño, inicio y duración de la pista de referencia— e `initSegmentSize` da la
cabecera: es lo que una app necesita para mantener una lista HLS por rangos de bytes mientras graba.
Si el aviso lanza, se ignora, igual que `onWarning`: un fallo de la app no puede cortar el cierre del
archivo.

**Verificación.** `SegmentIndexTest` comprueba campo a campo cada `sidx`, que los tiempos de cada
pista son continuos entre segmentos, que el `tfra` sigue apuntando a los `moof`, que los segmentos
avisados son contiguos y cubren el archivo, que con o sin índice el demuxer entrega exactamente lo
mismo, y que un fragmento que empieza antes del origen se indexa desde 0. En `FfmpegIntegrationTest`, FFmpeg decodifica sin un error el archivo indexado hecho con
material real de x264 y AAC, busca en mitad de él, y reproduce todos los fotogramas a través de una
lista HLS montada **solo** con los avisos. El fuzzing incluye un fMP4 indexado de la librería y otro
de `-movflags dash`.

### 9.6 Validación con grabaciones reales

Tres grabaciones de alrededor de un minuto hechas por programas que no son FFmpeg ni la librería, que
es donde aparecen las combinaciones que ningún archivo de prueba trae. No se guardan en el
repositorio.

| Grabación | Origen | Lo que trae |
|---|---|---|
| MP4 | Capturadora nativa de un móvil Xiaomi (`MediaMuxer` de Android) | HEVC 1156×2510 de frecuencia variable, AAC mono a 44,1 kHz, `co64`, y una edición vacía de 0,87 s porque el vídeo empieza después que el audio |
| MP4 | Capturadora nativa de una Lenovo Legion Tab | H.264 High 2560×1600 **con B-frames** y frecuencia variable, con todos los `ctts` desplazados 6,68 s y compensados en el `elst` |
| MKV | OBS con QuickSync HEVC, escrito por las librerías de FFmpeg | HEVC 1920×1200 a 60 fps con B-frames, dos pistas AAC y `CodecDelay` |

**Qué se comprobó.**

- **Lectura contra `ffprobe`, paquete a paquete**: mismos datos (MD5 de cada paquete), tamaños y
  keyframes en las tres. En los dos MP4, además, los mismos PTS y DTS al microsegundo, con la
  edición vacía y el desplazamiento de 6,68 s incluidos.
- **Remux** de cada una a MKV, MP4 y fMP4 con `sidx`: los nueve decodifican en FFmpeg exactamente los
  mismos fotogramas que su original (MD5 de cada fotograma decodificado), y las tres listas HLS
  montadas solo con `onSegment` reproducen todos los fotogramas.
- **`seekTo`** en trece puntos por grabación: siempre en el último keyframe anterior al destino.
- **Fuzzing** partiendo de las tres: cuarenta cortes de cada original, que se leen hasta el último
  paquete completo sin lanzar, y 73 824 variantes en tres minutos, sin un fallo.

**Lo que encontró: dos fallos, corregidos** (ver el CHANGELOG).

1. **Una pausa entre un B-frame y su referencia en fMP4.** `FragmentedMp4Muxer` saca el DTS de los
   PTS ordenados del fragmento, como CMAF: la muestra `i` en orden de decodificación se decodifica en
   el `i`-ésimo PTS. Con B-frames normales eso adelanta uno o dos fotogramas, pero en la grabación
   de la tablet la pantalla se quedó quieta justo entre un B-frame (mostrado a los 4,6 s) y su
   referencia (a los 11,3 s), y el B-frame quedó decodificándose a los 11,3 s. FFmpeg retrasa la
   pista entera lo que mida el mayor adelanto: el vídeo del fMP4 salía 6,68 s por detrás del audio.
   Ahora el DTS de cada muestra no pasa del menor PTS de las que quedan por decodificar más la
   profundidad de reordenación por la mediana del intervalo entre fotogramas; la pausa pasa a la
   duración del B-frame. En la misma grabación, FFmpeg retrasa ahora el vídeo 49 ms, un B-frame en
   un tramo de esa frecuencia. Sin pausas así no cambia ni un byte.
2. **La lista de edición de un fMP4 no se aplicaba al leerlo.** `Mp4Demuxer` la leía del `trak` pero
   solo la pasaba a las pistas planas. El fMP4 hecho desde OBS, con el cebado del AAC en su `elst`,
   se releía con el audio 21 ms más tarde que el MP4 plano equivalente y que FFmpeg.

**Lo que no es un fallo.**

- **El idioma**: FFmpeg dice «eng» y la librería «und». Android escribe en el `mdhd` el código 0, que
  no es ningún idioma ISO 639; FFmpeg lo traduce con la tabla de idiomas de QuickTime, donde 0 es el
  inglés. La grabación no declara idioma.
- **El audio de OBS** sale 21 ms después que en FFmpeg al leer el MKV: FFmpeg resta el `CodecDelay` a
  cada tiempo y `MkvDemuxer` lo entrega aparte, en `codecDelayUs`. Los muxers lo convierten bien:
  MKV → MKV y MKV → MP4 dejan el audio exactamente donde lo pone FFmpeg en el original.
- **FFmpeg retrasa uno o dos fotogramas el vídeo con B-frames de un fMP4**, por la misma regla del
  punto 1: 34 ms en el de OBS. El fMP4 que escribe el propio FFmpeg con ese archivo empieza el vídeo
  a los 33 ms. Es lo que cuesta el offset negativo de CMAF, que es lo que esperan los reproductores
  de HLS y DASH.
- **Tras `seekTo` en la grabación de la tablet** el audio empieza hasta 6,7 s antes del keyframe: las
  otras pistas se colocan por DTS, como en el demuxer de FFmpeg, y ese archivo lleva el DTS 6,68 s
  por detrás del PTS.
- Los avisos «non monotonically increasing dts» al decodificar con FFmpeg a `null` salen igual con
  los originales del móvil y de la tablet: los provoca la conversión de frecuencia de FFmpeg con el
  vídeo variable de Android.

### 9.7 Cómo se verificó

- Suite completa: 239 tests, sin fallos ni omitidos, incluida la integración con FFmpeg real, en
  Windows con JDK 21 y repitiendo el CI con JDK 17.
- Los tests nuevos de cada corrección, ejecutados contra el código anterior para confirmar que
  reproducen el fallo (#29, #30, el del centinela de §9.2, el del inicio negativo de §9.5 y los dos
  de §9.6).
- Fuzzing y comparación con la 3.0.0 publicada, con los números de §9.2 y §9.3.
- `public-api.txt` frente al de la 3.0.0: 82 líneas añadidas, ninguna eliminada.
- Benchmarks con `verificarLimites`, el consumidor con Kotlin 1.9 contra el artefacto publicado en el
  Maven local, y animal-sniffer contra Android API 26.

### 9.8 Observaciones y pendientes

- **No queda nada del §7.** Lo que sigue son observaciones, no compromisos.
- **Índice `sidx` único al principio** (perfil DASH *on-demand*): queda fuera a propósito, porque
  obligaría a reescribir el archivo al cerrar (§9.5). Partir un archivo en varios para DASH es
  trabajo del empaquetador.
- **`seekTo` en fMP4** cuesta unos 50 µs porque vuelve a parsear el fragmento de destino. Si algún
  uso lo necesitara más rápido, el siguiente paso sería guardar en cada punto de control el DTS de
  cada muestra del fragmento, que es memoria que ahora no se gasta.
- **En la próxima versión mayor**, quitar de `SampleTable` los tres métodos que la 3.1.0 dejó sin
  uso.
- La campaña semanal de fuzzing guarda sus hallazgos como artefacto del CI; revisarlos forma parte
  del trabajo de cada versión.
- **La fachada no expone el `sidx`.** `MkvKotlin.createFragmentedMp4Muxer` y `createMuxer` devuelven
  la interfaz `Muxer`, así que `segmentIndex` y `onSegment` se usan construyendo
  `FragmentedMp4Muxer` directamente, como en el README. Cambiar el tipo que devuelven cambiaría su
  firma, que exige la mayor; si hiciera falta antes, lo compatible es una sobrecarga nueva.
- **`seekTo` de un MP4 plano ya no es solo memoria**: lee del archivo las páginas de tabla que
  necesita, como ya hacía `readPacket` con los datos. En Android, como cualquier lectura de disco, no
  debe llamarse desde el hilo principal.
