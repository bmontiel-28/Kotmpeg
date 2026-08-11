# Registro de cambios

Cambios relevantes de Kotmpeg Core para quien lo usa: funcionalidades añadidas y errores
corregidos, en orden inverso por fecha. El versionado sigue [SemVer](https://semver.org/lang/es/).

Desde la 1.0.0 la API pública es estable: **romperla exige subir la versión mayor**, y el cambio va
declarado como `Cambios incompatibles` en su entrada. No depende de que nadie se acuerde —
`PublicApiTest` compara la superficie pública contra un volcado versionado y falla si se mueve.

---

## Cómo leer este archivo

### El número de versión ya te dice cuánto trabajo te va a costar actualizar

Una versión se lee `MAYOR.MENOR.PARCHE`, y lo que cambia de una a otra no mide lo grande que fue el
cambio, sino **qué tienes que hacer tú**:

| Cambia… | Ejemplo | Qué significa para ti |
|---|---|---|
| El **parche** | `2.0.0` → `2.0.1` | Se corrigió algo. Actualiza y ya está: no hay nada nuevo que aprender ni nada que tocar. |
| La **menor** | `2.0.0` → `2.1.0` | Hay algo nuevo que **puedes** usar si quieres. Lo que ya tenías escrito sigue funcionando igual. |
| La **mayor** | `1.1.0` → `2.0.0` | Algo que antes funcionaba **deja de funcionar solo**. Lee la sección `Cambios incompatibles` antes de actualizar. |

### Qué quiere decir «Cambios incompatibles»

Es la sección que avisa de que **esta versión no es un reemplazo directo de la anterior**: hay algo
que funcionaba y que, al actualizar, deja de funcionar hasta que hagas algo al respecto. Se llama
así porque la versión nueva y la vieja no son *compatibles* entre sí: no se pueden intercambiar sin
más.

Es la única sección que **tienes que leer** antes de subir de versión. Las demás cuentan lo que
ganas; esta cuenta lo que te va a pedir a cambio, y siempre dice qué hacer.

Casi siempre lo que pide es **volver a compilar tu proyecto**, y esto es lo que suele desconcertar:
tu código fuente puede estar perfecto, sin una sola línea que cambiar, y aun así el programa fallar
al ejecutarse con un `NoSuchMethodError`. Pasa porque tu aplicación no guarda el *texto* de las
llamadas a la librería, sino una referencia exacta a cada función —con su número de parámetros
incluido—, y si en la versión nueva una función ganó un parámetro, la referencia que tu aplicación
guardaba ya no apunta a nada. Recompilar la vuelve a escribir apuntando a la función nueva.

Por eso una entrada puede decir a la vez «no hay que tocar nada» y «es un cambio incompatible»: lo
primero se refiere a tu código, lo segundo a lo ya compilado.

### Las demás secciones

| Sección | Qué contiene |
|---|---|
| **Corregido** | Algo que estaba mal y ya no lo está. No tienes que hacer nada para beneficiarte. |
| **Añadido** | Capacidades nuevas, opcionales. Si no las usas, no te afectan. |
| **Cambiado** | Algo que sigue funcionando igual desde fuera, pero por dentro se comporta distinto (más rápido, mejor mensaje de error, menos memoria). |
| **Eliminado** | Algo que ya no está. Aparece acompañado de `Cambios incompatibles` si alguien podía estar usándolo. |
| **Documentación** | Solo cambia lo que está escrito, no el código. |

---

## [2.0.0] — 2026-08-10

Tres correcciones de metadatos en el muxer Matroska, salidas de un análisis forense de un archivo
real de 107 s con vídeo a 60 fps y tres pistas de audio. **Ninguna era de corrupción**: ese archivo
decodifica entero y sin un solo error, con sus DTS monótonos y su índice completo. Lo que fallaba
era lo que un reproductor o un editor leen *sobre* el contenido.

La mayor no la fuerza el alcance de los cambios, que es pequeño, sino su forma: dos de ellos añaden
un parámetro a un constructor público, y eso obliga a recompilar aunque no haya que tocar ni una
línea de tu código. Si esa frase te suena rara, está explicada en
[Qué quiere decir «Cambios incompatibles»](#qué-quiere-decir-cambios-incompatibles).

### Cambios incompatibles

- **`TrackInfo.Video` y `TrackInfo.Audio` ganan el parámetro `default: Boolean = true`** al final
  del constructor. Cambian por tanto sus constructores y sus `copy()`. En código fuente no hay que
  tocar nada; quien haya compilado contra la `1.1.0` tiene que recompilar.
- **`MkvMuxer` gana el parámetro `dateUtcMillis: Long?`** al final del constructor primario, con el
  reloj del sistema como valor por defecto. El constructor por `File` no cambia.
- `equals`/`hashCode` de las dos pistas pasan a considerar `default`. Dos pistas que solo difieran
  en ese campo dejan de ser iguales, que es lo correcto pero cambia el comportamiento de quien
  deduplique pistas o cachee por pista.

### Corregido

- **Un archivo con varias pistas de audio las declaraba todas predeterminadas.** `FlagDefault` no
  se escribía nunca, y su valor por omisión en la especificación es 1, así que cada reproductor
  elegía una pista distinta: la misma grabación sonaba a mezcla, a micrófono o a audio del sistema
  según el programa con que se abriera. Ahora el elemento se escribe **solo cuando la pista no es
  predeterminada**, de modo que un archivo en el que todas lo sean sale byte a byte igual que antes.
  `MkvDemuxer` lo lee de vuelta, así que un `remux()` ya no pierde el dato.
- **`DefaultDuration` perdía precisión y anunciaba una cadencia falsa.** Se calculaba en
  microsegundos y se truncaba, así que 60 fps —16 666,67 µs— salía como 16 666 000 ns y ffprobe
  leía **60,0024 fps**: unos 4 ms de deriva por minuto en la línea de tiempo de un editor. El
  cálculo pasa a nanosegundos y redondea: 1/60 s son ahora los 16 666 667 ns exactos. Las cadencias
  que sí caben en un microsegundo, como 25 o 50 fps, no cambian ni un byte.
- **No se escribía la fecha de grabación.** `DateUTC` no se emitía, así que la fecha solo sobrevivía
  en el nombre del archivo — justo lo que se pierde al renombrar o al reimportar en un editor.

### Añadido

- **`TrackInfo.default`**, para marcar qué pista debe elegir el reproductor cuando el usuario no ha
  elegido ninguna. Con varias pistas del mismo tipo, exactamente una debería llevarlo a `true`.
  Matroska lo guarda como `FlagDefault`; **MP4 no tiene equivalente**, así que este dato no
  sobrevive a una conversión a MP4 y vuelta.
- **`TrackInfo.defaultDurationNs`**, la duración nominal por muestra en la unidad en la que Matroska
  define `DefaultDuration`. Existe aparte de `defaultDurationUs` porque el microsegundo no puede
  representar 1/60 s. `TrackInfo.Video` la calcula desde `frameRate` redondeando.
- **`EbmlWriter.writeSInt`**, para los elementos EBML con signo. `DateUTC` cuenta nanosegundos desde
  2001-01-01, así que cualquier fecha anterior a esa es negativa y `writeUInt` no vale. Escribe una
  carga fija de 8 bytes: la longitud mínima con signo depende del bit alto del primer byte —un 0x80
  de un byte es −128, no 128— y elegirla mal produce un valor con el signo cambiado que ninguna
  herramienta señala como error.
- `MkvMuxer` acepta la fecha por parámetro en vez de leer el reloj por dentro, para que un test
  pueda fijarla y comparar.

## [1.1.0] — 2026-08-10

### Añadido

- **`MkvKotlin.requireDistinct(inputs, output)`** pasa a ser pública. Rechaza que el archivo de
  salida sea también una de las entradas, que es un fallo silencioso y no un error: el muxer trunca
  el destino al abrirlo, así que la operación lee un archivo que se está reescribiendo por debajo y
  el resultado depende de que el escritor no adelante al lector. Compara por ruta canónica, de modo
  que un enlace simbólico o una ruta relativa distinta al mismo archivo tampoco se cuelan.

  `remux()` y `concat()` ya la aplicaban por dentro y **siguen haciéndolo**: no hay que llamarla
  para usarlas. Se expone para quien arme su propia canalización con `openDemuxer` y `createMuxer`,
  donde no existe ningún punto central que pueda hacer esa comprobación, y así use la misma
  validación que la fachada en vez de una propia que se olvide de los enlaces simbólicos.

  Es la única línea que se mueve en `public-api.txt`, y solo se añade: **ninguna firma existente
  cambia**, así que actualizar desde la `1.0.1` no exige tocar nada.

## [1.0.1] — 2026-08-10

Release de compatibilidad: **el código de la librería es idéntico al de la `1.0.0`**, y su API
pública también —el volcado de `public-api.txt` no se movió—. Lo que cambia es con qué compilador
se construye el artefacto.

### Corregido

- **La `1.0.0` no se puede consumir desde un proyecto Android.** Se publicó compilada con Kotlin
  **2.4.10**, y una app que herede el Kotlin integrado de AGP —que va por 2.2.10— no puede leer esa
  metadata. El síntoma no se parece a la causa: falla la compilación **entera** con
  `was compiled with an incompatible version of Kotlin`, señalando incluso llamadas a la propia
  biblioteca estándar como `firstOrNull` o `with`, porque al resolver a la versión más alta
  `kotlin-stdlib` sube a 2.4.10 en todo el classpath de la app.

  El core pasa a compilarse con **Kotlin 2.2.10** y el POM declara `kotlin-stdlib:2.2.10`. Quien
  ya hubiera puesto la `1.0.0` en un proyecto Android tiene que subir a esta: no hay forma de
  rodearlo desde el lado de la app salvo forzar la versión de la stdlib a mano.

> **Antes de subir la versión de Kotlin de este proyecto**, comprueba qué `kotlin-gradle-plugin`
> arrastra el AGP de las apps que lo consumen. El compilador de la app es el techo, no el de aquí:
> publicar con una versión más nueva de la que ese AGP sabe leer deja el artefacto inservible sin
> que ningún test de este repositorio se entere.

## [1.0.0] — 2026-08-10

Primera versión: el motor de contenedores completo, en Kotlin puro y sobre cualquier JVM 17. Esta
entrada es la línea base del proyecto — describe **qué hay**, no qué cambió, porque no hay ninguna
versión anterior contra la que comparar. A partir de aquí cada entrada recoge solo el delta.

### Añadido

**Contenedores**

- **Matroska (MKV)**: lectura y escritura completas, con `SeekHead`, índice `Cues`, lacing y
  tolerancia a streams truncados — un archivo cortado a media escritura devuelve lo que sí quedó
  grabado en vez de fallar.
- **MP4 / ISO BMFF plano**: escritura con tablas `stts`/`stsz`/`stss`/`stco` completas, `co64` y
  `mdat` de 64 bits para archivos de más de 4 GiB, y modo `mp4FastStart` que recoloca el `moov`
  delante para reproducción progresiva (el `-movflags +faststart` de FFmpeg).
- **MP4 fragmentado (fMP4/CMAF)**: escritura estrictamente *append-only* —`ftyp` + `moov` vacío con
  `mvex`/`trex`, pares `moof`+`mdat` y un índice `mfra` al cerrar—, que sobrevive a que se mate el
  proceso y sirve de insumo a un empaquetador HLS/DASH. Lee fMP4 propio y de terceros, con defaults
  de `trex`, `default-base-is-moof` y `ctts` firmados.
- **Códecs soportados en ambos contenedores**: H.264/AVC (`avcC`), H.265/HEVC (`hvcC`), AAC
  (AudioSpecificConfig, incluidos los perfiles HE y HE-v2) y Opus (`OpusHead`/`dOps`, con
  conversión entre `CodecDelay`/`SeekPreRoll` y pre-skip).
- **N pistas de audio** por archivo, sin límite.

**Operaciones de alto nivel**

- `MkvKotlin.remux()`: conversión MKV ↔ MP4 **sin recodificar**, con filtro de pistas y callback de
  progreso.
- `MkvKotlin.concat()`: unión de segmentos ya codificados, tampoco recodifica.
- `MkvKotlin.openDemuxer()` / `detectFormat()`: lectura e inspección con detección de formato por
  cabecera, no por extensión.
- `MkvKotlin.createMuxer()`: escritura, con las opciones `mp4FastStart` y `mp4Fragmented`.

**Marcas de tiempo y sincronización**

- **Derivación automática de DTS** para fuentes que solo entregan PTS —los codificadores por
  hardware y Matroska—: `dts_i = sortedPts_i − max(sortedPts_i − pts_i)`, que garantiza `dts ≤ pts`,
  preserva duraciones y produce offsets `ctts` mínimos no negativos.
- **Edit lists** que alinean el inicio de presentación a cero preservando el offset entre pistas, y
  soporte de B-frames en los dos contenedores.
- Rechazo explícito, con un mensaje que nombra el campo, de un desfase que no cabe en un bloque de
  Matroska (unos ±32,7 s entre un paquete y el inicio de su cluster) en vez del truncamiento
  silencioso que produciría un archivo descolocado.

**Metadata de presentación**

- Rotación (0/90/180/270) por matriz `tkhd` en MP4 y `ProjectionPoseRoll` en Matroska.
- Píxeles no cuadrados: tamaño de presentación del `tkhd`, y `DisplayWidth`/`DisplayHeight` de
  Matroska con su `DisplayUnit` resuelto al leer —una proporción declarada, como la que escribe
  `ffmpeg -aspect`, se convierte a píxeles en vez de tomarse literal—.
- Color y HDR10 estático: `colr` nclx + `mdcv` + `clli` en MP4, `Colour` + `MasteringMetadata` en
  Matroska, con `ColorInfo.bt709()` y `ColorInfo.hdr10()` como atajos.

**E/S y utilidades**

- `SeekableInput` / `SeekableOutput`: E/S con buffer y seek/patch sobre `File`, `RandomAccessFile` o
  un **`FileDescriptor` ya abierto**, que es lo que permite escribir y leer sin ruta de archivo.
- `NalUnits`: conversión Annex-B ↔ ISO con prefijo de longitud, construcción y parseo de
  `avcC`/`hvcC`, y lectura de SPS de HEVC.
- `AacConfig` / `OpusConfig`: construcción y parseo de AudioSpecificConfig y OpusHead. `AacConfig`
  distingue lo que declara el ASC (el **núcleo** del bitstream) de lo que sale del decodificador,
  que con SBR y PS no coinciden.
- `PcmMixer` / `PcmResampler`: mezcla saturada con ganancias, conversión mono↔estéreo y remuestreo
  lineal en streaming.

**Robustez**

- Política declarada, y con tres suites que la hacen cumplir: un archivo dañado o manipulado produce
  **un error claro o un fin de stream**, nunca un cuelgue, un consumo de memoria sin control ni un
  descriptor que se quede abierto. Cubre tablas de índices que declaran millones de entradas con
  unos pocos bytes, cabeceras de caja con `largesize` que desbordan, frecuencias de muestreo
  imposibles y archivos truncados en cualquier punto.
- Una pista ilegible se descarta con un aviso y el resto del archivo se sigue leyendo; una sola
  muestra corrupta no corta la lectura de todas las pistas.
- `remux()` y `concat()` rechazan que la salida sea también una de las entradas, comparando rutas
  canónicas para que un enlace simbólico o una ruta relativa distinta al mismo archivo tampoco se
  cuelen.
- `mp4FastStart` cierra el archivo completo **antes** de intentar recolocar el índice, así que
  quedarse sin espacio a mitad deja una salida válida —solo que sin inicio rápido— y no un archivo
  irreproducible. La sustitución final es un movimiento atómico dentro del mismo directorio.

**Canal de avisos**

- `onWarning: (String) -> Unit` en la lectura y en las operaciones de conversión, para lo que se
  degrada sin ser fatal: una pista de códec no reconocido, una muestra que se salta, una pista que
  se pierde al convertir.

### Notas de esta versión

- **Cero dependencias.** El código de producción no usa nada fuera de la biblioteca estándar de
  Kotlin y de `java.io`/`java.nio`. Sin binarios nativos, sin JNI, sin reflexión.
- **Cero APIs de plataforma**, lo que hace que la suite entera corra en una JVM normal y que la
  librería se pueda usar en Android desde API 34 sin *desugaring* ni rutas de compatibilidad.
- La superficie pública está documentada en KDoc, que es lo que enseña el IDE mientras escribes
  contra la librería: qué hace cada función, qué contrato tiene —quién bloquea, quién devuelve el
  mismo array que recibe, quién hay que llamar desde un solo hilo— y qué hay detrás de las
  decisiones que a primera vista parecen raras.
