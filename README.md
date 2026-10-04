# Kotmpeg Core

[![CI](https://github.com/bmontiel-28/Kotmpeg/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/bmontiel-28/Kotmpeg/actions/workflows/ci.yml)
[![JitPack](https://jitpack.io/v/bmontiel-28/Kotmpeg.svg)](https://jitpack.io/#bmontiel-28/Kotmpeg)
[![Licencia MIT](https://img.shields.io/badge/licencia-MIT-blue.svg)](LICENSE)
[![JVM 17](https://img.shields.io/badge/JVM-17%2B-orange.svg)](#requisitos)
[![Android API 26+](https://img.shields.io/badge/Android-API%2026%2B-3ddc84.svg)](#en-android)

**Lee y escribe contenedores MKV y MP4/fMP4 en Kotlin puro, pensado para grabar en el móvil:
pantalla, audio del sistema y micrófono —mezclados en una pista o cada uno en la suya—, remux sin
recodificar, sincronización de tiempos entre pistas y metadata de rotación, color y HDR10. Sin
dependencias, sin binarios nativos y sin una sola API de plataforma.**

**Versión: 3.0.0 — estable.**

- **Si ya usas la `2.1.1`, tu código no cambia.** La `3.0.0` no quita ni modifica ninguna firma
  pública —ni siquiera hace falta recompilar—: solo añade. Lo que la hace mayor son unos pocos
  cambios de **comportamiento**, todos correcciones, que el [CHANGELOG](CHANGELOG.md) explica uno a
  uno con lo que hay que revisar.
- La API pública está congelada: romperla exige subir la mayor. No depende de que nadie se acuerde
  — `PublicApiTest` compara la superficie pública contra un volcado versionado y falla si se mueve.
- El análisis técnico de la `3.0`, con mediciones antes/después, está en [`ANALISIS.md`](ANALISIS.md).

La librería **no codifica ni decodifica**: empaqueta y desempaqueta streams que ya vienen
comprimidos. Lo único que procesa en crudo es el audio PCM, para poder mezclar micrófono y sistema
antes de codificar. Todo el alcance, con lo que queda fuera y por qué, está en
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
    d.tracks.forEach { println("${it.id}: ${it.name} (${it.language}) default=${it.default}") }
}
```

Y el nivel bajo, cuando ya tienes paquetes codificados (por ejemplo, de `MediaCodec`) y solo
quieres el contenedor:

```kotlin
val muxer = MkvKotlin.synchronizedMuxer(MkvKotlin.createMuxer(File("grabacion.mp4"), mp4Fragmented = true))
val pantalla = muxer.addTrack(TrackInfo.Video(codec = VideoCodec.H264, width = 1080, height = 2400, codecPrivate = avcC))
val audio = muxer.addTrack(TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 2, codecPrivate = asc))
muxer.start()
// Desde el callback de cada codificador, en su propio hilo:
muxer.writePacket(MediaPacket(pantalla, NalUnits.annexBToLengthPrefixed(salidaH264), ptsUs, isKeyFrame = esIdr))
muxer.writePacket(MediaPacket(audio, salidaAac, ptsUs))
// Al terminar:
muxer.stop()
```

## ¿Es este proyecto para ti?

| Si necesitas… | ¿Sirve? |
|---|---|
| Grabar pantalla con audio del sistema y micrófono, **mezclados en una pista o por separado** | ✅ Es el caso central, y una [regla del proyecto](#audio-de-grabación-combinado-o-por-separado) |
| Escribir un contenedor con paquetes de un codificador por hardware (`MediaCodec`) | ✅ Es el caso central |
| Generar fMP4 para grabación a prueba de cortes o para HLS/DASH | ✅ Es el caso central |
| Convertir entre MKV y MP4 sin recodificar ni perder sincronía | ✅ Es el caso central |
| Leer un MKV/MP4 pista a pista, con seek exacto | ✅ Es el caso central |
| Unir segmentos ya codificados sin recodificar | ✅ Es el caso central |
| Comprimir o descomprimir vídeo/audio | ❌ No hay ningún códec aquí, [ver por qué](#lo-que-no-está-implementado-y-por-qué) |
| VP9, AV1, MP3, AVI, MPEG-TS… | ❌ Solo H.264/H.265 + AAC/Opus sobre MKV/MP4 |
| Filtros de vídeo (`scale`, `crop`, `overlay`) | ❌ Esto es un muxer, no un motor de proceso de vídeo |
| Emitir por RTMP/SRT/RTSP | ❌ Es otra capa; esto produce el fMP4 que alimenta al emisor |

## Dónde encaja

```
 micrófono (PCM) ─┐
                  ├─► PcmResampler / PcmMixer ─► codificador AAC ─┐
 sistema   (PCM) ─┘   (solo si se quiere mezcla)                  │
                                                                  ├─► Kotmpeg Core ─► .mkv / .mp4
 micrófono / sistema por separado ───────────► codificador AAC ───┤      (muxer)
 pantalla ───────────────────────────────────► codificador H.264 ─┘
```

Al muxer entran paquetes **ya comprimidos** (H.264/H.265, AAC/Opus) con sus marcas de tiempo, y
sale un archivo correcto y sincronizado, o al revés. De dónde salgan esos paquetes —un codificador
por hardware, un socket, otro contenedor— le da igual: el modelo de datos (`MediaPacket`,
`TrackInfo`) es el mismo. Antes del codificador, y solo para el audio, la librería trae lo
necesario para combinar las dos fuentes de una grabación.

## Glosario rápido

| Término | Qué significa |
|---|---|
| **Contenedor** | El "envase" del archivo: MKV o MP4. Guarda las pistas y los tiempos, pero **no** comprime nada. |
| **Códec** | Lo que sí comprime: H.264/H.265 para vídeo, AAC/Opus para audio. **Esta librería no implementa ninguno**: los recibe ya comprimidos. |
| **PCM** | Audio sin comprimir: lo que entregan `AudioRecord` y la captura de audio del sistema, antes del codificador. Es lo único que la librería procesa en crudo. |
| **Muxear / demuxear** | Meter pistas ya comprimidas en un contenedor / sacarlas. Ninguna de las dos toca la compresión. |
| **Remuxear** | Cambiar de contenedor sin recodificar (MKV → MP4). Casi instantáneo y **sin pérdida**. Es `ffmpeg -c copy`. |
| **Keyframe** | Fotograma completo, que se decodifica solo. Solo se puede empezar a reproducir o cortar limpio en uno. |
| **fMP4** | MP4 fragmentado: se escribe en trozos autocontenidos. Sobrevive a que se mate el proceso y es lo que consumen HLS/DASH. |

Dos más que salen en los tiempos: **PTS** (cuándo se *muestra* un fotograma) y **DTS** (cuándo se
*decodifica*). Con B-frames no coinciden, y esa diferencia la resuelve la librería —ver
[sincronización](#cómo-se-garantiza-la-sincronización).

---

# Audio de grabación: combinado o por separado

**Regla del proyecto, que no se negocia en ningún cambio: el audio del sistema y el del micrófono se
pueden grabar combinados en una pista, cada uno en la suya, o cualquiera de los dos solo — y también
la mezcla junto a las dos pistas sueltas.** `RecordingAudioLayoutsTest` la hace cumplir en los tres
contenedores y tras convertir entre ellos; si un cambio la rompe, el cambio está mal.

| Montaje | Pistas de audio en el archivo | Cómo |
|---|---|---|
| Mezcla | `Mezcla` | `PcmMixer.mix` antes de un único codificador |
| Solo micrófono | `Micrófono` | una pista |
| Solo sistema | `Audio del sistema` | una pista |
| Separadas | `Micrófono` + `Audio del sistema` | una pista por fuente, un codificador por fuente |
| Mezcla y separadas | `Mezcla` + `Micrófono` + `Audio del sistema` | las dos cosas a la vez |

### Mezclar en una pista

Micrófono y sistema rara vez llegan iguales: lo normal en Android es micrófono **mono a 44,1 kHz**
y sistema **estéreo a 48 kHz**. Se igualan y se suman antes de codificar:

```kotlin
val aTasaDelSistema = PcmResampler(inputRate = 44_100, outputRate = 48_000, channels = 1)

fun mezclar(sistemaEstereo48k: ShortArray, microfonoMono44k: ShortArray): ShortArray {
    val micA48k = aTasaDelSistema.resample(microfonoMono44k)
    val micEstereo = PcmMixer.convertChannels(micA48k, from = 1, to = 2)
    return PcmMixer.mix(listOf(sistemaEstereo48k, micEstereo))           // o gains = listOf(1f, 0.8f)
}
// Al terminar la grabación: aTasaDelSistema.flush() devuelve el último frame retenido.
```

- **La suma satura**, no se envuelve: dos señales fuertes dan `32767`, nunca un chasquido de signo
  cambiado.
- **Alimenta bloques que cubran el mismo tiempo.** `mix` trata la fuente más corta como silencio
  a partir de su final, así que si los bloques no cuadran, se oye un hueco. El remuestreador es
  exacto en enteros: tras N frames de entrada ha emitido exactamente los que corresponden, sin
  deriva en sesiones de horas, así que no hace falta corregir nada a mano.
- **Rendimiento**: mezclar una hora de estéreo a 48 kHz en bloques de 10 ms cuesta unos 0,3 s de
  CPU sin ganancias (la ruta es entera, ~6 veces más rápida que en la `2.x`) y unos 2,3 s con
  ganancias, medido en un equipo de desarrollo. El resultado es idéntico bit a bit al de la `2.x`.

### Cada fuente en su pista

Una pista por fuente, con nombre y **exactamente una predeterminada** (si no, cada reproductor elige
una distinta):

```kotlin
val mezcla = muxer.addTrack(TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 2,
    codecPrivate = ascMezcla, name = "Mezcla", default = true))
val microfono = muxer.addTrack(TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 1,
    codecPrivate = ascMicro, name = "Micrófono", default = false))
val sistema = muxer.addTrack(TrackInfo.Audio(codec = AudioCodec.AAC, sampleRate = 48_000, channelCount = 2,
    codecPrivate = ascSistema, name = "Audio del sistema", default = false))
```

El nombre, el idioma y la pista predeterminada sobreviven en MKV, MP4 y fMP4, y al convertir entre
ellos. Cada pista puede empezar en su propio instante: el desfase entre pistas se conserva.

> La captura del audio del sistema en Android es `AudioPlaybackCapture` (Android 10+) y la decide
> la app; la librería recibe el PCM o los paquetes AAC resultantes igual que los del micrófono.

---

# Instalación

## Requisitos

**JDK 17 o superior** y, si tu proyecto es Kotlin, **Kotlin 1.9 o superior**. El código de
producción **no depende de nada**: ni de terceros, ni de ningún SDK, solo de la biblioteca estándar
de Kotlin y de `java.io`/`java.nio`.

El artefacto genera bytecode JVM 17, así que quien lo consuma tiene que compilar a 17 o más. Se
compila en nivel de lenguaje Kotlin 2.0 y declara `kotlin-stdlib:2.0.21`, precisamente para no
imponer a la app un compilador más nuevo del que tiene: el CI compila y ejecuta un proyecto
consumidor con Kotlin 1.9 (`compat/kotlin-1.9`) contra cada artefacto.

## En Android

Es un jar de Kotlin/JVM, no un AAR, y no declara manifiesto, recursos ni permisos.

| Requisito | Valor |
|---|---|
| `minSdk` soportado | **26 (Android 8.0)** |
| Nivel de Java de tu app | 17 o superior (`compileOptions` y `kotlinOptions`) |
| Kotlin de tu app | 1.9 o superior |
| Permisos que exige la librería | Ninguno |
| Dependencias que arrastra | Ninguna |

Toda la superficie de plataforma que usa —`java.io.File`, `RandomAccessFile`, `FileDescriptor`,
`ByteBuffer`, `FileChannel`, `java.nio.file.Files`, `java.util.zip.Inflater`— existe en la API 26.
No es una suposición: `AndroidApiCompatibilityTest` recorre cada clase compilada contra la firma
oficial de la API 26 de Android y falla si aparece una sola llamada del JDK que allí no exista. Así
se encontró que `OpusConfig` usaba una sobrecarga de `ByteBuffer.position(int)` que Android no
tiene. La cota de 26 la pone `java.nio.file`, que se usa en la sustitución atómica de
`mp4FastStart`.

Lo que conviene saber al integrarla:

- **Aquí no hay códecs.** La codificación la hace el chip vía `MediaCodec`; esta librería recibe los
  paquetes que salen de ahí. `NalUnits.annexBToLengthPrefixed` convierte la salida de vídeo al
  formato del contenedor, y la configuración de códec (`avcC`/`hvcC`/ASC/OpusHead) viaja en
  `TrackInfo.codecPrivate`.
- **Los codificadores entregan desde hilos distintos.** Envuelve el muxer con
  `MkvKotlin.synchronizedMuxer(...)` y llama a `writePacket` desde cada callback.
- **Las marcas de tiempo de `MediaCodec` se pasan tal cual**, aunque cuenten desde el arranque del
  dispositivo, y **en el orden en que lleguen**: los tres muxers empiezan la línea de tiempo en el
  paquete más temprano de todas las pistas, aunque no sea el primero en llegar (lo normal cuando el
  codificador de vídeo tarda más que el de audio).
- **El audio no necesita `isKeyFrame`**: AAC y Opus se escriben siempre como muestras
  independientes.
- **Puedes reutilizar tu buffer** entre paquetes: ningún muxer guarda una referencia al
  `ByteArray` de un `MediaPacket` después de `writePacket`: cuando tienen que retener muestras —el
  fMP4 hasta cerrar cada fragmento, el MKV y el fMP4 al arrancar— guardan una copia.
- **`codecPrivate`**: el audio sin él recibe una configuración por defecto (ASC u OpusHead) igual en
  los tres contenedores; el vídeo sin `avcC`/`hvcC` se rechaza en `addTrack`, también en MKV, porque
  los reproductores de Android no pueden abrirlo.
- **`SeekableInput`/`SeekableOutput` aceptan un `FileDescriptor` ya abierto**, así que un
  `ParcelFileDescriptor` de `ContentResolver` sirve directamente para MediaStore/SAF.
- **Para grabaciones largas, `mp4Fragmented = true` o MKV**: sobreviven a que se mate el proceso y
  no acumulan índice en memoria. En un móvil con poca RAM, `MkvKotlin.createFragmentedMp4Muxer(file,
  maxFragmentBytes = 16L * 1024 * 1024)` acota lo que retiene cada fragmento. `SeekableOutput.sync()`
  fuerza además los datos al disco si la app quiere protegerse de un corte de batería.

## Cómo añadirla a tu proyecto

### Opción A — desde JitPack

JitPack compila la librería a partir de un **tag** de este repositorio:

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
    implementation("com.github.bmontiel-28:Kotmpeg:3.0.0")
}
```

El artefacto se llama **`Kotmpeg`**, como el repositorio, y no `kotmpeg-core`: JitPack solo usa la
forma multi-módulo `com.github.Usuario.Repo:Modulo:Tag` cuando el build deja varios artefactos, y
aquí sale uno. Escribirla con punto antes de `Kotmpeg` da un `Could not find`.

> **La versión de la coordenada es el nombre del tag**, no el `version` del `build.gradle.kts`.
> Las disponibles, con el estado de su build, están en
> [`jitpack.io/#bmontiel-28/Kotmpeg`](https://jitpack.io/#bmontiel-28/Kotmpeg). Si la versión
> que anuncia este README todavía no aparece ahí, está en preparación y aún no tiene tag: usa la
> última de la lista.

### Opción B — desde tu Maven local

Útil para probar un cambio de la librería en tu app antes de publicar:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); mavenCentral() } }
// build.gradle.kts
dependencies { implementation("com.braymon:kotmpeg-core:3.0.0") }
```

Las dos opciones publican el jar y un jar de fuentes, así que el IDE deja navegar el código y leer
el KDoc. **No son intercambiables**: cada coordenada solo resuelve en su repositorio.

---

# Comportamiento en tiempo de ejecución

## Contrato de un solo hilo

**Los muxers, demuxers, `SeekableInput`/`SeekableOutput` y `PcmResampler` no son seguros entre
hilos**, y es intencional: serializar por dentro costaría rendimiento en el 100 % de los casos.

Si varios productores escriben al mismo archivo —lo normal en cuanto hay vídeo y audio en hilos
distintos—, envuelve el muxer con `MkvKotlin.synchronizedMuxer`, que serializa cada operación con
un único cerrojo. Un `PcmResampler` por fuente, usado desde el hilo de esa fuente. `PcmMixer` no
tiene estado y se puede llamar desde cualquier hilo.

## Memoria al escribir archivos largos

`Mp4Muxer` (MP4 **no** fragmentado) mantiene en RAM una entrada por muestra hasta `stop()`, porque
las tablas del `moov` no se pueden escribir hasta conocer el archivo entero. Cada muestra ocupa unos
37 bytes en columnas de tipos primitivos, y la tabla crece por páginas, sin copiarse entera al
llenarse: dos horas a 60 fps con audio AAC (~770 000 muestras) son unos **34 MB** antes de `stop()`
(54 MB en la `2.x`).

Para grabaciones largas usa **`mp4Fragmented = true`** o **MKV**: vacían a disco según avanzan y
sobreviven a un corte de proceso. El fMP4 sí retiene las muestras de **un** fragmento —su `moof` va
delante de ellas—, como mucho un GOP, 10 s o 64 MB; los dos límites se ajustan con
`MkvKotlin.createFragmentedMp4Muxer`.

Al **arrancar**, el MKV y el fMP4 retienen además los paquetes que lleguen hasta que todas las
pistas han entregado el primero, para empezar la línea de tiempo en el más temprano. En la práctica
es la diferencia de latencia entre codificadores, unos cientos de milisegundos; el tope es de 5 s o
16 MB en MKV y los mismos dos límites del fragmento en fMP4, por si una pista no llega a producir
nada.

**`mp4FastStart` cuesta el doble de disco.** El archivo se cierra primero completo, con su `moov` al
final; recolocarlo al principio es reescribirlo entero a un temporal en el mismo directorio, forzar
ese temporal al disco y sustituir con él el original. Para una salida de 4 GiB, cuenta con algo más
de 8 GiB libres. **Pase lo que pase, lo escrito no se pierde**: si la reescritura falla, el original
queda intacto y reproducible, sin inicio rápido, y `stop()` lanza explicándolo.

## Memoria al *leer* archivos largos

`Mp4Demuxer` construye el mapa **completo** de muestras al abrir, porque es lo que permite el seek
exacto por índice. Para un archivo de 2 horas a 60 fps con audio AAC:

| Pista | Muestras | RAM aprox. |
|---|---|---|
| Vídeo 60 fps | ~432 000 | ~16 MB |
| Audio AAC (1024 muestras/paquete) | ~337 000 | ~13 MB |
| **Total** (medido) | | **~29 MB** |

Se paga **al abrir**: en ese archivo, unos 0,3 s (la `2.x` tardaba más de 10 s y ocupaba ~48 MB).
Con fMP4 el recorrido además atraviesa todos los fragmentos. `MkvDemuxer` no tiene este coste:
Matroska se recorre cluster a cluster y el índice `Cues` solo se consulta al buscar.

## Errores y avisos

| Canal | Qué es | Qué hacer |
|---|---|---|
| `onWarning: (String) -> Unit` | **No fatal.** Algo se degradó pero la operación sigue: una pista que no se puede leer, una muestra que se salta, un archivo cortado. | Registrarlo, y avisar si afecta a lo que se pidió. Nunca ignorarlo en silencio. |
| Excepciones | **Fatal.** Cabecera corrupta, MP4 sin `moov`, un desfase de tiempo que no cabe en el contenedor, un parámetro inválido, un error de E/S real. | El mensaje nombra el campo o la caja concreta. El archivo queda cerrado. |

Un archivo **truncado** —la grabación se interrumpió— no es fatal en ningún contenedor: se lee hasta
el último paquete completo, el stream termina limpio y el corte se avisa por `onWarning`.

```kotlin
MkvKotlin.remux(entrada, salida, onWarning = { println("aviso: $it") })
```

La política es que un archivo dañado produzca **un error claro o un fin de stream**, nunca un
cuelgue ni un consumo de memoria sin control. La hacen cumplir `RobustnessTest`,
`UntrustedTableSizesTest`, `LargeFileEdgeCasesTest` y `Mp4HeaderHardeningTest`, y está descrita en
[`SECURITY.md`](SECURITY.md).

---

# Referencia técnica

## Matriz de soporte

| | MKV | MP4 |
|---|---|---|
| H.264/AVC | ✅ mux/demux (`V_MPEG4/ISO/AVC`, avcC) | ✅ mux/demux (`avc1`) |
| H.265/HEVC | ✅ mux/demux (`V_MPEGH/ISO/HEVC`, hvcC) | ✅ mux/demux (`hvc1`/`hev1`) |
| AAC | ✅ (AudioSpecificConfig) | ✅ (`mp4a` + esds) |
| HE-AAC (SBR/PS) | ✅ (`SamplingFrequency` del núcleo + `OutputSamplingFrequency`) | ✅ (tasa real desde el ASC) |
| Opus | ✅ (OpusHead, CodecDelay/SeekPreRoll) | ✅ (`Opus` + dOps) |
| N pistas de audio (mezcla, micrófono, sistema…) | ✅ ilimitadas | ✅ ilimitadas |
| Idioma, nombre y pista predeterminada | ✅ (`Language`, `Name`, `FlagDefault`) | ✅ (`mdhd`, `udta`/`name`, bit `track_enabled` del `tkhd`) |
| B-frames (pts≠dts) | ✅ | ✅ (ctts + edit lists, DTS derivado automáticamente) |
| Vídeo de frecuencia variable (captura de pantalla) | ✅ | ✅ (duraciones exactas también entre fragmentos fMP4) |
| Índice de búsqueda | ✅ Cues + SeekHead | ✅ stss/stco completos |
| Archivos > 4 GiB | ✅ | ✅ (mdat de 64 bits + co64) |
| Inicio rápido / progresivo | ✅ (por diseño del formato) | ✅ `mp4FastStart` (= `-movflags +faststart`) |
| Fragmentado / a prueba de cortes | ✅ (por diseño del formato) | ✅ fMP4 lectura y escritura (= `frag_keyframe+empty_moov`) |
| Rotación de pantalla | ✅ (`Projection`/PoseRoll) | ✅ (matriz `tkhd`, visible en ffprobe) |
| Píxeles no cuadrados | ✅ (`DisplayWidth`/`Height`, con `DisplayUnit` resuelto al leer) | ✅ (tamaño de presentación del `tkhd`) |
| Color y HDR10 estático | ✅ (`Colour` + `MasteringMetadata`) | ✅ (`colr` nclx + `mdcv` + `clli`) |
| Compresión de contenido | ✅ lectura (`ContentEncoding`: eliminación de cabecera y zlib) | — (no existe en MP4) |
| Concatenación sin recodificar | ✅ | ✅ |

## Equivalencias con FFmpeg

| FFmpeg | Kotmpeg Core |
|---|---|
| `ffmpeg -i in.mp4 -c copy out.mkv` (remux) | `MkvKotlin.remux(in, out)` |
| Demuxer `concat` (unir segmentos sin recodificar) | `MkvKotlin.concat(inputs, output)` |
| `-movflags +faststart` | `createMuxer(..., mp4FastStart = true)` |
| `-movflags frag_keyframe+empty_moov` (fMP4) | `createMuxer(..., mp4Fragmented = true)` |
| `-map` (selección de pistas) | `remux(..., trackFilter = { id -> ... })` |
| `-metadata:s:a:0 language=spa` / `title=` | `TrackInfo.Audio(language = "spa", name = "Micrófono")` |
| `-disposition:a:1 0` | `TrackInfo.Audio(default = false)` |
| `-display_rotation` / matriz de rotación | `TrackInfo.Video(rotationDegrees = 90)` |
| `-color_primaries/-color_trc/-colorspace/-color_range` + HDR10 | `TrackInfo.Video(color = ColorInfo(...))` |
| ffprobe (inspección) | `openDemuxer(file).tracks` / `durationUs` |
| Seek por índice | `Demuxer.seekTo(us)` (Cues / stss, por bisección) |
| Filtro `amix` / `pan` | `PcmMixer` (mezcla saturada con ganancias, mono↔estéreo) |
| `-ar` / `-ac` sobre PCM | `PcmResampler` (remuestreo lineal en streaming, fase exacta) |

**Detalle del fMP4/CMAF**, por ser la parte menos habitual: `FragmentedMp4Muxer` escribe `ftyp` +
`moov` vacío (`mvex`/`mehd`/`trex`) seguido de pares `moof`+`mdat` —un fragmento por GOP de vídeo,
o por duración configurable si solo hay audio— y cierra con un índice `mfra`. Los datos se escriben
**solo hacia delante**, que es lo que lo hace servir igual para grabación a prueba de cortes y para
empujar fragmentos a un empaquetador HLS/DASH. Lo único que se reescribe hacia atrás son los campos
de duración de la cabecera —`mvhd`, `tkhd`, `mehd` y la lista de edición—, que se reservan a cero y
se rellenan al cerrar; si el proceso muere antes, se quedan a cero y lo grabado hasta el último
fragmento completo se reproduce sin más. La duración del último fotograma de cada fragmento se toma
del keyframe que abre el siguiente, así que el vídeo de frecuencia variable de una captura de
pantalla no desalinea los tiempos. Los B-frames van por `trun` v1 con offsets de composición
firmados. El demuxer lee fMP4 propio y de FFmpeg.

## Estructura

Todo el proyecto es un único módulo Gradle, bajo `src/main/kotlin/com/braymon/kotmpeg/`:

```
com/braymon/kotmpeg/
│
├── MkvKotlin.kt         Fachada: createMuxer / openDemuxer / detectFormat / remux / concat /
│                        synchronizedMuxer.
├── Muxer.kt             Interfaces Muxer y Demuxer.
├── TimelineGate.kt      Retención de arranque: origen común de los muxers que escriben en vivo.
│
├── audio/               PCM de grabación: PcmMixer (mezcla micrófono + sistema, canales) y
│                        PcmResampler (remuestreo en streaming con fase exacta).
├── ebml/                EBML: lector, escritor e IDs de Matroska (RFC 8794).
├── mkv/                 MkvMuxer / MkvDemuxer (SeekHead, Cues, lacing, ContentEncoding).
├── mp4/                 ISO/IEC 14496-12: Mp4Muxer (faststart), FragmentedMp4Muxer (fMP4),
│                        Mp4Demuxer, BoxBuilder, SampleEntries y SampleTable.
├── model/               Modelo canónico: MediaPacket, TrackInfo, códecs, ColorInfo/HDR.
├── codecconfig/         NalUnits (Annex-B ↔ ISO, avcC/hvcC), AacConfig, OpusConfig.
├── io/                  SeekableInput / SeekableOutput (File, RAF o FileDescriptor).
└── pipeline/Remuxer.kt  Copia demuxer→muxer con filtro/progreso, y concat.
```

El modelo canónico (`MediaPacket`, `TrackInfo`) usa NALUs con prefijo de longitud de 4 bytes y
configuración de códec en formato ISO (avcC/hvcC/ASC/OpusHead), de modo que **remuxear entre MKV y
MP4 es una copia bit a bit sin pérdida**, y cualquier fuente de paquetes alimenta cualquier
contenedor.

## Mapa de la API pública

| Clase / objeto | Para qué |
|---|---|
| `MkvKotlin` | Fachada: `createMuxer` (con `mp4FastStart`/`mp4Fragmented`), `createFragmentedMp4Muxer` (con límites de memoria), `openDemuxer`, `detectFormat`, `remux`, `concat`, `synchronizedMuxer` |
| `Muxer` / `Demuxer` | Interfaces de escritura/lectura de contenedores |
| `MkvMuxer` / `MkvDemuxer` | Matroska directo (si no quieres pasar por la fachada) |
| `Mp4Muxer` / `FragmentedMp4Muxer` / `Mp4Demuxer` | ISO BMFF plano, fMP4 y lectura de ambos |
| `MediaPacket`, `TrackInfo.Video/Audio`, `VideoCodec`, `AudioCodec`, `ContainerFormat` | Modelo de datos canónico (nombre, idioma y pista predeterminada por pista; rotación y color en vídeo) |
| `ColorInfo` / `HdrStaticInfo` | Color BT.709/BT.2020, rango, PQ/HLG y HDR10 estático |
| `PcmMixer` / `PcmResampler` | Audio de grabación: mezcla saturada con ganancias, mono↔estéreo, remuestreo en streaming |
| `Remuxer` | Copia de streams demuxer→muxer con filtro/progreso y `concat` de segmentos |
| `NalUnits`, `HevcSpsInfo`, `BitReader` | Annex-B ↔ ISO, avcC/hvcC, parser de SPS HEVC |
| `AacConfig`, `OpusConfig` | AudioSpecificConfig y OpusHead/dOps |
| `SeekableInput` / `SeekableOutput` | E/S buffered con seek/patch sobre `File`, `RandomAccessFile` o un `FileDescriptor`; `sync()` para forzar los datos al almacenamiento |

## Cómo se garantiza la sincronización

- **Origen común**: los tres muxers empiezan la línea de tiempo en el paquete más temprano de todas
  las pistas, así que admiten marcas de un reloj absoluto (las de `MediaCodec`) sin declarar
  duraciones absurdas, y conservan el desfase entre pistas que arrancan en instantes distintos. El
  MP4 lo calcula al cerrar; el MKV y el fMP4, que escriben en vivo, retienen los primeros paquetes
  hasta que cada pista ha entregado el suyo, porque con dos codificadores el primero que llega no
  siempre es el más temprano.
- **MKV**: `TimestampScale` estándar de 1 ms, clusters alineados a keyframe con `Cues` para
  búsqueda exacta; los bloques llevan PTS.
- **MP4**: `stts` desde DTS, `ctts` para el offset de composición, `stss` para keyframes y **edit
  lists** que alinean el inicio de presentación a cero y compensan el cebado del codificador.
- **Fuentes sin DTS** (los encoders por hardware y MKV solo dan PTS): el muxer MP4 deriva un DTS
  monótono a partir de la secuencia de PTS (`dts_i = sortedPts_i − max(sortedPts_i − pts_i)`), que
  garantiza `dts ≤ pts`, preserva duraciones y produce offsets `ctts` mínimos no negativos.
- **Audio mezclado**: `PcmResampler` lleva la fase en enteros, así que el micrófono remuestreado no
  se adelanta ni se retrasa respecto al sistema aunque la grabación dure horas.
- **Opus**: `CodecDelay`/`SeekPreRoll` en MKV y pre-skip en `dOps` en MP4 se convierten entre sí.

## Lo que **no** está implementado, y por qué

Todas son decisiones de alcance, no carencias pendientes:

| Funcionalidad | Aquí | Por qué |
|---|---|---|
| Códecs (H.264/H.265/AAC/Opus) | ❌ | Este proyecto empaqueta streams **ya codificados**. En un móvil la codificación va por el chip, y en un servidor, por lo que ya tengas. |
| Filtros de vídeo (`scale`, `crop`, `overlay`) | ❌ | Esto es un muxer, no un motor de edición. El único proceso de señal es el de audio PCM (`PcmMixer`/`PcmResampler`), y existe por la regla de audio: mezclar micrófono y sistema antes de codificar. |
| Subtítulos y capítulos | ❌ (las pistas ajenas se ignoran limpiamente al leer) | Los flujos objetivo no los generan ni los consumen; añadirlos es posible sobre la misma base. |
| Otros códecs (VP9, AV1, MP3, ProRes…) | ❌ | Alcance deliberado: los cuatro con soporte universal en MKV/MP4 y aceleración garantizada por hardware. |
| Otros contenedores (AVI, MPEG-TS, MOV antiguos…) | ❌ | La librería nace exactamente para MKV y MP4/fMP4. (Los WebM se pueden *leer* porque son Matroska.) |
| Protocolos de red (RTMP, SRT, HLS push, RTSP…) | ❌ | Capa distinta. El fMP4 de esta librería es el insumo directo de cualquier empaquetador o emisor. |
| Herramienta de línea de comandos | ❌ (es una librería) | El objetivo es integrarse con una API tipada. |
| SIMD / ensamblador | ❌ | La JVM no da acceso a eso; el trabajo es sobre todo E/S y copia de buffers. |
| Recuperar un MP4 con el `moov` corrupto o ausente | ❌ (falla limpio con "no se encontró la caja moov") | Reconstruir las tablas escaneando el `mdat` es mucha superficie para un caso que el fMP4 ya cubre por diseño. Una pista ilegible sí se tolera: se descarta y el resto se lee. |
| Entrada/salida por streaming puro (tuberías, sockets) | ❌ (hace falta un destino posicionable) | Los muxers vuelven atrás a parchear tamaños al cerrar. Sí vale cualquier **descriptor ya abierto**. |
| Segmentación e índice `sidx` en fMP4 | ❌ (se escribe `moof`+`mdat` y un `mfra` final) | El fMP4 de aquí es un **único archivo** solo-añadir; segmentar es trabajo del empaquetador. |
| Salida sobre el propio archivo de entrada | ❌ (rechazado con un error claro) | El muxer trunca el destino al abrirlo. Escribe a un temporal y renómbralo. |
| Cifrado y compresión bzlib/lzo de Matroska | ❌ (la pista se descarta con aviso) | Entregar esos bloques sin deshacerlos sería entregar basura al decodificador. La eliminación de cabecera y zlib, que sí aparecen en archivos reales, se leen. |

---

# Desarrollo

```bash
./gradlew test
```

211 tests. Necesitan **JDK 17+**; si además tienes **ffmpeg** en el `PATH`, se ejecuta también la
suite de integración, que genera archivos con FFmpeg real, los reescribe con esta librería en ambas
direcciones y valida el resultado con `ffprobe` y una decodificación completa sin errores. Sin
ffmpeg esos tests se omiten solos (`assumeTrue`).

Tres tests comprueban que una operación fallida no deja archivos abiertos: en Linux leyendo
`/proc/self/fd` y en Windows comprobando que el archivo se deja renombrar, algo que Windows impide
mientras está abierto. En macOS no hay forma fiable de medirlo y se omiten. Otros dos, de HE-AAC,
necesitan un FFmpeg con codificador HE (`aac_mf` o `libfdk_aac`). Un `SKIPPED` en el log es siempre
una de esas causas, nunca un fallo tapado — por eso el build los imprime.

Cuatro tests no comprueban una funcionalidad sino una **política** del proyecto:

| Test | Qué protege |
|---|---|
| `RecordingAudioLayoutsTest` | La regla de audio: mezcla, micrófono, sistema, separadas y mezcla+separadas en todos los contenedores |
| `PublicApiTest` | La superficie pública, congelada en `public-api.txt` |
| `AndroidApiCompatibilityTest` | Que el bytecode solo use API del JDK presente en Android 8.0 (sus dependencias son solo de test) |
| `CommentPolicyTest` | La forma de documentar el código |

Además, el CI publica el artefacto en el Maven local y compila y ejecuta con él
`compat/kotlin-1.9`, una app mínima en Kotlin 1.9 que graba pantalla con mezcla, micrófono y
sistema en los tres contenedores. Es la que avisa si la librería se publica con un Kotlin más nuevo
del que las apps pueden leer. Lleva su propio wrapper, fijado en Gradle 8, porque el plugin de
Kotlin 1.9 no funciona con Gradle 9; se lanza desde su carpeta:

```bash
./gradlew publishToMavenLocal
cd compat/kotlin-1.9
sh gradlew run -PkotmpegVersion=3.0.0
```

En Windows, el último paso es `.\gradlew.bat run -PkotmpegVersion=3.0.0`.

Cómo contribuir y qué hacer cuando cambia la API pública está en [`CONTRIBUTING.md`](CONTRIBUTING.md).

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
