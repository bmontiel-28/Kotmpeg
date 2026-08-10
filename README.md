# Kotmpeg Core

[![CI](https://github.com/bmontiel-28/Kotmpeg/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/bmontiel-28/Kotmpeg/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/bmontiel-28/Kotmpeg.svg)](https://jitpack.io/#bmontiel-28/Kotmpeg)
[![Licencia MIT](https://img.shields.io/badge/licencia-MIT-blue.svg)](LICENSE)
[![JVM 17](https://img.shields.io/badge/JVM-17%2B-orange.svg)](#requisitos)
[![Android API 34+](https://img.shields.io/badge/Android-API%2034%2B-3ddc84.svg)](#en-android)

**Lee y escribe contenedores MKV y MP4/fMP4 en Kotlin puro, sobre cualquier JVM 17 — remux sin
recodificar, sincronización de tiempos entre pistas y metadata de rotación, color y HDR10, sin
dependencias, sin binarios nativos y sin una sola API de plataforma.**

**Versión: 1.0.1 — estable.** Desde la `1.0.0` la API pública queda congelada: lo que se
publica aquí se mantiene, y romperlo exige una `2.0.0`. No depende de que nadie se acuerde —
`PublicApiTest` compara la superficie pública contra un volcado versionado y falla si se mueve.

La librería **no codifica ni decodifica**: empaqueta y desempaqueta streams que ya vienen
comprimidos. Todo el alcance, con lo que queda fuera y por qué, está en
[Lo que no está implementado](#lo-que-no-está-implementado-y-por-qué).

## En 30 segundos

```kotlin
// Convertir MKV ↔ MP4 sin recodificar. Milisegundos, sin pérdida de calidad.
MkvKotlin.remux(File("entrada.mkv"), File("salida.mp4"))

// Unir segmentos ya codificados, tampoco recodifica.
MkvKotlin.concat(listOf(File("p1.mkv"), File("p2.mkv")), File("completo.mp4"))

// Inspeccionar un archivo, como ffprobe.
MkvKotlin.openDemuxer(File("clip.mp4")).use { d ->
    println("${d.durationUs / 1000} ms, ${d.tracks.size} pistas")
    d.tracks.filterIsInstance<TrackInfo.Video>().forEach {
        println("${it.codec} ${it.width}x${it.height} rot=${it.rotationDegrees}")
    }
}
```

Y el nivel bajo, cuando ya tienes paquetes codificados de donde sea y solo quieres el contenedor:

```kotlin
MkvKotlin.createMuxer(File("salida.mp4"), mp4Fragmented = true).use { muxer ->
    val video = muxer.addTrack(
        TrackInfo.Video(codec = VideoCodec.H264, width = 1920, height = 1080, codecPrivate = avcC),
    )
    muxer.start()
    muxer.writePacket(MediaPacket(video, nalus, ptsUs, isKeyFrame = true))
    muxer.stop()
}
```

## ¿Es este proyecto para ti?

| Si necesitas… | ¿Sirve? |
|---|---|
| Convertir entre MKV y MP4 sin recodificar ni perder sincronía | ✅ Es el caso central |
| Escribir un contenedor con paquetes que produce otra cosa (un encoder, la red, un archivo) | ✅ Es el caso central |
| Leer un MKV/MP4 pista a pista, con seek exacto | ✅ Es el caso central |
| Generar fMP4 append-only para HLS/DASH o para grabación a prueba de cortes | ✅ Es el caso central |
| Unir segmentos ya codificados sin recodificar | ✅ Es el caso central |
| Comprimir o descomprimir vídeo/audio | ❌ No hay ningún códec aquí, [ver por qué](#lo-que-no-está-implementado-y-por-qué) |
| VP9, AV1, MP3, AVI, MPEG-TS… | ❌ Solo H.264/H.265 + AAC/Opus sobre MKV/MP4 |
| Filtros de vídeo (`scale`, `crop`, `overlay`) | ❌ Esto es un muxer, no un motor de proceso de señal |
| Emitir por RTMP/SRT/RTSP | ❌ Es otra capa; esto produce el fMP4 que alimenta al emisor |

## Dónde encaja

Un muxer se sitúa siempre en un extremo de la cadena, y saber cuál evita buscar aquí cosas que no
están:

```
   fuente de paquetes                     Kotmpeg Core                   archivo
 (encoder, red, otro archivo)  ──────►  muxea / demuxea  ──────►      .mkv / .mp4
```

Lo que entra son paquetes **ya comprimidos** (H.264/H.265, AAC/Opus) con sus marcas de tiempo, y lo
que sale es un archivo correcto y sincronizado, o al revés. De dónde salgan esos paquetes —un
codificador por hardware, un decodificador, un socket, otro contenedor— le da igual: el modelo de
datos (`MediaPacket`, `TrackInfo`) es el mismo.

## Glosario rápido

Si vienes de usar FFmpeg por línea de comandos, o directamente de cero, estos seis términos
aparecen en todo el documento:

| Término | Qué significa |
|---|---|
| **Contenedor** | El "envase" del archivo: MKV o MP4. Guarda las pistas y los tiempos, pero **no** comprime nada. La extensión del archivo es su contenedor. |
| **Códec** | Lo que sí comprime: H.264/H.265 para vídeo, AAC/Opus para audio. Un mismo códec puede ir dentro de cualquiera de los dos contenedores. **Esta librería no implementa ninguno**: los recibe ya comprimidos. |
| **Muxear / demuxear** | Muxear = meter pistas ya comprimidas dentro de un contenedor. Demuxear = sacarlas. Ninguna de las dos toca la compresión. Es exactamente lo que hace esta librería. |
| **Remuxear** | Cambiar de contenedor sin recodificar (MKV → MP4). Es casi instantáneo y **sin pérdida**, porque solo se reescribe el envase. Es `ffmpeg -c copy`. |
| **Keyframe** | Fotograma completo, que se decodifica solo. Los demás dependen de él. Solo se puede empezar a reproducir o cortar limpio en un keyframe. |
| **fMP4** | MP4 fragmentado: se escribe en trozos autocontenidos en vez de arreglar el índice al final. Sobrevive a que se mate el proceso y es lo que consumen HLS/DASH. |

Dos más que salen en los tiempos: **PTS** (cuándo se *muestra* un fotograma) y **DTS** (cuándo se
*decodifica*). Con B-frames no coinciden, y esa diferencia es la que la librería resuelve por ti —
ver [sincronización](#cómo-se-garantiza-la-sincronización).

---

# Instalación

## Requisitos

Uno solo: **JDK 17 o superior**. El código de producción **no depende de nada**: ni de terceros, ni
de ningún SDK, solo de la biblioteca estándar de Kotlin y de `java.io`/`java.nio`. Se compila y se
testea en una máquina sin más herramientas instaladas.

El artefacto genera bytecode JVM 17, así que quien lo consuma tiene que compilar a 17 o más.

## En Android

Se puede usar en Android sin nada especial: es un jar de Kotlin/JVM, no un AAR, y no declara
manifiesto, recursos ni permisos.

| Requisito | Valor |
|---|---|
| `minSdk` soportado | **34 (Android 14)** |
| Nivel de Java de tu app | 17 o superior (`compileOptions` y `kotlinOptions`) |
| Permisos que exige la librería | Ninguno |
| Dependencias que arrastra | Ninguna |

Toda la superficie de plataforma que usa —`java.io.File`, `RandomAccessFile`, `FileDescriptor`,
`ByteBuffer`, `FileChannel`, `java.nio.file.Files`— está disponible de sobra en API 34, así que no
hace falta *desugaring* ni ninguna ruta de compatibilidad.

Dos cosas que conviene tener claras antes de integrarla en una app:

- **Aquí no hay códecs.** En Android, la codificación y decodificación las hace el chip vía
  `MediaCodec`; esta librería recibe los paquetes que salen de ahí y los escribe al contenedor, o
  al revés. La conversión entre Annex-B y el formato ISO que necesita el contenedor la hace
  `NalUnits`, y la configuración de códec (`avcC`/`hvcC`/ASC/OpusHead) viaja en
  `TrackInfo.codecPrivate`.
- **`SeekableInput`/`SeekableOutput` aceptan un `FileDescriptor` ya abierto**, así que un
  `ParcelFileDescriptor` de `ContentResolver` sirve directamente: se puede escribir y leer por
  MediaStore/SAF sin materializar un archivo intermedio en almacenamiento con scope.

## Cómo añadirla a tu proyecto

### Opción A — desde JitPack

JitPack compila la librería a partir de un **tag** de este repositorio. Añade el repositorio y la
dependencia:

```kotlin
// settings.gradle.kts de tu proyecto — dónde buscar
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts — qué bajar
dependencies {
    implementation("com.github.bmontiel-28:Kotmpeg:1.0.1")
}
```

Fíjate en que el artefacto se llama **`Kotmpeg`**, como el repositorio, y no `kotmpeg-core`, como el
artefacto que produce el build. Es lo que despista: JitPack solo usa el formato multi-módulo
`com.github.Usuario.Repo:Modulo:Tag` cuando el build deja **varios** artefactos, y aquí sale uno
solo, así que se nombra por el repositorio. Escribir la forma multi-módulo —con punto antes de
`Kotmpeg`— da un `Could not find` sin más explicación: esa ruta no existe.

> **La versión de la coordenada es el nombre del tag**, no el `version` del `build.gradle.kts`:
> JitPack construye a partir del tag y lo usa literalmente. La lista de las que hay disponibles, con
> el estado del build de cada una, está en
> [`jitpack.io/#bmontiel-28/Kotmpeg`](https://jitpack.io/#bmontiel-28/Kotmpeg); si alguna vez
> difiere de lo de aquí, manda esa página.

### Opción B — desde tu Maven local

Útil para probar un cambio de la librería en tu app antes de publicar nada. En este proyecto:

```bash
./gradlew publishToMavenLocal
```

Y en el proyecto que la consume:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }
// build.gradle.kts
dependencies { implementation("com.braymon:kotmpeg-core:1.0.1") }
```

Las dos opciones publican el jar y un jar de fuentes, así que el IDE deja navegar el código y leer
el KDoc. **No son intercambiables**: cada coordenada solo resuelve en el repositorio que le
corresponde.

---

# Comportamiento en tiempo de ejecución

Tres contratos que no se ven en las firmas y que causan el 90 % de las sorpresas.

## Contrato de un solo hilo

**Ni el motor de contenedores ni sus muxers/demuxers son seguros entre hilos**, y es intencional:
mantienen una posición de lectura/escritura y buffers compartidos, así que serializar por dentro
costaría rendimiento en el 100 % de los casos para cubrir un uso que no es el habitual.

Un `Muxer`, un `Demuxer`, un `SeekableInput` o un `SeekableOutput` pertenecen **a un solo hilo cada
vez**. Si varios productores tienen que escribir al mismo archivo —lo normal en cuanto hay vídeo y
audio en hilos distintos—, la serialización es responsabilidad de quien integra; si atiendes varias
peticiones a la vez, dale a cada una su propia instancia.

## Memoria al escribir archivos largos

`Mp4Muxer` (MP4 **no** fragmentado) mantiene en RAM una entrada por cada muestra escrita hasta que
se llama a `stop()`, porque las tablas `stts`/`stsz`/`stco` del `moov` no se pueden escribir hasta
conocer el archivo entero. Es un crecimiento real y no acotado: a 30 fps son ~108 000 entradas por
hora de vídeo, más las de audio.

Para archivos largos usa **`mp4Fragmented = true`** o **MKV**. Los dos son incrementales por diseño
—vacían a disco según avanzan— y además sobreviven a un corte de proceso.

**`mp4FastStart` cuesta disco, no RAM, pero cuesta el doble.** El archivo se cierra primero
completo, con su `moov` al final; recolocar ese `moov` al principio significa reescribirlo entero a
un temporal en el mismo directorio y sustituir con él el original, así que durante `stop()`
conviven las dos copias. Para una salida de 4 GiB, cuenta con algo más de 8 GiB libres.

**Pase lo que pase, lo escrito no se pierde.** Si la reescritura falla —lo más probable es
justamente quedarse sin espacio—, el archivo original queda intacto y reproducible, solo que sin
inicio rápido, y `stop()` lanza explicándolo. La sustitución final es un movimiento atómico dentro
del mismo directorio: o el destino queda reemplazado, o no se toca.

## Memoria al *leer* archivos largos

El mismo coste existe en el otro sentido: `Mp4Demuxer` construye el mapa **completo** de muestras
al abrir, antes de entregar el primer paquete, porque es lo que permite hacer seek exacto por
índice. Para un archivo de 2 horas a 60 fps con audio AAC:

| Pista | Muestras | RAM aprox. |
|---|---|---|
| Vídeo 60 fps | ~432 000 | ~26 MB |
| Audio AAC (1024 muestras/paquete) | ~337 000 | ~20 MB |
| **Total** | | **~45 MB** |

Se paga **de golpe al abrir**, no progresivamente. En un móvil eso puede ser una porción notable
del heap de la app, así que tenlo en cuenta al dimensionar si abres archivos muy largos. Con fMP4 el
recorrido además arranca en el byte 0 y atraviesa todos los fragmentos antes de devolver el control.

`MkvDemuxer` no tiene este problema: Matroska se recorre cluster a cluster y el índice `Cues` solo
se consulta al buscar.

## Errores y avisos

Hay dos canales, y confundirlos es la causa de "se convirtió, pero el archivo no tiene audio":

| Canal | Qué es | Qué hacer |
|---|---|---|
| `onWarning: (String) -> Unit` | **No fatal.** Algo se degradó pero la operación sigue: una pista de códec no reconocido que se descarta al leer, una muestra ilegible que se salta, una pista que se pierde al convertir. | Registrarlo, y avisar si afecta a lo que se pidió. Nunca ignorarlo en silencio. |
| Excepciones | **Fatal.** Archivo truncado, corrupto, sin `moov`, un desfase de tiempo que no cabe en el contenedor. | El mensaje nombra el campo o la caja concreta. El archivo queda cerrado. |

```kotlin
MkvKotlin.remux(entrada, salida, onWarning = { println("aviso: $it") })
```

La política declarada es que un archivo dañado produzca **un error claro o un fin de stream**,
nunca un cuelgue ni un consumo de memoria sin control. Hay tres suites dedicadas a hacerla cumplir
—`RobustnessTest`, `UntrustedTableSizesTest` y `LargeFileEdgeCasesTest`— y está descrita en
[`SECURITY.md`](SECURITY.md), junto con cómo reportar un archivo que la burle.

---

# Referencia técnica

## Matriz de soporte

| | MKV | MP4 |
|---|---|---|
| H.264/AVC | ✅ mux/demux (`V_MPEG4/ISO/AVC`, avcC) | ✅ mux/demux (`avc1`) |
| H.265/HEVC | ✅ mux/demux (`V_MPEGH/ISO/HEVC`, hvcC) | ✅ mux/demux (`hvc1`/`hev1`) |
| AAC | ✅ (AudioSpecificConfig) | ✅ (`mp4a` + esds) |
| Opus | ✅ (OpusHead, CodecDelay/SeekPreRoll) | ✅ (`Opus` + dOps) |
| N pistas de audio | ✅ ilimitadas | ✅ ilimitadas |
| B-frames (pts≠dts) | ✅ | ✅ (ctts + edit lists, DTS derivado automáticamente) |
| Índice de búsqueda | ✅ Cues + SeekHead | ✅ stss/stco completos |
| Archivos > 4 GiB | ✅ | ✅ (mdat de 64 bits + co64) |
| Inicio rápido / progresivo | ✅ (por diseño del formato) | ✅ `mp4FastStart` (= `-movflags +faststart`) |
| Fragmentado / streaming | ✅ (por diseño del formato) | ✅ fMP4 lectura y escritura (= `frag_keyframe+empty_moov`) |
| Rotación de pantalla | ✅ (`Projection`/PoseRoll) | ✅ (matriz `tkhd`, visible en ffprobe) |
| Píxeles no cuadrados | ✅ (`DisplayWidth`/`Height`, con `DisplayUnit` resuelto al leer) | ✅ (tamaño de presentación del `tkhd`) |
| Color y HDR10 estático | ✅ (`Colour` + `MasteringMetadata`) | ✅ (`colr` nclx + `mdcv` + `clli`) |
| Concatenación sin recodificar | ✅ | ✅ |

## Equivalencias con FFmpeg

| FFmpeg | Kotmpeg Core |
|---|---|
| `ffmpeg -i in.mp4 -c copy out.mkv` (remux) | `MkvKotlin.remux(in, out)` |
| Demuxer `concat` (unir segmentos sin recodificar) | `MkvKotlin.concat(inputs, output)` |
| `-movflags +faststart` | `createMuxer(..., mp4FastStart = true)` |
| `-movflags frag_keyframe+empty_moov` (fMP4) | `createMuxer(..., mp4Fragmented = true)` |
| `-map` (selección de pistas) | `remux(..., trackFilter = { id -> ... })` |
| `-display_rotation` / matriz de rotación | `TrackInfo.Video(rotationDegrees = 90)` |
| `-color_primaries/-color_trc/-colorspace/-color_range` + HDR10 | `TrackInfo.Video(color = ColorInfo(...))` |
| ffprobe (inspección) | `openDemuxer(file).tracks` / `durationUs` |
| Seek por índice | `Demuxer.seekTo(us)` (Cues / stss) |
| Filtro `amix` / `pan` | `PcmMixer` (mezcla saturada con ganancias, mono↔estéreo) |
| `-ar` / `-ac` sobre PCM | `PcmResampler` (remuestreo lineal en streaming) |

**Detalle del fMP4/CMAF**, por ser la parte menos habitual: `FragmentedMp4Muxer` escribe `ftyp` +
`moov` vacío (`mvex`/`trex`) seguido de pares `moof`+`mdat` —un fragmento por GOP de vídeo, o por
duración configurable si solo hay audio— y cierra con un índice `mfra`. La salida es
**estrictamente append-only**: nunca retrocede a parchear, y por eso sirve igual para grabación a
prueba de cortes que para empujar fragmentos a un empaquetador HLS/DASH. Los B-frames van por
`trun` v1 con offsets de composición firmados y `tfdt` acumulado sin deriva. El demuxer lee fMP4
tanto propio como de FFmpeg: `tfhd`/`tfdt`/`trun` con defaults de `trex`, `default-base-is-moof` y
`ctts` firmados.

## Estructura

Todo el proyecto es un único módulo Gradle, bajo `src/main/kotlin/com/braymon/kotmpeg/`:

```
com/braymon/kotmpeg/
│
├── MkvKotlin.kt         Fachada: createMuxer / openDemuxer / detectFormat / remux / concat.
├── Muxer.kt             Interfaces Muxer y Demuxer.
│
├── ebml/                EBML: lector, escritor e IDs de Matroska (RFC 8794).
├── mkv/                 MkvMuxer / MkvDemuxer (SeekHead, Cues, lacing).
├── mp4/                 ISO/IEC 14496-12: Mp4Muxer (faststart), FragmentedMp4Muxer (fMP4),
│                        Mp4Demuxer, BoxBuilder y SampleEntries (stsd, color/HDR).
├── model/               Modelo canónico: MediaPacket, TrackInfo, códecs, ColorInfo/HDR.
├── codecconfig/         NalUnits (Annex-B ↔ ISO, avcC/hvcC), AacConfig, OpusConfig.
├── audio/               DSP de PCM: PcmResampler, PcmMixer.
├── io/                  SeekableInput / SeekableOutput (File, RAF o FileDescriptor).
└── pipeline/Remuxer.kt  Copia demuxer→muxer con filtro/progreso, y concat.
```

El modelo canónico de datos (`MediaPacket`, `TrackInfo`) usa NALUs con prefijo de longitud de 4
bytes y configuración de códec en formato ISO (avcC/hvcC/ASC/OpusHead), de modo que **remuxear
entre MKV y MP4 es una copia bit a bit sin pérdida**, y cualquier fuente de paquetes alimenta
cualquier contenedor.

## Mapa de la API pública

| Clase / objeto | Para qué |
|---|---|
| `MkvKotlin` | Fachada: `createMuxer` (con `mp4FastStart`/`mp4Fragmented`), `openDemuxer`, `detectFormat`, `remux`, `concat` |
| `Muxer` / `Demuxer` | Interfaces de escritura/lectura de contenedores |
| `MkvMuxer` / `MkvDemuxer` | Matroska directo (si no quieres pasar por la fachada) |
| `Mp4Muxer` / `FragmentedMp4Muxer` / `Mp4Demuxer` | ISO BMFF plano, fMP4 y lectura de ambos |
| `MediaPacket`, `TrackInfo.Video/Audio`, `VideoCodec`, `AudioCodec`, `ContainerFormat` | Modelo de datos canónico (con `rotationDegrees` y `color` por pista de vídeo) |
| `ColorInfo` / `HdrStaticInfo` | Color BT.709/BT.2020, rango, PQ/HLG y HDR10 estático |
| `Remuxer` | Copia de streams demuxer→muxer con filtro/progreso y `concat` de segmentos |
| `PcmResampler` / `PcmMixer` | DSP de audio: remuestreo lineal en streaming, mezcla saturada con ganancias, mono↔estéreo |
| `NalUnits`, `HevcSpsInfo`, `BitReader` | Annex-B ↔ ISO, avcC/hvcC, parser de SPS HEVC |
| `AacConfig`, `OpusConfig` | AudioSpecificConfig y OpusHead/dOps |
| `SeekableInput` / `SeekableOutput` | E/S buffered con seek/patch sobre `File`, `RandomAccessFile` o un `FileDescriptor` |

## Cómo se garantiza la sincronización

- **MKV**: `TimestampScale` estándar de 1 ms, clusters alineados a keyframe con `Cues` para
  búsqueda exacta; los bloques llevan PTS (los B-frames no necesitan más señalización en Matroska).
- **MP4**: `stts` desde DTS, `ctts` para el offset de composición, `stss` para keyframes y **edit
  lists** que alinean el inicio de presentación a cero preservando el offset entre pistas.
- **Fuentes sin DTS** (los encoders por hardware y MKV solo dan PTS): el muxer MP4 deriva un DTS
  monótono espec-correcto a partir de la secuencia de PTS
  (`dts_i = sortedPts_i − max(sortedPts_i − pts_i)`), que garantiza `dts ≤ pts`, preserva
  duraciones y produce offsets `ctts` mínimos no negativos.
- **Opus**: `CodecDelay`/`SeekPreRoll` en MKV y pre-skip en `dOps` en MP4 se convierten entre sí.

## Lo que **no** está implementado, y por qué

Todas son decisiones de alcance, no carencias pendientes:

| Funcionalidad | Aquí | Por qué |
|---|---|---|
| Códecs (H.264/H.265/AAC/Opus) | ❌ | Este proyecto empaqueta streams **ya codificados**. Implementar un códec en la JVM sería lento y enorme; en un móvil la codificación va por el chip, y en un servidor, por lo que ya tengas. |
| Filtros de vídeo/audio (`scale`, `crop`, `overlay`, `blend`) | ❌ | Esto es un muxer, no un motor de edición ni de proceso de señal. El único DSP que hay es el de PCM (`PcmMixer`/`PcmResampler`), y está porque el remuestreo hace falta para casar pistas. |
| Subtítulos y capítulos | ❌ (las pistas ajenas se ignoran limpiamente al leer) | Los flujos objetivo no los generan ni los consumen; añadirlos es posible sobre la misma base si hace falta. |
| Otros códecs (VP9, AV1, MP3, ProRes…) | ❌ | El alcance es deliberado: los cuatro con soporte universal en MKV/MP4 y aceleración garantizada por hardware. Menos superficie = menos bugs. |
| Otros contenedores (AVI, MPEG-TS, MOV antiguos, ASF…) | ❌ | La librería nace exactamente para MKV y MP4/fMP4. (Los WebM se pueden *leer* porque son Matroska, si llevan códecs soportados.) |
| Protocolos de red (RTMP, SRT, HLS push, RTSP…) | ❌ | Capa distinta. El fMP4 de esta librería es el insumo directo de cualquier empaquetador o emisor. |
| Herramienta de línea de comandos | ❌ (es una librería) | El objetivo es integrarse con una API tipada. Nada impide construir una CLI encima: el proyecto no depende de ninguna plataforma. |
| SIMD / ensamblador | ❌ | La JVM no da acceso a eso, y un muxer no lo necesita: el trabajo es E/S y copia de buffers, no aritmética por muestra. |
| Recuperar un MP4 con el `moov` corrupto o ausente | ❌ (falla limpio con "no se encontró la caja moov") | Reconstruir las tablas escaneando el `mdat` significa reimplementar un detector de NALUs por códec y adivinar los tiempos: mucha superficie para un caso que el fMP4 ya cubre por diseño. Los fallos de **pista** individual sí se toleran: una pista ilegible se descarta y el resto del archivo se lee. |
| Entrada/salida por streaming puro (tuberías, sockets) | ❌ (hace falta un destino posicionable) | Los tres muxers vuelven atrás a parchear tamaños, duración e índices al cerrar. Lo que sí está cubierto es cualquier **descriptor ya abierto**, vía `SeekableInput`/`SeekableOutput`. |
| Segmentación e índice `sidx` en fMP4 | ❌ (se escribe `moof`+`mdat` y un `mfra` final) | El fMP4 de aquí es un **único archivo** solo-añadir, que es lo que necesita una grabación a prueba de cortes. Un empaquetador DASH espera además un `sidx` por segmento y los segmentos en archivos separados; eso es trabajo del empaquetador, que recibe este archivo como entrada. |
| Salida sobre el propio archivo de entrada | ❌ (rechazado con un error claro) | El muxer trunca el destino al abrirlo, así que `remux(f, f)` leería un archivo que se reescribe por debajo. Escribe a un temporal y renómbralo. |
| `OutputSamplingFrequency` de Matroska al **escribir** | ❌ (se **lee**, pero no se escribe) | Kotmpeg lee las dos tasas —sin eso, un HE-AAC ajeno se leería a la mitad de su frecuencia— pero al escribir emite un solo `SamplingFrequency` ya con la tasa de salida, que es el dato que importa. No produce desajuste; lo que no es es conformante en sentido estricto. |

---

# Desarrollo

```bash
./gradlew test
```

130 tests. Necesitan **JDK 17+** y nada más; si además tienes **ffmpeg** en el `PATH`, se ejecuta
también la suite de integración, que genera archivos con FFmpeg real, los reescribe con esta
librería en ambas direcciones y valida el resultado con `ffprobe` y una decodificación completa sin
errores. Sin ffmpeg esos tests se omiten solos (`assumeTrue`) y no fallan.

Tres tests miden descriptores abiertos leyendo `/proc/self/fd` y se omiten fuera de Linux. Un
`SKIPPED` en el log es siempre una de esas dos cosas, nunca un fallo tapado — por eso el build los
imprime.

Cómo contribuir, la política de comentarios que `CommentPolicyTest` hace cumplir y qué hacer cuando
cambia la API pública están en [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Licencia

Kotmpeg Core es **software libre bajo licencia MIT** (archivo [`LICENSE`](LICENSE)). En palabras
llanas:

- ✅ Puedes **usarlo, modificarlo y distribuirlo libremente**, también en productos comerciales y
  de código cerrado.
- 📄 Lo que la licencia exige es **conservar el aviso de copyright y el texto de la MIT** en las
  copias o partes sustanciales del código. Eso es todo.
- 🙏 Aparte de la licencia, se **agradece** —sin ser obligatorio— la mención
  ("Hecho con [Kotmpeg](https://github.com/bmontiel-28/Kotmpeg)") en la documentación o los
  créditos.
