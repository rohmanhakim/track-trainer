# AGENTS.md

Instructions for AI coding agents working on **track-trainer**.

## What this project is

A personal-use Android app: a practice/player app for guitarists working with
"play-along" tab videos. Core: local + YouTube video playback (offline-cached),
speed control, A-B loop, animated count-in, tap-tempo metronome, volume boost.

**Full requirements live in `docs/` — read them before doing anything:**

| Doc | Read when |
|---|---|
| `docs/01-product-overview.md` | You need features, scope, non-goals, UX layouts |
| `docs/02-architecture.md` | You need stack, package layout, pipelines, data model, decisions (D1–D8) |
| `docs/03-roadmap.md` | You need the current phase, stories, acceptance criteria, Definition of Done |
| `docs/04-risks.md` | Touching risky areas (YouTube extraction, audio sync, caching) |

## Current status

- **Phase 1 complete** (exited 2026-10-26): Compose + M3 foundation, local video playback,
  speed control 0.25×–2×, portrait study layout + graceful landscape fallback, all on-device
  verified (emulator API 37 + Redmi API 35). `docs/tasks/phase-1.md` — T01–T07 all `done`.
- **Current execution checklist:** `docs/tasks/phase-2.md` — 6 session tasks (T01–T06).
  Work through them in order; T01 (NewPipe spike, gate G1 → D2) is the first task. Update each
  task's status + Session notes as you go.
- Then follow `docs/03-roadmap.md`: one phase at a time, stories + acceptance
  criteria, Definition of Done applies (§10).

## Hard rules

1. **Scope authority is the docs.** If a request contradicts `docs/`, say so and
   ask; don't silently deviate. V2 ideas → icebox (`01-product-overview` §4.2).
2. **Personal use only.** No accounts, analytics, cloud, Play Store compliance
   work. Sideload APK distribution (decision D1).
3. **Stack is fixed** (`02-architecture` §1): Kotlin, Jetpack Compose + M3,
   Media3 ExoPlayer, Hilt, Room, DataStore, coroutines/Flow, Gradle KDL +
   version catalog, single `:app` module, package-by-feature.
4. **Don't grow scope mid-phase.** New ideas → note them for grooming; don't
   implement them.

## Build & test commands

Gradle 9.5.0 / AGP 9.3.3, configuration cache enabled (first build is slow —
subsequent ones are not). `local.properties` is machine-specific — never commit it.

**Environment: no system Java.** Prefix Gradle with Android Studio's bundled JBR:

```bash
export JAVA_HOME=/home/arif/android-studio/jbr   # add to ~/.bashrc to make permanent
```

```bash
# Build              ✓ verified BUILD SUCCESSFUL (7 s, config cache warm)
./gradlew assembleDebug
# Unit tests         ✓ verified BUILD SUCCESSFUL
./gradlew testDebugUnitTest
# Instrumented tests (device required — not run yet)
./gradlew connectedDebugAndroidTest
# Lint               ✓ verified BUILD SUCCESSFUL (first run downloads ~100 MB of
#                    lint jars from dl.google.com — takes minutes, may need retry)
./gradlew lintDebug
# Install on connected device (requires owner's device)
./gradlew installDebug
# Docs are markdown-only — no build
```

## Conventions

- **Project facts:** namespace/applicationId `io.github.rohmanhakim.tracktrainer`;
  source root `app/src/main/java/`; single `:app` module; R8 keep rules live in
  `app/src/main/keepRules/` (AGP combines them automatically).
- **Toolchain:** AGP 9.3.3 built-in Kotlin (no separate Kotlin Gradle plugin) —
  adding Compose requires the `org.jetbrains.kotlin.plugin.compose` plugin;
  Hilt/Room use KSP. compileSdk 37 / targetSdk 36 / minSdk 26 / Java 11.
- Kotlin idioms: immutability for UI state, `Flow` for streams, sealed interfaces
  for events/state, no Javadoc-style noise — comment the *why* only.
- Package-by-feature per `02-architecture` §2; keep new features inside their
  feature package, not in `app/` or `ui/` catch-alls.
- Room schema changes = new migration, never destructive fallback in dev data
  the owner is using.
- UI: portrait study mode is the primary layout (landscape deferred).

## Installed skills (`.agents/skills/`)

From `rcosteira79/android-skills` (update with `npx skills update`):

| Skill | Use for |
|---|---|
| `android-dev` | General Android development guidance |
| `android-debugging` | Logcat, crashes, performance issues |
| `android-gradle-logic` | Gradle build logic, version catalog |
| `android-source-search` | Searching AOSP/framework source |
| `android-testing` | Unit/instrumented test patterns |
| `android-ux` | Material/UX patterns |
| `compose` | Jetpack Compose idioms |
| `kotlin-coroutines` | Coroutine/dispatcher/structured-concurrency work |
| `kotlin-flows` | Flow/StateFlow/SharedFlow patterns |
| `android-data-layer` | Repository/Room/DataStore layer |
| `datastore` | Preferences/DataStore specifics |
| `gradle-build-performance` | Build speed tuning |
| `coil-compose` | Image loading in Compose (thumbnails) |

**Invoke the matching skill before implementing in its area.**
There is no Media3/ExoPlayer skill — for player, cache, and audio-processor work,
rely on `docs/02-architecture.md` + official Media3 docs.

## Risk hot-spots (read `docs/04-risks.md` first)

- **YouTube extraction** (R1, gate G1) — behind the `youtube/Extractor` interface;
  NewPipe primary, yt-dlp fallback. Spike before committing.
- **Audio processor chain** (R2/R3/R10, gate G2) — metronome click mix +
  volume boost live in `DefaultAudioSink`'s processor chain; verify actual
  device behavior, watch for OEM pipelines bypassing it.
- **Cache keys** (R6) — must be `videoId + itag`, never the URL (URLs expire).
