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
| T03 | Loop UI + frame step: A/B markers, loop toggle, named loop points list, frame step ±1 | done | P3-1, P3-5 |
| T04 | Preset persistence & restore: debounced auto-save, restore on open | done | P3-4 |
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

**Status:** done · **Depends on:** T02 · **Stories:** P3-1, P3-5

### Goal
The study-surface loop controls per §5.2 portrait layout: A/B markers, loop
toggle, frame-step ±1 (F10), and a named loop-points list (select/rename/delete,
decision D1b: "Save loop…" creates a new point, "Update loop" updates the active
one). Replaces the loop `PlaceholderSlot` in `StudyScreen` with real
functionality. T02's pending on-device checks (seam deviation ≤100 ms, 10-min
soak, seam audition at 0.5×/1×) are folded into this session per decision D2.

### In scope
- `StudyScreen`: `LoopControls` row — "Set A"/"Set B" (tap to set or move),
  clear each independently, `● A ─── B ●` range visual, LOOP on/off toggle, and
  frame-step ±1 buttons; state driven by `LoopController` via `PlayerUiState`.
  (Markers render as text labels + range visual in the loop row rather than
  overlaid on the M3 `Slider` track, because the slider's reserved track inset
  makes precise marker-x measurement fragile; verified visually on device.)
- `LoopPointsList`: select (loads A/B into the controller), rename, delete.
- Save/update button (D1b) writing via `LoopRepository`; persists with the track
  (feeds P3-4 restore).
- Frame step ±1 (F10): paused-only; `SeekParameters.EXACT` so a single frame
  isn't snapped to the nearest sync frame, then `CLOSEST_SYNC` restored for the
  loop seam; `frameDurationMs(frameRate)` with ~33 ms (30 fps) fallback (R14);
  pure, unit-tested function in `player/FrameStep.kt`.
- `LoopController.clearA()`/`clearB()` added to unset a point independently.

### Out of scope
- Preset chips / per-track preset management UI (Phase 7).
- Count-in overlay / metronome (Phases 4–5) — they read loop state later.
- Landscape focus layout (deferred to Phase 7).
- Seek-bar marker overlay (deferred — see In scope note above).

### How
- Follow the existing `SpeedControls`/`Transport` shared composable patterns
  (`ui/shared/`).
- `LoopState.positionA`/`positionB`/`loopEnabled`/`activeLoopPointId` exposed on
  `PlayerUiState.loopState`; `loopPoints` from `LoopRepository.observeLoopPoints`.
- Frame-step math extracted to `player.FrameStep.frameDurationMs` so it's
  unit-testable without a player.

### Acceptance
- [x] `assembleDebug` + `testDebugUnitTest` green, zero new warnings (56 tests,
      incl. `FrameStepTest` + `PlaybackIntentTest`).
- [x] `lintDebug` green (0 errors; 12 pre-existing warnings, none in changed files).
- [x] On-device: app installs + launches on Redmi `94d32c7b7d78` without crashes; Track DB ready (7 tracks).
- [x] On-device (manual taps): A and B settable in one tap each, visible as markers; loop toggle starts/stops repetition (P3-1).
- [x] On-device (manual taps): named loop points selectable/renamable/deletable; persists with the track (feeds P3-4 restore).
- [x] On-device (manual taps): paused, frame-step ±1 advances ~1 frame on 30/60 fps content (P3-5).
- [x] Folded T02 checks: seam deviation ≤100 ms via `loop seam:` logcat (steady-state
      overruns 0–72 ms over 130 seams), 10-min soak (10.3 min contiguous, gaps
      4.7–5.0 s, no crash), seam audition at 0.5×/1× clean by ear (owner,
      headphones) (D2).

### Remaining Issues
- [x] When setting point B then loop is active, when the playabck reaches the point B, in a fraction of second the `Pause` button will chaneg into `Play` then go back to `Pause` again when the playback goes back to point A. the experience making the button to be blinking when the playback transitioning from point B to A
      — **RESOLVED (2026-11-01).** Root cause: the button label derived from
      `player.isPlaying`, which transiently dips to false whenever the player buffers —
      including every loop-seam seek to A. Fix: transport button, `keepScreenOn`,
      `togglePlayPause`, and the frame-step paused-gate now derive from
      `isPlayingIntent` (`playWhenReady && state != STATE_ENDED`; pure helper in
      `player/PlaybackIntent.kt`, unit-tested in `PlaybackIntentTest`). Verified on
      Redmi: 12 UI snapshots across ~11 seams all showed Pause (no blink); owner
      confirmed visually.
- [x] 0 seam events even though it's looping — so the loop seek works but the seam events aren't being emitted/logged (a logging bug, separate from the loop itself)
      — **RESOLVED (2026-11-01).** Root cause: `MutableSharedFlow()` with zero
      replay/buffer — `tryEmit` silently drops whenever the collector isn't ready at
      that instant (return value was ignored). Fix: `extraBufferCapacity = 16` +
      suspending-emit fallback in `LoopController`. Regression tests
      `seamEventIsEmittedForEachSeam` + `seamEventsAreNotDroppedWhileCollectorIsBusy`
      fail against the old code, pass with the fix. Verified on Redmi: `loop seam:`
      logcat flows (130 seams captured during the soak).
- [x] on navigating to Study Activity, when the playhead haven't reached the timestamp within a loop's point A and B, but user selected a loop and make it active (the `Loop` chip is active) the loop does not looping until the playhead exceeds point A of that loop
      — **RESOLVED (2026-11-01).** Root cause: `LoopController` only sought to A when the
      playhead passed B — nothing happened at arm time. Fix (owner decision):
      seek-to-A-on-arm — `setLoopEnabled(true)` now aligns the playhead to A whenever it is
      outside [A, B) (covers both this before-A case and the earlier "armed past B" catch-up
      seam), reusing the seam mute mask but emitting **no** `SeamEvent` so P3-2 seam stats
      only measure real B→A passes. Verified on Redmi: Select on "Loop 1" (0:10–0:15) with
      the playhead paused at 0:01 jumped to 0:10 instantly, zero `loop seam:` lines at arm;
      loop cadence after Play 5.15 s, overruns 21–27 ms.
- [x] when a playback is playing then user navigates back to Track List screen without pausing the track, the track still playing in the background while the screen is Track List screen
      — **RESOLVED (2026-11-01).** Root cause: only the system-back `BackHandler` called
      `releasePlayer()`; the TopAppBar back arrow was wired to the raw `onBack`, and the
      activity-scoped `PlayerViewModel` survives leaving composition — so player + audio
      leaked onto the Track List screen. Fix: one `onLeave` handler (release → back) now
      serves all three exits (system back, top-bar arrow, error dialog). Verified on Redmi:
      Back-while-playing via arrow → Track List + `dumpsys audio` shows our AudioTrack idle;
      system back → Track List + player fully released; reopening rebuilds and plays from
      cache. Background audio stays Phase 7 (F13) scope — this restores the documented
      interim "back releases the player" contract.

### Session notes
- Implemented: `player/PlayerViewModel` (loopState/loopPoints on `PlayerUiState`,
  LoopRepository injection, setLoopA/B, clearLoopA/B, toggleLoop, saveLoopPoint
  D1b, select/rename/deleteLoopPoint, frameStep + SeekParameters.EXACT handling,
  removed DEBUG_LOOP_SEED block), `player/FrameStep.kt`, `loop/LoopController`
  (+clearA/clearB), `ui/study/LoopControls.kt`, `ui/study/LoopPointsList.kt`,
  `ui/study/StudyScreen.kt` (+save/rename/delete dialogs), `player/PlayerScreen.kt`
  (wiring), `res/values/strings.xml`.
- `Icons`/`FilledTextField`/`Modifier.background`/`FontWeight` are unavailable in
  this toolchain's Compose material3 — UI uses text buttons (`×`, Rename, Delete)
  and `OutlinedTextField` instead.
- `assembleDebug` + `testDebugUnitTest` (51 tests) green; `lintDebug` green; `assembleDebug` installs + launches cleanly on Redmi `94d32c7b7d78` (no FATAL from our package).
- **T03 UX fix (compressed UI):** portrait control surface overflowed its 60% share, clipping the bottom placeholder to an un-tappable sliver. Fixed by (1) removing the Phase 4–5 `Tap tempo` / `Count-in / metronome` placeholders from `ControlSurface` (they ate ~112 dp), (2) wrapping `ControlSurface` + `LoopPointsList` in `verticalScroll`, (3) `LoopPointsList` height `200.dp` → `heightIn(max = 120.dp)` + inner scroll, (4) shorter frame-step labels (`◀ −1 frame` / `+1 frame ▶`), (5) clear buttons → `Clear` text with a ≥48 dp target, and (6) `VIDEO_PORTRAIT_HEIGHT_FRACTION` `0.40` → `0.36` so Save loop… stays above the nav bar even with A/B set. Verified on Redmi: all controls (Set A/B, Clear, Loop toggle, Step ±1 frame, Save loop…) fully visible + tappable with A/B set.
- **T03 UX fixes round 2 (3 reported issues, all verified on Redmi):** (1) loop group was one wide `SpaceBetween` row that squeezed `Set B` un-tappable when A+B set → restructured `LoopControls` into stacked weighted rows (Set A|Set B, range|Loop, Clear A|Clear B, frame-step) so each button keeps a full cell. (2) could not create a 2nd loop — `saveLoopPoint` updated the active point and the button was stuck on "Update loop" → `saveLoopPoint` now always inserts a new point and the button is the constant "Save loop…" (multiple loops confirmed: Loop 1 + Loop 2 created). (3) Delete popup showed "Back"/"Add" → now "Delete"/"Cancel" (plus rename dialog "Save"/"Cancel"). Verified via UI-hierarchy bounds + dialog dump on the Redmi.
- Remaining on-device boxes (named-loop rename/delete persistence across reopen, P3-5 frame-step on 30/60 fps, folded T02 seam/soak checks) need manual UI taps — left for owner verification on the Redmi; they'll be re-groomed/closed in T04/T05.
- **2026-11-01: T03 acceptance close-out + 2 bug fixes (both verified on Redmi `94d32c7b7d78`).**
  - Seam-event drop fix (`loop/LoopController.kt`): zero-capacity `MutableSharedFlow`
    + `tryEmit` silently dropped every seam event → buffered `extraBufferCapacity = 16`
    + suspending-emit fallback. 2 regression tests added (`LoopControllerTest` 8/8);
    both fail against the old code (reproduced) and pass with the fix.
  - Blink fix (`player/PlaybackIntent.kt` + `PlayerViewModel`/`PlayerScreen`/
    `StudyScreen`): transport button / `keepScreenOn` / `togglePlayPause` / frame-step
    paused-gate derive from `isPlayingIntent` (`playWhenReady && state != STATE_ENDED`);
    `PlaybackIntentTest` 3 tests. Side-effect fix: tapping Pause during the seam's
    transient buffering now pauses (used to no-op/keep playing).
  - P3-2 deviation measured via `loop seam:` logcat: steady-state overruns 0–72 ms
    (target ≤100 ms), seam cadence 4.7–5.0 s, 130 seams over 10.3 min contiguous
    (09:41–09:51) — 10-min soak passed, zero interruptions, no crash.
  - P3-3 audition (owner, headphones): clean at 1× and 0.5× — no pop/click at the seam;
    the ~5 ms mute seam mask holds; no Phase 5 fade trigger needed.
  - Gates: `assembleDebug` + `testDebugUnitTest` (56 tests) + `lintDebug` green,
    zero new warnings (12 lint warnings are pre-existing: dependency-version notices,
    unused strings, Phase-1 `DefaultLocale`/`OldTargetApi`).
  - Grooming note — **implemented same-day in the remaining-issues fix below** (owner
    decision): arming the loop while playback is already past B used to seek to A on the
    first poll (one catch-up seam; observed 10.8 s overrun on arm). Now handled by
    seek-to-A-on-arm; nothing left for Phase 4+ grooming.
  - Verification tooling note: `adb logcat` without `-T` replays the ring buffer first;
    the buffer only holds ~7 min of TrackTrainer output, so long soaks need a
    persistent capture (`adb logcat -s TrackTrainer > file`).
- **2026-11-01: T03 remaining-issues fix (2 bugs, both verified on Redmi `94d32c7b7d78`).**
  - Seek-to-A-on-arm (`loop/LoopController.kt`): `setLoopEnabled(true)` → `alignToAOnArm()` —
    playhead outside [A, B) at arm time seeks to A immediately (before-A and past-B cases),
    through the seam mute mask but without a `SeamEvent` (an arm seek is not a boundary
    crossing — keeps P3-2 deviation stats honest and kills the past-B catch-up seam).
  - Back-leak fix (`player/PlayerScreen.kt`): one `onLeave` (release → back) for all three
    exit paths (system `BackHandler`, StudyScreen top-bar arrow, error dialog). The arrow
    previously bypassed `releasePlayer()`, so the activity-scoped VM kept playing audio on
    the Track List screen.
  - `LoopControllerTest` 5 new arm-time cases (before-A, past-B, inside-range, unset/inverted
    bounds, mute-mask restore); 6 existing fixtures now arm from inside [A, B) — arming from
    outside intentionally seeks. Note: `advanceTimeBy(5)` does not run a task scheduled at
    the exact boundary — the volume-restore assertion needs a `runCurrent()` flush.
  - Device matrix (10:29–10:32): arm before A at paused 0:01 → instant jump to 0:10 (no fake
    seam log); loop cadence 5.15 s, overruns 21–27 ms; arrow-Back while playing → Track List
    + AudioTrack idle; system back → Track List + player fully released; reopen → cache-hit
    playback (player rebuild after release OK).
  - Gates: `assembleDebug` + `testDebugUnitTest` (61 tests) + `lintDebug` green. Lint tally
    14 warnings, all pre-existing, none in the changed files. T05 note: the earlier "12
    warnings" tally missed 2 `AutoboxingStateCreation` hits (`StudyScreen.kt:91,94` —
    `renameId`/`deleteId` should be `mutableLongStateOf`; T03 dialog code, trivial fix).

## T04 — Preset persistence & restore

**Status:** done · **Depends on:** T01, T03 · **Stories:** P3-4

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
- [x] On-device: reopening a track restores speed + loop points (P3-4).
- [x] On-device: preset wins over session-level default; session-level speed is
      the starting default for tracks with no saved preset.
- [x] Speed + loop survive rotation and player rebuild (no regression of P1-4).
- [x] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
**2026-11-01: T04 code complete — owner interactive matrix passed (all boxes checked; closed in T05 sweep).**

What was built:
- **Schema (owner-approved amendment):** `Preset.loopEnabled` column for exact
  loop-on restore, Room migration **4→5** (`ALTER TABLE presets ADD COLUMN
  loopEnabled INTEGER NOT NULL DEFAULT 0` — existing rows default off; never
  auto-arm a loop the owner can't see coming), schema v5 exported
  (`app/schemas/…/5.json`), `02-architecture` §4 updated. Decision context: T04's
  "restore loop-on" contradicted the V1 `Preset` schema (no loop-enabled column) —
  owner chose adding the column over deriving loop-on from `activeLoopPointId`.
- `player/PresetAutoSaver.kt` — debounced (500 ms) auto-save coordinator: coalesces
  rapid changes into one write; a cross-track `schedule` writes the old snapshot
  immediately (never drops the previous track's pending change); `flush()` settles
  pending writes (cancel + join; the write itself is `NonCancellable`).
- `PlayerViewModel` wiring: `restorePreset(trackId)` runs after `startPlayer` on all
  three open paths. Preset wins over session-level `currentSpeed`; no preset ⇒
  session-level speed is the starting default and **no row is written** for
  untouched tracks (restore is suppressed from auto-save via `applyingPreset`).
  Saves scheduled from `setSpeed`, `setLoopA/B`, `clearLoopA/B`, `toggleLoop`,
  `saveLoopPoint`, `selectLoopPoint`, `deleteLoopPoint`. Flush on `releaseInternal`;
  `onCleared` does a bounded `runBlocking(Dispatchers.IO)` flush (viewModelScope is
  already cancelled there) — no data loss on release/clear.
- Behavior decisions (documented in code):
  - Armed active loop point starts playback at A on open (`alignToAOnArm` — same
    semantics as tapping a loop point in the list).
  - `loopEnabled = true` with no resolvable loop point (ad-hoc A/B never saved as a
    named point — bounds aren't in the V1 schema) restores loop **off**.
  - Manual A/B edits (`setLoopA/B`, `clear*`) clear `activeLoopPointId`: the bounds
    no longer match the named point, so restore must not resurrect its stale bounds.
  - Stale loop state cleared at `openTrack` (`loopController.clear()`) — latent bug
    fixed: A/B from a previously open track could leak across tracks (and into the
    next track's saved preset).
  - `uiState.speed` synced at open — latent bug fixed: after release + reopen the
    speed chip showed 1× while playback ran at the session speed.
- Tests: `AppDatabaseMigration45Test` (3 — real file-backed v4 DDL → Room opens at
  v5; rows preserved; loopEnabled defaults off; round-trips; idempotent re-open),
  `PresetDaoTest` +2, `PresetAutoSaverTest` (5 — coalescing, flush, no-op flush,
  cross-track ordering, loop fields). **70 tests total, 0 failures.**
- Gates: `assembleDebug` + `testDebugUnitTest` (70) + `lintDebug` green. Lint tally
  14 (12 warnings + 2 hints), all pre-existing, none in the changed files.
- On-device (automated part): `installDebug` on Redmi `94d32c7b7d78`; app launches
  clean over the real dev DB — **MIGRATION_4_5 applied on-device, 7 tracks intact**
  (`Track DB ready, tracks=7`, no AndroidRuntime errors).

Owner interactive matrix (Redmi) — all passed:
- [x] Reopen a track with saved speed + active armed loop → both restored (P3-4).
- [x] Preset wins over session default; untouched track opens at session speed.
- [x] Loop-off with an active point → reopen → stays off (the exact-restore case).
- [x] Rotation mid-play: speed + A/B + loop-on survive (P1-4 no regression).
- [x] Swipe-away (process death) → reopen → still restored.
- [x] Rapid speed-slider drag → no jank, few DB writes (optional logcat look).

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