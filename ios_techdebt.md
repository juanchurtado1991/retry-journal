# iOS Tech Debt — resolver en macOS

**Estado en Linux (2026-07-14, última sesión):** `ghost-sync` **1.0.0** está listo en JVM — API `flush()` + `getHeadState()`, state machine en `HeadReplayExecutor`, **181 tests JVM**, Kover ≥90 %, `ciTestJvm ciCompile ciCoverage` en verde. Bug hunts hasta **ronda 20** completados (ver `CHANGELOG.md`/`git log` para detalle; rondas 1–18 por un agente Cursor, 19–20 por Claude Code en esta sesión). **Solo falta validar iOS en Mac** antes de publicar a Maven Central.

**Rondas 19–20 (esta sesión, 2026-07-14):**
- Ronda 19 (`HeadReplayExecutor.kt`): cancelar `flush()` a mitad de una llamada HTTP dejaba el `ReplayClaim` cross-process sin liberar — una limpieza `suspend` dentro de `catch (CancellationException)` se descartaba silenciosamente al estar el job ya cancelado. Arreglado con `withContext(NonCancellable)`. También se corrigió `completeHeadReplayOrStop` tragándose `CancellationException` en su `catch (Throwable)`.
- Ronda 20 (`DiskQueueConstants.kt` / `DiskQueue.kt`): el empaquetado del índice en memoria (antes 26 bits) no alcanzaba para un solo campo al tamaño máximo documentado por defecto (64 MiB) más el overhead fijo del registro — cualquier body en el límite anunciado en el README siempre fallaba con `RecordTooLargeException`. Ampliado a 30 bits + guard en el constructor de `DiskQueue` que rechaza un `maxRecordFieldSize` imposible de empaquetar.
- Ambos verificados empíricamente (test que falla contra el código pre-fix, pasa post-fix) antes de aplicarse. Ver commits `fix: bug hunt round 19/20 ...` y `test: add bug hunt round 19/20 regression suite ...`.

> ⚠️ **Reescritura de historial (2026-07-14):** se eliminaron todos los trailers `Co-authored-by:` (Claude y Cursor) de los 153 commits de `main` a pedido del usuario — **todos los SHA de `main` cambiaron**. El contenido de los archivos es idéntico (verificado con `git diff` árbol-a-árbol antes/después, vacío). Queda una rama local `backup/pre-coauthor-strip` en la máquina Linux con el historial original por si hace falta. **El remoto `origin` (GitHub) estaba vacío — nada se había pusheado nunca**, así que no hay conflicto de historial divergente que resolver ahí.
>
> **Si en la Mac ya existe un clon previo de este repo** (de antes de esta sesión), **no hagas `git pull`** — los SHA no coinciden con los nuevos. Hay que traer el historial reescrito de otra forma: re-clonar desde donde se termine pusheando esta versión, o transferir con `git bundle`/`scp` el repo Linux tal cual. Si la Mac no tiene clon todavía, simplemente clona desde donde termine viviendo el remoto una vez se pushee.

> Entorno requerido: macOS con Xcode instalado, JDK 17, y este repo clonado.

---

## 0. Handoff desde Linux (antes de abrir Xcode)

En la Mac, tras clonar o transferir el repo (ver aviso de reescritura de historial arriba):

```bash
# Verificar que traes los commits recientes (ronda 20 debe ser el HEAD, o cerca)
git log -6 --oneline

# Paridad CI en Mac (JVM — debe pasar igual que en Linux: 181 tests)
./gradlew ciTestJvm ciCompile ciCoverage --no-daemon

# Compilar sample desktop (sanity check rápido, opcional)
./gradlew :sync-sample:composeApp:run
```

**Commits esperados en la punta de `main`:** `test: add bug hunt round 20 regression suite (181 JVM tests)` sobre `fix: bug hunt round 20 hardening on max record field packing` sobre `test: add bug hunt round 19 ...` / `fix: bug hunt round 19 ...`.

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
# Terminal 1 — chaos server (misma máquina)
./gradlew :sync-sample:server:run

# Terminal 2 — desde sync-sample/iosApp/ (crear .xcodeproj una vez — ver iosApp/README.md)
xcodebuild -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' build
```

**Verificar manualmente:**
- [ ] La app arranca en simulador
- [ ] `GhostSyncWorker` (kmpworkmanager) se registra sin crash al inicio
- [ ] Peticiones offline se encolan (botón de mutación con servidor apagado / modo avión)
- [ ] `runtime.flush()` / **Sync now** entrega las peticiones con servidor encendido
- [ ] **Head chip** — el primer chip y el subtítulo bajo "Pending" reflejan `getHeadState()` (`Awaiting replay`, `finishing local removal`, `blocked`)
- [ ] Dead-letter visible cuando el chaos server devuelve 400

**Referencia:** `sync-sample/iosApp/README.md`, `sync-sample/README.md`

---

## 3. Ktor Darwin engine (obligatorio)

El sample usa `ktor-client-darwin` en `iosMain`. Confirmar que:
- [ ] Peticiones HTTP reales salen por red (no solo compile)
- [ ] `OfflineQueuedException` se lanza con modo avión / sin red
- [ ] Multipart upload funciona en iOS (si el botón de upload está habilitado en la UI)

---

## 4. Cobertura de tests (JVM — ya verificado en Linux)

Kover mide **solo `commonMain` + `jvmMain`** durante `jvmTest`. Los source sets `androidMain` e `iosMain` están excluidos del gate porque no tienen unit tests en CI Linux.

| Target | Medido en CI | Gate |
|--------|--------------|------|
| JVM (`jvmTest`) | Sí | ≥ 90 % líneas |
| Android (`androidMain`) | No | — |
| iOS (`iosMain`) | No | — |

Comandos (repetir en Mac por si acaso):

```bash
./gradlew :ghost-sync:jvmTest
./gradlew ciCoverage
./gradlew :ghost-sync:koverHtmlReport   # ghost-sync/build/reports/kover/html/index.html
```

### Android (deuda)

No hay `androidUnitTest` hoy. Cuando exista, añadir `:ghost-sync:testDebugUnitTest` a CI y revisar si el gate Kover debe incluir `androidMain`.

---

## 5. Tests iOS (recomendado — cerrar deuda)

Hoy **no hay tests nativos iOS**. Opciones:

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

## 6. Publicación Maven 1.0.0 — artefactos iOS

Al publicar con `./gradlew publishToMavenCentral`, verificar que los klibs iOS aparecen en el staging:

```bash
./gradlew :ghost-sync:publishAllPublicationsToSonatypeRepository --no-daemon
# Revisar en Sonatype UI: iosArm64, iosSimulatorArm64, jvm, android
```

**Versión:** `1.0.0` (`gradle/libs.versions.toml` → `publish-version`)

**Requisitos locales (una sola vez en Mac o Linux con GPG):**
- `sonatypeUsername` / `sonatypePassword` en `gradle.properties` o `~/.gradle/gradle.properties`
- GPG signing configurado (`signing.keyId`, `signing.password`, `signing.secretKeyRingFile`)

**Orden sugerido:** ítems 1–3 de este doc → tag `v1.0.0` → publish → Sonatype promote.

---

## 7. Checklist final en Mac

| # | Tarea | Estado |
|---|-------|--------|
| 0 | `git pull` + `ciTestJvm ciCoverage` en Mac | ⬜ |
| 1 | Compilación iOS (6 targets) | ⬜ |
| 2 | Sample corre en simulador | ⬜ |
| 3 | Offline queue + flush E2E | ⬜ |
| 4 | `getHeadState()` visible en UI (chip + subtítulo Pending) | ⬜ |
| 5 | kmpworkmanager worker en background | ⬜ |
| 6 | (Opcional) `iosSimulatorArm64Test` | ⬜ |
| 7 | (Opcional) CI job macOS en GitHub | ⬜ |
| 8 | (Release) klibs iOS en Sonatype staging + tag `v1.0.0` | ⬜ |

Cuando los ítems **0–4** estén marcados, **iOS queda validado para publicar `1.0.0`**.

---

## Comandos rápidos de diagnóstico

```bash
# Ver targets iOS disponibles
./gradlew tasks --group="ios" | head -30

# Limpiar y recompilar solo ghost-sync iOS
./gradlew :ghost-sync:clean :ghost-sync:compileKotlinIosSimulatorArm64 --no-daemon

# Logs detallados si falla cinterop
./gradlew :ghost-sync:compileKotlinIosArm64 --info --no-daemon 2>&1 | tail -100

# Embed framework para Xcode (desde Gradle task del template KMP)
./gradlew :sync-sample:composeApp:embedAndSignAppleFrameworkForXcode
```

---

## Qué ya NO hace falta en Mac

- Refactor 1.0.0 / state machine — hecho en Linux
- Tests JVM / Kover — verdes en Linux (`181` tests, bug hunts hasta ronda 20)
- Sample desktop — `getHeadState()` en chip de cabeza + subtítulo Pending
- Manual replay APIs — eliminadas; sample usa solo `runtime.flush()`
