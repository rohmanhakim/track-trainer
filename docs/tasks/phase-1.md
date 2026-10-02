# Phase 1 Tasks — Minimal Player

Session-level execution plan for `docs/03-roadmap.md` Phase 1. The roadmap stays
the source of truth for scope and acceptance criteria; this file holds the *how*
and per-session status/notes.

**Conventions**

- **ID:** `Tnn`, scoped to this phase (`phase-2.md` gets its own numbering later).
- **One task = one focused session**, no time limit.
- Status per task: `not-started` → `in-progress` → `done`. Update the index table as you go.
- **No scope changes mid-session.** Anything new discovered goes back to the roadmap
  (grooming) first, then becomes a task here.
- A task is **done only when every acceptance box is checked**, including
  on-device verification (roadmap Definition of Done #3).
- Fill **Session notes** during/after the session: what worked, surprises, deviations.

**Task index**

| # | Task | Status | Roadmap stories |
|---|---|---|---|
| T01 | Compose foundation | in-progress | S1 checklist |
| T02 | Dependencies + Hilt + Room skeleton | not-started | S1 checklist |
| T03 | Library screen + local reference | not-started | P1-1 |
| T04 | Missing-file detection & re-link | not-started | P1-1a |
| T05 | Player core | not-started | P1-2, P1-5 |
| T06 | Speed control | not-started | P1-3, spike S-3 |
| T07 | Portrait study layout | not-started | P1-4 |

**Order:** T01→T02 foundation; T03→T04 data before player; T05→T06 features;
T07 only once controls exist to arrange. Environment reminder: every Gradle
command needs `export JAVA_HOME=/home/arif/android-studio/jbr` (AGENTS.md).

---

## T01 — Compose foundation

**Status:** in-progress · **Depends on:** — · **Stories:** S1 checklist (partial)

### Goal
Convert the generated Views/AppCompat template into a launchable Compose + M3 app:
placeholder screen, correct theme, dead template code gone.

### In scope
- Add Compose: BOM + `androidx.activity:activity-compose`, Material 3, and the
  `org.jetbrains.kotlin.plugin.compose` plugin (AGP 9 built-in Kotlin — no `kotlin-android` plugin).
- `MainActivity` + `<activity android:name=".MainActivity">` with `LAUNCHER` intent-filter
  (manifest currently declares **no activity**).
- M3 theme in Kotlin replacing `themes.xml` / View-based theme references; app label stays.
- Remove now-unused View-template deps (`appcompat`, `material` XML) if nothing references them.
- `enableEdgeToEdge` + portrait orientation lock (Phase 1 is portrait-only per roadmap).

### Out of scope
- Hilt/Room/Media3/deps beyond Compose (→ T02), real content of any kind.

### How
- Version catalog: add `androidx-activity-compose`, `androidx-compose-bom`,
  `androidx-material3`; plugin `org.jetbrains.kotlin.plugin.compose` (same version as Kotlin
  bundled with AGP 9 — verify what AGP expects before picking a version).
- `app/build.gradle.kts`: `buildFeatures { compose = true }`, compose plugin alias,
  `implementation(platform(libs.androidx.compose.bom))`.
- Keep `keepRules/` and existing test dirs untouched.

### Acceptance
- [x] `./gradlew assembleDebug` green
- [x] `testDebugUnitTest` + `lintDebug` green
- [ ] Installs and launches on device showing a placeholder M3 screen
- [x] No `appcompat`/`material` warnings for unused template deps

### Session notes

**Code + build done 2026-10-02; only the on-device launch check remains.**

- **Versions:** Compose BOM `2026.09.00` (Compose 1.12 / M3 1.4.0), `activity-compose 1.13.0`,
  compose plugin **`2.2.10`** = the KGP bundled with AGP 9.3.3 (verified from the AGP POM on
  Google Maven). KGP 2.2.10 compiled the 2026.09 BOM cleanly — planned fallback (explicit
  KGP bump) was **not** needed.
- **Files:** version catalog + `app/build.gradle.kts` (compose plugin alias,
  `buildFeatures { compose = true }`, BOM platform, appcompat/material removed);
  `app/MainActivity.kt` (edge-to-edge + placeholder screen); `ui/theme/Theme.kt` (static M3
  light/dark, no dynamic color); manifest activity `.app.MainActivity` (exported, LAUNCHER,
  portrait lock); XML themes re-parented to `android:Theme.Material.*.NoActionBar` (required —
  the MaterialComponents parent would crash once the `material` dep was removed);
  `colors.xml` deleted (unreferenced after theme rewrite).
- **Deviation:** task text says `.MainActivity`; architecture §2 puts MainActivity in the
  `app/` feature package → used **`.app.MainActivity`** (package-by-feature is the hard rule).
- **Builds:** first `assembleDebug` 3m 50s (dependency downloads + config-cache store, cold);
  `testDebugUnitTest` + `lintDebug` 20s warm. Lint: **0 errors, 6 warnings**, all expected —
  targetSdk 36→37 (docs pin 36), Gradle 9.5→9.8 / AGP 9.3.3→9.4.1 / core-ktx 1.19.1 /
  compose-plugin 2.4.20 "newer available" (versions deliberately pinned — no churn mid-task),
  portrait-lock pair `LockedOrientationActivity` + `DiscouragedApi` (required by T01).
- ⚠️ **Note for T07:** `DiscouragedApi` warns fixed orientations are ignored on Android 16+
  — the portrait lock may be a no-op on new devices; adaptive layout (T07) is the real answer.
- ⏳ **Pending:** `./gradlew installDebug` + launch on physical device (none connected at
  session time — owner task). Check placeholder screen in light *and* dark mode.

---

## T02 — Dependencies + Hilt + Room skeleton

**Status:** not-started · **Depends on:** T01 · **Stories:** S1 checklist (partial)

### Goal
All Phase 1 libraries land in the version catalog, Hilt is wired up, and the Room
`Track` schema compiles and creates its DB on device. No UI.

### In scope
- Version catalog additions (+ KSP plugin): **Hilt** (+`hilt-android-compiler`),
  **Room** (runtime/ktx, `room-compiler`), **DataStore**, **coroutines/Flow**,
  **Media3** (`exoplayer`, `ui`, `session`, `datasource`, `common`), lifecycle/ViewModel compose.
- Hilt: plugin + KSP, `@HiltAndroidApp` `Application`, manifest `android:name`.
- Room: `Track` entity per 02-architecture §4 (`sourceType(LOCAL|YOUTUBE|DIRECT)`,
  `sourceRef`, `title`, `durationMs`, `thumbnailPath`, `offline`, `pinned`,
  `needsRefresh`, `cacheBytes`, `lastPlayedAt`), DAO, `AppDatabase`, singleton provider.
- Empty package-by-feature skeleton under `io.github.rohmanhakim.tracktrainer`
  (`app/`, `data/`, `player/`, `library/` placeholder, `ui/`).

### Out of scope
- Repositories with logic, DataStore contents, Media3 playback (later tasks).

### How
- KSP versions align with the Kotlin version AGP 9 bundles — check before pinning.
- Room schema export dir optional for personal use; if enabled, gitignore the generated
  schema path decisions go in session notes.

### Acceptance
- [ ] All deps resolve; `assembleDebug` green
- [ ] `testDebugUnitTest` + `lintDebug` green
- [ ] On device: app launches (Hilt Application active, no DI crash) and DB file is created

### Session notes
_(pending)_

---

## T03 — Library screen + local reference

**Status:** not-started · **Depends on:** T01, T02 · **Stories:** P1-1

### Goal
The owner can pick a local video (referenced in place, never copied) and see it in
a library list that survives restart and reboot.

### In scope
- `[+ Add track]` on a library screen (`LazyColumn` of tracks: title, duration, thumbnail optional).
- SAF picker (`ActivityResultContracts.OpenDocument`, `video/*`) → store the `content://` URI
  as `Track.sourceRef` with `takePersistableUriPermission(..., FLAG_GRANT_READ_URI_PERMISSION
  | FLAG_GRANT_PERSISTABLE_URI_PERMISSION)`.
- `TrackRepository` (simple DAO-backed) + ViewModel with Flow; Room from T02.
- Copy display title/metadata from the SAF cursor (`OpenableColumns`); duration read
  lazily (MediaMetadataRetriever) or left blank until T05 — decide in session notes.

### Out of scope
- The 3-source chooser (Phase 2, P2-6) — Phase 1 is local-only add.
- Health check / re-link (→ T04), any playback (→ T05).

### How
- Local-only: no network permission changes.
- Persistable grant = survives reboot; verify on device after a reboot (roadmap P1-1 AC).

### Acceptance
- [ ] P1-1: pick → row appears; survives app restart **and device reboot**; reference resolves at least via metadata read (playback itself waits for T05)
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [ ] Verified on physical device

### Session notes
_(pending)_

---

## T04 — Missing-file detection & re-link

**Status:** not-started · **Depends on:** T03 · **Stories:** P1-1a

### Goal
A moved/deleted/revoked local file shows a clear ⚠️ state with a re-link action;
the app never crashes.

### In scope
- Health check helper: try opening the URI (`ContentResolver.query`/`openFileDescriptor`)
  → on failure mark track *"file missing — re-link"* (flag column or computed state).
- Library row UI for the missing state + **Re-link** action → re-runs the SAF picker,
  updates `sourceRef`, releases old persistable grant (`releasePersistableUriPermission`).
- One-time hint when the picked source is a cloud provider (01-product-overview §5.1) —
  include if cheap, else defer to Phase 2 chooser and note it.

### Out of scope
- Copy-into-app fallback (explicitly rejected, D11), 128-grant-cap handling (R13, ignore for now).

### How
- Run the check on library load (cheap) and on track open — not a background poller.

### Acceptance
- [ ] P1-1a: rename/delete source file → row flags missing, app never crashes
- [ ] Re-link with a valid file → track restored (and playable once T05 exists)
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [ ] Verified on physical device

### Session notes
_(pending)_

---

## T05 — Player core

**Status:** not-started · **Depends on:** T03 (T04 nice-to-have) · **Stories:** P1-2, P1-5

### Goal
Tapping a library track plays the video with working transport: play/pause, seek,
position/duration, screen stays on.

### In scope
- `player/` package: ExoPlayer (Media3) owned by a ViewModel — decide simple
  (ExoPlayer + `AndroidView` in Compose) vs `PlayerView` composable; record choice.
- Transport: play/pause, seek bar (draggable), position/duration labels, `Listener` for state.
- Handle `content://` URIs (`MediaItem.fromUri`); position persistence out of scope.
- Keep-screen-on while playing (P1-5).
- Back navigation returns to library; rotation keeps playback (P1-4 partial — layout itself in T07).

### Out of scope
- Speed (→ T06), loop/A-B, metronome, MediaSession/notification (Phase 7), thumbnails.

### How
- Media3 deps already in catalog (T02) — this task only wires usage.
- Lifecycle: release player on ViewModel/screen teardown to avoid leaked audio.

### Acceptance
- [ ] P1-2: play/pause responds <100 ms; seek lands ≈ target; no crash on rapid seeking
- [ ] P1-5: screen stays awake while playing; sleeps after pause
- [ ] Video + audio render (speaker or headphones)
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [ ] Verified on physical device

### Session notes
_(pending)_

---

## T06 — Speed control

**Status:** not-started · **Depends on:** T05 · **Stories:** P1-3, spike S-3

### Goal
Playback speed adjusts live from 0.25×–2× with pitch preserved — the first
genuinely musician-facing feature, and the inline spike S-3 verdict is recorded here.

### In scope
- Speed UI in the transport area: preset chips (0.5× / 0.75× / 1× — extend later) + fine slider (±small steps).
- `player.setPlaybackParameters(PlaybackParameters(speed))` applied live; value persists across pause/play within the session.
- **Spike S-3:** listen check at 0.5× and 1.5× for pitch preservation and artifacts; write the verdict + device used into Session notes (feeds roadmap R8 / F4 transpose decision).

### Out of scope
- Independent pitch/transpose (F4, post-Phase-1), speed in presets (P3-4 is Phase 3), loop interaction.

### How
- Roadmap R8 caveat: Sonic-based processing may artifact <0.5× — if audible, document rather than fix (acceptance is 0.5×/1.5× clean).

### Acceptance
- [ ] P1-3: 0.25×–2× applies live; pitch unchanged audibly at 0.5× and 1.5×; speed survives pause/play
- [ ] Spike S-3 verdict written in Session notes
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [ ] Verified on physical device (headphones)

### Session notes
_(pending)_

---

## T07 — Portrait study layout + Phase 1 review

**Status:** not-started · **Depends on:** T05, T06 · **Stories:** P1-4

### Goal
The portrait study layout from 01-product-overview §5.2 becomes real: video top
~40 %, control surface below — and Phase 1 gets its final DoD sweep.

### In scope
- Portrait arrangement: video pinned top ~40 %; control surface below with real
  controls (transport, speed) and **empty placeholder slots** for loop/tap/count-in
  areas (Phase 3–5 fill them — layout structure lands now).
- Graceful landscape fallback (video + controls stacked/full-bleed; full HUD = later phase).
- Rotation during playback keeps playing (P1-4).
- Empty-library and no-track-yet states.
- **Phase 1 review:** walk every P1-x acceptance criterion + roadmap Definition of Done;
  mark roadmap stories done; note leftovers for grooming.

### Out of scope
- Landscape focus HUD (deferred, roadmap), loop/tap/count-in functionality (placeholders only).

### How
- Single Compose hierarchy with adaptive arrangement (orientation/`WindowSizeClass`) —
  not two screens (01-product-overview §5.2).

### Acceptance
- [ ] P1-4: portrait shows documented arrangement; landscape = graceful fallback; playback uninterrupted through rotation
- [ ] All Phase 1 stories (P1-1, P1-1a, P1-2, P1-3, P1-5) re-verified as done
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [ ] Full Definition of Done sweep on physical device → **Phase 1 exit** (roadmap §10)

### Session notes
_(pending)_

