# Análisis técnico de Kotmpeg Core

Las secciones 1 a 8 son la revisión que dio lugar a la **3.0.0**. La [sección 9](#9-la-310) es la
**3.1.0**, que atiende las recomendaciones del §7, con su propio histórico, mediciones y
observaciones. La [sección 10](#10-la-400) es la **4.0.0**, que cierra todo lo que quedaba
abierto —en el código, en la API y en las observaciones de §9.6 y §9.8— para que la siguiente
versión no arrastre nada.

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
- ~~**En la próxima versión mayor**, quitar de `SampleTable` los tres métodos que la 3.1.0 dejó sin
  uso~~. **Hecho en la 4.0.0** ([§10.2](#102-una-api-que-ya-no-se-rompe-al-crecer)).
- La campaña semanal de fuzzing guarda sus hallazgos como artefacto del CI; revisarlos forma parte
  del trabajo de cada versión.
- ~~**La fachada no expone el `sidx`.**~~ **Resuelto en la 4.0.0**: `segmentIndex` y `onSegment`
  son opciones de `MuxerOptions`, que la fachada pasa al muxer ([§10.2](#102-una-api-que-ya-no-se-rompe-al-crecer)).
- **`seekTo` de un MP4 plano ya no es solo memoria**: lee del archivo las páginas de tabla que
  necesita, como ya hacía `readPacket` con los datos. En Android, como cualquier lectura de disco, no
  debe llamarse desde el hilo principal.

## 10. La 4.0.0

Una versión **mayor** con un único fin: que no quede nada pendiente, ni en el código ni en la API, y
que lo que se añada después no obligue a otra. Rompe la API una sola vez, a propósito, y cada
ruptura deja la librería en una forma en la que crecer ya no rompe. El CHANGELOG da el paso de
migración de cada punto; aquí va el porqué.

Se ordenó por prioridad: primero lo que daba **resultados distintos según el contenedor** —lo único
que un usuario no puede esquivar—, después la forma de la API, y al final lo que solo es nuevo.

### 10.1 Correcciones de comportamiento

1. **El cebado del audio, igual en los tres contenedores.** `MkvDemuxer` seguía la definición de
   Matroska: los tiempos de los paquetes tal cual y el cebado en `codecDelayUs`. `Mp4Demuxer` restaba
   de los tiempos la edición inicial de una pista de audio y devolvía `codecDelayUs = 0`. El mismo
   AAC llegaba así 21 ms antes desde un MP4 que desde un MKV, y quien lo presentara tal cual tenía
   un desfase distinto según el contenedor. Ahora `Mp4Demuxer` convierte esa edición en
   `codecDelayUs`, y los muxers la vuelven a escribir como edición o como `CodecDelay`: un AAC
   escrito en MP4, fMP4 y MKV se relee con los mismos tiempos y el mismo cebado en los tres. En
   vídeo la edición inicial no es cebado sino el desfase de los B-frames, y se sigue restando.

2. **`seekTo` por tiempo de presentación, y siempre en un keyframe.** Dos fallos distintos con el
   mismo síntoma —un recorte desde el punto de búsqueda que no empieza con imagen y sonido—:
   - En **MP4**, las demás pistas se colocaban por tiempo de decodificación, como el demuxer de
     FFmpeg. Es la observación de §9.6 con la grabación de la tablet: su DTS va 6,68 s por detrás
     del PTS, y tras buscar el audio empezaba 6,7 s antes que la imagen. Ahora el audio empieza en
     el paquete que suena en el instante del keyframe, y otra pista de vídeo en su propio último
     keyframe no posterior. Los límites se siguen localizando por bisección entre los puntos de
     control del índice bajo demanda.
   - En **MKV**, se devolvía el tiempo del cue y se leía desde el principio de su cluster, o, sin
     índice, desde el último cluster anterior al destino. Pero un cue no tiene por qué marcar cada
     keyframe —FFmpeg solo indexa el primero de cada cluster— ni su cluster empezar en él: en un
     MKV de FFmpeg con un keyframe cada cinco fotogramas salía primero el keyframe del principio del
     cluster, que no era el pedido, y sin índice salían fotogramas que no se pueden decodificar.
     Ahora se recorren las cabeceras de los bloques —sin leer las cargas— desde el cue anterior al
     destino hasta el último keyframe no posterior a él, y se lee desde el cluster previo al de ese
     keyframe, porque es ahí donde el muxer pudo dejar el paquete de audio que ya sonaba en ese
     instante. Pero también puede haberlo escrito detrás del keyframe —OBS lo hace con el que
     empieza en el mismo milisegundo—, así que, pasado el keyframe, se sigue leyendo hasta que cada
     una de las demás pistas da un paquete posterior a él: unos pocos paquetes, con un tope de 1 s
     para una pista que se acabe o tenga un hueco. Todo lo de las demás pistas se retiene hasta entonces y
     se recorta con el mismo criterio que en MP4: el audio, desde el paquete que suena en el
     keyframe; otra pista de vídeo, desde su último keyframe no posterior. Lo de la propia pista anterior al
     keyframe se descarta sin leer su carga. La única diferencia con MP4, donde el índice lleva a
     cualquier muestra, es la otra pista de vídeo cuyo keyframe queda más atrás del cluster previo:
     empieza en el siguiente, nunca en un fotograma que no se puede decodificar, y el contrato de
     `Demuxer.seekTo` lo dice. Si ese keyframe no llega en 10 s de lectura —un bloque dañado—, se
     deja de retener y se entrega lo leído, para no acumular el resto del archivo en memoria.
     Esa espera no se corta por tiempo mientras la pista siga dando paquetes, aunque sean
     anteriores al keyframe: va por detrás en el archivo. Es lo que pasa al intercalar por tiempo
     de decodificación un vídeo cuyo DTS va segundos por detrás del PTS —la grabación de la tablet
     de §9.6, pasada a MKV, lleva el audio de cada instante 6,7 s más adelante que su vídeo—, y
     cortar al segundo hacía empezar el audio hasta 5,6 s antes que la imagen. Por eso el silencio
     que se le tolera a una pista es un segundo más lo que iba por detrás en su último paquete: al
     final de esa misma grabación el codificador vacía 1,5 s de fotogramas seguidos, sin audio
     entre ellos, y no es un hueco del audio. Lo guardado mientras tanto tiene dos topes, diez
     segundos de lectura y 64 MiB; el segundo cubre un archivo dañado cuyos tiempos dejan de
     avanzar, que sin él hacía guardar todo lo que quedaba del archivo antes de entregar el primer
     paquete. Un destino anterior al primer cue se busca igual, desde el primer cluster: leer desde
     el del primer cue perdía el audio que el muxer había dejado delante. Y en un archivo que empieza en
     tiempos negativos —Matroska los admite, pero `CueTime` no tiene signo y el muxer guarda el
     cue del primer keyframe como cero— buscar al principio devolvía cero y entregaba desde el
     keyframe siguiente; ahora cae en el primero y devuelve su tiempo.
   - En **los dos**, una pista que ya ha terminado en el instante del keyframe —un micrófono que se
     cortó antes que el vídeo— no entrega nada. Se colocaba en su último paquete o su último
     keyframe, y tras buscar salía primero sonido o imagen de segundos antes: el mismo síntoma.
     En MP4 se sabe sin coste: solo se mira cuando la búsqueda ya llegó a la última muestra de
     esa pista, y entonces basta recorrer desde su keyframe o su paquete hasta el final. En MKV, que
     no tiene índice de muestras, se decide con lo leído tras el keyframe: una pista de audio que
     no da nada en ese segundo y cuyo último paquete dejó de sonar antes, ha terminado o está en un
     hueco, y en los dos casos no suena nada. En vídeo no se puede decir lo mismo: en Matroska un
     fotograma se muestra hasta el siguiente, y una captura de pantalla quieta durante segundos no
     se distingue de una pista acabada hasta llegar al final del archivo. Así que una pista de
     vídeo se da por terminada solo cuando la lectura llega a ese final; antes, sigue
     entregándose desde su último keyframe, como hasta ahora.

3. **`concat` compara la cabecera entera.** La salida lleva una sola cabecera por pista, la de la
   primera entrada, así que cualquier diferencia que viaje en ella llegaba al archivo sin error:
   otro SPS daba una segunda mitad corrupta, otra rotación una segunda mitad girada. Ahora se
   comparan también `codecPrivate`, rotación y color en vídeo, y `codecPrivate` y cebado en audio, y
   el error enumera todas las diferencias, no la primera.

4. **`.webm` ya no se escribe como Matroska.** WebM solo admite VP8, VP9 o AV1 con Vorbis u Opus, y
   el alcance de códecs de la librería está cerrado en H.264/H.265 con AAC/Opus. Salía un archivo
   con extensión `.webm`, cabecera `matroska` y códecs que ningún navegador reproduce; ahora la
   extensión no se deduce y el error lo explica.

5. **Un índice `Cues` roto se avisa.** Se tragaba en silencio, y unas búsquedas de repente lentas no
   tenían explicación; peor, un `Cues` roto delante de los clusters rechazaba el archivo entero. Ahora
   el índice se descarta entero —uno a medias podría mandar una búsqueda a un sitio equivocado—, se
   avisa por `onWarning`, y la búsqueda recorre los clusters.

6. **`SeekableOutput` trunca siempre.** Solo lo hacía el constructor de `File`. Sobre un descriptor
   abierto en `"rw"` —lo que da `MediaStore`— el contenido anterior quedaba detrás del final, y en
   Matroska, escrito a prueba de cortes con tamaños desconocidos, se leía como parte del último
   cluster. Truncar al abrir y no al cerrar mantiene esa garantía también si el proceso muere.
   De paso, un destino de solo lectura —un `RandomAccessFile` en `"r"`, un `FileChannel` abierto
   para leer— se rechaza al construir con un mensaje que dice que se abra en `"rw"`: una escritura
   vacía basta para que Java lo detecte, y antes salía un `NonWritableChannelException` sin
   mensaje. Un `FileDescriptor` de solo lectura Java no lo distingue, y falla con el error del
   sistema.

7. **Una sola pista predeterminada por tipo, sin tener que pedirla.** `TrackInfo.default` valía
   `true` en todas, y con mezcla, micrófono y sistema cada reproductor elegía una. Ahora, sin fijarlo,
   el muxer marca la primera de cada tipo, salvo que otra lleve `true`; lo resuelven los tres muxers
   con la misma función, así que el mismo conjunto de pistas sale igual en MKV y en MP4.

8. **En fMP4, una pista que arranca tarde o se pausa se decodifica desde donde suena.**
   `FragmentedMp4Muxer` empezaba el tiempo de decodificación de todas las pistas en cero, y lo que
   tardaba una en arrancar —un micrófono que entra medio segundo después que la pantalla— iba en
   sus desfases de composición. La presentación salía bien, pero el audio quedaba con el DTS medio segundo por
   detrás del PTS, cuando en audio son iguales. La búsqueda, que coloca el audio por DTS, lo dejaba
   medio segundo tarde —tras buscar, empezaba en 1,48 s en vez de en el paquete de 0,99 s que
   sonaba—, y la duración del fMP4 se quedaba corta frente a la del MP4 plano con el mismo
   contenido (4,000 s frente a 4,033 s), igual que el segmento de su lista de edición si llevaba
   cebado. Lo mismo pasaba a mitad de pista: cada fragmento seguía donde acabó el anterior, y la
   duración del último paquete de un fragmento se estima si el siguiente aún no ha llegado. Tras
   una pausa del micrófono que cruzara un corte, el audio quedaba con el DTS por detrás del PTS lo
   que durara la pausa, hasta el final; y con duraciones declaradas que no coinciden con los
   huecos reales —el vídeo de frecuencia variable de OBS—, el desfase se iba acumulando: 7 ms en
   85 s, que el fMP4 declaraba de menos. Ahora cada fragmento se decodifica, como mínimo, desde su
   primer instante presentado, como queda en MP4 plano, y los dos dan los mismos tiempos y la misma
   duración. Los fMP4 ya escritos con un arranque tardío se siguen buscando bien: el demuxer mide en
   la primera muestra cuánto va la presentación de cada pista por delante de su decodificación
   —cero en audio, salvo en esos archivos— y lo descuenta al buscar. Una pausa a mitad de pista en
   un archivo antiguo no se puede descontar así, y se sigue buscando tarde desde ella; pasarlo por
   `remux` lo reescribe bien. Un efecto que se ve en FFmpeg: si el que arranca tarde es un
   vídeo con B-frames, con desfases firmados FFmpeg lo retrasa uno o dos fotogramas, como a
   cualquier vídeo así y a sus propios fMP4 (§10.3); antes el desfase del arranque, positivo, lo
   tapaba. `nonNegativeCompositionOffsets` lo resuelve como en los demás casos.

9. **La fachada no vacía la salida que no va a escribir.** `remux` y `concat` abrían el destino —lo
   que lo trunca— antes de comprobar las entradas, y una llamada que no podía salir bien
   —segmentos incompatibles, un `trackFilter` que no deja ninguna pista, un vídeo sin
   `codecPrivate`— dejaba en 0 bytes el archivo que ya había. Esas comprobaciones, que hacía
   `Remuxer` con el muxer ya abierto, están ahora en dos funciones que la fachada llama antes de
   abrir el destino; la excepción es la misma.

### 10.2 Una API que ya no se rompe al crecer

Kotlin compila los parámetros con valor por defecto y las `data class` a firmas JVM que **cambian con
cada parámetro o campo nuevo**, y eso es lo que había obligado a remiendos: una función de fábrica
aparte para el fMP4 con límites, propiedades mutables que había que asignar antes de `start()`, y
código muerto que se mantenía para no quitar líneas del volcado. Lo que cambia:

- **`MuxerOptions`.** Todas las opciones de escritura en una clase con propiedades `var`, que los
  muxers y la fachada reciben como bloque (`MuxerOptions.() -> Unit`). Una propiedad nueva añade dos
  métodos y no cambia ninguna firma. Las opciones se validan **antes** de abrir el archivo, y con un
  destino ya abierto se cierra si no valen. Cada muxer usa las de su formato e ignora las demás, para
  que el mismo bloque sirva aunque sea la extensión la que decide. La excepción es `fragmented`, que
  no es una opción del formato sino la elección del muxer: la fachada la usa para elegir, y
  `Mp4Muxer` la rechaza, porque ignorarla daría un MP4 plano a quien pidió uno a prueba de cortes.
- **El modelo con bloque.** `TrackInfo.Video`, `TrackInfo.Audio` y `ColorInfo` se construyen con
  los datos obligatorios y un bloque, y `copy` recibe otro. `HdrStaticInfo` sigue siendo
  `data class`: sus doce campos son los de SMPTE ST 2086 y CTA-861.3, que no van a crecer, y lo
  que se añada a HDR irá en `ColorInfo`. En `copy`, unas dimensiones de presentación iguales a las
  codificadas siguen a estas si cambian; unas distintas —un vídeo anamórfico— se conservan.
- **`MediaPacket.isKeyFrame` obligatorio.** Su valor por defecto, `false`, es lo que hizo que la
  2.x escribiera audio sin un solo punto de acceso y lo que haría que un keyframe olvidado pasara
  sin error.
- **`MkvKotlin` pasa a llamarse `Kotmpeg`**, sin alias: un alias sería otra cosa que retirar en la
  siguiente mayor.
- **Nombres que decían otra cosa**: `readBits`/`writeBits` contaban bytes y pasan a
  `readUInt`/`writeUInt`.
- **Fuera de la API lo que nunca fue API.** Kotlin emite como públicas en el bytecode las clases
  `internal`, y `PublicApiTest` las congelaba como si fueran API: cualquier refactor interno era un
  cambio de API. Ahora el test pregunta la visibilidad a los metadatos de Kotlin con
  `kotlin-reflect` —solo en los tests, no llega al artefacto— y las deja fuera. Con eso se hicieron
  `internal` las piezas de bajo nivel (`EbmlReader`, `EbmlWriter`, `EbmlElement`, `MatroskaIds`,
  `BoxBuilder`, `HevcSpsInfo`, `BitReader`), `private` las constantes de los compañeros de los
  muxers, `internal` los constructores de `AacConfig.Parsed` y `OpusConfig.Parsed`, y se quitó el
  código muerto de `SampleTable` que pedía §9.8. `public-api.txt` pasa de 693 líneas a 451, y lo que
  queda es lo que un usuario escribe o lo que llama su código compilado.
- **El volcado ve los constructores puente.** Con las opciones como último parámetro de los
  constructores y `= {}` por defecto, lo que llama el código compilado contra `Mp4Muxer(file)`, sin
  bloque, no es el constructor de dos parámetros sino uno sintético que termina en
  `DefaultConstructorMarker`, y el test dejaba fuera todo lo sintético: quitar el `= {}` habría sido
  una ruptura binaria sin que el volcado se moviera. Ahora los reconoce por su forma —los parámetros del original, una máscara
  `int` por cada 32 y el marcador, lo que los distingue de los de acceso a un constructor privado—
  y los incluye, como ya hacía con los métodos `$default`. Son dieciséis líneas del volcado.

### 10.3 Lo nuevo

- **Inicio rápido sobre un `SeekableOutput`.** Sin ruta no hay temporal, así que el `moov` se inserta
  delante desplazando los datos en el sitio, de atrás hacia delante en bloques de 1 MiB. El resultado
  es el mismo archivo, byte a byte, que por ruta; lo que no tiene es la red de seguridad del
  temporal, y el KDoc y el README lo dicen. Para leer lo ya escrito sobre un descriptor —cuyo canal
  de `FileOutputStream` solo escribe— se abre un canal de lectura sobre el mismo descriptor, una
  sola vez, que se cierra con la salida: abandonado, Android lo denuncia como recurso perdido. Si el
  descriptor no admite lectura —el `"w"` que `ContentResolver` da por defecto—, el muxer lo rechaza
  al construirse con un error que dice cómo abrirlo. La primera versión lo descubría al desplazar,
  en `stop()`, con la grabación entera escrita y sin `moov` en ninguna parte.
- **Desfases de composición sin signo en fMP4** (`nonNegativeCompositionOffsets`). Cierra la
  observación de §9.6: FFmpeg retrasa la pista de vídeo de un fMP4 lo que mida su mayor desfase
  negativo. Con la opción, el `trun` va en versión 0 con desfases desplazados lo que adelante el
  primer fragmento, y una lista de edición lo compensa. Como ese desfase no se conoce hasta el primer
  fragmento, la cabecera se escribe con él, igual que `-movflags delay_moov`. Si un fragmento
  posterior necesitara más —vídeo de frecuencia variable que se espacia—, se adelanta lo justo el
  tiempo de decodificación de las muestras que se pasarían, con el criterio de
  `boundReorderOffsets`, sin mover la primera muestra ni el tramo del fragmento. Medido con FFmpeg
  sobre x264 con B-frames: con desfases firmados el vídeo se lee desplazado; sin signo, cada
  fotograma donde se escribió, al tic de 90 kHz. El desplazamiento se aplica igual en todo lo que da
  tiempos de composición —`trun`, `tfra` y, con `segmentIndex`, `sidx`—; sin él en el `sidx`, cada
  segmento se anunciaba un fotograma antes de lo que empieza.
- **`writingApp` también en MP4.** Solo llegaba al `WritingApp` de Matroska. Ahora va además en la
  etiqueta `©too` del `moov` (`udta` > `meta` con su `hdlr` `mdir` > `ilst`), en UTF-8, que es donde
  la escribe FFmpeg y lo que `ffprobe` muestra como `encoder`, tanto en MP4 plano como en fMP4.
  Vacía, no se escribe nada. En MKV `ffprobe` no la muestra —su `encoder` sale de las `Tags`—, pero
  sí `mkvinfo` y MediaInfo, que leen `WritingApp`.
- `Kotmpeg.detectFormat(SeekableInput)` público, y `initSegmentSize` en cada `Mp4Segment`.

### 10.4 Decisiones cerradas

Tres puntos se cierran **sin cambio**, y conviene dejar escrito por qué:

- **El suelo de Kotlin no sube.** La librería compila con Kotlin 2.2 pero en nivel de lenguaje y de
  API 2.0, declara `kotlin-stdlib` 2.0.21, y el CI compila y ejecuta con Kotlin 1.9 un consumidor
  contra cada artefacto. Subirlo no corrige nada y dejaría fuera a las apps que aún no han migrado
  a Kotlin 2; se reconsiderará el día que la API pública necesite algo que 2.0 no tenga.
- **`seekTo` en fMP4 se queda como está.** Cuesta decenas de microsegundos porque vuelve a recorrer
  el fragmento de destino desde su punto de control. Guardar en cada punto el DTS de cada muestra
  lo bajaría, a cambio de memoria que ahora no se gasta, y una búsqueda va seguida de leer datos de
  vídeo, que cuesta milisegundos. Los benchmarks lo vigilan por memoria, que es lo que no depende de
  la máquina.

  En MP4 plano, en cambio, sí había algo que corregir, y lo había introducido la propia 4.0.0: para
  colocar el audio por tiempo de presentación (§10.1) se buscaba la primera muestra posterior al
  instante y se retrocedía una, y retroceder en un MP4 plano obliga a volver al punto de control y
  recorrer hasta 255 muestras. Medido contra la 3.1.0 en la misma máquina y sesión, `seekTo` había
  pasado de 27,5 a 37,5 µs. Ahora el cursor dice el DTS de la siguiente muestra sin moverse
  (`knowsNextDts` y `nextDtsUs`) y la búsqueda se para antes de pasarse. En la revisión de cierre,
  medido de nuevo contra la 3.1.0 en dos tandas de la misma sesión, `seekTo` tarda lo mismo (17,1
  y 14,7 µs en una; 29,0 y 29,6 µs en otra) y buscar y leer el primer paquete es más rápido —de 97
  a 52 µs, y de 138 a 123 µs— y reserva un tercio de memoria, 160 bytes frente a 460. Los tiempos
  absolutos cambian con la carga de la máquina; por eso solo se comparan dentro de una sesión.

  En fMP4, en cambio, ese mismo cambio había doblado la memoria: cada búsqueda reservaba el doble
  que en la 3.1.0, 13,5 KB frente a 7,0 KB, dentro de su límite pero sin motivo. Los fragmentos se
  cortan en los keyframes, así que el paquete de audio que suena en uno es casi siempre el último
  del fragmento anterior. La búsqueda llegaba al final de ese fragmento, cargaba el siguiente para
  ver que se pasaba y retrocedía una muestra; y el cursor, que solo guardaba un fragmento, tenía
  que volver a su punto de control y parsear otra vez hasta tres. Ahora guarda también el
  anterior, y el DTS de la muestra siguiente se pregunta con dos miembros primitivos en vez de un
  `Long?`, que reservaba un objeto en cada paso: 6,2 KB por búsqueda, por debajo de la 3.1.0. Su
  límite en `limites.properties` baja de 32 KB a 10 KB, de modo que esa regresión —13,5 a 15,1 KB
  según la tanda— ya no pasa.
- **El cebado del segundo segmento se queda dentro en `concat`.** Quitarlo exigiría decodificar y
  recodificar el principio de cada segmento, y `concat` no recodifica. Tampoco hace falta para la
  sincronía: cada segmento se desplaza según el final real del audio anterior, así que su cebado
  ocupa los ~21 ms de la unión y su primer fotograma y su primer sonido útil siguen alineados, como
  en el original. Lo único perceptible es ese silencio de la unión. El KDoc de `concat` lo explica, y
  un test lo fija en MP4 y MKV.

### 10.5 Gradle 9

El build pasa de Gradle 8.14.5 a **9.8.1**. Hizo falta poco: cambiar la configuración de la firma
de Android de `by configurations.creating` a `configurations.create(...)`, obsoleto desde la 9.6, y
subir el resolvedor de toolchains a la 1.0.0, porque la 0.8.0 usa una constante que Gradle 9 quitó
y fallaría el día que tuviera que descargar un JDK. Queda un único aviso de obsolescencia,
`Configuration.setVisible`, que viene del plugin de Kotlin 2.2.10 —el compilador se fija ahí a
propósito, ver el §10.4— y que Gradle no retira hasta la 11. `compat/kotlin-1.9` sigue con su propio
wrapper en Gradle 8, porque el plugin de Kotlin 1.9 no funciona con Gradle 9.

### 10.6 Cómo se verificó

- **Suite completa: 275 tests**, sin fallos ni omitidos, incluida la integración con FFmpeg real,
  lanzando Gradle con JDK 21 y otra vez con JDK 17, como el CI.
- **Cada corrección con su test**, escrito para fallar con el comportamiento anterior: el cebado en los
  tres contenedores, la búsqueda por tiempo de presentación, la búsqueda en MKV con y sin índice
  (también sobre un MKV de FFmpeg con keyframes a mitad de cluster), con el audio que suena en el
  keyframe escrito antes o detrás de él y con una segunda pista de vídeo, la búsqueda pasado el
  final de una pista en MP4, fMP4 y MKV, los errores de `concat` y su sincronía, el rechazo de
  `.webm` y el de `fragmented` en `Mp4Muxer`, el aviso del índice roto, el truncado de
  `SeekableOutput`, la pista predeterminada, el rechazo de un destino que no se puede leer para el
  inicio rápido en el sitio, y que el `sidx` y el `tfra` coinciden con desfases sin signo. Los dos últimos de la búsqueda se comprobaron
  además contra el código anterior, donde fallan: con el audio escrito detrás del keyframe
  empezaba 48 ms antes, y tras buscar pasado el final de una cámara salía primero su keyframe de
  3,2 s antes. Los dos últimos, también: el destino de solo escritura se aceptaba y fallaba al
  cerrar, y el `sidx` sin signo iba 3000 tics —un fotograma a 90 kHz— por detrás del `tfra`.
- **Lo nuevo contra referencias**: el inicio rápido sobre un descriptor da el mismo archivo, byte a
  byte, que por ruta, también sobre un archivo que ya tenía datos; con FFmpeg, los desfases con
  signo dejan el vídeo de un fMP4 desplazado y sin signo cada fotograma en su sitio; y `writingApp`
  es el `encoder` que muestra `ffprobe` en MP4 y fMP4, también con texto no ASCII.
- **Las tres grabaciones reales de §9.6**, repetidas con la 4.0.0:
  - los paquetes que lee la librería son, pista a pista, los de `ffprobe` (MD5 de cada uno);
  - cada una pasada a MKV, MP4, MP4 con inicio rápido por ruta y por descriptor, fMP4 con `sidx`,
    fMP4 sin signo y MKV → MP4 decodifica en FFmpeg **exactamente** los mismos fotogramas de vídeo y
    de audio que el original (MD5 de cada fotograma decodificado), 21 salidas;
  - FFmpeg pone el vídeo del fMP4 firmado 34 ms (OBS) y 49 ms (tablet) por detrás; el sin signo, en
    su sitio;
  - `seekTo` en tres puntos de cada una cae en un keyframe y el audio empieza entre 0 y 23 ms antes
    de él —el paquete que suena en ese instante—. En la grabación de la tablet empezaba 6,7 s antes;
  - la única salida de FFmpeg al decodificarlas son los avisos de DTS no monótono que ya da el
    original, en el mismo número.

  Tras los últimos cambios —la etiqueta `©too` y la búsqueda en MKV— se repitió lo que tocan: la
  búsqueda en tres puntos de cada grabación, y el `encoder` de cada una pasada a MP4 y fMP4, que
  es el `writingApp` pedido. En el MKV de OBS el resultado cambió: OBS escribe el paquete de audio
  del mismo milisegundo detrás del keyframe, y con la lectura desde el cluster anterior las dos
  pistas de audio empezaban un paquete antes, 22 ms; ahora empiezan en el del instante del
  keyframe, como en MP4. Ese caso es el que llevó a seguir leyendo tras el keyframe (§10.1). En los
  MP4, el audio empieza entre 1 y 20 ms antes del keyframe, el paquete que suena en él. Con la
  regla de las pistas ya terminadas se añadió una búsqueda justo antes del final de cada
  grabación y otra pasado el final: en las tres, todas las pistas llegan hasta el final, y las dos
  caen en el último keyframe con el audio en el paquete que suena, como debe ser.
- **Fuzzing con semilla nueva** (20261014): 1 025 811 variantes en cinco minutos, ninguna falla, y la
  mayor reserva fue de 2,6 MiB. El fuzzer llama a `seekTo`, así que recorre también la búsqueda
  nueva de MKV. Tras los últimos cambios de la búsqueda, dos pasadas más de dos minutos con
  semillas nuevas (40000015 y 40000016): 476 955 y 435 980 variantes, ninguna falla, y una reserva
  máxima de 3,6 MiB. Tras la revisión de cierre, otra de dos minutos (40000017): 799 447
  variantes, ninguna falla, y 3,8 MiB de reserva máxima.
- **Benchmarks** con `verificarLimites`: los once dentro de su límite de memoria.
- **El consumidor con Kotlin 1.9** contra el artefacto 4.0.0 publicado en el Maven local, y
  `AndroidApiCompatibilityTest` contra la API 26 de Android, que encontró dos llamadas nuevas que no
  existen allí antes de publicar.
- **`seekTo` medido contra la 3.1.0** en la misma máquina y sesión, con su código extraído del tag
  (§10.4): en MP4 plano y en fMP4 tarda lo mismo; buscar y leer el primer paquete es más rápido y
  reserva un tercio de memoria; y en fMP4 cada búsqueda reserva 6,2 KB frente a 7,0 KB —7,4 KB en
  el peor de cinco forks de JMH, lejos de su límite nuevo de 10 KB—. La memoria de las búsquedas en
  MP4 plano varía de un fork a otro, entre 0 y unos 110 bytes, según lo que el JIT consiga eliminar
  —un iterador, un `ByteBuffer`—, y lo mismo en la 3.1.0: con tres forks, 72 bytes de media en las
  dos, muy por debajo de su límite.
- **La compilación de JitPack, simulada en local**: sin `build/` ni `.gradle/`, con JDK 17 como
  `JAVA_HOME` y el mismo `publishToMavenLocal` sin tests que lanza JitPack, sobre Gradle 9.8.1.
- `public-api.txt` frente al de la 3.1.0: de 693 líneas a 451, con todo lo que sale explicado en el
  CHANGELOG.
- **La revisión de cierre**, sobre la librería entera y no solo sobre lo que cambió desde la 3.1.0:
  - el mismo contenido escrito en MKV, MP4 y fMP4 —firmado y sin signo— se relee con los mismos
    tiempos y la misma duración, y se busca igual: así salieron la pista tardía y la pausa (§10.1);
  - nueve tipos de destino —`File`, `RandomAccessFile`, `FileChannel` y `FileDescriptor`, en
    lectura y escritura, solo escritura o solo lectura según los admite cada uno— con los cuatro
    muxers: lo que se puede escribir sale igual, y lo que no se rechaza al construir con un
    mensaje que dice qué hacer, sin dejar canales abiertos;
  - un disco lleno a mitad de grabación: MKV y fMP4 quedan legibles hasta el fallo, el MP4 plano
    no —su índice va al final—, y cerrar repite el error en vez de tragárselo;
  - los cuatro tipos de origen dan los mismos paquetes y se cierran;
  - 64 combinaciones de `MuxerOptions`, más los extremos, sobre H.264 y AAC reales de x264:
    todas decodifican en FFmpeg sin errores, y las imposibles no crean el archivo;
  - archivos de terceros hechos con FFmpeg —recortados con `-ss`, con `negative_cts_offsets`,
    fMP4, MKV con Opus, audio que arranca tarde en MKV y en MP4—: el MD5 de cada paquete es el de
    `ffprobe`, sus tiempos difieren en un desplazamiento constante —el cebado— y se buscan bien;
    `ffprobe` en modo detallado no da ningún aviso de contenedor sobre 30 salidas;
  - las tres grabaciones reales, pasadas a MKV, MP4, MP4 con inicio rápido, fMP4 firmado, sin
    signo y con `sidx`: los mismos paquetes, la misma duración, 105 búsquedas que caen donde deben
    —lo que destapó el audio retrasado del MKV de la tablet— y una decodificación sin errores. Los
    únicos mensajes de FFmpeg son los avisos de DTS de su muxer `null`, que da igual con los
    originales;
  - cada corrección nueva con un test que falla sin ella, comprobado deshaciéndola; la de memoria
    de fMP4, con `verificarLimites`, que sin ella da 15 147 bytes por búsqueda con un límite de
    10 240.

### 10.7 Lo que queda

Nada de la lista de la 4.0.0, ni de lo que salió al repasarla antes de cerrarla: `Mp4Muxer`
ignoraba `fragmented`, `writingApp` no llegaba a MP4, el volcado de API no veía los constructores
puente, la búsqueda en MKV dejaba el audio a unos milisegundos del paquete que suena en el
keyframe, una pista ya terminada se seguía entregando tras buscar pasado su final, y la propia
búsqueda por tiempo de presentación había hecho más lento `seekTo` en MP4 plano. Una revisión
final de todo lo que cambió desde la 3.1.0 encontró dos más en lo nuevo: el inicio rápido sobre un
descriptor de solo escritura fallaba al cerrar, con la grabación sin índice, y el `sidx` con
desfases sin signo no llevaba su desplazamiento. Una revisión de cierre, sobre la librería entera
y no solo sobre lo que cambió, encontró siete más: un `remux` o `concat` rechazado dejaba vacía la
salida que ya existía; en fMP4, una pista que arrancaba tarde o se pausaba en un corte de
fragmento quedaba con el DTS por detrás del PTS y se buscaba tarde; en MKV, buscar antes del primer
cue perdía el audio que sonaba, y en un archivo con tiempos negativos devolvía cero; en MKV, el
audio escrito segundos por detrás de su vídeo empezaba esos segundos antes tras buscar —lo
encontraron las grabaciones reales—; lo que retenía esa búsqueda no tenía tope si los tiempos
dejaban de avanzar; un destino de solo lectura fallaba sin mensaje; y en fMP4 cada búsqueda
reservaba el doble de memoria que en la 3.1.0. Todo está corregido arriba, y el cebado del segundo
segmento de `concat`, explicado y fijado con un test (§10.4).

Las observaciones de §9.6 que eran comportamiento de la librería —el audio adelantado tras buscar
en la tablet y el vídeo retrasado del fMP4 en FFmpeg— están resueltas en §10.1 y §10.3; las que son
de FFmpeg —el idioma «eng» por el código 0 de Android y los avisos de DTS del vídeo variable— no
dependen de la librería.

Lo que sigue fuera es a propósito o no depende de la librería:

- el índice `sidx` único al principio, por lo que explica §9.5;
- en MKV, el audio que el muxer escribió más de diez segundos —o 64 MiB— por detrás de su vídeo:
  la búsqueda deja de esperarlo y empieza antes del keyframe. Esperar más es guardar más en
  memoria en cada búsqueda, y el caso real más extremo visto, el de la tablet, va 6,7 s por detrás;
- en un fMP4 escrito por una versión anterior, una pausa del audio en un corte de fragmento: se
  sigue buscando tarde desde ella, y `remux` lo arregla (§10.1);
- en MKV, saber que una pista de vídeo ha terminado antes que el archivo lejos de su final:
  Matroska no lo dice, y la alternativa —leer hasta el final en cada búsqueda— costaría más que
  entregar un GOP de esa pista (§10.1);
- el aviso de obsolescencia del plugin de Kotlin 2.2.10 (§10.5), que no afecta hasta Gradle 11 y
  se irá con el plugin cuando se suba el compilador;
- en MP4 plano, el hueco inicial de una pista que arranca después que las demás se escribe al
  milisegundo, porque la escala de la película es 1000, como la que escribe FFmpeg: su primera
  muestra se lee hasta medio milisegundo desplazada (0,33 ms en la revisión). El KDoc de la
  constante lo dice;
- Matroska guarda los tiempos negativos tal cual y MP4 los lleva a cero, por lo que el mismo
  contenido puede leerse con un desplazamiento constante distinto en cada contenedor; es lo que
  explica el KDoc de `MkvMuxer`, y la diferencia entre paquetes es la misma;
- FFmpeg calcula la duración de un fMP4 con `sidx` sin descontar la lista de edición —6,033 s
  donde son 6,0—, y con los suyos igual: 6,067 s en uno de 6 s escrito con `-movflags dash`;
- que JitPack compile el tag con Gradle 9.8.1 solo se puede ver al publicarlo; en local se simuló
  su build exacto (§10.6).
