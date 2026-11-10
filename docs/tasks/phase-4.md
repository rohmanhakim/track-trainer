# Phase 4 Tasks — Count-in

Session-level execution plan for `docs/03-roadmap.md` Phase 4. The roadmap stays
the source of truth for scope and acceptance criteria; this file holds the *how*
and per-session status/notes.

**Conventions** (same as `phase-3.md`)

- **ID:** `Tnn`, scoped to this phase (`phase-1/2/3.md` use their own numbering).
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
| T01 | Click engine: PCM synthesis + standalone `AudioTrack` absolute-time scheduler + output-latency measurement | not-started | feeds P4-1, P4-2, P4-3 |
| T02 | Count-in controller: boundary-anchored state machine, triggers, freeze/resume/cancel | not-started | P4-1, P4-2 |
| T03 | Count-in overlay: punch-in numerals, dimmed frame, ring, latency-compensated visual clock | not-started | P4-3 |
| T04 | Count-in config: `Preset` columns, Room migration 5→6, auto-save + restore | not-started | P4-4 |
| T05 | Track settings screen ("count-in \| metronome"): destination + count-in controls + tempo input | not-started | P4-4 |
| T06 | Phase 4 review & exit sweep | not-started | all P4-x |

**Order:** T01 → T02 → T03 → T04 → T05 → T06 (Kanban WIP limit: one task at a
time, device-verified before moving on).

**Engine model & grooming decisions (owner, 2026-11-10)** — every task below
assumes these; if reality contradicts one, stop and re-groom (conventions):

1. **One tempo source.** Count-in and (Phase 5) metronome share `Preset.bpm`.
   Phase 4 sets it via the settings screen (slider + manual input; default 120
   when unset); Phase 5's tap tempo overwrites the same field. Click spacing is
   **real time**: `wall period = (60000 / bpm) ÷ playback speed` — at 0.5× the
   count runs at half tempo, matching the music you're about to hear (A5
   refinement: ticks are scheduled on the wall clock but spaced like the music).
2. **Count-in is boundary-anchored** (D5 hybrid): tick *i* at `trigger + i·period`,
   final tick = the resume instant — player stays frozen on the frame at A (or
   00:00) until then. If A is off the track's beat, the gap from the final tick
   to the first track beat after resume equals A's misalignment — accepted
   (irreducible; escape hatches: move A onto a beat, or the per-loop-point
   `offsetMs` nudge planned in 02-architecture §3.7).
3. **Metronome (Phase 5) is track-anchored**: a beat grid over the whole track's
   media timeline; the loop is only a playback window — loop restarts re-anchor
   wall-clock scheduling but never re-phase the grid (recorded in
   02-architecture §3.5). Phase 4 only needs the *media-time* API shape in T01
   so the scheduler code is reusable by Phase 5.
4. **Triggers:** count-in fires only (a) play-from-stopped at 00:00, (b) every
   loop seam restart before A, (c) play-from-stopped exactly at an armed loop's
   A. Not on resume-from-pause, not on arbitrary mid-track play.
5. **Config encoding:** `countInBars > 0` = bars mode (4/4; N bars = N×4 beats —
   time signature is Phase 5), else clicks mode (`countInClicks`, default 4,
   range 1–8; bars range 1–4). New columns `countInEnabled` (default **on** —
   it is the point of the phase; owner can toggle) + `countInSubdivision`
   (default off) via migration 5→6. `clickSound`/`clickVolume`/`bpm` already
   exist in the schema.
6. **Settings screen = its own destination** in the existing *manual* nav —
   extend `MainActivity`'s two-destination state, no navigation-compose yet
   (keeps the Phase 1 T05 decision). The **latency nudge (±10 ms, fine ±30) is
   device-level** → DataStore, not per-track. Tap tempo + metronome controls
   land on this same screen in Phase 5 (disabled placeholders shown now).
7. **During a count the intent reads "playing"**: transport shows pause,
   pressing it cancels the count (stays paused at A/00:00), keep-screen-on
   holds while counting (P1-5 must not sleep through a count).

Environment reminder: every Gradle command needs
`export JAVA_HOME=/home/arif/android-studio/jbr` (AGENTS.md).

## T01 — Click engine + latency measurement

**Status:** not-started · **Depends on:** — · **Stories:** feeds P4-1, P4-2, P4-3

### Goal
The standalone-`AudioTrack` click half of D5, living in `metronome/` (Phase 5's
synthesis code shares it): synthesize click/beep PCM, schedule ticks at absolute
times from *media-time* beat positions, and measure output latency so the T03
overlay can compensate (P4-3). One-shot use while the player is paused — the
in-chain processor is Phase 5 (spike S-2 / gate G2), not here.

### In scope
- `metronome/ClickSynth.kt`: PCM one-shots for `ClickSound.CLICK` and
  `ClickSound.BEEP` — short (~10–50 ms), mono 16-bit at the output rate, no
  waveform self-clicks; deterministic (unit-testable).
- `metronome/ClickSchedule.kt` (pure): beat math — `periodMs = 60000 / bpm`;
  media-time beat list for a count (N ticks + optional subdivision off-beats);
  wall-clock mapping `wallTime = anchorWallNanos + (beatMediaMs − anchorMediaMs)
  ÷ speed`. API shape is media-time-first even though Phase 4 always anchors at
  the resume boundary — decision 3 above.
- `metronome/ClickTrack.kt`: thin `AudioTrack` wrapper — one track per count
  burst, created/started/released inside the count's scope, absolute scheduling
  against `SystemClock.elapsedRealtimeNanos()` (the §3.4 shared time base).
- `metronome/OutputLatency.kt`: measured output latency via
  `AudioTrack.getTimestamp` + `AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER`,
  exposed as nanos with a sane fallback constant (~50 ms) if the probe fails;
  value logged for the R2 record.
- Unit tests: schedule math (bpm→period, speed scaling at 0.5×/1×/1.5×, N-tick
  lists, subdivision), synthesis buffers non-silent and length-correct, latency
  estimator arithmetic on fake timestamps.
- On-device smoke (logcat + ear): evenly spaced ticks at 60/120/180 BPM for
  ≥60 s; measured latency stable across 3 runs.

### Out of scope
- Count-in state machine and triggers (T02), overlay (T03), settings UI (T05).
- In-chain `AudioProcessor` mixing (Phase 5 spike S-2 → gate G2).
- `SoundPool`/`MediaPlayer` for clicks — rejected by 02-architecture §3.5
  (unpredictable latency).

### How
- Framework `android.media.AudioTrack` only — no new dependencies.
- Never chain `delay(period)` for tick spacing (accumulates drift); schedule
  each tick against the absolute `elapsedRealtimeNanos` instant, or pre-write
  offsets into one buffer — pick the simplest that lands within ±10 ms on the
  Redmi and record the choice in session notes (Phase 5 inherits it).
- Keep everything Compose/Media3-free and constructor-injected so JVM tests run.
- Latency probe: a short-lived (near-)silent track read once per count start or
  once per app session — decide in-session; log the result either way.

### Acceptance
- [ ] Unit tests pass: schedule math at 0.5×/1×/1.5×; N-tick + subdivision
      generation; synth buffers non-silent; latency math on fake timestamps.
- [ ] On-device: ticks at 60/120/180 BPM sound evenly spaced for ≥60 s;
      scheduled-vs-logged spacing error ≤ ±10 ms.
- [ ] Output latency measured on the Redmi, stable across 3 runs, value logged
      in session notes (feeds the R2 update in T06).
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)

## T02 — Count-in controller

**Status:** not-started · **Depends on:** T01 · **Stories:** P4-1, P4-2

### Goal
`countin/CountInController`: the pure-logic state machine that decides *when* a
count runs and freezes/resumes the player around it — `Idle → Counting(n…1) →
Go` (or `Cancelled`). Fires at the three trigger points (decision 4), freezes at
A/00:00, resumes exactly on the final tick, and cancels cleanly. Unit-tested
with fakes like `LoopController`.

### In scope
- `countin/CountInController.kt` — pure Kotlin, no Compose/Media3 types:
  - Inputs: player surface (play/pause/position — extend the `LoopPlayer` pattern
    or a sibling interface), loop state, config holder, click-engine facade
    (T01), virtual clock for tests.
  - Exposes `StateFlow<CountInState>` (`Idle` / `Counting` with tick index,
    count N, per-tick wall instants, resume instant) — the T03 overlay consumes
    this, plus `cancel()`.
  - Tick math per decision 2: tick *i* at `trigger + i·period`, resume at
    `trigger + (N−1)·period`; first tick immediate (≤~50 ms after trigger);
    subdivision ticks at `t_i + period/2` (no numerals).
  - Mode resolution per decision 5 (bars×4 vs clicks; `enabled=false` ⇒ never
    fires); spacing `period = (60000/bpm) ÷ speed` with bpm default 120 when
    unset (decision 1).
- **Trigger wiring in `PlayerViewModel`:**
  - (a)/(c): `togglePlayPause` play-from-stopped branch — position ≈ 0 (define
    epsilon, e.g. ≤100 ms), or exactly at armed-loop A with loop enabled →
    count first, `player.play()` only on the final tick.
  - (b): the existing `loopController.seamEvents` collector (line ~141) — after
    `seekTo(A)` completes, pause → count → play. Coordinate with the seam's
    volume mute mask / `restoreJob` so resume happens after the unmute.
  - Not on resume-from-pause or mid-track play (decision 4).
- **Intent/UX semantics (decision 7):** during a count `isPlayingIntent` reads
  true (transport shows pause; keep-screen-on holds — extend `PlaybackIntent`).
  Transport tap while counting cancels and stays paused at A/00:00. Also cancel
  on: seek, loop toggle-off, count-in toggle-off, track switch/open, settings
  navigation.
- Config: reads a `CountInConfig` interface — defaults hardcoded until T04 backs
  it with Room (define the seam now so T04 only swaps the source).
- Unit tests (fake clock + fake player/clicker): triggers fire only from the
  three start states; resume lands exactly on final tick; ≥20 consecutive seam
  counts with identical spacing; every cancel path; bars/clicks/subdivision
  resolution; speed scaling (0.5× doubles period).

### Out of scope
- Overlay rendering (T03), Room persistence (T04), settings UI (T05).
- The Phase 5 track-anchored metronome grid (decision 3).

### How
- Follow the `LoopController` recipe: constructor-injected scope, `StateFlow`
  state, virtual-time tests; keep it deterministic (no wall-clock reads outside
  an injected clock).
- Decide in-session whether the count starts *before or after* the seam seek
  settles (seek is async) — after, for seam counts; document the choice.
- Rotation mid-count: `PlayerViewModel` survives; verify state re-attaches.

### Acceptance
- [ ] Unit tests pass (trigger matrix, resume-on-final-tick, 20-seam loop,
      cancel paths, mode/speed math).
- [ ] On-device **P4-1**: from stopped at 00:00 → N clicks (default 4) →
      playback starts on the final tick; first beat of music lands on count
      "1"+1 (roadmap wording — verify feel at 1×).
- [ ] On-device **P4-2**: count before A on every loop, frame frozen at A the
      whole count, player resumes on the final tick; ≥20 consecutive iterations,
      no desync; cancel mid-count works (transport tap).
- [ ] Count respects speed: tick spacing at 0.5× is double that at 1×.
- [ ] Keep-screen-on holds through a count (screen doesn't sleep while frozen);
      intent/transport read correctly throughout.
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)

## T03 — Count-in overlay + visual sync

**Status:** not-started · **Depends on:** T02 · **Stories:** P4-3

### Goal
The visuals of 01-product-overview §5.2 "Count-in overlay": dimmed frozen frame,
punch-in numerals, beat-progress ring — driven by one visual clock that shares
the click's absolute time base minus measured output latency (± nudge), so
numeral flips coincide with *heard* clicks within ~±30 ms (P4-3).

### In scope
- `ui/shared/CountInOverlay.kt`:
  - Scrim over the video surface while `CountInState = Counting` (frame already
    frozen by T02; overlay sits above `PlayerView`, works in portrait study and
    landscape fallback — same composable both places).
  - Punch-in numeral per beat: countdown (N…1), scale 0.8→1.0 + fade animation
    per beat; subdivision half-beats get a small pulse, no numeral.
  - Beat-progress ring (sweep from final-tick countdown) — cheap, per 01 §5.2
    ("beat-progress ring / flashing dot for multi-bar counts").
  - Fade out on `Go`; instant clear on `Cancelled`.
- **Visual clock:** single frame loop (`withFrameNanos` / `withFrameMillis`)
  while counting; display tick index derived from
  `now + latency + nudge` vs the T01/T02 tick schedule — never per-tick
  `delay()`/`LaunchedEffect` chains (drift + recomposition jitter).
- Latency inputs: T01's `OutputLatency` value + device-level `countInNudgeMs`
  from DataStore (`AppPreferences`, key added in T04 — read with default 0 so
  T03 works before T04; see decision 6).
- Wire into `PlayerScreen`/`StudyScreen` over the surface (one line; the overlay
  self-sizes).
- Unit tests: schedule→display mapping (given wall time + latency + nudge ⇒
  which numeral/ring progress), boundary cases (between ticks, first tick,
  final tick).

### Out of scope
- The metronome's visual beat flash (Phase 5 reuses this clock — note in
  session notes if the clock is shaped to be reusable).
- Sound (T01) and trigger logic (T02) — overlay only *renders* state.

### How
- Latency compensation direction: the *click* is heard `latency` after it is
  written, the animation should fire when the click is **heard** ⇒ shift the
  visual schedule **earlier** by `measuredLatency + nudge` (nudge sign: positive
  = "show later" — define and document in code).
- Keep the composable stateless (reads `CountInState` + latency params) so tests
  are plain functions.
- On-device judging: headphones + a wall clock in-frame photo/video trick if
  needed; if ear/eye can't resolve ±30 ms, record a short clip and inspect
  waveform vs. screen recording frames — note the method used.

### Acceptance
- [ ] Unit tests pass for display mapping and latency-shift math.
- [ ] On-device **P4-3** (owner, headphones): numeral flip coincides with heard
      click within ~±30 ms at 60 and 180 BPM; method recorded in session notes.
- [ ] Overlay appears only while counting; frozen frame visible beneath the
      dim; gone before music plays; cancel clears instantly.
- [ ] Portrait study + landscape fallback both show the overlay correctly.
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)

## T04 — Count-in config: schema + persistence

**Status:** not-started · **Depends on:** T02 · **Stories:** P4-4

### Goal
Make the count-in config real, per-track, and persistent: two new `Preset`
columns via non-destructive Room migration **5→6**, auto-save from the settings
UI (T05), restore on open — plus the device-level latency nudge in DataStore.
After this, T02's `CountInConfig` seam is backed by Room instead of defaults.

### In scope
- `data/local/Preset.kt`: add `countInEnabled: Boolean = true` and
  `countInSubdivision: Boolean = false` (decision 5; `countInClicks`,
  `countInBars`, `clickSound`, `clickVolume`, `bpm` already exist).
- `AppDatabase`: version **6**, `MIGRATION_5_6` — pure `ALTER TABLE presets ADD
  COLUMN` (enabled default 1, subdivision default 0; **no DROP, no destructive
  fallback** — owner's DB has real data), registered in `DatabaseModule`,
  schema v6 exported to `app/schemas/…/6.json`.
- Migration test modeled on `AppDatabaseMigration45Test` (insert a v5 row →
  migrate → assert new columns + old data intact) + `PresetDao` round-trip for
  the new fields.
- `PresetAutoSaver` / `PlayerViewModel`: schedule saves when count-in fields
  change (from T05's screen); `restorePreset` applies all count-in fields +
  `bpm` on open (existing pattern — extend the field list).
- `CountInConfig` Room-backed implementation (swaps T02's default source);
  live `StateFlow` so a settings change mid-session is respected by the *next*
  count (P4-4: "respected on next loop" — no restart needed).
- DataStore (`data/prefs/AppPreferences.kt`): `countInNudgeMs: Int = 0`
  (device-level, decision 6; range clamp ±30 in T05's UI).

### Out of scope
- The settings screen UI itself (T05).
- Global/per-track default management UI (Phase 7 chips).
- Changing existing column defaults or re-deriving old rows.

### How
- Copy the `MIGRATION_4_5` style exactly (companion-object `Migration` with
  `execSQL`, no Room auto-migration).
- Room schema change = new migration always; run the unit migration test AND
  install-over-previous-build on device (real v5 data survives).
- Preset defaults: new tracks keep `countInEnabled = true`; migration backfills
  existing rows to enabled — flag in session notes if the owner's first
  post-update count-in feels unexpected (flip is a one-line default change).

### Acceptance
- [ ] Unit tests: migration 5→6 applies on in-memory DB with pre-seeded row;
      DAO round-trips `countInEnabled`/`countInSubdivision`.
- [ ] `app/schemas/…/6.json` exported and committed; Room validates on startup
      (no schema-mismatch crash).
- [ ] On-device: install debug over the previous build (real v5 DB with owner's
      tracks/presets) → opens clean; count-in config changes survive app
      restart; restore-on-open works (feeds **P4-4**).
- [ ] DataStore nudge persists across restart.
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)

## T05 — Track settings screen ("count-in | metronome")

**Status:** not-started · **Depends on:** T02, T04 · **Stories:** P4-4

### Goal
The owner's screen decision (2026-11-10): a **own destination** reachable from
the playback screen that configures the count-in and the tempo — and is the
future Phase 5 home for tap tempo + metronome controls. Full-screen layer over
the player; back returns with player state intact.

### In scope
- **Nav (manual, decision 6):** extend `MainActivity`'s two-destination state to
  a sealed 3-destination model (`Library / Player(trackId) / TrackSettings`).
  No navigation-compose (Phase 1 T05 decision stands). `PlayerScreen` (or the
  study top bar) gains an entry affordance — the `count-in | metronome` row
  replacing the never-used `PlaceholderSlot` idea (add the row in the portrait
  study layout; landscape HUD entry deferred with its layout, note for Phase 7).
- **`ui/settings/TrackSettingsScreen.kt`** sections:
  - **Count-in:** on/off switch · mode chips (clicks / bars) · N stepper
    (clicks 1–8, bars 1–4) · subdivision switch · click sound chips (CLICK /
    BEEP — first use of the placeholder enum) · click volume slider.
  - **Tempo:** BPM slider (range ~40–240, default 120 when preset 0) + manual
    numeric entry → `Preset.bpm` (decision 1). Coexists with Phase 5's tap
    tempo in the same field.
  - **Sync:** latency nudge control (±10 ms fine, clamp ±30) → DataStore
    `countInNudgeMs`; show the measured latency value read-only (from T01) for
    debugging R2.
  - **Placeholders:** disabled `PlaceholderSlot`s for "Tap tempo" and
    "Metronome" sections (visible future scope; the component finally gets
    used).
- Edits write through the T04 auto-save/config seam (debounced, no explicit
  save button); changes respected on the **next** count (P4-4).
- `ui/settings/` package + entry-row plumbing; `PlayerViewModel` (activity-
  scoped) must **not** release the player when `PlayerScreen` leaves composition
  for the settings destination.

### Out of scope
- Tap tempo capture, metronome on/off, time signature, accent, click-vs-track
  phase nudge (all Phase 5, same screen).
- Landscape HUD entry button (rides with the Phase 7 landscape focus layout).
- Preset chips / per-track default management (Phase 7).

### How
- **Player-lifecycle hazard (the one real risk here):** settings as a nav
  destination disposes `PlayerScreen`. Preferred: render settings as a
  full-screen **overlay inside the Player branch** of the manual nav (player
  stays composed, surface keeps its state, back just pops the overlay).
  Alternative — plain third destination — only if proven safe (release is
  explicit via `onLeave`, VM is activity-scoped; verify audio doesn't leak the
  way the T03 back-leak fix addressed). Decide at session start, record it.
- Entry row: single row in `StudyScreen` reading/writing the same state as the
  screen (current count-in on/off + bpm summary text) — tapping opens settings.
- Slider + number field both bound to one `bpm` source of truth; guard NaN/out-
  of-range.

### Acceptance
- [ ] On-device **P4-4**: change clicks↔bars, N, subdivision on the screen →
      next count honors them (mode switch respected on the *next loop*);
      subdivision half-beats audible; sound/volume change audible on next count.
- [ ] BPM slider + manual entry change tick spacing on the next count; value
      survives restart (preset restore).
- [ ] Settings screen opens from the study row; back returns to the player
      **with position/speed/loop state unchanged**; rotation safe in both
      screens; no player leak (audio stops on back-to-library as before).
- [ ] Nudge persists (DataStore) and measurably shifts the visual schedule.
- [ ] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
(empty — fill during session)

## T06 — Phase 4 review & exit sweep

**Status:** not-started · **Depends on:** T01–T05 · **Stories:** P4-1…P4-4

### Goal
On-device verification of all Phase 4 stories, regression sweep over earlier
phases, and doc close-out so the roadmap can tick Phase 4 off and Phase 5
(tap tempo + metronome) can be groomed.

### In scope
- **On-device (headphones, Redmi API 35):**
  - **P4-1** count-in triggers: play from 00:00, seam restart (A→B), and
    play-at-armed-A each run N clicks then resume; final tick lands on the
    resume frame (boundary-anchored, decision 2).
  - **P4-2** clicks/bars mode + N stepper + subdivision (half-beats audible);
    mode switch takes effect on the *next* count.
  - **P4-3** clicks vs speed: verify spacing = `(60000/bpm) ÷ speed` —
    count at 0.5× is twice as long, at 1.5× two-thirds; resume stays exact.
  - **P4-4** settings screen (from T05): all controls honored next count,
    BPM/nudge survive restart.
- **Regression sweep:** local track playback (R16-era path), P3-2/P3-3 loop
  seams (10-min soak if time permits), speed control 0.25×–2×, preset restore,
  count-in off = zero behavior change vs Phase 3 build.
- **Doc close-out:** check off roadmap §6 Phase 4 stories; record measured
  output-latency/nudge findings in `docs/04-risks.md` (R2) — including the
  nudge range used and whether ±30 ms was sufficient; confirm the G2 note
  status (metronome click mix still Phase 5, so G2 stays open).
- `assembleDebug`, `testDebugUnitTest`, `lintDebug` all green.

### Out of scope
- Tap tempo, metronome grid, time signature, accent (Phase 5 — will inherit
  this screen and the T01 engine).
- Landscape HUD entry button (Phase 7).

### How
- Test in order: build → unit/lint → install → P4-1 (triggers) → P4-2/3
  (engine) → P4-4 (settings) → regression sweep → doc updates.
- For P4-3, use a visible loop boundary or frame counter rather than
  impression; measure 4 clicks at two speeds and compare durations.
- Any bug found → fix in the owning task's section (add a session note there),
  not as an ad-hoc edit.

### Acceptance
- [ ] P4-1…P4-4 all verified on device with headphones; results recorded in
      Session notes (what was tested, device, any drift observed).
- [ ] Regression sweep clean: local track, seams, speed, preset restore,
      count-in-off parity with Phase 3 behavior.
- [ ] Roadmap `03-roadmap.md` Phase 4 stories checked; `04-risks.md` R2
      updated with real latency/nudge data; G2 status confirmed.
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green, zero new
      warnings.
- [ ] Phase 4 exit criteria in roadmap §6 met; Phase 5 grooming unblocked.

### Session notes
(empty — fill during session)

---
*Phase 4 task breakdown — docs only; no code was written in the grooming
session that produced this file.*