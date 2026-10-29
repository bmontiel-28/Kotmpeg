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

**Versión: 4.1.0**

- **La `4.1.0` hace que una grabación cortada siga sirviendo.** Si el proceso muere antes de
  `stop()`, el MKV y el MP4 fragmentado se abren con su duración y se pueden recorrer hasta el
  último cluster o fragmento cerrado, sin reempaquetarlos, y `Kotmpeg.repair` los cierra en el
  sitio. Ver [grabaciones interrumpidas](#grabaciones-interrumpidas).
- **La `4.0.0` es la versión que cierra todo lo pendiente, y cambia la API.** La fachada se llama
  `Kotmpeg` (antes `MkvKotlin`); las opciones de escritura van en un bloque
  ([`MuxerOptions`](#opciones-de-escritura)) en vez de en parámetros sueltos; y `TrackInfo` y
  `ColorInfo` se construyen con los datos obligatorios y un bloque para el resto. Así, lo que se
  añada en adelante —una opción, un campo de pista— ya no rompe a nadie.
- **Y corrige comportamientos**: el cebado del audio llega igual desde MP4 que desde MKV, `seekTo`
  coloca las demás pistas por tiempo de presentación y cae siempre en un keyframe, `concat` rechaza
  segmentos con cabeceras distintas, `.webm` ya no produce un archivo que nadie reproduce, el
  muxer elige solo la pista predeterminada de cada tipo, una pista que arranca tarde o se pausa da
  en fMP4 los mismos tiempos que en MP4 plano, y un `remux` o `concat` que no puede salir bien deja
  intacta la salida que ya existía. El [CHANGELOG](CHANGELOG.md) da, para cada cambio, el paso exacto de
  migración.
- La API pública está congelada: romperla exige subir la mayor. No depende de que nadie se acuerde
  — `PublicApiTest` compara la superficie pública contra un volcado versionado y falla si se mueve.
- El análisis técnico de cada versión mayor, con mediciones antes/después, está en
  [`ANALISIS.md`](ANALISIS.md).

La librería **no codifica ni decodifica**: empaqueta y desempaqueta streams que ya vienen
comprimidos. Lo único que procesa en crudo es el audio PCM, para poder mezclar micrófono y sistema
antes de codificar. Todo el alcance, con lo que queda fuera y por qué, está en
[Lo que no está implementado](#lo-que-no-está-implementado-y-por-qué).

## En 30 segundos

```kotlin
// Convertir MKV ↔ MP4 sin recodificar. Milisegundos, sin pérdida de calidad.
Kotmpeg.remux(File("entrada.mkv"), File("salida.mp4"))

// Unir segmentos ya codificados, tampoco recodifica.
Kotmpeg.concat(listOf(File("p1.mkv"), File("p2.mkv")), File("completo.mp4"))

// Inspeccionar un archivo, como ffprobe.
Kotmpeg.openDemuxer(File("clip.mp4")).use { d ->
    println("${d.durationUs / 1000} ms, ${d.tracks.size} pistas")
    d.tracks.forEach { println("${it.id}: ${it.name} (${it.language}) default=${it.default}") }
}
```

Y el nivel bajo, cuando ya tienes paquetes codificados (por ejemplo, de `MediaCodec`) y solo
quieres el contenedor:

```kotlin
val muxer = Kotmpeg.synchronizedMuxer(Kotmpeg.createMuxer(File("grabacion.mp4")) { fragmented = true })
val pantalla = muxer.addTrack(TrackInfo.Video(VideoCodec.H264, 1080, 2400) { codecPrivate = avcC })
val audio = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = asc })
muxer.start()
// Desde el callback de cada codificador, en su propio hilo:
muxer.writePacket(MediaPacket(pantalla, NalUnits.annexBToLengthPrefixed(salidaH264), ptsUs, isKeyFrame = esIdr))
muxer.writePacket(MediaPacket(audio, salidaAac, ptsUs, isKeyFrame = true))
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
                  ├─► PcmStreamMixer ──────────► codificador AAC ─┐
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
| Mezcla | `Mezcla` | `PcmStreamMixer` (o `PcmMixer.mix` con bloques ya cuadrados) antes de un único codificador |
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
  deriva en sesiones de horas, así que no hace falta corregir nada a mano. Si cuadrarlos te toca a
  ti, usa `PcmStreamMixer` (abajo), que lo hace por ti.
- **Rendimiento**: mezclar una hora de estéreo a 48 kHz en bloques de 10 ms cuesta unos 0,3 s de
  CPU sin ganancias (la ruta es entera, ~6 veces más rápida que en la `2.x`) y unos 2,3 s con
  ganancias, medido en un equipo de desarrollo. El resultado es idéntico bit a bit al de la `2.x`.

### Mezclar en streaming con `PcmStreamMixer`

En Android cada fuente se lee en su propio hilo y cada `AudioRecord` entrega trozos de un tamaño
distinto, así que cuadrar bloques a mano es justo lo incómodo. `PcmStreamMixer` (desde la `3.1.0`)
recibe cada fuente según llega, en su formato, y entrega la mezcla ya alineada:

```kotlin
val mezclador = PcmStreamMixer(outputRate = 48_000, outputChannels = 2)
val micro = mezclador.addSource(sampleRate = 44_100, channels = 1)
val sistema = mezclador.addSource(sampleRate = 48_000, channels = 2)      // o gain = 0.8f

// Desde el hilo del micrófono:             mezclador.write(micro, pcmMicro)
// Desde el hilo de la captura del sistema:  mezclador.write(sistema, pcmSistema)

// Desde el hilo del codificador:
val desde = mezclador.emittedFrames
val bloque = mezclador.read()                         // lo ya alineado; puede venir vacío
val ptsUs = inicioUs + desde * 1_000_000 / 48_000     // marca del primer frame del bloque

// Al terminar la grabación:
val cola = mezclador.flush()
```

- **El resultado es el de mezclar a mano**: idéntico, muestra a muestra, a convertir y sumar los
  flujos enteros con `PcmResampler`, `PcmMixer.convertChannels` y `PcmMixer.mix`, sea cual sea el
  tamaño de los trozos y el orden en que lleguen.
- **Es seguro entre hilos**: cada fuente desde su hilo y la lectura desde el del codificador. Los
  arrays se copian, así que puedes reutilizar tu buffer.
- **Si una fuente se retrasa**, se la espera hasta `maxLatencyMs` (500 ms por defecto). Pasado ese
  margen, su parte se mezcla como silencio y, cuando el audio retrasado llega, se descarta lo ya
  entregado: un tirón deja un hueco corto, pero nunca desincroniza esa fuente para el resto de la
  grabación. `lostFrames(fuente)` lo cuenta, y si crece, esa fuente se está quedando atrás.
- **Arranque**: las fuentes que entregan audio antes de que salga la primera mezcla se consideran
  simultáneas. Si sabes que una arrancó X ms después, escríbele antes X ms de silencio. Una fuente
  que empieza cuando la mezcla ya ha salido se coloca donde llega.
- **No corrige la deriva de reloj** entre fuentes: las frecuencias se toman por su valor nominal.
  Si un dispositivo entregara una fuente algo más despacio de lo que declara, se vería en
  `lostFrames` como pérdidas puntuales, nunca como un desfase creciente.

### Cada fuente en su pista

Una pista por fuente, con nombre y **exactamente una predeterminada** (si no, cada reproductor elige
una distinta). Desde la `4.0.0` eso sale solo: si no fijas `default`, el muxer marca la primera
pista de cada tipo y ninguna más, así que basta con añadir primero la que deba sonar:

```kotlin
val mezcla = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = ascMezcla; name = "Mezcla" })
val microfono = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 1) { codecPrivate = ascMicro; name = "Micrófono" })
val sistema = muxer.addTrack(TrackInfo.Audio(AudioCodec.AAC, 48_000, 2) { codecPrivate = ascSistema; name = "Audio del sistema" })
```

Con `default = true` en otra pista, esa pasa a ser la predeterminada y la primera deja de serlo.

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
tiene, y en la `4.0.0`, que `ByteBuffer.clear()` y `limit(int)` devuelven allí `Buffer`. La cota de
26 la pone `java.nio.file`, que se usa en la sustitución atómica de `fastStart`.

Lo que conviene saber al integrarla:

- **Aquí no hay códecs.** La codificación la hace el chip vía `MediaCodec`; esta librería recibe los
  paquetes que salen de ahí. `NalUnits.annexBToLengthPrefixed` convierte la salida de vídeo al
  formato del contenedor, y la configuración de códec (`avcC`/`hvcC`/ASC/OpusHead) viaja en
  `TrackInfo.codecPrivate`.
- **Los codificadores entregan desde hilos distintos.** Envuelve el muxer con
  `Kotmpeg.synchronizedMuxer(...)` y llama a `writePacket` desde cada callback.
- **Las marcas de tiempo de `MediaCodec` se pasan tal cual**, aunque cuenten desde el arranque del
  dispositivo, y **en el orden en que lleguen**: los tres muxers empiezan la línea de tiempo en el
  paquete más temprano de todas las pistas, aunque no sea el primero en llegar (lo normal cuando el
  codificador de vídeo tarda más que el de audio).
- **`isKeyFrame` es obligatorio**, para que un keyframe no se quede sin marcar por olvido. En audio
  da igual lo que pases: AAC y Opus se escriben siempre como muestras independientes.
- **Puedes reutilizar tu buffer** entre paquetes: ningún muxer guarda una referencia al
  `ByteArray` de un `MediaPacket` después de `writePacket`: cuando tienen que retener muestras —el
  fMP4 hasta cerrar cada fragmento, el MKV y el fMP4 al arrancar— guardan una copia.
- **`codecPrivate`**: el audio sin él recibe una configuración por defecto (ASC u OpusHead) igual en
  los tres contenedores; el vídeo sin `avcC`/`hvcC` se rechaza en `addTrack`, también en MKV, porque
  los reproductores de Android no pueden abrirlo.
- **`SeekableInput`/`SeekableOutput` aceptan un `FileDescriptor` ya abierto**, así que un
  `ParcelFileDescriptor` de `ContentResolver` sirve directamente para MediaStore/SAF. Ábrelo en
  `"rw"`: `SeekableOutput` trunca el destino al abrirlo, y con `fastStart` necesita leerlo para
  recolocar el índice en el sitio. Si no se puede leer —el `"w"` que se usa por defecto—, el muxer
  lo rechaza al construirse, antes de grabar nada. La librería cierra los canales que abre sobre el
  descriptor, pero el descriptor es de quien lo abrió: en Android, `FileInputStream` y
  `FileOutputStream` no se adueñan de un `FileDescriptor` ajeno, así que el `ParcelFileDescriptor`
  se cierra aparte, con `use`.
- **Para grabaciones largas, `fragmented = true` o MKV**: sobreviven a que se mate el proceso y
  no acumulan índice en memoria. En un móvil con poca RAM, `maxFragmentBytes = 16L * 1024 * 1024`
  acota lo que retiene cada fragmento. `SeekableOutput.sync()` fuerza además los datos al disco si
  la app quiere protegerse de un corte de batería.

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
    implementation("com.github.bmontiel-28:Kotmpeg:4.1.0")
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
dependencies { implementation("com.braymon:kotmpeg-core:4.1.0") }
```

Las dos opciones publican el jar y un jar de fuentes, así que el IDE deja navegar el código y leer
el KDoc. **No son intercambiables**: cada coordenada solo resuelve en su repositorio.

---

# Comportamiento en tiempo de ejecución

## Contrato de un solo hilo

**Los muxers, demuxers, `SeekableInput`/`SeekableOutput` y `PcmResampler` no son seguros entre
hilos**, y es intencional: serializar por dentro costaría rendimiento en el 100 % de los casos.

Si varios productores escriben al mismo archivo —lo normal en cuanto hay vídeo y audio en hilos
distintos—, envuelve el muxer con `Kotmpeg.synchronizedMuxer`, que serializa cada operación con
un único cerrojo. Un `PcmResampler` por fuente, usado desde el hilo de esa fuente. `PcmMixer` no
tiene estado y se puede llamar desde cualquier hilo. `PcmStreamMixer` es la excepción a propósito:
es seguro entre hilos, porque existe precisamente para recibir cada fuente desde su hilo.

## Memoria al escribir archivos largos

`Mp4Muxer` (MP4 **no** fragmentado) mantiene en RAM una entrada por muestra hasta `stop()`, porque
las tablas del `moov` no se pueden escribir hasta conocer el archivo entero. Cada muestra ocupa unos
37 bytes en columnas de tipos primitivos, y la tabla crece por páginas, sin copiarse entera al
llenarse: dos horas a 60 fps con audio AAC (~770 000 muestras) son unos **34 MB** antes de `stop()`
(54 MB en la `2.x`).

Para grabaciones largas usa **`fragmented = true`** o **MKV**: vacían a disco según avanzan y
sobreviven a un corte de proceso (ver [grabaciones interrumpidas](#grabaciones-interrumpidas)). El fMP4 sí retiene las muestras de **un** fragmento —su `moof` va
delante de ellas—, como mucho un GOP, 10 s o 64 MB; los dos límites son las opciones
`maxFragmentDurationUs` y `maxFragmentBytes`.

Al **arrancar**, el MKV y el fMP4 retienen además los paquetes que lleguen hasta que todas las
pistas han entregado el primero, para empezar la línea de tiempo en el más temprano. En la práctica
es la diferencia de latencia entre codificadores, unos cientos de milisegundos; el tope es de 5 s o
16 MB en MKV y los mismos dos límites del fragmento en fMP4, por si una pista no llega a producir
nada.

**`fastStart` sobre un `File` cuesta el doble de disco.** El archivo se cierra primero completo, con
su `moov` al final; recolocarlo al principio es reescribirlo entero a un temporal en el mismo
directorio, forzar ese temporal al disco y sustituir con él el original. Para una salida de 4 GiB,
cuenta con algo más de 8 GiB libres. **Pase lo que pase, lo escrito no se pierde**: si la
reescritura falla, el original queda intacto y reproducible, sin inicio rápido, y `stop()` lanza
explicándolo.

Sobre un `SeekableOutput` —un descriptor de `MediaStore`, donde no hay ruta para un temporal— el
`moov` se inserta delante desplazando los datos **en el sitio**, de 1 MiB en 1 MiB desde el final:
no necesita disco extra, pero tampoco tiene esa red, y si el proceso muere a mitad del
desplazamiento el archivo queda inservible. El resultado es el mismo archivo, byte a byte, que por
ruta.

## Memoria al *leer* archivos largos

Desde la `3.1.0`, `Mp4Demuxer` **no carga las muestras en memoria**: al abrir recorre sus tablas una
vez para validarlas y guarda un punto de control cada 256 muestras, y después lee cada muestra del
archivo cuando hace falta. Para un archivo de 2 horas a 60 fps con audio AAC (~770 000 muestras),
medido en la misma máquina:

| | `3.0.0` | `3.1.0` |
|---|---|---|
| Heap del demuxer abierto, MP4 / fMP4 | ~28 MB / ~28 MB | **~0,5 MB / ~0,13 MB** |
| Abrir | ~38 ms | ~23 ms |
| `seekTo`, MP4 / fMP4 | ~0,7 µs / ~0,7 µs | ~7 µs / ~50 µs |
| Leer el archivo entero (paquetes de pocos bytes) | ~44 ms | ~77 ms |

La diferencia crece con la duración: con la `3.0.0` diez horas eran más de 140 MB, que en el heap
de un móvil es un `OutOfMemoryError`; ahora son del orden de 2,5 MB. A cambio, buscar y leer cuestan
algo más, pero siguen en microsegundos por operación, muy por debajo de lo que cuesta leer los datos
de un vídeo real. Los paquetes son idénticos a los de la `3.0.0`; dónde quedan las demás pistas tras
`seekTo` cambió en la `4.0.0` (ver [sincronización](#cómo-se-garantiza-la-sincronización)).

En la `4.0.0`, `seekTo` en MP4 plano dejó de retroceder por la tabla de muestras para colocar el
audio, que era la mitad de su coste. Medido en la misma máquina y sesión contra la `3.1.0`: de 27,5
a 15,5 µs, y buscar y leer el primer paquete, de 150 a 111 µs. Son otra máquina y otra sesión que
las de la tabla, así que vale la proporción, no las cifras absolutas.

`MkvDemuxer` nunca tuvo este coste: Matroska se recorre cluster a cluster y el índice `Cues` solo se
consulta al buscar. Si ese índice está roto se avisa por `onWarning` y se busca recorriendo clusters.

## Grabaciones interrumpidas

Si el proceso muere antes de `stop()` —la app falla, el sistema la mata para liberar memoria, el
usuario la cierra—, el **MKV** y el **MP4 fragmentado** quedan **válidos y reproducibles hasta el
último cluster o fragmento cerrado**, con su duración y, en MKV, con su índice, sin
reempaquetarlos:

- **MKV**: el `SeekHead` se escribe al empezar y el índice `Cues` va en un hueco reservado delante
  de los clusters (`cuesReserveBytes`, 64 KiB por defecto). Cada vez que se cierra un cluster se
  añade su cue y se actualiza la `Duration`, en un orden en el que un corte en cualquier punto deja
  un índice que solo apunta a clusters completos. El cluster en curso se lee hasta donde llegue.
- **MP4 fragmentado**: la duración de `mvhd`, `tkhd`, `mehd` y la lista de edición se actualiza
  tras cada fragmento. Sin ella, los extractores de Android daban duración cero.

Lo que el corte deja a medias —el último bloque o fragmento, el tamaño del último cluster, el índice
`mfra`— lo cierra **`Kotmpeg.repair`** en el sitio, sin copiar el archivo:

```kotlin
val result = Kotmpeg.repair(File("grabacion.mkv"))
// RepairResult(format=MKV, repaired=true, durationUs=…, discardedBytes=…)

contentResolver.openFileDescriptor(uri, "rw")!!.use { pfd -> Kotmpeg.repair(pfd.fileDescriptor) }
```

Con archivos de esta librería deja los mismos paquetes y la misma duración que reempaquetar el
archivo con `remux`, sin necesitar otra vez su tamaño libre en disco; con los de otros programas
puede cambiar el orden en que se intercalan las pistas o un microsegundo de redondeo. Un archivo ya
cerrado no se toca (`repaired = false`): un MKV con el tamaño del `Segment` escrito, un fMP4 que
acaba en `mfra`. Uno completo de otro programa sin `mfra` se trata como cortado, y solo se le pone la
duración y el `mfra`. Lo que no reconoce se rechaza con `IllegalArgumentException` sin modificar
nada. Si la propia reparación se interrumpe, se puede volver a lanzar: los tests la cortan en cada
una de sus operaciones y la relanzan.

El MP4 plano (`Mp4Muxer`) no tiene arreglo: su índice `moov` solo se escribe en `stop()`. Para
grabar, usa MKV o `fragmented = true`.

Todo esto vale frente a la muerte del proceso, que deja en el archivo lo que ya había llegado al
sistema. Un corte de luz es otra cosa: el sistema puede perder lo que aún no había llevado al disco,
y no necesariamente en orden. `repair` reconstruye el índice a partir de los clusters o fragmentos
que de verdad quedaron y acepta, en MKV y en fMP4, un final de ceros, que es lo habitual; un hueco
en mitad del archivo lo rechaza sin tocarlo. Lo que no se puede perder se fuerza al disco con
`SeekableOutput.sync()`.

## Errores y avisos

| Canal | Qué es | Qué hacer |
|---|---|---|
| `onWarning: (String) -> Unit` | **No fatal.** Algo se degradó pero la operación sigue: una pista que no se puede leer, una muestra que se salta, un archivo cortado. | Registrarlo, y avisar si afecta a lo que se pidió. Nunca ignorarlo en silencio. |
| Excepciones | **Fatal.** Cabecera corrupta, MP4 sin `moov`, un desfase de tiempo que no cabe en el contenedor, un parámetro inválido, un error de E/S real. | El mensaje nombra el campo o la caja concreta. El archivo queda cerrado. |

Un archivo **truncado** —la grabación se interrumpió— no es fatal en ningún contenedor: se lee hasta
el último paquete completo, el stream termina limpio y el corte se avisa por `onWarning`. Para
cerrarlo en el sitio está [`Kotmpeg.repair`](#grabaciones-interrumpidas).

```kotlin
Kotmpeg.remux(entrada, salida, onWarning = { println("aviso: $it") })
```

La política es que un archivo dañado produzca **un error claro o un fin de stream**, nunca un
cuelgue ni un consumo de memoria sin control. La hacen cumplir `RobustnessTest`,
`UntrustedTableSizesTest`, `LargeFileEdgeCasesTest` y `Mp4HeaderHardeningTest`, y el fuzzing de
`DemuxerFuzzTest`, que la comprueba sobre miles de archivos mutados en cada ejecución de los tests
y sobre millones en una campaña semanal del CI. Está descrita en [`SECURITY.md`](SECURITY.md).

---

# Referencia técnica

## Opciones de escritura

Los tres muxers y la fachada reciben las opciones en un bloque de `MuxerOptions`. Cada muxer usa las
de su formato e ignora las demás, así que el mismo bloque sirve aunque sea la extensión la que
decida el contenedor, y un valor imposible se rechaza **antes** de abrir el archivo:

```kotlin
Kotmpeg.createMuxer(File("clip.mp4")) {
    fragmented = true
    maxFragmentBytes = 16L * 1024 * 1024
}
Mp4Muxer(SeekableOutput(pfd.fileDescriptor)) { fastStart = true }
```

| Opción | Por defecto | Contenedor | Qué hace |
|---|---|---|---|
| `fastStart` | `false` | MP4 | Pone el `moov` delante de los datos al cerrar (`-movflags +faststart`) |
| `fragmented` | `false` | MP4, en la fachada | MP4 fragmentado en vez de plano; excluye `fastStart`. `Mp4Muxer` la rechaza |
| `fragmentDurationUs` | 2 s | fMP4 | Longitud de fragmento cuando no hay vídeo que corte por keyframes |
| `maxFragmentDurationUs` | 10 s | fMP4 | Corte forzoso aunque no llegue ningún keyframe |
| `maxFragmentBytes` | 64 MB | fMP4 | Memoria máxima retenida por fragmento; con `segmentIndex`, como mucho 1 GiB |
| `segmentIndex` | `false` | fMP4 | Un `sidx` por pista y fragmento (`-movflags dash`) |
| `onSegment` | — | fMP4 | Aviso por cada fragmento escrito, para HLS o DASH en vivo |
| `nonNegativeCompositionOffsets` | `false` | fMP4 | Desfases de composición sin signo y lista de edición, para que FFmpeg sincronice exacto |
| `maxClusterDurationMs` | 5000 | MKV | Duración máxima de un cluster |
| `cuesReserveBytes` | 64 KiB | MKV | Hueco delante de los clusters para el índice, que se amplía al cerrar cada cluster: un archivo cortado se abre con índice y duración. ~22 bytes por cluster; 0 lo desactiva |
| `creationTimeMillis` | ahora | todos | Fecha de creación; `null` la omite, para una salida idéntica byte a byte |
| `writingApp` | `"Kotmpeg"` | todos | El `WritingApp` de Matroska y la etiqueta `©too` de MP4, el `encoder` de ffprobe; vacía, no se escribe en MP4 |

Hasta la `3.x` eran parámetros con valor por defecto, una función aparte para el fMP4 con límites y
propiedades del muxer que había que asignar antes de `start()`. Una opción nueva era una firma
nueva; ahora es una propiedad más del bloque.

## Matriz de soporte

| | MKV | MP4 |
|---|---|---|
| H.264/AVC | ✅ mux/demux (`V_MPEG4/ISO/AVC`, avcC) | ✅ mux/demux (`avc1`) |
| H.265/HEVC | ✅ mux/demux (`V_MPEGH/ISO/HEVC`, hvcC) | ✅ mux/demux (`hvc1`/`hev1`) |
| AAC | ✅ (AudioSpecificConfig) | ✅ (`mp4a` + esds) |
| HE-AAC (SBR/PS) | ✅ (`SamplingFrequency` del núcleo + `OutputSamplingFrequency`) | ✅ (tasa real desde el ASC) |
| Opus | ✅ (OpusHead, CodecDelay/SeekPreRoll) | ✅ (`Opus` + dOps) |
| N pistas de audio (mezcla, micrófono, sistema…) | ✅ ilimitadas al escribir; al leer, hasta 256 pistas | ✅ ilimitadas al escribir; al leer, hasta 64 de audio y vídeo |
| Idioma, nombre y pista predeterminada | ✅ (`Language`, `Name`, `FlagDefault`) | ✅ (`mdhd`, `udta`/`name`, bit `track_enabled` del `tkhd`) |
| Aplicación que escribe el archivo | ✅ (`WritingApp`) | ✅ (`©too`, el `encoder` de ffprobe) |
| B-frames (pts≠dts) | ✅ | ✅ (ctts + edit lists, DTS derivado automáticamente) |
| Vídeo de frecuencia variable (captura de pantalla) | ✅ | ✅ (duraciones exactas también entre fragmentos fMP4) |
| Índice de búsqueda | ✅ Cues + SeekHead (y sin índice, recorriendo clusters) | ✅ stss/stco completos |
| Archivos > 4 GiB | ✅ | ✅ (mdat de 64 bits + co64) |
| Inicio rápido / progresivo | ✅ (por diseño del formato) | ✅ `fastStart` (= `-movflags +faststart`), por ruta o sobre un descriptor |
| Fragmentado / a prueba de cortes | ✅ (por diseño del formato) | ✅ fMP4 lectura y escritura (= `frag_keyframe+empty_moov`) |
| Segmentos indexados para HLS/DASH | — | ✅ un `sidx` por pista y fragmento (= `-movflags dash`) y aviso por segmento |
| Rotación de pantalla | ✅ (`Projection`/PoseRoll) | ✅ (matriz `tkhd`, visible en ffprobe) |
| Píxeles no cuadrados | ✅ (`DisplayWidth`/`Height`, con `DisplayUnit` resuelto al leer) | ✅ (tamaño de presentación del `tkhd`, y `pasp` para Media3; al leer, el `pasp` es el respaldo) |
| Color y HDR10 estático | ✅ (`Colour` + `MasteringMetadata`) | ✅ (`colr` nclx + `mdcv` + `clli`) |
| Compresión de contenido | ✅ lectura (`ContentEncoding`: eliminación de cabecera y zlib) | — (no existe en MP4) |
| Concatenación sin recodificar | ✅ | ✅ |

## Equivalencias con FFmpeg

| FFmpeg | Kotmpeg Core |
|---|---|
| `ffmpeg -i in.mp4 -c copy out.mkv` (remux) | `Kotmpeg.remux(in, out)` |
| Demuxer `concat` (unir segmentos sin recodificar) | `Kotmpeg.concat(inputs, output)` |
| `-movflags +faststart` | `createMuxer(file) { fastStart = true }` |
| `-movflags frag_keyframe+empty_moov` (fMP4) | `createMuxer(file) { fragmented = true }` |
| `-movflags dash` (un `sidx` por pista y fragmento) | `createMuxer(file) { fragmented = true; segmentIndex = true }` |
| `-movflags delay_moov` sin `negative_cts_offsets` | `createMuxer(file) { fragmented = true; nonNegativeCompositionOffsets = true }` |
| `-reserve_index_space` (MKV) | `createMuxer(file) { cuesReserveBytes = n }`, y además el índice se amplía mientras se graba |
| `ffmpeg -i cortado.mkv -c copy nuevo.mkv` (recuperar una grabación cortada) | `Kotmpeg.repair(cortado)`, en el sitio |
| `-map` (selección de pistas) | `remux(..., trackFilter = { id -> ... })` |
| `-metadata:s:a:0 language=spa` / `title=` | `TrackInfo.Audio(...) { language = "spa"; name = "Micrófono" }` |
| `-disposition:a:1 0` | `TrackInfo.Audio(...) { default = false }` |
| `-display_rotation` / matriz de rotación | `TrackInfo.Video(...) { rotationDegrees = 90 }` |
| `-color_primaries/-color_trc/-colorspace/-color_range` + HDR10 | `TrackInfo.Video(...) { color = ColorInfo.hdr10(hdr) }` |
| ffprobe (inspección) | `openDemuxer(file).tracks` / `durationUs` |
| Seek por índice | `Demuxer.seekTo(us)` (Cues / stss, por bisección; cae siempre en un keyframe) |
| Filtro `amix` / `pan` | `PcmMixer` (mezcla saturada con ganancias, mono↔estéreo) y `PcmStreamMixer` (en vivo, fuentes con formato y ritmo distintos) |
| `-ar` / `-ac` sobre PCM | `PcmResampler` (remuestreo lineal en streaming, fase exacta) |

**Detalle del fMP4/CMAF**, por ser la parte menos habitual: `FragmentedMp4Muxer` escribe `ftyp` +
`moov` vacío (`mvex`/`mehd`/`trex`) seguido de pares `moof`+`mdat` —un fragmento por GOP de vídeo,
o por duración configurable si solo hay audio— y cierra con un índice `mfra`. Los datos se escriben
**solo hacia delante**, que es lo que lo hace servir igual para grabación a prueba de cortes y para
empujar fragmentos a un empaquetador HLS/DASH. Lo único que se reescribe hacia atrás son los campos
de duración de la cabecera —`mvhd`, `tkhd`, `mehd` y la lista de edición—, que se reservan a cero y
se ponen al día tras cada fragmento; si el proceso muere, declaran la duración hasta el último
fragmento completo, y lo grabado hasta él se reproduce sin más. La duración del último fotograma de cada fragmento se toma
del keyframe que abre el siguiente, así que el vídeo de frecuencia variable de una captura de
pantalla no desalinea los tiempos. Los B-frames van por `trun` v1 con offsets de composición
firmados, como en CMAF, y lo que un B-frame se decodifica después de mostrarse queda acotado aunque
una pausa de la pantalla caiga entre él y su referencia. FFmpeg retrasa la pista de vídeo entera lo
que mida el mayor de esos offsets negativos, uno o dos fotogramas; si el archivo va a pasar por
FFmpeg, `nonNegativeCompositionOffsets = true` los escribe sin signo (`trun` v0) con una lista de
edición que los compensa, y la cabecera espera al primer fragmento para conocer el desfase. El
demuxer lee fMP4 propio y de FFmpeg, con su lista de edición igual que en un MP4 plano.

## Segmentos para HLS y DASH

Con `segmentIndex`, `FragmentedMp4Muxer` escribe delante de cada fragmento un índice `sidx` por
pista, igual que `-movflags dash` de FFmpeg: cada fragmento pasa a ser un **segmento indexado** que
un reproductor DASH o HLS puede pedir por rangos de bytes. Sigue siendo un único archivo que se
escribe solo hacia delante —el fragmento entero está en memoria antes de escribirse, así que su
índice se calcula sin volver atrás—, y por tanto igual de resistente a cortes. Cuesta 52 bytes por
pista y fragmento.

`onSegment` avisa de cada fragmento en cuanto está en disco, con su posición, su tamaño, su
duración y el tamaño de la cabecera (`initSegmentSize`): es todo lo que hace falta para mantener
una lista HLS en vivo mientras se graba, sin releer el archivo.

```kotlin
val lista = StringBuilder()
val muxer = FragmentedMp4Muxer(File(dir, "grabacion.mp4")) {
    segmentIndex = true
    onSegment = { s ->
        lista.append("#EXTINF:%.3f,\n".format(Locale.ROOT, s.durationUs / 1e6))
        lista.append("#EXT-X-BYTERANGE:${s.size}@${s.offset}\ngrabacion.mp4\n")
    }
}
// addTrack, start(), writePacket… La lista empieza con #EXTM3U, #EXT-X-VERSION:7 (la que admite
// fMP4), #EXT-X-TARGETDURATION con la duración máxima redondeada hacia arriba, y
// #EXT-X-MAP:URI="grabacion.mp4",BYTERANGE="${s.initSegmentSize}@0"; al cerrar, #EXT-X-ENDLIST.
```

Así lo prueba el CI: una lista montada solo con esos avisos tiene que reproducir en FFmpeg todos los
fotogramas. No se declara la marca CMAF (`cmfc`), porque CMAF exige una sola pista por archivo y
este muxer intercala todas; si la necesitas, escribe cada pista con su propio muxer.

## Estructura

Todo el proyecto es un único módulo Gradle, bajo `src/main/kotlin/com/braymon/kotmpeg/`:

```
com/braymon/kotmpeg/
│
├── Kotmpeg.kt           Fachada: createMuxer / openDemuxer / detectFormat / remux / concat /
│                        repair / synchronizedMuxer.
├── MuxerOptions.kt      Opciones de escritura de los tres muxers y de la fachada.
├── Muxer.kt             Interfaces Muxer y Demuxer.
├── RepairResult.kt      Lo que devuelve repair.
├── TimelineGate.kt      Retención de arranque: origen común de los muxers que escriben en vivo.
│
├── audio/               PCM de grabación: PcmMixer (mezcla micrófono + sistema, canales),
│                        PcmResampler (remuestreo en streaming con fase exacta) y
│                        PcmStreamMixer (mezcla en vivo de fuentes desalineadas).
├── ebml/                EBML: lector, escritor e IDs de Matroska (RFC 8794).
├── mkv/                 MkvMuxer (con MkvLayout: SeekHead e índice en vivo, y MkvTimeline:
│                        duración final y cues, comunes con la reparación) y MkvRepair.
│                        MkvDemuxer, con MkvHeaderParser (cabecera e índice), MkvContentDecoder
│                        (ContentEncoding) y MkvSeekFilter (búsqueda en keyframe).
├── mp4/                 ISO/IEC 14496-12. Escritura: Mp4Muxer (faststart), FragmentedMp4Muxer
│                        (fMP4, con FragmentTiming, FragmentDurations y FragmentLimits),
│                        MovieBoxes (cabeceras comunes), SampleEntries, SampleTable y
│                        BoxBuilder. Lectura: Mp4Demuxer, con Mp4BoxReader,
│                        Mp4SampleEntryReader, FragmentWalker y su índice bajo demanda
│                        (SampleIndex). Reparación: FragmentedMp4Repair y FragmentIndex (mfra).
├── model/               Modelo canónico: MediaPacket, TrackInfo, códecs, ColorInfo/HDR.
├── codecconfig/         NalUnits (Annex-B ↔ ISO, avcC/hvcC), AacConfig, OpusConfig y
│                        AudioPriming (el cebado, igual en los tres muxers).
├── io/                  SeekableInput / SeekableOutput (File, RAF o FileDescriptor) y
│                        RepairTarget (abrir sin truncar, para repair).
└── pipeline/Remuxer.kt  Copia demuxer→muxer con filtro/progreso, y concat.
```

El modelo canónico (`MediaPacket`, `TrackInfo`) usa NALUs con prefijo de longitud de 4 bytes y
configuración de códec en formato ISO (avcC/hvcC/ASC/OpusHead), de modo que **remuxear entre MKV y
MP4 es una copia bit a bit sin pérdida**, y cualquier fuente de paquetes alimenta cualquier
contenedor.

## Mapa de la API pública

| Clase / objeto | Para qué |
|---|---|
| `Kotmpeg` | Fachada: `createMuxer`, `openDemuxer`, `detectFormat`, `remux`, `concat`, `repair`, `synchronizedMuxer` |
| `MuxerOptions` | Las opciones de escritura, en el bloque de los muxers y de la fachada: `fastStart`, `fragmented`, límites del fMP4, `segmentIndex`, `onSegment`, `nonNegativeCompositionOffsets`, `maxClusterDurationMs`, `cuesReserveBytes`, `creationTimeMillis`, `writingApp` |
| `RepairResult` | Lo que hizo `Kotmpeg.repair`: si cambió el archivo, la duración que declara y los bytes que descartó |
| `Muxer` / `Demuxer` | Interfaces de escritura/lectura de contenedores |
| `MkvMuxer` / `MkvDemuxer` | Matroska directo (si no quieres pasar por la fachada) |
| `Mp4Muxer` / `FragmentedMp4Muxer` / `Mp4Demuxer` | ISO BMFF plano, fMP4 y lectura de ambos |
| `Mp4Segment` | Un fragmento ya escrito, tal como lo avisa `onSegment`: posición, tamaño, inicio, duración y tamaño de la cabecera |
| `MediaPacket`, `TrackInfo.Video/Audio`, `VideoCodec`, `AudioCodec`, `ContainerFormat` | Modelo de datos canónico (nombre, idioma y pista predeterminada por pista; rotación y color en vídeo; cebado en audio). Las pistas se construyen con los datos obligatorios y un bloque, y `copy { }` cambia lo que se quiera |
| `ColorInfo` / `HdrStaticInfo` | Color BT.709/BT.2020, rango, PQ/HLG y HDR10 estático |
| `PcmMixer` / `PcmResampler` | Audio de grabación: mezcla saturada con ganancias, mono↔estéreo, remuestreo en streaming |
| `PcmStreamMixer` | Mezcla en vivo de micrófono y sistema: cada fuente en su formato y desde su hilo, entrega alineada, segura entre hilos |
| `Remuxer` | Copia de streams demuxer→muxer con filtro/progreso y `concat` de segmentos |
| `NalUnits` | Annex-B ↔ ISO, avcC/hvcC |
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
- **Cebado del audio**: los tiempos de los paquetes no lo descuentan, y su duración va en
  `TrackInfo.Audio.codecDelayUs`. Es lo que define Matroska para `CodecDelay` y, desde la `4.0.0`,
  lo que entregan los dos demuxers: el mismo AAC llega con los mismos tiempos desde un MP4, un fMP4
  o un MKV, y pasa de uno a otro sin desfase.
- **Búsqueda**: `seekTo` cae en el último keyframe del vídeo no posterior al destino —también en un
  MKV cuyos clusters no empiezan en keyframe, o sin índice—, y las demás pistas siguen desde ese
  instante de presentación: el audio, en el paquete que suena en él, y otra pista de vídeo, en su
  propio último keyframe no posterior. Un recorte que empiece ahí tiene imagen y sonido desde el
  principio. En MKV se lee desde el cluster anterior al del keyframe, que es donde el muxer pudo
  dejar ese audio, y se sigue hasta pasar el keyframe en cada una de las demás pistas, por si lo
  escribió detrás; si el keyframe de otra pista de vídeo queda aún más atrás, empieza en el
  siguiente, nunca en un fotograma que no se puede decodificar. Una pista que ya ha terminado en
  ese instante no entrega nada; en MKV, de una de vídeo solo se sabe cerca del final del archivo.
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
| Filtros de vídeo (`scale`, `crop`, `overlay`) | ❌ | Esto es un muxer, no un motor de edición. El único proceso de señal es el de audio PCM (`PcmMixer`/`PcmResampler`/`PcmStreamMixer`), y existe por la regla de audio: mezclar micrófono y sistema antes de codificar. |
| Subtítulos y capítulos | ❌ (las pistas ajenas se ignoran limpiamente al leer) | Los flujos objetivo no los generan ni los consumen; añadirlos es posible sobre la misma base. |
| Otros códecs (VP9, AV1, MP3, ProRes…) | ❌ | Alcance deliberado: los cuatro con soporte universal en MKV/MP4 y aceleración garantizada por hardware. |
| Otros contenedores (AVI, MPEG-TS, MOV antiguos…) | ❌ | La librería nace exactamente para MKV y MP4/fMP4. (Los WebM se pueden *leer* porque son Matroska.) |
| Escribir WebM | ❌ (`.webm` se rechaza con un error que lo explica) | WebM solo admite VP8/VP9/AV1 con Vorbis/Opus, y aquí se escribe H.264/H.265 con AAC/Opus: el resultado no lo reproduciría un navegador. Usa `.mkv`. |
| Protocolos de red (RTMP, SRT, HLS push, RTSP…) | ❌ | Capa distinta. El fMP4 de esta librería es el insumo directo de cualquier empaquetador o emisor. |
| Herramienta de línea de comandos | ❌ (es una librería) | El objetivo es integrarse con una API tipada. |
| SIMD / ensamblador | ❌ | La JVM no da acceso a eso; el trabajo es sobre todo E/S y copia de buffers. |
| MP4 híbrido: muestras en el `moov` y además fragmentos | ❌ (se leen las del `moov`, con aviso) | La norma lo permite pero es raro, y leerlo obliga a casar dos índices distintos de la misma pista. |
| Recuperar un MP4 con el `moov` corrupto o ausente | ❌ (falla limpio con "no se encontró la caja moov") | Reconstruir las tablas escaneando el `mdat` es mucha superficie para un caso que el fMP4 ya cubre por diseño. Una pista ilegible sí se tolera: se descarta y el resto se lee. |
| Entrada/salida por streaming puro (tuberías, sockets) | ❌ (hace falta un destino posicionable) | Los muxers vuelven atrás a parchear tamaños al cerrar. Sí vale cualquier **descriptor ya abierto**. |
| Índice `sidx` único al principio (perfil DASH *on-demand*) | ❌ (con `segmentIndex`, cada fragmento lleva el suyo) | Un índice global va delante de todos los fragmentos, que solo se conocen al cerrar: habría que reescribir el archivo entero, y dejaría de ser solo-añadir y a prueba de cortes. Partir el archivo en varios es trabajo del empaquetador. |
| Salida sobre el propio archivo de entrada | ❌ (rechazado con un error claro) | El muxer trunca el destino al abrirlo. Escribe a un temporal y renómbralo. |
| Cifrado y compresión bzlib/lzo de Matroska | ❌ (la pista se descarta con aviso) | Entregar esos bloques sin deshacerlos sería entregar basura al decodificador. La eliminación de cabecera y zlib, que sí aparecen en archivos reales, se leen. |

---

# Desarrollo

```bash
./gradlew test
```

451 tests. Necesitan **JDK 17+**; si además tienes **ffmpeg** en el `PATH`, se ejecuta también la
suite de integración, que genera archivos con FFmpeg real, los reescribe con esta librería en ambas
direcciones y valida el resultado con `ffprobe` y una decodificación completa sin errores. Sin
ffmpeg esos tests se omiten solos (`assumeTrue`).

Cuatro tests comprueban que no quedan archivos abiertos —tres tras una operación fallida y uno
mientras `concat` recorre sus entradas—: en Linux leyendo
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
sh gradlew run -PkotmpegVersion=4.1.0
```

En Windows, el último paso es el mismo con `.\gradlew.bat` en lugar de `sh gradlew`.

Cómo contribuir y qué hacer cuando cambia la API pública está en [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Fuzzing

`DemuxerFuzzTest` muta archivos válidos de los dos contenedores y comprueba que cada variante falle
con un error tipado o termine limpia: nunca un cuelgue, un `Error` ni una reserva de memoria
desproporcionada. Parte de archivos generados en cada ejecución por la propia librería y, si está
instalado, por FFmpeg: el repositorio no guarda archivos de vídeo. Dentro de `./gradlew test` hace
siempre la misma pasada corta —20 000 variantes—, y el CI lanza cada semana una campaña larga con
semilla nueva (`.github/workflows/fuzz.yml`). Para una campaña en local, de diez minutos y con la
semilla que quieras:

```bash
./gradlew test --tests "*DemuxerFuzzTest*" -Dkotmpeg.fuzz.seconds=600 -Dkotmpeg.fuzz.seed=1234
```

Cada fallo queda en `build/fuzz-findings/`, con la entrada exacta y lo necesario para reproducirlo.

## Benchmarks

Los benchmarks JMH están en `benchmarks/`, un build aparte que compila contra el código actual:

```bash
./gradlew -p benchmarks verificarLimites
```

Miden abrir, buscar y leer un MP4 de dos horas, escribirlo con `Mp4Muxer`, y el audio PCM en
bloques de 10 ms. La tarea falla si alguno reserva por operación más memoria que su límite en
`benchmarks/limites.properties`; el CI la ejecuta en cada push y publica los tiempos en el resumen
del job.

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
