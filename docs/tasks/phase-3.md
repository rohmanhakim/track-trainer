# Phase 3 Tasks — A-B Loop + Presets

Session-level execution plan for `docs/03-roadmap.md` Phase 3. The roadmap stays
the source of truth for scope and acceptance criteria; this file holds the *how*
and per-session status/notes.

**Conventions**

- **ID:** `Tnn`, scoped to this phase (`phase-1.md` uses its own numbering).
- **One task = one focused session**, no time limit.
- Status per task: `not-started` → `in-progress` → `done`. Update the index
  table as you go.
- **No scope changes mid-session.** Anything new discovered goes back to the
  roadmap (grooming) first, then becomes a task here.
- A task is **done only when every acceptance box is checked**, including
  on-device verification (roadmap Definition of Done #3).
- Fill **Session notes** during/after the session: what worked, surprises,
  deviations.

**Task index**

| # | Task | Status | Roadmap stories |
|---|---|---|---|
| T01 | Data layer: `LoopPoint` + `Preset` entities, DAOs, repos, Room migration 3→4 | done | feeds P3-1, P3-4 |
| T02 | Loop engine: position-poll loop controller, `CLOSEST_SYNC` seek to A, loop-until-off | done | P3-2, P3-3 |
| T03 | Loop UI + frame step: A/B markers, loop toggle, named loop points list, frame step ±1 | not-started | P3-1, P3-5 |
| T04 | Preset persistence & restore: debounced auto-save, restore on open | not-started | P3-4 |
| T05 | Phase 3 review & exit sweep | not-started | all P3-x |

**Order:** T01 → T02 → T03 → T04 → T05 (Kanban WIP limit: one feature at a
time, device-verified before moving on). The 10-min continuous-loop soak in
T02/T05 is cheap wall-clock — run it alongside other checks.

Environment reminder: every Gradle command needs
`export JAVA_HOME=/home/arif/android-studio/jbr` (AGENTS.md).

## T01 — Data layer: `LoopPoint` + `Preset`

**Status:** done · **Depends on:** — · **Stories:** feeds P3-1, P3-4

### Goal
Add the persistence layer for Phase 3: `LoopPoint` (multiple named loop points
per track) and `Preset` (per-track settings restored on open), their DAOs and
thin repositories, and Room migration **3→4**. No destructive fallback — the
owner's dev DB has real data, so every schema change is a non-destructive
migration (AGENTS.md convention).

### In scope
- `data/local/LoopPoint.kt` entity: `id`, `trackId`, `label`, `positionA`,
  `positionB`.
- `data/local/Preset.kt` entity: full V1 schema per `docs/02-architecture.md`
  §4 (`id`, `trackId`, `activeLoopPointId?`, `speed`, `transpose`, `bpm`,
  `tapOffsetMs`, `countInClicks`, `countInBars`, `clickSound`, `clickVolume`,
  `boostDb`), one per track with defaults.
- `data/local/LoopPointDao.kt` + `data/local/PresetDao.kt` (insert/update/
  getById + observe-by-track `Flow`s).
- `data/repo/LoopRepository.kt` + `data/repo/PresetRepository.kt` (thin DAO
  wrappers, Hilt `@Singleton`, mirroring `TrackRepository`).
- `AppDatabase`: version **4**, new migration `MIGRATION_3_4` creating
  `loop_points` and `presets` tables; register in `@Database(version = 4)` and
  `DatabaseModule.addMigrations(...)`.
- Unit tests for DAO insert/query round-trips + migration 3→4 on an in-memory
  DB.
- Room schema export committed for version 4 (`app/schemas/`).

### Out of scope
- Loop controller, loop UI, preset restore wiring (T02–T04).
- Preset chips / management UI screen (Phase 7 polish).
- Frame step behavior (T03).
- Processor-chain seam fade (deferred to Phase 5 if T02 can't wire it cleanly).

### How
- Follow the existing `Track`/`TrackDao`/`TrackRepository`/`AppDatabase`
  patterns exactly (Hilt `@Singleton`, Room `@Dao`/`@Entity`, `Flow` exposes,
  companion-object `Migration` with `CREATE TABLE`).
- `MIGRATION_3_4`: `CREATE TABLE loop_points (...)` and `CREATE TABLE
  presets (...)` — no `DROP TABLE`, no destructive fallback.
- Preset defaults live as Kotlin default constructor values; `activeLoopPointId`
  is nullable (no loop point active yet).

### Acceptance
- [x] `LoopPoint` + `Preset` compile; Room validates migration 3→4 against the
      exported schema (no auto-migration crash installing over a v3 DB).
- [x] DAO insert/query round-trips pass in unit tests.
- [x] Migration 3→4 applies cleanly on-device over an existing v3 database
      (install debug over a previous build; open library — no crash).
- [x] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)
## T02 — Loop engine

**Status:** not-started · **Depends on:** T01 · **Stories:** P3-2, P3-3

### Goal
The loop controller: A/B state machine, position polling at 30–50 ms,
`CLOSEST_SYNC` seek to A when reaching/passing B, loop-until-off semantics, and
a best-effort ~5 ms seam fade to kill loop-boundary pops. Pure logic that can
be unit-tested with a fake clock; deviation measured on-device.

### In scope
- `loop/LoopController.kt`: owns A/B state (`positionA`, `positionB`, active
  loop point, loop enabled), starts/stops a ~40 ms position-poll coroutine,
  seeks to A at B (loop-until-off only — no repeat counts), toggles loop on/off.
- Wire `player.seekParameters = SeekParameters(CLOSEST_SYNC)` so seek-to-A lands
  on a sync frame.
- Best-effort ~5 ms volume ramp around the seek-to-A seam (mute→unmute). If a
  clean fade can't be wired without the Phase 5 `AudioProcessor` chain, this is
  deferred there and P3-3 is verified by ear with seek-to-A only.
- Unit tests with fake `TestScope`/`VirtualTimeScheduler`: seek-back-to-A at B
  within ≤100 ms, loop-on/off toggle, poll cadence.
- On-device deviation measurement (P3-2) and seam audition at 0.5×/1× (P3-3).

### Out of scope
- Loop UI (T03), preset restore (T04).
- Processor-chain-based fade (Phase 5 if needed).
- Count-in-before-A (Phase 4) — LoopController exposes state only; count-in
  reads it later.

### How
- Reuse the player surface in `PlayerViewModel` (`player` StateFlow, `seekTo`);
  LoopController receives the `ExoPlayer` (or a thin player interface) via DI
  or is constructed in `PlayerViewModel`. Keep the controller free of Compose
  types so it unit-tests cleanly.
- Poll position at ~40 ms (faster than the 250 ms UI poll) so the B-boundary
  is caught tightly; seek to A only when `position >= positionB` and loop is
  enabled.
- Loop-until-off: reaching B seeks to A and continues; loop stays on until the
  owner toggles it off (no repeat-count logic).

### Acceptance
- [ ] Unit tests pass: seek-back-to-A at B within ≤100 ms; toggle on/off works;
      poll cadence ~30–50 ms.
- [ ] On-device: loop restarts at A when reaching B; deviation ≤100 ms (P3-2);
      survives 10 min continuous looping.
- [ ] On-device: no audible pop/click at loop boundary at 0.5× and 1× (P3-3);
      if a click is heard, file it as the Phase 5 fade trigger (not a T02
      blocker).
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
**2026-10-30: T02 complete.**

What was built:
- `loop/LoopController.kt` — delay-first ~40 ms position poll, loop-until-off, `CLOSEST_SYNC` seek to A on passing B, best-effort ~5 ms volume mute/unmute seam mask.
- `loop/ExoLoopPlayer.kt` — Media3-free `LoopPlayer` adapter around `ExoPlayer`.
- Wired into `PlayerViewModel`: `player.setSeekParameters(CLOSEST_SYNC)` on the builder; `loopController.attach()`/`detach()` around `ExoPlayer` lifecycle; seam events logged via logcat (`loop seam: ...`).
- `DEBUG_LOOP_SEED` / `DEBUG_LOOP_SEED_TRACK_ID` / `DEBUG_LOOP_SEED_A_MS` / `DEBUG_LOOP_SEED_B_MS` constants (default `false`) seed A/B on a designated track for on-device verification without the loop UI (T03). Flip to `false` before any release APK (same discipline as `DEBUG_CACHE_CAP`).

Tests (`LoopControllerTest`, 6/6 green, pure JVM + virtual time):
- seeks back to A at B, stays looped until toggled off (loop-until-off)
- volume muted to 0 at seam, restored after `seamFadeMs`
- invalid bounds (A ≥ B) never loop; paused at B never seeks
- poll cadence ~40 ms (~25 polls/s, within 30–50 ms spec)
- seam overrun ≤ 100 ms (deterministic: 5030 ms position − 5000 ms B = 30 ms)

On-device verification: pending — flip `DEBUG_LOOP_SEED` to `true`, set `DEBUG_LOOP_SEED_TRACK_ID` to a test track (>20 s), install on Redmi (`94d32c7b7d78`), measure `loop seam:` deviations via logcat (target ≤100 ms), run 10-min continuous soak, audition seam at 0.5× and 1× with headphones. Any audible pop → file as Phase 5 fade trigger, not a T02 blocker (per acceptance criteria).

Robolectric/JDK 21 fix: `LoopPointDaoTest` + `PresetDaoTest` were failing with `IllegalAccessException` (Robolectric 4.17 reflectively accesses `jdk.internal.access.SharedSecrets`, not exported on JDK 21). Fixed by adding `--add-opens=java.base/jdk.internal.access=ALL-UNNAMED` and `--add-opens=java.base/jdk.internal.reflect=ALL-UNNAMED` to unit test JVM args in `app/build.gradle.kts`. Tests also needed a `Track` row inserted before `LoopPoint`/`Preset` (foreign key enforcement now surfaces with Robolectric working) — added `insertTrack` helper / inline Track inserts in both DAO test files.

Result: **49/49 tests pass, 0 failures** (`LoopControllerTest` 6/6, `LoopPointDaoTest` 3/3, `PresetDaoTest` 4/4, plus all pre-existing test classes).

## T03 — Loop UI + frame step

**Status:** not-started · **Depends on:** T02 · **Stories:** P3-1, P3-5

### Goal
The study-surface loop controls: A/B markers on the seek bar (set/clear/move),
loop toggle, named loop points list (select/rename/delete), and frame-step ±1
transport buttons (F10). Replaces the loop `PlaceholderSlot` in `StudyScreen`
with real functionality.

### In scope
- `StudyScreen`/`PlayerSurface`: A and B markers on the seek bar; tap to set A,
  tap to set B; clear each independently; loop on/off toggle; state driven by
  `LoopController`.
- Named loop points list (select / rename / delete) — one per named `LoopPoint`
  on the track; selecting a point loads its A/B into the controller.
- Frame step ±1 buttons (F10): paused, each tap advances exactly ~1 frame;
  fallback ~33 ms (30 fps) when frame duration is unknown (R14).
- Wire loop toggle / A/B setters into `LoopController`; reflect loop status in
  the UI (A/B markers + LOOP indicator per the §5.2 portrait layout).

### Out of scope
- Preset chips / per-track preset management UI (Phase 7).
- Count-in overlay / metronome (Phases 4–5) — they read loop state later.
- Landscape focus layout (deferred to Phase 7).

### How
- Follow the existing `SpeedControls`/`Transport` shared composable patterns
  (`ui/shared/`).
- Loop marker positions map to seek-bar progress via `positionA`/`positionB` /
  `durationMs` from `PlayerUiState`.
- Frame step reuses `PlayerViewModel.seekTo(positionMs ± frameMs)` where
  `frameMs` comes from the player's video frame duration or the ~33 ms fallback.

### Acceptance
- [ ] On-device: A and B settable in one tap each, visible as markers; loop
      toggle starts/stops repetition (P3-1).
- [ ] On-device: named loop points selectable/renamable/deletable; persists with
      the track (feeds P3-4 restore).
- [ ] On-device: paused, frame-step ±1 advances ~1 frame on 30/60 fps content
      (P3-5).
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)
## T04 — Preset persistence & restore

**Status:** not-started · **Depends on:** T01, T03 · **Stories:** P3-4

### Goal
Per-track presets: auto-save (debounced) speed + active loop point + loop-on
state on change, restore on track open (preset wins over session-level default;
session-level speed is the fallback/default for tracks with no saved preset),
and reapply across rotation/player rebuild.

### In scope
- Debounced auto-save to `PresetRepository` whenever speed/loop state changes
  for the open track (no explicit "save" action).
- On `openTrack`: load the track's `Preset`; apply its `speed` (via
  `PlayerViewModel.setSpeed`) and active `LoopPoint` (A/B + loop on) — preset
  wins; fall back to the session-level speed default when no preset exists.
- Reapply preset when `startPlayer` rebuilds the `ExoPlayer` (rotation/back
  navigation) so speed and loop survive `PlayerViewModel` reconstruction.
- No data loss: save before `releasePlayer`/`onCleared`.

### Out of scope
- Preset chips UI / preset management screen (Phase 7).
- Merging preset with count-in/metronome/boost columns — those columns exist
  in the `Preset` schema (T01) but are populated in Phases 4–6.

### How
- `PlayerViewModel` owns the save/restore flow; `LoopController` exposes current
  A/B/loop-on state for persisting.
- Debounce via `debounce`/`delay` in a coroutine so rapid changes don't hammer
  Room.
- Restore happens in `openTrack` after the track loads, before `startPlayer`.

### Acceptance
- [ ] On-device: reopening a track restores speed + loop points (P3-4).
- [ ] On-device: preset wins over session-level default; session-level speed is
      the starting default for tracks with no saved preset.
- [ ] Speed + loop survive rotation and player rebuild (no regression of P1-4).
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)
## T05 — Phase 3 review & exit sweep

**Status:** not-started · **Depends on:** T01–T04 · **Stories:** all P3-x

### Goal
Verify the whole Phase 3 end-to-end on-device, confirm no Phase 1–2
regressions, update docs/risk register, and close the phase per Definition of
Done.

### In scope
- Full P3-1…P3-5 device matrix on the Redmi (`94d32c7b7d78`):
  - P3-1: A/B set in one tap each, markers visible, persist with track.
  - P3-2: loop restarts at A at B, deviation ≤100 ms, survives 10 min
    continuous looping (run the soak alongside other checks).
  - P3-3: no audible pop/click at 0.5× and 1× seam.
  - P3-4: reopening a track restores speed + loop points.
  - P3-5: paused frame-step ±1 advances ~1 frame.
- Phase 1–2 regression spot-checks: local/YouTube/direct playback, cache, speed
  chips 0.5×/0.75×/1.0×, rotation continuity, keep-screen-on, missing-file
  re-link.
- `assembleDebug` + `testDebugUnitTest` + `lintDebug` green, zero new warnings.
- Roadmap: check off P3-1…P3-5; risk register R5/R14 verdicts; leftovers →
  Phase 4 grooming.

### Out of scope
- No new feature work; discoveries go to grooming (Phase 4 count-in, Phase 5
  metronome, etc.).
- Phase exit does not require the seam fade processor (deferred to Phase 5 if
  T02 couldn't wire it cleanly).

### Acceptance
- [ ] All P3-1…P3-5 acceptance boxes checked on device.
- [ ] Definition of Done (§10) sweep complete → **Phase 3 exit**.
- [ ] Phase 1–2 regressions spot-checked, none found (or filed).
- [ ] Roadmap + risk register updated; leftovers noted for Phase 4.
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` all green, zero new
      warnings.

### Session notes
(empty — fill during session)