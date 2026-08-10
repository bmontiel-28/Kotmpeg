# Política de seguridad

## Versiones soportadas

| Versión | Soporte |
|---|---|
| `1.x` | ✅ |

Se da soporte a la última versión publicada de la serie mayor en curso.

## Cómo reportar una vulnerabilidad

**No abras un issue público.** Usa el reporte privado de seguridad del repositorio —en GitHub,
*Security → Advisories → Report a vulnerability*—, que no deja el detalle a la vista mientras se
corrige.

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
- una excepción no tipada donde debería haber un fin de stream limpio.

La política declarada es que un archivo dañado produzca **un error claro o un fin de stream**, nunca
un cuelgue ni un consumo sin control. Hay tres suites dedicadas a esto —`RobustnessTest`,
`UntrustedTableSizesTest` y `LargeFileEdgeCasesTest`—; un caso que las burle es un reporte útil.

## Qué no lo es

- Que un códec no soportado se descarte: es el comportamiento documentado, y además se avisa por
  `onWarning`.
- Los límites declarados como no-objetivos en el
  [README](README.md#lo-que-no-está-implementado-y-por-qué), como no reconstruir un MP4 sin `moov`.
- Un `OutOfMemoryError` al abrir un archivo legítimo y enorme con `Mp4Demuxer`: el coste del mapa
  completo de muestras está medido y documentado en el
  [README](README.md#memoria-al-leer-archivos-largos). Es un límite conocido del formato, no un
  fallo de validación.
