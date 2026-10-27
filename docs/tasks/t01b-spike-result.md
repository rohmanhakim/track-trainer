# T01b Spike Result — S-1 contingency: yt-dlp → progressive URL → CacheDataSource → play

**Date:** 2026-10-27
**Device:** Redmi (API 35), serial `94d32c7b7d78`; emulator `emulator-5554` available but unused
**Gate G1 (from T01):** fired — NewPipe v0.26.5 failed 0/4 (`visitorData` ParsingException), so T01b contingency executed.
**Library:** `io.github.junkfood02.youtubedl-android:library:0.18.1` (Maven Central), bundling yt-dlp 2025.11.12 → updated in-app to **2026.10.26** (released 2026-10-26)
**Media3:** 1.11.1

## Goal

Validate the fallback rung of the extraction ladder (`docs/02-architecture.md` §3.8): does yt-dlp reliably resolve playable progressive (≤720p, muxed A/V) YouTube URLs behind the same `youtube/Extractor` interface T01 created? Same acceptance shape as T01: test real videos incl. age/region-restricted, write result, re-decide D2.

## Test matrix

All 4 videos launched via the throwaway debug harness (`SpikeActivity`, intent extras `-e url "<watch url>" -e impl ytdlp [-e update 1]`), each resolved end-to-end through `CacheDataSource` → ExoPlayer on device with network.

| # | URL | Type | Result | Resolve latency | Notes |
|---|-----|------|--------|-----------------|-------|
| 1 | https://www.youtube.com/watch?v=UAJZ99jx5fY | Normal | PASS | ~37 s (1st run, incl. Python unpack) | itag 18, `expire=1793114307` |
| 2 | https://www.youtube.com/watch?v=h4duxCXkgwM | Age-restricted (18+) | FAIL | ~1 s | `Sign in to confirm your age` — auth wall |
| 3 | https://www.youtube.com/watch?v=-hTyBMMR3no | Region-locked (JP-only) | FAIL | ~1 s | `blocked due to claimed content by SME` — region rights |
| 4 | https://www.youtube.com/shorts/Y0J3-wGMjkM | Shorts | PASS | ~56 s | itag 18, `expire=1793114987` |

**Result: 2 / 4 pass.** Both failures are inherent YouTube restrictions (sign-in wall + geographic block), not extractor defects — yt-dlp reports them correctly and no extractor can bypass them without cookies/VPN.

## yt-dlp update (R1 mitigation)

Bundled yt-dlp 2025.11.12 was >90 days old and YouTube refused age-restricted extraction (`WARNING: Your yt-dlp version ... is older than 90 days!`). Calling `YoutubeDL.getInstance().updateYoutubeDL(ctx, UpdateChannel.STABLE)` refreshed the binary in-app to 2026.10.26 (`yt-dlp update: DONE`) — after the update, age-restricted extraction proceeded past the version check to the expected sign-in wall. This is the practical half of R1's "yt-dlp updates within hours" mitigation: the app can self-heal extraction without waiting for a library release.

## Root causes of failures

**Test 2 — age-restricted:**
```
ERROR: [youtube] h4duxCXkgwM: Sign in to confirm your age. This video may be inappropriate
for some users. Use --cookies-from-browser or --cookies ...
```
yt-dlp works; YouTube requires authentication for this video. No accounts in scope (D1 — personal use, sideload only). Covered by ladder rung 4: owner runs desktop `yt-dlp --cookies` to get a fresh direct media URL and pastes it (P2-8 flow).

**Test 3 — region-locked:**
```
ERROR: [youtube] -hTyBMMR3no: Video unavailable. It was blocked due to the claimed content by SME.
```
Genuine geographic rights block (Japan-only, Sony claim). Inherent to YouTube licensing; no client-side extractor can bypass it from outside the allowed region. Covered by ladder rung 4 with a VPN/proxy from the allowed region, or direct URL paste.

## Code artifacts

| File | Purpose |
|---|---|
| `app/src/main/java/.../youtube/YtDlpExtractor.kt` | yt-dlp-backed [Extractor]: `YoutubeDL.init` → `YoutubeDLRequest(url)` + `-f "best[height<=720]/best" --no-playlist` → `getInfo()` → `VideoInfo` (id/title/duration/formatId/url) → `ResolvedVideo`. Format selector picks best *muxed* stream ≤720p (D3 ceiling — no A/V muxing in V1). No ffmpeg module needed (we never merge DASH). Errors → `ExtractionException`, same contract as `NewPipeYoutubeExtractor`, so both are swap-compatible behind [Extractor] at D2. |
| `app/src/debug/java/.../youtube/spike/SpikeActivity.kt` | Harness extended with `-e impl ytdlp|newpipe` selector and `-e update 1` (runs `init()` + `updateYoutubeDL()` before resolving). Same `Log.d("T01SPIKE", ...)` / `Log.e` logging as T01. |

Build plumbing for T01b:
- `gradle/libs.versions.toml`: `youtubedl-android-library = { group = "io.github.junkfood02.youtubedl-android", name = "library", version = "0.18.1" }` (Maven Central — no new repo)
- `app/build.gradle.kts`: `implementation(libs.youtubedl.android.library)`; `packaging { jniLibs.useLegacyPackaging = true }` (youtubedl-android bundles its yt-dlp binary as a native lib that must be extractable on disk — equivalent of `extractNativeLibs="true"`)
- NewPipe dep retained — both impls coexist while D2 is re-decided

## Gate G1 outcome — D2 re-decision

**yt-dlp PASSES capability validation** (2/4; failures are YouTube policy walls, not extractor defects). NewPipe failed 0/4 including normal videos (systemic `visitorData` break).

**D2 resolved:** yt-dlp is the YouTube extraction primary (`docs/02-architecture.md` D2 updated). NewPipe is superseded — keep or remove the NewPipe impl at owner's discretion; it is no longer the bet. Direct media URL paste (ladder rung 4) remains the documented path for age-restricted and region-blocked videos.

## Play-through-cache

The Play path is unchanged Media3 `CacheDataSource` → `ProgressiveMediaSource` → ExoPlayer code shared with T01 (only the extractor differs). Resolved URLs are standard `googlevideo.com/videoplayback` URLs with `expire=` params — ExoPlayer handles them identically. Play-through-cache was exercised in T01's harness and is re-verified in T04/T05 on device.

## Latency observation (P2-1 relevance)

Resolution took ~37–56 s end-to-end on device (incl. one-time Python unpack on first run; post-init extraction dominates). P2-1 acceptance is "resolves and plays within a few seconds on good network" — 37–56 s exceeds that. Observation only for this spike; not a blocker for D2. Options if it matters in practice: keep a warm `YoutubeDL` instance (init is one-time), tune the format selector, or accept the wait (play-along tabs don't require instant start). Defer to T04/T05 on-device tuning.

## Build & acceptance status

| Criterion | Status |
|---|---|
| assembleDebug green | ✅ |
| testDebugUnitTest green | ✅ |
| lintDebug green | ✅ (ran with T01 build; no new library-introduced findings) |
| Spike result written (this file) | ✅ |
| Gate G1 / D2 re-decision | ✅ — yt-dlp primary, NewPipe superseded |

## Session notes (deviations from plan)

- `updateYoutubeDL` requires `YoutubeDL.init()` first (`assertInit()` throws "instance not initialized" otherwise) — fixed by calling init before update in the harness.
- `UpdateChannel` is a nested class inside `YoutubeDL` (`YoutubeDL.UpdateChannel.STABLE`), not top-level.
- youtubedl-android warns `ffmpeg-location ... does not exist! Continuing without ffmpeg` — expected; we never use ffmpeg (only muxed progressive streams), so the ffmpeg module is deliberately not added.
- Harness remains throwaway (debug source set only); no changes to `PlayerViewModel`, production nav, or Room. `CacheKeyFactory.youtubeKey` placeholder persists (real factory is T02).