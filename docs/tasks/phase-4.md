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
| T01 | Click engine: PCM synthesis + standalone `AudioTrack` absolute-time scheduler + output-latency measurement | done | feeds P4-1, P4-2, P4-3 |
| T02 | Count-in controller: boundary-anchored state machine, triggers, freeze/resume/cancel | done | P4-1, P4-2 |
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

**Status:** done · **Depends on:** — · **Stories:** feeds P4-1, P4-2, P4-3

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
- [x] Unit tests pass: schedule math at 0.5×/1×/1.5×; N-tick + subdivision
      generation; synth buffers non-silent; latency math on fake timestamps.
- [x] On-device: ticks at 60/120/180 BPM sound evenly spaced for ≥60 s;
      scheduled-vs-logged spacing error ≤ ±10 ms.
- [x] Output latency measured on the Redmi, stable across 3 runs, value logged
      in session notes (feeds the R2 update in T06).
- [x] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes

**Result:** all acceptance boxes met on the Redmi (API 35) — acoustic spacing
worst **6.0 ms** everywhere (budget ±10 ms); 32 unit tests green; both Gradle
gates green. Owner ear-confirm still invited (speaker volume was raised from
1/15 to 10/15 for the tests).

**What shipped**
- `metronome/`: `ClickSchedule` (media-time beat math + wall mapping),
  `ClickSynth` (CLICK/BEEP PCM one-shots), `ClickBuffer` (pure burst layout),
  `ClickTrack` (one MODE_STATIC AudioTrack per burst), `OutputLatency`
  (getTimestamp probe + 50 ms fallback, session-cached).
- Debug spike: `metronome/spike/ClickSpikeActivity` (adb-automatable: `-e
  run/soak/probe` + bpm/ticks/speed/sound/subdivision extras) +
  `ClickLoopback` (mic capture + pure `LoopbackAnalysis` onset
  detector/analyzer). `RECORD_AUDIO` added to the **debug manifest only**.
- Tests: `ClickScheduleTest` (10), `ClickSynthTest` (7), `OutputLatencyTest`
  (4), `ClickBufferTest` (5), `LoopbackAnalysisTest` (6).

**Decisions made in-session (the task's "How" invites these)**
1. **Scheduling strategy: pre-written static buffer, not per-tick streaming.**
   Per-tick `write()`s were implemented first and *failed on the Redmi*:
   AudioFlinger logged `BUFFER TIMEOUT: remove track … due to underrun`
   between ticks and the playback head never advanced. The whole burst
   (silence gaps included) now goes into one `MODE_STATIC` buffer with a
   single `play()` at the absolute anchor → tick spacing is sample-exact
   (`ClickBuffer`), only `play()` is on the clock. **What Phase 5 inherits:** `ClickSynth` + `ClickSchedule` (both
   strategy-agnostic) and this burst layout *for count-in and any
   burst-shaped G2 fallback* (per-beat re-anchored bursts). The in-chain
   metronome path (AudioProcessor) is unaffected; a long continuous
   metronome stream would NOT use one static buffer — the failure mode to
   avoid is per-tick writes with long empty gaps (the Redmi underrun case).
2. **Latency probe cadence: once per app session** (`sessionNanos`), with
   uncached `measure()` for the 3-run stability check.
3. Smoke harness = debug-only spike activity, fully adb-automatable (buttons
   remain for manual ear checks).

**Device measurements (R2 data for the T06 risk-register update)**
- Acoustic loopback (mic, `MIC` source, end-to-end DAC→air→mic) — every run
  within the ±10 ms budget:
  - soak 60 BPM: **17 bursts, worst 5.0 ms, all matched** (×2 runs; first run
    had 1/17 onset-detection count flake — spacing still ≤5 ms);
  - soak 120 BPM: **28 bursts, worst 6.0 ms, all matched**;
  - soak 180 BPM: **35 bursts, worst 5.0 ms, all matched**;
  - speed 0.5× / 1.5×: 4/4 each, worst 5.0 ms (wall spacing 1000 ms / ~333 ms ✓);
  - subdivision: 7/7 ticks (half-beats audible), worst 6.0 ms; BEEP 4/4 worst
    6.0 ms.
- True output chain (play() → audible): **24–36 ms** across runs.
- `OutputLatency.probe` **cannot measure on this device**: the MTK AIDL HAL
  reports no live position (`hardware: Position{frames: -1}`), so every probe
  returns the 50 ms fallback (50.0/50.0/50.0). **T03 should start from the
  ~30 ms acoustic chain + user nudge, not from the probe.**
- Head-position observation alone quantizes to the deep-buffer mix period
  (worst 65–102 ms across soaks) and audio policy refuses fast tracks for
  Java clients (`USAGE_MEDIA` *and* `USAGE_GAME` land on `flags 0x8`) — never
  use head polling as the acceptance metric.
- Speaker media volume was **1/15** (raised to 10/15 via adb for the tests).
- Mic quirks: `AudioSource.UNPROCESSED` delivers garbage on this MTK;
  `VOICE_RECOGNITION` works; plain `MIC` used. The playback head *leads*
  audible time by ~150 ms (pipeline fill), so the harness records a settle
  tail after each burst. The engine itself runs on `Dispatchers.Default` —
  on the Main thread, cold-start frame work delayed the first burst ~800 ms.

**Build environment — needs follow-up before T06**
- KSP 2.3.12 + AGP 9.3.3 **incremental** builds intermittently drop generated
  classes from the DEX (`Hilt_*`, `AppDatabase_Impl`) → `ClassNotFoundException`
  at startup. Reproduce: clean build (good) → edit a file → incremental
  `assembleDebug` → `dexdump … | grep 'Class descriptor' | grep
  Hilt_TrackTrainerApplication` → 0.
- Worked around here: `ksp.incremental=false` added to `gradle.properties`
  (commented), configuration cache wiped, and **`clean` before every
  packaging build** (all clean builds were correct). `installDebug` also
  sometimes silently skips under the configuration cache — verify installs
  with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- A dedicated investigation (KSP upgrade / AGP flag) is preferable to living
  with clean-only builds; logged here so T06 doesn't rediscover it.

**Deviations from the task text**
- The "scheduled-vs-logged spacing" acceptance is met via **acoustic
  measurement** — the only ±10 ms-precise observable on this device (HAL
  timestamps dead, fast tracks refused). In-process `BurstMeasurement` values
  are reported as secondary, head-quantized numbers.

**Carry-forward to T02 / T03 (from the strategy review)**
- **T02 — first-tick latency budget:** a static burst must be built before
  `play()`; measured prep ≈ 500 ms (AudioTrack init dominates), so the
  spec's "first tick ≤ ~50 ms after trigger" needs either pre-building the
  burst when a count is anticipated (loop armed / play-from-0) or accepting
  and measuring the lateness. Also guard burst length: worst config
  (4 bars @ 60 BPM @ 0.25× ≈ 64 s ≈ 6 MB static buffer) is untested —
  validate on the Redmi or cap the range.
- **T03 — timing sources:** visual clock = `ClickSchedule` instants + ~30 ms
  acoustic chain + nudge; never `OutputLatency` probe (dead on this HAL) and
  never playback-head reads (head leads audible time by ~150 ms).

## T02 — Count-in controller

**Status:** done · **Depends on:** T01 · **Stories:** P4-1, P4-2

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
- [x] Unit tests pass (trigger matrix, resume-on-final-tick, 20-seam loop,
      cancel paths, mode/speed math).
- [x] On-device **P4-1**: from stopped at 00:00 → N clicks (default 4) →
      playback starts on the final tick; first beat of music lands on count
      "1"+1 (roadmap wording — verify feel at 1×).
- [x] On-device **P4-2**: count before A on every loop, frame frozen at A the
      whole count, player resumes on the final tick; ≥20 consecutive iterations,
      no desync; cancel mid-count works (transport tap).
- [x] Count respects speed: tick spacing at 0.5× is double that at 1×.
- [x] Keep-screen-on holds through a count (screen doesn't sleep while frozen);
      intent/transport read correctly throughout.
- [x] `assembleDebug` + `testDebugUnitTest` green, zero new warnings.

### Session notes
Filled 2026-11-11 — Redmi `94d32c7b7d78` (API 35), owner-operated taps, JBR builds.

**Built:** `countin/` — `CountInConfig.kt` (`HardcodedCountInConfig`: enabled=on,
clicks=4, bars=0, subdivision=off, bpm unset→120, CLICK, volume 1.0; T04 swaps the
source), `CountInClicker.kt` (interface + `TrackCountInClicker` over `ClickTrack`,
burst runs in viewModelScope, `stop()` releases the AudioTrack mid-burst),
`CountInController.kt` (`Idle → Counting(n…1) → Go`; `CountInTriggers` ε=100 ms;
`CountInPlayer` surface; `StateFlow<CountInState>` carries tick index, count,
period/speed, per-tick wall instants + resume instant for T03).
VM wiring: triggers (a)/(c) in `togglePlayPause` (ENDED-replay counts at 00:00 via
forced pos=0 — `seekTo` posts async), (b) in the `seamEvents` collector awaiting the
loop-seek settle; cancels on transport/seek/loop-off/track-open/release/background;
decision-7 intent recompute subscription + `counting` flag in the player listener
and frame-step gate.

**Decision-A realization:** one additive T01 delta — `ClickTrack.playBurst` gained
`onStart: (Long) -> Unit = {}` firing at the real `play()` (default no-op; no
prepare/play split, spike call sites unchanged). Ticks and the resume both anchor to
the *actual* burst start, so prep lateness shifts them together (unit-proven:
`prepLatenessShiftsClicksAndResumeTogether`, `twentyConsecutiveSeamCountsShareOneSchedule`).

**On-device results:**
- **P4-1:** play from 00:00 → `count start trigger=PLAY_AT_0000 n=4
  wallPeriod=500.0ms` → `count done: resumed on tick 4/4`; tap→resume 1567 ms
  = 64 ms prep + 1500 ms schedule (exact). Prep lateness over every run:
  **43–86 ms** — the ≤~50 ms first-tick budget is essentially met, so **no
  pre-build is needed** (T01's 480–800 ms figure was the cold path).
- **P4-2 soak:** **33/33 seam counts** each `resumed on tick 4/4`, zero missed
  seams (33 `loop seam` events = 33 counts). Owner: freeze → 4 clicks → music
  from A on every seam, no audible desync. Cancel: `count cancel:
  reason=TRANSPORT tickIndex=1`, stays paused at the anchor showing Play; next
  tap runs a fresh count.
- **Frozen frame:** screencap diffs taken *inside* a count are pixel-identical
  across the app (only the MIUI status-bar clock moves; the video's black→frame
  transition is a one-time async first-frame render after a seek, not playback).
- **Speed:** owner heard the 0.5× count at double length; unit: `wallPeriod=1000ms`,
  resume at +3000 ms.
- **Trigger (c):** `trigger=PLAY_AT_LOOP_A anchorMedia=20286ms` → resumed 4/4;
  negative mid-passage play → no count (decision 4 ✓). Rotation mid-count:
  count continues, no crash. Home mid-count: clicks stop (background cancel),
  reopen → paused at the anchor.
- **Owner feel (headphones):** count "feels immediate", clicks audible and
  comfortable; the musical "+1" alignment isn't ear-judgeable without a tempo
  reference — mechanical alignment is log-proven; real-practice sign-off at T06.

**Bug found & fixed on device:** seam counts could anchor *pre-seek*
(`anchorMedia=30272ms` = B, 16/19 anchors were B+poll-overrun): `LoopController`'s
`tryEmit` resumes the collector INLINE (`Main.immediate`) **before** `pollLoop`
issues `seekToA`, so `pendingSeek.inFlight` read false and the pre-seek position was
used (the seek still landed during count prep, masking it visually). Fix: `delay(1)`
in the collector before awaiting the settle — seek posted, then awaited. Re-verified:
**8/8 seams anchored at the identical settled-A** (`10828ms`), all resumed 4/4.

**In-session decisions:** seam count starts **after** the seek settles (per How, now
actually enforced by the fix above); background pause cancels the count (preserves
Phase 1's no-background-audio invariant — MediaSession is Ph7); `Go` persists until
the next `start` (a synchronous `Go→Idle` would be conflated away from T03);
frame-step is refused during a count (intent gate). Infra: `isReturnDefaultValues=true`
added to unit tests (controller logs via `android.util.Log`).

**Deferred:** bars/clicks/subdivision/BPM/click sound-volume stay hardcoded until
T04/T05 (math fully unit-tested; on-device count-off parity needs the T05 toggle);
the extreme burst (4 bars @ 60 BPM @ 0.5× ≈ 64 s static buffer) is unreachable
without the settings screen — re-check at T05/T06. R2 latency/nudge findings for
`04-risks.md` remain T06's job (prep-lateness data above feeds it).

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