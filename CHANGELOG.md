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
| La **mayor** | `1.1.0` → `2.0.0` | Algo que antes funcionaba **deja de funcionar igual**: por una firma que cambió o por un comportamiento que se corrigió. Lee la sección `Cambios incompatibles` antes de actualizar. |

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

Hay un segundo tipo, menos frecuente: el **cambio de comportamiento**. Ahí ninguna función cambia
de forma —tu aplicación compila y arranca igual, sin recompilar—, pero alguna hace ahora algo
distinto, casi siempre porque antes hacía algo mal. En la `3.0.0`, por ejemplo,
`MkvMuxer.addTrack` rechaza una pista de vídeo sin configuración de códec que antes aceptaba (y que
luego no se podía reproducir en Android). Cuenta igual como incompatible porque alguien podía estar
contando con lo de antes, y la entrada lo dice en el propio título: «Cambios incompatibles (de
comportamiento, no de API)».

Lo que pide este tipo es distinto: no recompilar, sino **leer cada punto y comprobar si tu
aplicación dependía del comportamiento anterior**. Si no dependía de ninguno, no tienes que hacer
nada.

### Las demás secciones

| Sección | Qué contiene |
|---|---|
| **Corregido** | Algo que estaba mal y ya no lo está. No tienes que hacer nada para beneficiarte. |
| **Añadido** | Capacidades nuevas, opcionales. Si no las usas, no te afectan. |
| **Cambiado** | Algo que sigue funcionando igual desde fuera, pero por dentro se comporta distinto (más rápido, mejor mensaje de error, menos memoria). |
| **Eliminado** | Algo que ya no está. Aparece acompañado de `Cambios incompatibles` si alguien podía estar usándolo. |
| **Documentación** | Solo cambia lo que está escrito, no el código. |

---

## [3.0.0] — 2026-10-07

Revisión completa del motor con tres objetivos: que no quede ningún fallo conocido sin cubrir, que
rinda en archivos y grabaciones largas, y que se pueda usar en móviles desde **Android 8.0**. El
análisis, con las mediciones antes/después, está en [`ANALISIS.md`](ANALISIS.md).

**Compatibilidad con la 2.1.1: total a nivel de código.** No se quita ni se cambia ninguna firma
pública —`public-api.txt` solo gana líneas—, así que una app compilada contra la `2.1.1` funciona
con la `3.0.0` sin tocar una línea ni recompilar. Es mayor por los cambios de comportamiento de la
sección siguiente: todos son correcciones, pero alguien podría depender del comportamiento viejo.

**La regla de audio queda fijada.** El audio del sistema y el del micrófono se pueden grabar
combinados en una pista (`PcmMixer`/`PcmResampler`), cada uno en la suya, o cualquiera de los dos
solo, y las combinaciones. `RecordingAudioLayoutsTest` la hace cumplir en los tres contenedores.

### Cambios incompatibles (de comportamiento, no de API)

- **`MkvMuxer` empieza la línea de tiempo en el paquete más temprano de todas las pistas**, como
  `Mp4Muxer`. Las marcas de `MediaCodec` cuentan desde el arranque del dispositivo y se escribían tal
  cual: un archivo de un segundo declaraba días de duración. Como escribe en vivo, retiene los
  primeros paquetes hasta que todas las pistas han entregado el suyo (ver *Corregido*). Un PTS
  inicial negativo (cebado de AAC) se conserva sin desplazar. Si dependías de las marcas absolutas
  en el MKV, guárdalas aparte.
- **`Mp4Demuxer` devuelve el idioma, el nombre y la pista predeterminada reales.** Antes daba
  siempre `und`, `null` y `true`. Si eliges pista por `default`, ahora puede haber pistas con
  `false` (las que el archivo marca como no activadas).
- **`MkvDemuxer` termina el stream ante un bloque cortado** en vez de lanzar `EbmlException`, y
  avisa por `onWarning`. Es la política que ya documentaba para archivos truncados.
- **Los constructores validan sus parámetros**: `maxClusterDurationMs`, `fragmentDurationUs`,
  `maxFragmentDurationUs` y `maxFragmentBytes` tienen que ser positivos, o lanzan
  `IllegalArgumentException` (antes producían un archivo roto). Con un `File`, se valida antes de
  abrirlo.
- **`MkvMuxer.addTrack` rechaza una pista de vídeo sin `codecPrivate`** (`avcC`/`hvcC`), igual que
  ya hacían los muxers MP4. Antes la aceptaba y escribía un MKV que los reproductores de Android
  no abren.
- **`PcmResampler` puede entregar un frame en la llamada siguiente** a la que lo entregaba antes,
  cuando la posición exacta cae justo en el borde de un trozo, y cada muestra puede diferir en el
  último bit. El flujo concatenado es el mismo, y ahora además exacto en sesiones largas (ver
  *Corregido*). `PcmMixer` da exactamente los mismos bytes que antes.

### Corregido

- **El fMP4 se corrompía si la app reutilizaba su buffer entre paquetes.** `FragmentedMp4Muxer`
  retiene las muestras hasta cerrar cada fragmento y guardaba una referencia al `ByteArray` de quien
  llama: con un buffer reutilizado —lo habitual al copiar la salida de `MediaCodec`— todas las
  muestras del fragmento acababan iguales a la última, sin ningún error (49 de 50 paquetes dañados
  en la prueba). Ahora guarda una copia, igual que la retención de arranque del MKV y del fMP4;
  `Mp4Muxer` escribe en el momento y nunca lo tuvo. El KDoc de `MediaPacket` deja escrito que el
  array se puede reutilizar.
- **El fMP4 desincronizaba las pistas cuando la más temprana no era la primera en llegar.** Con dos
  codificadores es lo normal: el de vídeo tarda más que el de audio, así que el primer trozo de
  audio llega al muxer antes que el primer fotograma aunque se capturara después.
  `FragmentedMp4Muxer` fijaba el origen con el primer paquete que llegaba, y el audio sonaba
  adelantado: 64 ms en la prueba, con la pantalla capturada desde 0 y el micrófono desde +64 ms.
  Ahora retiene los primeros paquetes —una copia— hasta que todas las pistas han entregado el suyo y
  fija el origen en el más temprano, como `Mp4Muxer`; el nuevo origen del MKV funciona igual. La
  espera está acotada (los límites de fragmento en fMP4; 5 s o 16 MB en MKV) por si una pista
  declarada no llega a producir nada. Lo fijan `TrackStartOrderTest` y una prueba con FFmpeg real,
  que fallan si el origen se toma del primer paquete que llega.
- **El MKV escribía pistas sin `CodecPrivate`.** El MP4 generaba la configuración del audio que
  llegaba sin ella; el MKV la dejaba vacía. FFmpeg lo tolera, pero Media3/ExoPlayer lanza «Missing
  CodecPrivate» y el extractor nativo de Android descarta la pista AAC. Ahora los dos contenedores
  generan la misma (ASC para AAC, OpusHead para Opus con el `preSkip` sacado de `codecDelayUs`).
- **El audio sin marcar como `isKeyFrame` salía como no sincronizable.** `MediaPacket.isKeyFrame`
  vale `false` por defecto y los tres muxers lo copiaban también para AAC y Opus: el MP4 salía con
  un `stss` vacío, el fMP4 con todas las muestras dependientes y el MKV solo de audio sin un cue.
  Ahora el audio es siempre muestra de sincronización.
- **`PcmResampler` derivaba en grabaciones largas.** Acumulaba la fase sumando un `double` por frame
  y el redondeo crecía con la sesión: con trozos de 960 frames de 44,1 a 48 kHz se desviaba a los
  6,6 minutos y llegaba a 5 frames en tres horas, contra su propia promesa de convergencia exacta.
  La fase va ahora en enteros y es exacta para cualquier duración.
- **El fMP4 desalineaba el vídeo de frecuencia variable.** La duración del último fotograma de cada
  fragmento se estimaba con el intervalo anterior; en una captura de pantalla, que solo emite
  fotogramas cuando algo cambia, una pausa de 2 s se contaba como 16 ms y el tiempo de
  decodificación quedaba casi 2 s por detrás del de presentación, acumulándose pausa a pausa. Ahora
  se toma del keyframe que abre el fragmento siguiente.
- **`OpusConfig` lanzaba `NoSuchMethodError` por debajo del `minSdk` 34 que se documentaba**:
  compilado con JDK 17, `ByteBuffer.position(int)` enlaza con una sobrecarga covariante que Android
  no tiene al menos hasta la API 30 (comprobado contra sus firmas). Se leen los campos con accesos
  absolutos.
- **Abrir un MP4 largo escrito por esta librería tardaba más de 10 s.** El reparto de muestras en
  chunks recorría `stsc` desde el principio para cada chunk, y con audio y vídeo intercalados hay
  casi tantas entradas como chunks. Ahora es un único recorrido: 0,3 s para dos horas a 60 fps.
- **El `ContentEncoding` de Matroska se ignoraba**: un MKV con eliminación de cabecera entregaba
  fotogramas sin sus primeros bytes, y uno con zlib, los datos comprimidos. Se deshacen las dos; una
  pista cifrada o con bzlib/lzo se descarta con aviso. La descompresión tiene un techo de 64 MiB
  por fotograma contra las bombas de descompresión, fijado por un test a los dos lados del límite:
  un fotograma de 64 MiB llega entero y uno de un byte más se descarta con aviso.
- **El idioma de las pistas no llegaba al MP4** (`mdhd` salía siempre con `und`): un MKV con audio
  en dos idiomas, pasado a MP4 y vuelta, perdía idioma, nombre y pista predeterminada.
- **El MKV declaraba un fotograma menos de duración** que el MP4 cuando los paquetes no traían
  duración: el último fotograma de cada pista se contaba como si durase cero.
- **HE-AAC en MKV no era conforme**: solo se escribía la tasa de salida, en `SamplingFrequency`.
  Ahora va la del núcleo ahí y la de salida en `OutputSamplingFrequency`, como pide Matroska y como
  hace FFmpeg. Era la única limitación de conformidad documentada.
- **`entry_count` del `elst` sin acotar**: un valor manipulado hacía recorrer el resto del archivo
  como lista de edición. Se acota al tamaño de la caja.
- **Un `mvhd` con escala cero** descartaba toda pista con lista de edición por una división entre
  cero, y un `TimestampScale` cero en Matroska anulaba todas las marcas. Se validan.
- **`SeekableOutput.close()` filtraba el descriptor** si fallaba el último vaciado del buffer (disco
  lleno). Ahora lo cierra siempre.
- **`mp4FastStart` sustituía el original sin forzar el temporal a disco**: un corte de batería justo
  después del movimiento podía dejar el archivo con su nombre y sin su contenido. Se hace `force`
  antes de sustituir.
- **Desbordamientos silenciosos en las conversiones de escala de tiempo** (`valor * escala` con
  `Long`): ahora se detectan y se resuelven con aritmética exacta, saturando si no caben.

### Añadido

- `MkvKotlin.synchronizedMuxer(muxer)`: envoltura segura entre hilos para cuando el vídeo y el audio
  llegan desde callbacks distintos, que es el caso normal en Android.
- `MkvKotlin.createFragmentedMp4Muxer(...)` (para `File` y para `SeekableOutput`) y un constructor
  de `FragmentedMp4Muxer` con `File` y los dos límites de retención: la forma de bajar los 64 MB
  que retiene como mucho cada fragmento en un móvil con poca memoria. Son funciones nuevas, no
  parámetros añadidos a las existentes, para no cambiar ninguna firma.
- `SeekableOutput.sync()`: fuerza los datos escritos al almacenamiento físico.
- `MatroskaIds.CONTENT_*`: los ids de `ContentEncodings`.
- `RecordingAudioLayoutsTest`: la regla de audio como test de política, con los cinco montajes en
  MKV, MP4 y fMP4 y tras convertir entre ellos.
- `AndroidApiCompatibilityTest`: comprueba el bytecode compilado contra la firma oficial de la API 26
  de Android. Sus dependencias son solo de test; el artefacto sigue sin dependencias.
- `compat/kotlin-1.9` y su paso en el CI: una app mínima en Kotlin 1.9 que compila y ejecuta contra
  el artefacto recién publicado. Con el nivel anterior falla exactamente como la 1.0.0 («Module was
  compiled with an incompatible version of Kotlin»). Lleva su propio wrapper fijado en Gradle 8,
  porque el plugin de Kotlin 1.9 no funciona con Gradle 9.
- Los tres tests que comprueban que una operación fallida no deja archivos abiertos también miden en
  **Windows**: antes solo podían hacerlo en Linux y en cualquier otro sistema se omitían.

### Cambiado

- **`minSdk` documentado: de 34 a 26 (Android 8.0)**, comprobado por el test anterior.
- **Kotlin de la app: de 2.1 a 1.9 o superior.** El artefacto se compila en nivel de lenguaje y API
  2.0 y declara `kotlin-stdlib:2.0.21`; con Kotlin 2.2.10 en ambos, una app necesitaba Kotlin 2.1 o
  más para poder leer su metadata. El compilador de este proyecto no cambia.
- **`PcmMixer`, misma API y mismos bytes, ~6 veces más rápido** sin ganancias —el caso de mezclar
  micrófono y sistema tal cual—: la suma va en enteros, sin pasar cada muestra por `double` ni
  desempaquetar un `Float` por muestra y fuente. `stereoToMono` también sin coma flotante.
- **`PcmResampler`, misma API, ~25 % más rápido**, con la salida dimensionada exacta (sin la copia
  final de recorte).
- Medido sobre un MP4 de dos horas a 60 fps con AAC (~770 000 muestras):

  | | 2.1.1 | 3.0.0 |
  |---|---|---|
  | Abrir con `Mp4Demuxer` | ~10,6 s | ~0,27 s |
  | Heap del demuxer abierto | ~47,5 MB | ~29,3 MB |
  | `seekTo` | ~0,9 ms | ~5 µs |
  | Heap de `Mp4Muxer` antes de `stop()` | ~54 MB | ~34 MB |
  | `Mp4Muxer.stop()` | ~0,9 s | ~0,4 s |

  Vienen de guardar las tablas de muestras en columnas primitivas paginadas (sin un objeto por
  muestra ni copias al crecer), de construir el `moov`/`moof` sobre un solo array sin cerrojos, del
  recorrido lineal de `stsc` y del seek por bisección.
- `SeekableInput` lee directamente al array de destino los bloques grandes (una muestra de vídeo ya
  no se copia dos veces), y `MkvDemuxer` lee la carga de cada bloque sin copia intermedia.
- `NalUnits.annexBToLengthPrefixed` y `lengthPrefixedToAnnexB` trabajan en una sola pasada y sin
  arrays intermedios: es la conversión que se hace con cada fotograma de un codificador por hardware.
- El `moof` del fMP4 se construye una vez en lugar de dos.

## [2.1.1] — 2026-08-13

La 2.1.0 hizo que el fMP4 declarase su duración en `mehd`, que es el campo que le corresponde a un
archivo fragmentado. Resulta que declararla ahí no basta.

### Corregido

- **El fMP4 seguía durando cero para media plataforma.** El `mehd` es correcto, pero hay
  consumidores muy extendidos que **solo miran `tkhd.duration`** y ni leen el `mehd` ni recorren los
  fragmentos: para ellos el archivo dura cero, no muestran duración y una barra de reproducción
  construida sobre ese valor sale vacía. El extractor MP4 de Android es uno de ellos, y es el que
  alimenta el índice de medios del sistema.

  Al cerrar, el total ya se conoce, así que se rellenan también `tkhd.duration` en cada pista y
  `mvhd.duration`, los dos en la escala del `mvhd` — `tkhd.duration` no va en la escala del medio
  (ISO/IEC 14496-12 §8.3.2.3), y confundirlas no rompe nada visible: escribe una cifra plausible
  48 veces más larga. Las versiones de caja no cambian (`mvhd` v0, `tkhd` v0, `mehd` v1).

  No es una licencia sobre el formato: libavformat escribe las duraciones reales en el `tkhd` de una
  salida fragmentada siempre que cierra el `moov` conociendo el total. Un archivo ya cerrado en
  disco sabe cuánto dura, y lo dice.
- **La lista de edición contradecía al `tkhd`.** El `segment_duration` del `elst` salía a cero, que
  era la respuesta honesta mientras se grababa. Con el `tkhd` ya relleno pasaba a ser una
  contradicción: la duración de una pista es la suma de sus ediciones (§8.6.6), así que un lector
  que hiciera esa suma volvía a ver una pista de duración cero por el otro camino. Se rellena en el
  mismo paso, descontándole el cebado igual que hace el muxer plano.

### Cambiado

- La escritura hacia atrás del fMP4 al cerrar pasa de 8 bytes a unos pocos campos más de la
  cabecera. La garantía no se mueve: se reservan a cero, se rellenan al cerrar y, si el proceso
  muere antes, se quedan como estaban —que es la información que había cuando no existían— y lo
  grabado hasta el último fragmento completo se reproduce igual.

Los muxers MP4 no fragmentados no estaban afectados: ya declaraban las tres cosas. Los tests nuevos
comparan las dos salidas campo por campo, que es la comprobación que faltaba.

## [2.1.0] — 2026-08-11

Cinco metadatos que `TrackInfo` ya exponía, que `MkvMuxer` escribía y que los dos muxers MP4
descartaban. Los archivos que producían eran válidos —decodifican enteros, DTS monótonos, marcas
correctas—, así que ningún control de integridad los delataba: lo que faltaba era información
*sobre* el contenido.

Los dos muxers construyen sus cajas de pista por separado, así que los cuatro primeros cambios van
en ambos.

### Corregido

- **El nombre de pista no llegaba al archivo.** `TrackInfo.name` se aceptaba y se descartaba sin
  aviso. Con varias pistas del mismo tipo es lo único que permite distinguirlas al reproducir. Se
  escribe ahora en `udta` > `name` dentro del `trak`; el texto del `hdlr` no servía porque es el
  nombre del manejador y salía igual en todas.
- **El cebado del codificador se ignoraba**, así que el audio se reproducía adelantado respecto al
  vídeo — unos 21 ms con AAC-LC a 48 kHz. `codecDelayUs` pasa a compensarse en la lista de edición.
  Es fácil confundirlo con lo que el `elst` ya hacía: aquello colocaba el **desfase de arranque de
  la pista** con una edición vacía, y son dos cosas distintas. El cebado va en el `media_time` de
  la entrada real, en ticks del medio, y se descuenta de la duración del segmento para que la
  edición no se salga del final. El fMP4 no tenía ninguna lista de edición y ahora la escribe para
  las pistas que declaren retardo.
- **No se podía expresar qué pista es la predeterminada.** El `tkhd` salía con flags fijos a 3, así
  que las tres pistas de audio de una grabación se anunciaban todas como reproducibles y cada
  reproductor elegía una. MP4 no tiene un `FlagDefault` como Matroska, pero su equivalente
  reconocido es el bit `track_enabled` (0x1): ahora `TrackInfo.default` decide entre 3 y 2, con lo
  que se conserva `track_in_movie` en los dos casos.
- **Los archivos no llevaban fecha.** `creation_time` y `modification_time` salían a cero en
  `mvhd`, `tkhd` y `mdhd`, así que la fecha solo sobrevivía en el nombre del archivo. Es el
  equivalente del `DateUTC` que Matroska ya escribía. El origen de tiempos de MP4 es 1904-01-01,
  no 1970, y el ancho del campo sigue a la versión de la caja.
- **El fMP4 no declaraba su duración.** La de `mvhd` es cero —correcto mientras se graba, porque
  aún no se conoce— pero al cerrar tampoco se emitía `mehd`, así que el archivo nunca decía cuánto
  duraba y un reproductor tenía que recorrerse todos los fragmentos para averiguarlo.

> **Nota para quien mida esto con ffprobe.** Dos de los cinco no se ven donde parecería:
> `stream_tags=title` no muestra el nombre de pista de un MP4 e `initial_padding` sale 0 en las
> pistas de audio. **No es que no estén**: un MP4 producido por el propio ffmpeg, con
> `-metadata:s:a:0 title=...` y AAC, se comporta exactamente igual —escribe el nombre en
> `trak/udta/name`, la misma caja que escribimos aquí, y ffprobe tampoco lo enseña; y su
> `initial_padding` también es 0, porque ese campo se alimenta del `CodecDelay` de Matroska y no
> de la lista de edición de MP4—. Para comprobarlos hay que mirar las cajas: `trak/udta/name` y el
> `media_time` del `elst`, que a 48 kHz debe valer 1024.

### Añadido

- **`Mp4Muxer.creationTimeMillis` y `FragmentedMp4Muxer.creationTimeMillis`**, la fecha que se
  escribe en las cabeceras. `null` deja los campos a cero. Hay que asignarla **antes de `start()`**.

  Es una propiedad y no un parámetro del constructor a propósito: así el cambio es puramente
  aditivo y esta versión es una menor. Como parámetro habría cambiado la firma de dos constructores
  públicos y habría obligado a recompilar a todo el mundo por un metadato.

### Cambiado

- El fMP4 deja de ser **estrictamente** solo-añadir: al cerrar vuelve atrás a rellenar los 8 bytes
  del `mehd`. Es el único punto en el que lo hace, y el archivo es válido con o sin ese parche — si
  el proceso muere antes, el `mehd` se queda a cero, que es exactamente la información que había
  cuando no existía. La garantía que importa, que lo grabado hasta el último fragmento completo se
  reproduce, no cambia.

## [2.0.1] — 2026-08-11

### Corregido

- **`CodecDelay` solo se escribía en las pistas Opus**, así que en AAC el audio quedaba por detrás
  del vídeo. `TrackInfo.Audio.codecDelayUs` es parte de la API pública y cualquiera puede
  rellenarlo, pero la escritura vivía dentro del condicional de Opus: con AAC el valor se aceptaba
  y **se descartaba en silencio**, el archivo salía sin el elemento y el reproductor no compensaba
  el retardo de arranque del codificador — unos 21 ms con AAC-LC a 48 kHz, que es el caso normal.

  Matroska define `CodecDelay` para cualquier códec con retardo de arranque, así que ahora se
  escribe siempre que `codecDelayUs` sea mayor que cero. Lo que sí es propio de Opus, y sigue
  dentro del condicional, es el `SeekPreRoll` de 80 ms.

  En Opus no cambia nada: si hay `codecPrivate`, el `preSkip` de su `OpusHead` mantiene la
  preferencia sobre el campo del modelo, porque es el dato que describe el bitstream de verdad.

  El demuxer ya leía el elemento para cualquier códec, así que la asimetría estaba solo en la
  escritura: se podía releer un `CodecDelay` ajeno que nosotros mismos no sabíamos emitir.

### Documentación

- El KDoc de `TrackInfo.Audio.codecDelayUs` empezaba por «Solo Opus», que es justo la creencia que
  produjo el fallo. Ahora dice para qué sirve en cualquier códec y cuándo manda el `OpusHead`.

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
