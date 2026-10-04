# Contribuir a Kotmpeg Core

Gracias por el interés. Esto es lo que conviene saber antes de abrir un PR.

## La regla de audio, que no se toca

**El audio del sistema y el del micrófono se pueden grabar combinados en una pista, cada uno en la
suya, o cualquiera de los dos solo, y la mezcla junto a las dos sueltas.** Es lo que distingue a
esta librería para móviles y forma parte de su contrato público, así que ningún cambio puede
romperla:

- `PcmMixer` y `PcmResampler` se quedan, con su API tal cual. Se pueden optimizar por dentro, pero
  sin cambiar lo que devuelven: `PcmFastPathEquivalenceTest` los compara contra una copia literal de
  la implementación anterior.
- Los muxers tienen que admitir cualquier número de pistas de audio con nombre, idioma y una sola
  predeterminada, en MKV, MP4 y fMP4, y conservarlas al convertir.
- `RecordingAudioLayoutsTest` comprueba los cinco montajes. Si falla, el cambio está mal, no el test.

## Antes de empezar

- **Abre un issue primero** si el cambio es de alcance (un códec nuevo, un contenedor nuevo, un
  cambio de API). Hay decisiones de alcance ya tomadas y documentadas en
  [lo que no está implementado y por qué](README.md#lo-que-no-está-implementado-y-por-qué): merece
  la pena confirmar que la idea encaja antes de escribir código.
- Para un bug, un issue con el archivo que lo reproduce (o cómo generarlo con `ffmpeg`) vale más
  que cualquier descripción.
- **Si el cambio necesita una API de plataforma, no va aquí.** Este proyecto es Kotlin/JVM puro y
  su build no conoce ningún SDK: que la librería se pueda usar igual en un servidor y en Android es
  consecuencia directa de eso. Lo que necesite `MediaCodec`, `Surface` o cualquier `android.*` va
  en la capa que la integre, no en la librería.

## Tests

```bash
./gradlew test
```

Necesitan JDK 17 o superior y nada más. **Con `ffmpeg` en el PATH se activan además los tests de
integración end-to-end**, que son los que validan la salida contra binarios reales; sin él se
omiten en silencio, así que merece la pena instalarlo (`winget install Gyan.FFmpeg`,
`brew install ffmpeg` o `apt install ffmpeg`).

Tres tests comprueban que una operación fallida no deja archivos abiertos (`OpenFiles`): en Linux
leyendo `/proc/self/fd` y en Windows comprobando que el archivo se deja renombrar, que Windows impide
mientras está abierto. En macOS no hay forma fiable de medirlo y se saltan solos; el CI, en Linux,
sí los ejecuta. El build imprime todos los `SKIPPED` a propósito, para que un verde con media suite
sin ejecutar no pase por cobertura.

El [CI](.github/workflows/ci.yml) corre en cada push la suite con FFmpeg instalado —para que los de
integración no se omitan en silencio— y comprueba además que el artefacto se puede publicar de
verdad.

Los tests de integración generan archivos de referencia con FFmpeg (x264 con B-frames, x265, AAC
multipista, Opus), los remuxean con Kotmpeg en ambas direcciones — incluidos `mp4FastStart`, la
escritura de fMP4 propio y la lectura del fMP4 generado por FFmpeg con `frag_keyframe+empty_moov` —
y comprueban con `ffprobe` códecs, dimensiones, fps, número de paquetes y duración, además de
exigir una decodificación completa sin un solo error.

La suite de robustez ataca la librería con archivos truncados en varios puntos y con basura tras
una cabecera válida, y comprueba que el resultado siempre sea un error claro: nunca un bloqueo, un
consumo desbocado de memoria ni un archivo que se quede abierto.

### La API pública está congelada

`PublicApiTest` compara la superficie pública contra un volcado versionado
(`src/test/resources/public-api.txt`) y falla si cambia, diciendo qué firma se quitó y cuál se
añadió. Existe porque en Kotlin **añadir un parámetro con valor por defecto es compatible en código
fuente pero no a nivel binario**: cambia la firma JVM, y quien haya compilado contra una versión
anterior recibe un `NoSuchMethodError`.

Un cambio que solo **añade** miembros es compatible hacia atrás; uno que quita o modifica una firma
existente no lo es, y va declarado como `Cambios incompatibles` en el [CHANGELOG](CHANGELOG.md).

Cuando el cambio es intencionado, regenera el volcado y **revisa el diff**:

```bash
./gradlew test --tests "*PublicApiTest*" "-Dkotmpeg.api.update=true"
```

## Estilo

- **La documentación del código va en KDoc, no en comentarios sueltos.** El proyecto no usa
  comentarios `//`: lo que hay que explicar se explica en el KDoc de la clase, la función o la
  propiedad, que es donde el IDE lo enseña a quien la usa y donde no se duplica cada vez que se
  toca el cuerpo.
- **Y ese KDoc dice el *por qué*, no el *qué*.** La firma ya dice lo que hace. Buena parte de lo
  que está escrito en este proyecto existe porque hubo un fallo concreto, y es lo único que impide
  reintroducirlo: si tocas una función cuyo KDoc explica una decisión —un `floorDiv` que parece un
  `/`, un `require` que parece de más, un orden de dos líneas que parece indiferente—, ese texto no
  sobra, es la razón por la que el código está así.
- **Una excepción, y solo una: el código que serializa o parsea formato binario byte a byte.** Ahí
  el nombre del campo de la especificación va en la propia línea (`u32(0)  // reservado`,
  `out.write(1)  // configurationVersion`), porque no hay ninguna declaración a la que colgarlo y
  es lo único que permite contrastar el código contra el estándar. Meterlo en el KDoc de la función
  sería duplicar la tabla de campos y condenarla a desincronizarse del cuerpo. Vale para estos ocho
  archivos y para ningún otro:

  ```
  mp4/Mp4Muxer.kt   mp4/Mp4Demuxer.kt   mp4/FragmentedMp4Muxer.kt   mp4/SampleEntries.kt
  codecconfig/NalUnits.kt   codecconfig/OpusConfig.kt   model/ColorInfo.kt   ebml/EbmlWriter.kt
  ```

  La regla no depende de que nadie se acuerde: `CommentPolicyTest` falla si aparece un comentario
  fuera de esos ocho archivos, y también si el número de anotaciones dentro de ellos baja de lo que
  hay registrado.
- El código y el KDoc están en español. Los nombres de identificadores y de tests, en inglés.
- `explicitApi()` está activo: cualquier miembro público necesita visibilidad y tipo de retorno
  explícitos. Piensa dos veces antes de ampliar la superficie pública.
- **Cero imports de `android.*` o `androidx.*`.** No es una preferencia: el build no tiene el SDK
  de Android, así que el primero que aparezca no compila.
- **No subas el nivel de Kotlin del artefacto sin mirar a las apps.** `languageVersion`/`apiVersion`
  (2.0) y `coreLibrariesVersion` (2.0.21) en `build.gradle.kts` deciden qué Kotlin necesita la app
  que consume la librería, no el compilador de aquí. El CI compila `compat/kotlin-1.9` contra cada
  artefacto; si ese paso falla, la librería se volvería inservible para esas apps, como pasó con la
  1.0.0. Ese proyecto lleva su propio wrapper fijado en Gradle 8, porque el plugin de Kotlin 1.9 no
  funciona con Gradle 9: el Gradle de aquí se puede subir, el suyo no.
- **Solo API del JDK que exista en Android 8.0 (API 26).** Compilar contra JDK 17 no avisa de nada,
  así que lo vigila `AndroidApiCompatibilityTest` contra la firma oficial de la API 26. Ojo con las
  sobrecargas covariantes de JDK 9+ (`ByteBuffer.position(int)`, `flip()`, `limit(int)`…): en
  Android antiguo no existen, y la alternativa es un acceso absoluto (`getShort(10)`) o llamarlas
  sobre `java.nio.Buffer`. `Math.multiplyHigh` tampoco (API 31).

## Cambios de API

Desde la 1.0.0 la API pública es estable: **un cambio incompatible exige subir la versión mayor**, y
va declarado en el [CHANGELOG](CHANGELOG.md) como `Cambios incompatibles`. Antes de proponer uno,
comprueba si lo que necesitas cabe como adición — añadir miembros no rompe a nadie y sale por una
menor.

## Subir de versión: los tres sitios

La versión vive en `build.gradle.kts` porque es la que acaba en el POM y en el nombre del jar, pero
hay dos copias en la documentación que **hay que tocar en el mismo commit**. Si se desincronizan,
el artefacto sale etiquetado con una versión y la documentación que lo acompaña anuncia otra, y eso
no se puede arreglar sin publicar de nuevo.

| Archivo | Qué hay que cambiar |
|---|---|
| `build.gradle.kts` | `version = "..."` — la fuente de verdad |
| `CHANGELOG.md` | La entrada nueva, con su fecha |
| `README.md` | La cabecera «Versión: …», las dos coordenadas de ejemplo y el `-PkotmpegVersion=` del comando de `compat/kotlin-1.9` |

`SECURITY.md` lleva la serie soportada (`3.x`), no una versión exacta, así que solo se toca al
subir la mayor.

Y al publicar, el **tag de git tiene que llamarse exactamente igual que la versión**: JitPack usa el
nombre del tag como número de versión del artefacto, y no mira el `version` del `build.gradle.kts`.
Si los dos no coinciden, el artefacto se publica con el número del tag y el POM dice otra cosa.

### Cuándo subir: mientras no haya tag, no se sube

Una versión sin tag **no está publicada**, así que sigue en preparación y todo lo nuevo va dentro de
ella, sean dos correcciones o veinte. Subir el número por cada tanda dejaría un rastro de versiones
que nadie puede descargar. La entrada del CHANGELOG se encabeza mientras tanto con
`— sin publicar`, y la fecha se escribe en el commit que crea el tag, que es cuando pasa a ser
cierta.

Lo mismo vale para las marcas de «en preparación» que se pongan mientras tanto en el resto de la
documentación. En el commit del tag, además de fechar el CHANGELOG:

| Archivo | Qué se quita |
|---|---|
| `README.md` | El paréntesis «(en preparación: …)» de la cabecera «Versión: …» |
| `SECURITY.md` | Si se sube la mayor, la fila de la serie anterior («`2.x` … hasta que se publique…») |

Se olvidan con facilidad porque nada falla si siguen ahí, y el resultado es una versión publicada
cuya portada dice que todavía no lo está.

### Cuánto subir: lo decide `public-api.txt`

| Qué le pasa a `public-api.txt` | Versión |
|---|---|
| No se mueve | patch: `1.0.0` → `1.0.1` |
| Solo aparecen líneas nuevas | menor: `1.0.0` → `1.1.0` |
| Desaparece o cambia una línea | **mayor**: `1.0.0` → `2.0.0` |

El disparador es objetivo y lo comprueba un test, así que no hay que juzgar si un cambio «es
grande»: basta con mirar de qué lado cae el diff del volcado.

Con una salvedad: **un cambio de comportamiento observable del que alguien pueda depender también
exige la mayor**, aunque el volcado solo gane líneas. Es el caso de la `3.0.0`: no quita ninguna
firma, pero `MkvMuxer` pasó a empezar la línea de tiempo en el paquete más temprano y `Mp4Demuxer` a
devolver idioma, nombre y pista predeterminada reales. Esos cambios van en `Cambios incompatibles`
igual que una firma rota, con lo que hay que revisar.

Y el caso que no parece incompatible pero lo es: **en Kotlin, añadir un parámetro con valor por
defecto es compatible en código fuente pero no a nivel binario**. El volcado lo delata porque la
firma JVM cambia, así que añadir un campo a algo como `AacConfig.Parsed` cae en la tercera fila y
no en la segunda. Las correcciones internas del demuxer, en cambio, no mueven el volcado: patch.

## Cómo está organizada la suite

Los tests se llaman por **lo que cubren**, no por cuándo se escribieron: `ContainerHardeningTest`,
`UntrustedTableSizesTest`, `LargeFileEdgeCasesTest`, `FastStartSafetyTest`, `HeAacConfigTest`,
`DisplayGeometryTest`, `WarningChannelTest`… Si vas a tocar una zona delicada —marcas de tiempo,
tablas del demuxer, el cierre con `mp4FastStart`— el KDoc de la clase que la cubre explica qué
fallaba antes, con qué archivo se reproducía y por qué el código está como está. Casi todos vienen
de un fallo real, no de un caso imaginado.

Cuatro de ellos no comprueban una funcionalidad sino una **política**, y por eso no se archivan nunca:

| | |
|---|---|
| `RecordingAudioLayoutsTest` | la regla de audio: mezcla, micrófono, sistema, separadas y mezcla+separadas |
| `PublicApiTest` | congela la superficie pública en `public-api.txt` |
| `CommentPolicyTest` | la forma de documentar el código, con su única excepción |
| `AndroidApiCompatibilityTest` | que el bytecode solo use API del JDK presente en Android 8.0 |

Cuando uno falla, o el cambio es intencionado —y entonces se regenera el volcado o se ajusta el
número en el mismo commit, que es una decisión con nombre— o es un descuido que acaba de evitarse.

## Antes de publicar

Un tag deja de ser reparable en silencio: a partir de ahí el artefacto está en manos de terceros.
Antes de crear uno:

- Corre la suite **con ffmpeg instalado**. Sin él se omiten los tests de integración, que son los
  únicos que comparan la salida real contra un decodificador de verdad.
- Comprueba que `public-api.txt` refleja la API que vas a publicar.
- Comprueba que `./gradlew publishToMavenLocal` deja el jar, el jar de fuentes y el POM, y que desde
  `compat/kotlin-1.9`, con su propio wrapper, `sh gradlew run -PkotmpegVersion=<versión>`
  (`.\gradlew.bat` en Windows) compila y termina con `OK`.
- Fecha la entrada del CHANGELOG **en el mismo commit que crea el tag**, no antes, y quita en ese
  mismo commit las marcas de «en preparación» del README y de `SECURITY.md`.
