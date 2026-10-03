# 03 — Roadmap (Agile Phase Plan)

## 1. How we work

- **Phases = releasable increments.** Each phase ends with an installable APK on the owner's device and a working feature set — nothing is "done" until it runs on-device (Definition of Done below).
- **Sprints within phases:** 1–2 week iterations; a phase contains 1–3 sprints. Backlog is prioritized; scope can be trimmed *within* a phase but not the exit criteria.
- **Cadence (solo developer):** keep rituals light — groom backlog at phase start, review at phase end. Kanban-style WIP limit: don't start a new feature while the current one is unverified on-device.
- **Risk-first:** the two highest-risk items (YouTube extraction, sink audio-processor chain) are *spikes* placed as early as their phase allows. Spikes produce a tested prototype + a written result, and gate decision D2.
- **Unphased flexibility:** volume boost (Phase 5's processor) can be pulled forward into any phase after Phase 1 — it is independent. Flag it at grooming.

## 2. Phase map

| Phase | Name | Theme | Exit = you can… |
|---|---|---|---|
| **1** | **Minimal Player** | **Most minimal deliverable** | Open a local video, play it, change speed, with adaptive layouts |
| 2 | YouTube + Offline Cache | Sources | Paste a link, play it, go offline later and play it again |
| 3 | A-B Loop + Presets | Looping core | Loop any passage seamlessly at any speed, settings remembered |
| 4 | Count-in | Start discipline | See 3…2…1 + clicks before the loop starts |
| 5 | Metronome + Tap Tempo | Rhythm engine | Tap the beat, hear a drift-free click over the track |
| 6 | Volume Boost | High-Z headphones | Hear quiet sources past the device max |
| 7 | Polish & Release | Productization | Background play, PiP, cache management, ship V1 |

V2 icebox items (see 01-product-overview §4.2) are groomed only after Phase 7.

## 3. Phase 1 — Minimal Player *(minimal deliverables)*

**Session-level task breakdown:** [`docs/tasks/phase-1.md`](tasks/phase-1.md) —
7 focused sessions (T01–T07), each with scope + acceptance + notes. That file tracks
execution status; this section remains the scope authority.

**Goal:** the smallest thing that is genuinely useful: *play a lesson video at any speed.* Everything later builds on this skeleton (navigation, player wrapper, layout system, Room).

**Deliverables**
- ✅ **Scaffold exists** (Android Studio empty template: Gradle 9.5 / AGP 9.3.3, `:app`, minSdk 26 — see 02-architecture §1.1).
- **S1 (remaining scaffold work):** convert template → documented stack:
  - Views/AppCompat → **Compose + Material 3** (add Compose BOM + `kotlin.plugin.compose` plugin; MainActivity + launcher intent in the manifest — currently no activity exists; M3 theme replacing `themes.xml`).
  - Version catalog additions: Media3 (exoplayer/ui/session/datasource), Hilt (+ KSP), Room (+ KSP), DataStore, coroutines/Flow, Compose BOM/M3.
  - Hilt `Application` class; package-by-feature skeleton (02-architecture §2) under `io.github.rohmanhakim.tracktrainer`.
  - Remove now-unused View-template deps (appcompat/material XML) if nothing references them.
- Local video **reference** (SAF picker → `takePersistableUriPermission`, no copy) + Room `Track` table + a trivial library screen (list of tracks).
- Player screen: ExoPlayer surface, play/pause, seek bar, position/duration.
- Speed control 0.25×–2× (chips + slider), pitch verified preserved.
- Adaptive layouts: Phase 1 is **portrait-only** (study-mode arrangement, content minimal — video + transport + speed). Landscape focus layout is deferred and groomed into a later phase (Phase 7 or earlier if trivial).
- Keep-screen-on while playing.

**Stories & acceptance criteria**

| ID | Story | Acceptance criteria |
|---|---|---|
| P1-1 | As the owner I pick a local video | File appears in library after pick (referenced in place — **instant, no copy**); survives app restart **and device reboot**; playable |
| P1-1a | As the owner a missing file is handled | Moved/deleted/unreadable reference → track flagged *"file missing — re-link"* with re-pick action; app never crashes |
| P1-2 | As the owner I play/pause and seek | Controls respond <100 ms; seek lands within ~1 s; no crashes on rapid seeking |
| P1-3 | As the owner I change playback speed | 0.25×–2× applies live; pitch unchanged audibly at 0.5×/1.5×; persists across play/pause |
| P1-4 | As the owner I rotate the device | Portrait layout scales correctly; **landscape = graceful fallback (video + controls, full HUD deferred)**; playback continues uninterrupted through rotation |
| P1-5 | As the owner the screen stays on during playback | No sleep while playing; sleeps again after pause + timeout |

**Explicitly NOT in Phase 1:** YouTube, caching, loop, count-in, metronome, boost, presets, PiP, MediaSession.

**Sprint example:** S1 stack conversion (Compose/Hilt/deps) + reference import + library; S2 player surface + transport; S3 speed + portrait layout + on-device verification.

## 4. Phase 2 — YouTube + Direct URL + Offline Cache

**Goal:** second source, invisible caching. **Contains the decision-gate spike for D2.**

**Deliverables**
- **Spike (first task):** NewPipe Extractor → progressive URL → `CacheDataSource` → play. Test on 3–5 real videos incl. age/region-restricted ones. Write spike result; if it fails → activate yt-dlp fallback spike; update D2.
- **Add-track 3-source chooser (F16):** local video / YouTube link / direct media URL (01-product-overview §5.1) — local option lands here as a proper source even though local playback works from Phase 1.
- Paste-URL flow → resolution → playback through `CacheDataSource` with stable key (`videoId + itag`).
- **Direct media URL flow** — same pipeline, cache key = normalized URL hash; `needsRefresh` handling when expired.
- Offline indicator (subtle dot) per track; play fully offline after first view.
- LRU eviction (implementation correctness; **no size cap enforced initially**); expired-URL replay handled (re-extract on cache miss).

**Stories & acceptance criteria**

| ID | Story | Acceptance criteria |
|---|---|---|
| P2-1 | As the owner I paste a YouTube link | Resolves and plays within a few seconds on good network |
| P2-2 | As the owner the caching is invisible | No download UI anywhere; first view plays while caching; no "download" wording |
| P2-3 | As the owner I play offline | Airplane-mode play of a previously-viewed video works end-to-end |
| P2-4 | As the owner cached videos survive URL expiry | Replay >6 h later works (stable cache key), no manual action |
| P2-5 | As the owner the cache is handled sanely | LRU eviction correct (unpinned evicted oldest-first, pinned never evicted); **no size cap enforced yet** — reachable from cache screen when setting is added later |
| P2-6 | As the owner I choose a source when adding | [ + Add track ] shows exactly 3 options (local / YouTube / direct URL); wrong-URL-kind paste → friendly hint, not an error |
| P2-7 | As the owner I paste a direct media URL | Plays via the same `CacheDataSource` pipeline; full feature set; offline dot after first view |
| P2-8 | As the owner an expired direct URL is handled | Cache gone + URL expired → track prompts re-paste (never a silent failure); fresh URL restores playback |

## 5. Phase 3 — A-B Loop + Presets

**Goal:** the core rehearsal mechanic.

**Deliverables**
- A/B markers on the seek bar; set/clear/move; loop toggle.
- Position-polling loop controller (30–50 ms) with `CLOSEST_SYNC` seek to A.
- Seam fade (~5 ms) to kill loop-boundary pops.
- Multiple named loop points per track + repeat counts.
- Per-track `Preset` in Room: speed, loop points auto-restored on open.
- Frame step ±1 (F10) — small, rides along with seek work.

**Stories & acceptance criteria**

| ID | Story | Acceptance criteria |
|---|---|---|
| P3-1 | As the owner I mark A and B | Markers visible, set in one tap each, persist with the track |
| P3-2 | As the owner the passage repeats | Loop restarts at A when reaching B; deviation ≤100 ms; survives 10 min continuous looping |
| P3-3 | As the owner the seam is clean | No audible pop/click at loop boundary at 0.5× and 1× |
| P3-4 | As the owner I recall my settings | Reopening a track restores speed + loop points (preset) |
| P3-5 | As the owner I step frames | Paused, each tap advances exactly ~1 frame; usable on 30/60 fps content |

## 6. Phase 4 — Count-in

**Goal:** animated 3…2…1 + clicks to start every loop prepared.

**Deliverables**
- Standalone-`AudioTrack` click generator with absolute-time scheduling (shared with Phase 5's click synthesis code).
- Count-in controller: counts before 00:00 (player start) and before A (each loop iteration), player frozen during count.
- Animated overlay: punch-in numerals, dimmed frozen frame, beat-progress ring; visual schedule synced to heard click (output-latency compensated).
- Config: count length (clicks or bars), subdivision clicks, on/off.
- **Count-in mode = fixed clicks by default (4).** A second mode that follows the tapped tempo/BPM grid is a later addition — both modes stay available; fixed is often enough (owner decision).

**Stories & acceptance criteria**

| ID | Story | Acceptance criteria |
|---|---|---|
| P4-1 | As the owner I get a count-in at track start | 4…3…2…1 (or N) then playback starts on the final beat; first beat of music lands on count "1"+1 |
| P4-2 | As the owner I get a count-in before A on every loop | Count plays while frame stays frozen at A; player resumes on final tick; repeatable indefinitely |
| P4-3 | As the owner the animation matches the sound | Numeral flip coincides with heard click within ~±30 ms (latency-compensated) |
| P4-4 | As the owner I configure the count | Clicks vs bars, subdivision on/off; respected on next loop |

**Risk note:** start alignment is one-shot (parallel AudioTrack while paused), not sample-accurate — compensated via measured latency + nudge; verify on actual headphones (R2).

## 7. Phase 5 — Metronome + Tap Tempo

**Goal:** rhythm engine — the hardest phase. **Contains spike #2: custom `AudioProcessorChain` in `DefaultAudioSink`.**

**Deliverables**
- Spike first: audio chain injects a test click mixed with playback audio; verify on-device sync quality, no audio glitches, works with speed ≠ 1.
- Click processor: scheduled clicks mixed sample-exactly into the player stream.
- Metronome UI: on/off, BPM, time signature, subdivision, accent, click volume, **phase nudge ±ms**.
- Tap tempo: big portrait tap pad (hero control), small landscape corner button; → BPM + phase; half/double-time detection; outlier rejection; stored per track.
- Visual beat flash (cheap reuse of count-in clock — include unless it threatens the exit date).

**Stories & acceptance criteria**

| ID | Story | Acceptance criteria |
|---|---|---|
| P5-1 | As the owner I tap the beat | 4+ taps → plausible BPM within ±0.5; single tap can't produce a value |
| P5-2 | As the owner the tap grid aligns | Grid vs track offset editable via nudge; alignment survives loop restarts |
| P5-3 | As the owner the click tracks the music | Over a 5-min loop: no perceptible drift (verify by ear + by recording a loop and checking grid) |
| P5-4 | As the owner the click works at any speed | Click stays locked at 0.5×, 1×, 1.5× |
| P5-5 | As the owner the mix is right | Independent volumes; clicks unaffected by volume boost (order verified) |

## 8. Phase 6 — Volume Boost

**Goal:** high-Z headphone workaround. *(Can be scheduled earlier — independent of Phases 4–5; only depends on Phase 1 and reuses the Phase 5 chain — if pulled before Phase 5, implement boost as its own single processor and add the click mixer later.)*

**Deliverables**
- Gain + soft-limiter processor in the sink chain: 0…+18 dB.
- Boost slider with clipping hint at high settings.
- Per-output-device profiles (headphones vs speaker); remember per device class.

**Stories & acceptance criteria**

| ID | Story | Acceptance criteria |
|---|---|---|
| P6-1 | As the owner I exceed device max | +6/+12/+18 dB audible increase on headphones at system max; no crash, no pipeline fallback to non-processed path |
| P6-2 | As the owner the sound stays usable | At recommended settings, no harsh clipping on loud passages; limiter engages (visible meter optional) |
| P6-3 | As the owner boost is scoped | Boost applies to headphone output only; speaker unaffected |

## 9. Phase 7 — Polish & V1 Release

**Goal:** productization; everything F11–F15 not already shipped.

**Deliverables**
- MediaSession + notification/lock-screen controls; background audio continuation.
- Picture-in-Picture.
- Presets UI polish (chips, per-track defaults, one-tap recall).
- Cache management screen: usage, clear, pin/"keep offline" (background `DownloadService`), "always keep opened tracks offline" toggle. *(Size cap = only if real usage shows a need; none initially — owner decision.)*
- **Landscape focus layout** (deferred from Phase 1) — groom here or earlier if trivial.
- Edge cases: expired URL mid-play, cache miss mid-track, corrupted cache, import failure, rapid A/B re-set during playback.
- App icon, name, versioning; final on-device soak test.

**Acceptance (release gate)** — all of 01-product-overview §6 success criteria met on the owner's device over a 1-week real-practice trial.

## 10. Definition of Done

A story is done when:

1. Compiles with zero warnings introduced by the change.
2. Acceptance criteria for that story checked off in this doc.
3. No regression in previously shipped stories of the same phase (spot-check the phase's earlier stories).
4. Backlog/this doc updated (statuses, newly discovered risks added to 04-risks.md).

A **phase** is done when all its stories meet the DoD *and* the phase exit statement holds during a real practice session.

## 11. Gates & working agreements

- **Gate G1 (end of Phase 2 spike):** NewPipe extraction reliability decides D2 (fallback to yt-dlp or continue).
- **Gate G2 (end of Phase 5 spike):** in-chain click mixing quality on-device decides whether hybrid count-in latency compensation (P4) needs a rework.
- **Scope rule:** new ideas go to the V2 icebox unless the phase exit criteria are not yet threatened; phases never grow mid-phase without an explicit groom.
- **Phase pull-forward:** Phase 6 (volume boost) may be scheduled any time after Phase 1 — owner decides at grooming.
- **No skipping verification:** a feature that works on emulator but not the device is not done.


