# Convenciones de código — Ghost-Sync KMP

Reglas obligatorias para todo el código Kotlin de este repositorio (librería `:ghost-sync` y módulos `sample/*`). Ver el plan de arquitectura para el razonamiento completo detrás de cada decisión de diseño.

1. **No magic strings.** Ninguna literal de texto repetida o significativa suelta en el código: nombres de cabeceras HTTP, extensiones de archivo, nombres de query params, etc. viven en un objeto de constantes.
2. **No magic numbers.** Igual que la regla 1 pero para literales numéricas: tamaños de buffer, timeouts, códigos de estado HTTP, umbrales de compactación.
3. **Zero allocation.** Evitar asignaciones de heap innecesarias en steady-state. Reutilizar buffers y pools en vez de crear objetos nuevos en cada llamada, siguiendo el patrón `acquireScratchBuffer` / `releaseScratchBuffer` que ya usa Ghost.
4. **Comparación bitwise cuando haya beneficio real.** No forzarla donde no aporte nada medible; sí usarla donde sustituya una comparación más cara (flags de estado, máscaras).
5. **No loops redundantes.** Una sola pasada donde sea posible. Nunca iterar dos veces algo que se puede resolver en una.
6. **`if` siempre con llaves.** Ninguna rama de una sola línea sin `{ }`, sin excepción.
7. **Un archivo de constantes centralizado por área.** Ej. `DiskQueueConstants.kt`, `SyncEngineConstants.kt`. Nunca literales sueltas dentro de la clase que las usa.
8. **Carpetas ordenadas.** Estructura de paquetes por responsabilidad: `queue/`, `deadletter/`, `client/`, `engine/` dentro de `:ghost-sync`.
9. **Un archivo por clase.** Sin agrupar clases no relacionadas en el mismo `.kt`. Excepción estándar de Kotlin: una sealed hierarchy pequeña y sus `data class` hijas pueden coexistir si son un único concepto (ej. `FlushResult` y sus variantes).
10. **Optimización extrema donde sea posible.** Cada punto caliente (serialización, framing, hashing) se diseña primero para el caso feliz sin allocations; se mide con benchmarks, no por intuición.

## Decisiones de arquitectura fijas

- `:ghost-sync` es la **única dependencia de dominio** (serialización + red), basada en el motor Ghost (`com.ghostserializer:*`). Toda la infraestructura de I/O usa **Okio** (ya transitivo vía Ghost) — nunca `kotlinx-io`.
- `:ghost-sync` **no depende de ningún scheduler** (`kmpworkmanager`, `androidx.work`, etc.). `GhostSyncEngine.flush()` es una `suspend fun` agnóstica que cualquier infraestructura de despacho puede invocar. `kmpworkmanager` solo se usa como integración de referencia en `sample/composeApp`.
- Un solo módulo Gradle publicado (`:ghost-sync`) en vez de dividir en `core`/`client`/`engine`: nadie consume una parte sin las demás, así que dividir sería overhead de Gradle sin beneficio real.
