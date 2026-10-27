# Phase 2 Tasks — YouTube + Direct URL + Offline Cache

Session-level execution plan for `docs/03-roadmap.md` Phase 2. The roadmap stays
the source of truth for scope and acceptance criteria; this file holds the *how*
and per-session status/notes.

**Conventions**

- **ID:** `Tnn`, scoped to this phase (`phase-1.md` uses its own numbering).
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
| T01 | Spike S-1: NewPipe → cache → play (gate G1) | done | feeds P2-1 |
| T01b | Contingency spike: yt-dlp → cache → play (gate G1) | done | feeds P2-1, D2 |
| T01c | Remove NewPipe impl + reconcile post-G1 docs | done | D2, R1 docs |
| T02 | Cache core + stable keys | not-started | P2-2 (ground), P2-5 (impl) |
| T03 | Add-track chooser (F16) + direct media URL flow | not-started | P2-6, P2-7, P2-8 |
| T04 | YouTube add flow + extract-on-open pipeline | not-started | P2-1, P2-2 |
| T05 | Offline semantics + expiry + eviction verification | not-started | P2-3, P2-4, P2-5 |
| T06 | Phase 2 review & exit sweep | not-started | all P2-x |

**Order:** T01 → T02 foundation; T03 → T04 URL flows (direct before YouTube — zero-extraction path proves the cache first);
T05 cross-cutting semantics once both flows exist; T06 review only once P2-4's >6 h
wait has been exercised (schedule the wait overnight — it spans sessions).
Environment reminder: every Gradle command needs `export JAVA_HOME=/home/arif/android-studio/jbr` (AGENTS.md).

## T01 — Spike S-1: NewPipe → progressive URL → CacheDataSource → play

**Status:** done · **Depends on:** — · **Stories:** feeds P2-1

### Goal

Validate the Phase 2 risk bet (R1 / decision D2): can NewPipe Extractor reliably
resolve playable progressive (≤720p, muxed A/V) YouTube URLs? Write the spike
result; if it fails → activate contingency spike T01b (yt-dlp fallback) and re-decide D2 at gate G1.

### In scope

- `youtube/` package: kept `Extractor` interface (`resolve(watchUrl)` → title, duration, progressive URL, videoId, itag) + minimal NewPipe implementation.
- Add NewPipe Extractor dependency — verify artifact coordinates/repo during the session (likely JitPack `com.github.TeamNewPipe:NewPipeExtractor`).
- Debug harness (throwaway / debug-only entry): paste watch URL → resolve → feed progressive URL into Media3 `CacheDataSource` → ExoPlayer → play.
- Test **3–5 real videos including age/region-restricted ones**; record per-video result in Session notes.
- On failure: contingency **T01b — yt-dlp fallback spike** behind the same `Extractor` interface; then update D2 in `docs/02-architecture.md` with gate G1's outcome.

### Out of scope

- Production `Track` rows / Room storage (T04 builds on this).
- Cache keys factory beyond a placeholder key for the harness (T02).
- YouTube UI flow (T03/T04).

### How

- Version catalog: check whether NewPipe is on MavenCentral; if not, add JitPack repo + artifact. No version-catalog entry needed for a git-based artifact — declare `implementation("com.github.TeamNewPipe:NewPipeExtractor:<tag>")` in `app/build.gradle.kts`.
- Keep the `Extractor` interface thin and implementation-free of Android types so it can be unit-tested / swapped at G1.
- Harness can be a debug `MainActivity` override or an instrumented scratch test — whichever gets to "video plays on device" fastest.

### Acceptance

- [x] NewPipe resolves playable progressive URLs on ≥3 of 5 test videos — **NOT MET**: 0/4 passed (see Session notes / spike result). Gate G1 trigger met (≥2 failures).
- [x] Spike result written in Session notes (which videos passed/failed, resolution latency, any errors) → `docs/tasks/t01-spike-result.md`.
- [x] Gate G1 recorded: NewPipe fails on ≥2 → T01b (yt-dlp fallback) must be executed; D2 updated accordingly.
- [x] `assembleDebug` green; `testDebugUnitTest` green; `lintDebug` green; no new lint findings.

### Session notes

NewPipe Extractor v0.26.5 (JitPack) fails on all 4 test videos with `ParsingException: Could not get visitorData` during `fetchPage()`/`fetchAndroidClient()` — a YouTube innertube change NewPipe v0.26.5 doesn't handle. The failure is systemic (occurs before any video-specific logic), affecting normal, age-restricted, region-locked, and Shorts videos equally. Network is not the issue (device pings YouTube).

Test matrix (Redmi API 35, `94d32c7b7d78`):
1. `https://www.youtube.com/watch?v=UAJZ99jx5fY` (normal) — FAIL, ~1 s, visitorData
2. `https://www.youtube.com/watch?v=h4duxCXkgwM` (age-restricted 18+) — FAIL, ~1 s, visitorData
3. `https://www.youtube.com/watch?v=-hTyBMMR3no` (region-locked JP-only) — FAIL, ~1 s, visitorData
4. `https://www.youtube.com/shorts/Y0J3-wGMjkM` (Shorts) — FAIL, ~1 s, visitorData

Full report: `docs/tasks/t01-spike-result.md`.

Gate G1 outcome: **NewPipe FAILS (0/4)** → contingency spike **T01b — yt-dlp fallback** executed. yt-dlp (updated in-app to 2026.10.26) resolves normal + Shorts videos; age-restricted (sign-in wall) and region-locked (rights block) fail for inherent YouTube policy reasons, not extractor defects — covered by ladder rung 4 (direct URL paste). **D2 re-decided: yt-dlp primary, NewPipe superseded.** Full results: `docs/tasks/t01b-spike-result.md`.

Deviations from plan (noted, not scope changes):
- Core library desugaring (`desugar_jdk_libs_nio:2.1.5` + `isCoreLibraryDesugaringEnabled`) required by NewPipe on minSdk 26 — not in T01's "How".
- `INTERNET` permission added to manifest (Phase 1 was local-only).
- NewPipe v0.26.x API: extraction is `getStreamLHFactory().fromUrl(url)` → `getStreamExtractor()` → `fetchPage()` → `getVideoStreams()`; `fetchPage()` is mandatory before `getName()`/`getVideoStreams()`.
- `CacheKeyFactory.youtubeKey` is a placeholder (production factory is T02).
- Harness has no PlayerView (AndroidView unresolved in debug compose-ui variant); playback verified via logcat — resolution is the failure point, not playback.

## T02 — Cache core + stable keys

**Status:** not-started · **Depends on:** T01 · **Stories:** P2-2 (ground), P2-5 (impl)

### Goal

Productionize the caching foundation: `cache/` package with stable cache keys,
`SimpleCache` + LRU eviction, and `CacheDataSource` wired into the player pipeline
so http(s) streams read-through while disk is written — transparent, no download UI (P2-2).

### In scope

- `cache/` package: `CacheKeyFactory` — YouTube → `videoId + itag`; direct media URL → `SHA-256(normalized URL)` (strip volatile query params where safe); **never the full URL for YouTube** (R6 — URLs expire ~6 h).
- `SimpleCache` singleton + real LRU evictor: **effectively uncapped** (owner decision, R7) but LRU logic present and correct; pinned tracks exempt from eviction (semantics only — pin UI lands in Phase 7).
- `CacheDataSource.Factory` injected into the player's `MediaSource.Factory`; local `content://` (SAF) playback path untouched (guard P1 regressions).
- Unit tests for `CacheKeyFactory` key stability + normalization (never full URL; same key after URL expiry).
- Re-verify T01 harness now runs through production wiring.

### Out of scope

- Cache management UI / pin UI (Phase 7 deliverable).
- Size cap setting (explicitly none until Phase 7+; owner decision).
- "Keep offline" toggle beyond pinned-track exemption semantics.

### How

- Player `MediaSource.Factory` construction already lives in `PlayerViewModel.startPlayer` (T05) — swap `MediaItem.fromUri(sourceRef)` → cache-backed factory for http(s) items; keep `content://` direct.
- No Room schema change expected: `Track` already carries `sourceType`, `youtubeId`, `offline`, `pinned`, `needsRefresh`, `cacheBytes` — confirm `Track.kt` matches before coding.

### Acceptance

- [ ] `CacheKeyFactory` unit tests pass: stable keys across URL expiry, never full YouTube URL, deterministic normalization.
- [ ] YouTube + direct streams play through `CacheDataSource` → disk cache on device (offline dot appears after first view).
- [ ] Local SAF playback still works (no P1 regression) — spot-check T05/P1-2 on one local track.
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green; zero new warnings.

### Session notes

_To fill during/after the session._

## T03 — Add-track chooser (F16) + direct media URL flow

**Status:** not-started · **Depends on:** T02 · **Stories:** P2-6, P2-7, P2-8

### Goal

`[ + Add track ]` shows exactly 3 explicit options (local / YouTube / direct URL — D10,
no auto-detect). Local option lands here as a proper source even though local playback
worked from Phase 1. Direct media URL plays through the same pipeline with expiry
handling (P2-8).

### In scope

- Library screen → `[ + Add track ]` → 3-option chooser (P2-6).
- Wrong-kind paste tolerance check: YouTube URL pasted into Direct (or vice versa) → friendly hint, **not** an error (D10 / §5.1).
- Direct add → `Track(sourceType=DIRECT, sourceRef=<url>)` → full-feature playback through `CacheDataSource` pipeline (P2-7); subtle offline dot after first view; no download wording anywhere (P2-2).
- `needsRefresh` handling: cached copy gone + URL expired → track prompts **"URL expired — paste a new one"** with re-paste action; never a silent failure (P2-8); fresh URL restores playback.
- Local SAF flow re-wired through the chooser (existing T03 flow); store `content://` URI with `takePersistableUriPermission`, never copy (D11).

### Out of scope

- YouTube add flow (T04).
- Cloud-provider local file warning beyond the existing one-time hint (§5.1).

### How

- Paste-field tolerance check: simple heuristic (`youtube.com`, `youtu.be` → YouTube kind; http(s) stream URL → Direct kind) with override/confirmation if ambiguous.
- `TrackRepository.add` gains no new signature — `Track` entity unchanged.

### Acceptance

- [ ] Chooser shows exactly 3 options; selecting each adds a track of the matching `sourceType`.
- [ ] Direct URL paste → plays; offline dot after first view; airplane-mode replay works after first view.
- [ ] Wrong-kind paste → friendly hint, no crash, no error UI.
- [ ] Expired direct URL → re-paste prompt with working re-paste action.
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green.

### Session notes


## T04 — YouTube add flow + extract-on-open pipeline

**Status:** not-started · **Depends on:** T01, T02 · **Stories:** P2-1, P2-2

### Goal

Paste a YouTube watch URL → resolves and plays within a few seconds on good network
(P2-1). Extraction happens only on cache miss: cache hit replays with **zero extraction**
(ladder rung 1, §3.8). Extraction failure → friendly error + fallback-ladder hints,
never a silent failure, no WebView (D9).

### In scope

- YouTube add: paste watch URL → `Extractor` (yt-dlp impl, T01b) → `Track(sourceType=YOUTUBE)` with title, duration, youtubeId, thumbnail (P2-1); loading state while resolving (~30–60 s on add; replays instant from cache).
- Open pipeline: cache hit → `SimpleCache` replay, no extraction; cache miss (expired URL / first view) → re-extract via `Extractor`; extraction failure → friendly error + fallback hints (direct URL paste / local file import per §3.8 rungs 4–5).
- Re-extract-on-miss sets `needsRefresh` appropriately; successful resolve clears it.
- Verify "no download UI anywhere / no 'download' wording" for YouTube path too (P2-2).
- Cache hit logged/verifiable so P2-4's "no manual action" can be spot-checked.

### Out of scope

- Age/region-restricted handling beyond what yt-dlp returns natively (covered in T01b's test set; those videos fail on inherent YouTube policy walls — see fallback ladder rung 4).
- Alternative extractor impls (NewPipe removed after gate G1).

### How

- `Extractor.resolve` runs off the main thread (coroutine + `Dispatchers.IO`); result mapped to `Track` + inserted via `TrackRepository`.
- Player open reads `Track.sourceRef` → resolved progressive URL → cache-backed `MediaSource`; `MediaItem.fromUri(sourceRef)` replaced for YOUTUBE/DIRECT items.

### Acceptance

- [ ] YouTube watch URL → shows resolving state, plays within ~30–60 s on add; replays instant (zero extraction from cache) (P2-1).
- [ ] Cache-hit replay performs **zero extraction** (verified via logs / no network during replay).
- [ ] Cache miss after URL expiry → re-extract → plays again, no manual action (feeds P2-4; full battery in T05).
- [ ] Extraction failure → friendly error + fallback-ladder hints; app never crashes.
- [ ] No download wording anywhere in YouTube flow (P2-2).
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green.

### Session notes

_To fill during/after the session._

## T05 — Offline semantics + expiry + eviction verification

**Status:** not-started · **Depends on:** T03, T04 · **Stories:** P2-3, P2-4, P2-5

### Goal

Verify the P2-3/P2-4/P2-5 acceptance battery end-to-end on device: offline play,
>6 h replay with stable keys, LRU eviction correctness, offline-dot accuracy.

### In scope

- **P2-3:** Airplane-mode full play of a previously-viewed YouTube track + a previously-viewed direct-URL track, end-to-end (first view cached online, then offline).
- **P2-4:** Replay >6 h after first view — stable cache key means no re-extraction, no manual action. Schedule the wait overnight (start early in the session); reopen the track and confirm play.
- **P2-5:** LRU eviction correctness — oldest unpinned evicted first under pressure, pinned tracks never evicted. No size cap enforced yet (owner decision); confirm pin exemption logic exists and works.
- Offline dot accuracy across cached / not-cached / needsRefresh states.
- `offline` / `cacheBytes` / `lastPlayedAt` bookkeeping sync across views.

### Out of scope

- Size cap setting (Phase 7+).
- Pin UI (Phase 7) — pin exemption verified programmatically, not via UI.

### How

- P2-4's >6 h wait cannot be compressed; if the session ends before the wait completes, mark P2-4 "in progress" in Session notes and close it at the start of the next session.
- Eviction pressure can be simulated with several large videos; check `SimpleCache` eviction ordering via logs/cache directory.

### Acceptance

- [ ] Airplane-mode full play works for cached YouTube + cached direct tracks (P2-3).
- [ ] Replay >6 h later works with no manual action (P2-4).
- [ ] LRU evicts oldest unpinned first; pinned exempt (P2-5).
- [ ] Offline dot accurate in all three states.
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green; zero new warnings.

### Session notes

_To fill during/after the session._

## T06 — Phase 2 review & exit sweep

**Status:** not-started · **Depends on:** T05 · **Stories:** all P2-x

### Goal

Mirror Phase 1's T07 review: walk every P2-1…P2-8 acceptance criterion + Definition of
Done on the physical device, spot-check Phase 1 regressions, finalize D2 + risk register,
and note leftovers for Phase 3 grooming. Exit statement: *"paste a link, play it,
go offline later and play it again."*

### In scope

- Re-verify P2-1…P2-8 on device (Redmi + emulator spot-checks).
- Phase 1 regression spot-checks: local playback, speed 0.25×–2×, rotation continuity, keep-screen-on, missing-file re-link.
- Finalize D2 in `docs/02-architecture.md` (gate G1 outcome from T01).
- Update `docs/04-risks.md` with any newly discovered risks (R1 status after spike, R6 status after key-factory tests).
- Mark roadmap stories done; leftovers → Phase 3 grooming (loop, presets, count-in, metronome, boost-if-pulled, MediaSession, cache management UI, size cap).

### Out of scope

- No new feature work in this session; discoveries go to grooming.

### Acceptance

- [ ] All P2-1…P2-8 acceptance boxes checked on device.
- [ ] Definition of Done (§10) sweep complete → **Phase 2 exit**.
- [ ] Phase 1 regressions spot-checked, none found (or filed).
- [ ] D2 + risk register updated; leftovers noted for Phase 3.
- [ ] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green.

### Session notes

_To fill during/after the session._
_To fill during/after the session._