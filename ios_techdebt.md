# iOS Tech Debt — resolver en macOS

Todo lo demás del checklist de producción está resuelto en Linux. **Solo queda verificar y cerrar estos ítems en una Mac** antes de declarar `ghost-sync` listo en iOS.

> Entorno requerido: macOS con Xcode instalado, JDK 17, y este repo clonado.

---

## 1. Compilación KMP (obligatorio)

```bash
./gradlew \
  :ghost-sync:compileKotlinIosArm64 \
  :ghost-sync:compileKotlinIosSimulatorArm64 \
  :sync-sample:shared:compileKotlinIosArm64 \
  :sync-sample:shared:compileKotlinIosSimulatorArm64 \
  :sync-sample:composeApp:compileKotlinIosArm64 \
  :sync-sample:composeApp:compileKotlinIosSimulatorArm64 \
  --no-daemon
```

**Criterio de éxito:** las 6 tareas terminan sin errores.

**Si falla:** revisar `ghost-sync/src/iosMain/kotlin/com/ghostserializer/sync/queue/CurrentTimeMillis.kt` (cinterop `gettimeofday`) y `PlatformQueueFileLock.kt` (cinterop `flock`), además de dependencias Ghost/Ktor en targets nativos.

---

## 2. Sample app iOS (obligatorio)

```bash
# Desde sync-sample/iosApp/ — abrir en Xcode o:
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' build
```

**Verificar manualmente:**
- [ ] La app arranca en simulador
- [ ] `GhostSyncWorker` (kmpworkmanager) se registra sin crash al inicio
- [ ] Peticiones offline se encolan (botón de mutación con servidor apagado)
- [ ] `flush()` / sync manual entrega las peticiones con servidor encendido
- [ ] Dead-letter visible cuando el chaos server devuelve 400

**Referencia:** `sync-sample/iosApp/README.md`

---

## 3. Ktor Darwin engine (obligatorio)

El sample usa `ktor-client-darwin` en `iosMain`. Confirmar que:
- [ ] Peticiones HTTP reales salen por red (no solo compile)
- [ ] `OfflineQueuedException` se lanza con modo avión / sin red
- [ ] Multipart upload funciona en iOS (si el botón de upload está habilitado en la UI)

---

## 4. Cobertura de tests (JVM)

Kover mide **solo `commonMain` + `jvmMain`** durante `jvmTest`. Los source sets `androidMain` e `iosMain` están excluidos del gate porque no tienen unit tests en CI Linux (ver §4 tests iOS y deuda Android abajo).

| Target | Medido en CI | Gate |
|--------|--------------|------|
| JVM (`jvmTest`) | Sí | ≥ 90 % líneas |
| Android (`androidMain`) | No | — |
| iOS (`iosMain`) | No | — |

Comandos locales:

```bash
./gradlew :ghost-sync:jvmTest
./gradlew ciCoverage          # gate Kover
./gradlew :ghost-sync:koverHtmlReport   # ghost-sync/build/reports/kover/html/index.html
```

Los serializers KSP (`*Serializer` en `com.ghostserializer.sync.queue`) y el paquete `com.ghost.serialization.generated` están excluidos del informe — se ejercitan indirectamente vía `Ghost.encodeToBytes` / `DiskQueue`.

### Android (deuda)

No hay `androidUnitTest` hoy. Cuando exista, añadir `:ghost-sync:testDebugUnitTest` a CI y revisar si el gate Kover debe incluir `androidMain`.

---

## 5. Tests iOS (recomendado — cerrar deuda)

Hoy **no hay tests nativos iOS**. Opciones para cerrar la deuda:

### Opción A — mínima (smoke test en CI macOS)
Añadir job `ios` en `.github/workflows/ci.yml` con `macos-latest` que ejecute solo la compilación del paso 1.

### Opción B — ideal
Crear `ghost-sync/src/iosTest/` con al menos:
- `DiskQueue` enqueue/peek/remove con `FileSystem.SYSTEM` en directorio temporal
- `GhostSyncEngine.flush()` con `ktor-client-darwin` + mock server local

```bash
./gradlew :ghost-sync:iosSimulatorArm64Test --no-daemon
```

---

## 6. Publicación Maven — artefactos iOS (antes del primer release)

Al publicar con `./gradlew publishToMavenCentral`, verificar que los klibs iOS aparecen en el staging:

```bash
./gradlew :ghost-sync:publishAllPublicationsToSonatypeRepository --no-daemon
# Revisar en Sonatype UI que existen: iosArm64, iosSimulatorArm64
```

**Requisitos locales (una sola vez):**
- `sonatypeUsername` / `sonatypePassword` en `gradle.properties` o `~/.gradle/gradle.properties`
- GPG signing configurado (`signing.keyId`, `signing.password`, `signing.secretKeyRingFile`)

---

## 7. Checklist final en Mac

| # | Tarea | Estado |
|---|-------|--------|
| 1 | Compilación iOS (6 targets) | ⬜ |
| 2 | Sample corre en simulador | ⬜ |
| 3 | Offline queue + flush E2E | ⬜ |
| 4 | kmpworkmanager worker en background | ⬜ |
| 5 | (Opcional) `iosSimulatorArm64Test` | ⬜ |
| 6 | (Opcional) CI job macOS en GitHub | ⬜ |
| 7 | (Pre-release) klibs iOS en Sonatype staging | ⬜ |

Cuando los ítems 1–3 estén marcados, **iOS queda validado para `0.1.0`**.

---

## Comandos rápidos de diagnóstico

```bash
# Ver targets iOS disponibles
./gradlew tasks --group="ios" | head -30

# Limpiar y recompilar solo ghost-sync iOS
./gradlew :ghost-sync:clean :ghost-sync:compileKotlinIosSimulatorArm64 --no-daemon

# Logs detallados si falla cinterop
./gradlew :ghost-sync:compileKotlinIosArm64 --info --no-daemon 2>&1 | tail -100
```
