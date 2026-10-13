# Política de seguridad

## Versiones soportadas

| Versión | Soporte |
|---|---|
| `3.x` | ✅ |

Se da soporte a la última versión publicada de la serie mayor en curso.

## Cómo reportar una vulnerabilidad

**No abras un issue público.** Usa
[GitHub Security Advisories](https://github.com/bmontiel-28/Kotmpeg/security/advisories/new), que
permite un reporte privado y no deja el detalle a la vista mientras se corrige.

Incluye, si puedes, el archivo que lo reproduce (o cómo generarlo) y qué se observa: una excepción
concreta, un consumo de memoria desbocado, un cuelgue.

## Qué se considera vulnerabilidad aquí

Kotmpeg Core parsea **archivos que el usuario elige**, así que la entrada no es de confianza — y
esta es toda la superficie de ataque del proyecto: no hay red, ni reflexión, ni JNI, ni deserialización.
Interesa sobre todo cualquier archivo MKV o MP4 —manipulado o simplemente corrupto— que provoque:

- un consumo de memoria desproporcionado respecto a su tamaño (una tabla que declara millones de
  entradas con unos pocos bytes);
- un bucle que no termina o un tiempo de apertura desproporcionado;
- una lectura fuera de los límites del archivo;
- una excepción no tipada donde debería haber un fin de stream limpio;
- un bloque comprimido de Matroska que descomprima de forma desproporcionada (el techo es de
  64 MiB por fotograma).

La política declarada es que un archivo dañado produzca **un error claro o un fin de stream**, nunca
un cuelgue ni un consumo sin control. Hay cuatro suites dedicadas a esto —`RobustnessTest`,
`UntrustedTableSizesTest`, `LargeFileEdgeCasesTest` y `Mp4HeaderHardeningTest`—, y
`MatroskaContentEncodingTest` cubre los bloques comprimidos de Matroska: dañados, con una
codificación que no se soporta o que pasan del techo de descompresión; un caso que las burle es un
reporte útil.

Desde la `3.1.0` la política se comprueba además por **fuzzing** (`DemuxerFuzzTest`): archivos MKV,
MP4 y fMP4 válidos, mutados a ciegas y conociendo su estructura, se pasan por los dos demuxers y
cada variante tiene que terminar con un error tipado —de E/S, `EbmlException`,
`IllegalArgumentException` o `IllegalStateException`— o con un fin de stream limpio, sin cuelgues,
sin `Error`, sin dejar el archivo abierto y sin reservar memoria desproporcionada. Corre en cada
ejecución de los tests con una pasada fija, y cada semana en el CI con una campaña larga y semilla
nueva. Cada fallo que ha encontrado queda fijado por un test que reconstruye la entrada que lo
provocaba.

Si encuentras un archivo que la incumpla, puedes comprobarlo antes de reportar con una campaña
local (los detalles, en el [README](README.md#fuzzing)); el archivo que deja en `build/fuzz-findings/`
es justo lo que conviene adjuntar al reporte privado.

## Qué no lo es

- Que un códec no soportado se descarte: es el comportamiento documentado, y además se avisa por
  `onWarning`.
- Los límites declarados como no-objetivos en el
  [README](README.md#lo-que-no-está-implementado-y-por-qué), como no reconstruir un MP4 sin `moov`.
- Hasta la `3.0.0`, un `OutOfMemoryError` al abrir con `Mp4Demuxer` un archivo legítimo de muchas
  horas, por el mapa completo de muestras que construía. Desde la `3.1.0` ese mapa ya no existe (ver
  el [README](README.md#memoria-al-leer-archivos-largos)), así que en la serie actual un caso así
  **sí** es un reporte útil.
