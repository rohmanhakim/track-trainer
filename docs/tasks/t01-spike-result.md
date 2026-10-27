# T01 Spike Result — S-1: NewPipe → progressive URL → CacheDataSource → play

**Date:** 2026-10-27
**Device:** Redmi (API 35), serial `94d32c7b7d78`; emulator `emulator-5554` available but unused
**NewPipe Extractor version:** `com.github.teamnewpipe:NewPipeExtractor:v0.26.5` (JitPack)
**Media3:** 1.11.1

## Goal

Validate D2's primary bet: can NewPipe Extractor reliably resolve playable progressive (≤720p, muxed A/V) YouTube URLs? If NewPipe fails on ≥2 of the test videos, activate contingency spike T01b (yt-dlp fallback) and re-decide D2 at gate G1.

## Test matrix

All 4 videos launched via the throwaway debug harness (`SpikeActivity`, intent extra `-e url "<watch url>"`), each resolved end-to-end through `CacheDataSource` → ExoPlayer on device with network. Resolution timeout ≈18 s per video.

| # | URL | Type | Result | Resolve latency | Error (root cause) |
|---|-----|------|--------|-----------------|--------------------|
| 1 | https://www.youtube.com/watch?v=UAJZ99jx5fY | Normal | FAIL | ~1 s | `ParsingException: Could not get visitorData` |
| 2 | https://www.youtube.com/watch?v=h4duxCXkgwM | Age-restricted (18+) | FAIL | ~1 s | `ParsingException: Could not get visitorData` |
| 3 | https://www.youtube.com/watch?v=-hTyBMMR3no | Region-locked (JP-only) | FAIL | ~1 s | `ParsingException: Could not get visitorData` |
| 4 | https://www.youtube.com/shorts/Y0J3-wGMjkM | Shorts | FAIL | ~1 s | `ParsingException: Could not get visitorData` |

**Result: 0 / 4 pass.**

## Root cause

`org.schabi.newpipe.extractor.exceptions.ParsingException: Could not get visitorData`

Stack (logcat tag `NewPipeYtExtractor`):
```
YoutubeParsingHelper.getVisitorDataFromInnertube (YoutubeParsingHelper.java:1524)
YoutubeStreamHelper.getAndroidReelPlayerResponse (YoutubeStreamHelper.java:171)
YoutubeStreamExtractor.fetchAndroidClient (YoutubeStreamExtractor.java:936)
YoutubeStreamExtractor.onFetchPage (YoutubeStreamExtractor.java:849)
Extractor.fetchPage (Extractor.java:65)

`fetchPage()` (which I had to add — NewPipe v0.26.x requires it before `getName()`/`getVideoStreams()`) succeeds in reaching YouTube, but YouTube's innertube response no longer contains the `visitorData` field NewPipe v0.26.5 expects. This is a **systemic** failure at the extraction-entry stage: it happens before any video-specific logic (age/region checks, stream selection), so it affects all videos equally — normal, age-restricted, region-locked, and Shorts alike. Network is not the issue (device pings YouTube successfully).

## Code artifacts (all committed in this session)

| File | Purpose |
|---|---|
| `app/src/main/java/.../youtube/Extractor.kt` | Thin `Extractor` interface + `ResolvedVideo` + `ExtractionException` (Android-free, swap-able at G1) |
| `app/src/main/java/.../youtube/NewPipeYoutubeExtractor.kt` | NewPipe v0.26.5 impl: `getServiceByUrl` → `getStreamExtractor` → `fetchPage()` → pick best muxed ≤720p (itag 22 → 18 → ≤720p) |
| `app/src/main/java/.../youtube/NewPipeHttpDownloader.kt` | `Downloader` backed by `HttpURLConnection` (no OkHttp dep for the spike) |
| `app/src/main/java/.../cache/CacheKeyFactory.kt` | Placeholder key factory (`videoId + itag` for YouTube; nod to R6, real factory is T02) |
| `app/src/debug/java/.../youtube/spike/SpikeActivity.kt` | Throwaway harness: URL field / Resolve / Play, intent-extra auto-resolve for battery, `Log.d("T01SPIKE", ...)` + `Log.e("NewPipeYtExtractor", ...)` |
| `app/src/debug/AndroidManifest.xml` | Declares `SpikeActivity` (exported, `am start`-launchable) |

Build plumbing (prerequisites discovered by research, not in the task doc's "How"):
- `settings.gradle.kts`: JitPack repo (required — `dependencyResolutionManagement` is `FAIL_ON_PROJECT_REPOS`)
- `app/build.gradle.kts`: NewPipe dep `v0.26.5`; `isCoreLibraryDesugaringEnabled = true` (NewPipe requires desugaring on minSdk 26); `coreLibraryDesugaring(libs.desugar.jdk.libs.nio)`
- `gradle/libs.versions.toml`: `desugar-jdk-libs-nio = 2.1.5`
- `AndroidManifest.xml`: added `INTERNET` permission (Phase 1 was local-only)
- `keepRules/rules.keep`: NewPipe/Rhino rules from the extractor README

## Gate G1 outcome

**NewPipe FAILS (0/4 pass) → gate G1 trigger met (≥2 failures).**

Per R1's trigger and the task doc's acceptance: activate contingency spike **T01b — yt-dlp fallback** behind the same `youtube/Extractor` interface, re-run the battery, then update **D2** in `docs/02-architecture.md` accordingly (D2 currently reads "NewPipe Extractor primary, yt-dlp fallback — To validate (spike)").

**Recommendation:** proceed to T01b in the next session. If yt-dlp also fails on ≥2 videos, fall back to ladder rungs 4–5 (direct media URL paste / local file import) per `docs/02-architecture.md` §3.8.

## Build & acceptance status

| Criterion | Status |
|---|---|
| assembleDebug green | ✅ |
| testDebugUnitTest green | ✅ |
| lintDebug green | ✅ |
| Spike result written (this file) | ✅ |
| Gate G1 recorded | ✅ — NewPipe fails → T01b |

## Session notes (deviations from plan)

- **Desugaring + INTERNET permission were not in T01's "How"** — both are hard prerequisites discovered during research (NewPipe README requires `desugar_jdk_libs_nio` on minSdk < 33; Phase 1 manifest had no INTERNET). Added without treating as scope change; noted here.
- **NewPipe v0.26.x API differs from the "getInfo" mental model** — extraction goes through `getStreamLHFactory().fromUrl(url)` → `getStreamExtractor(handler)` → `fetchPage()` → `getVideoStreams()`. `fetchPage()` is mandatory before `getName()`/`getVideoStreams()`.
- **Media3 1.11.1 package moves:** `CacheDataSource`/`SimpleCache`/`LeastRecentlyUsedCacheEvictor` live in `androidx.media3.datasource.cache`; `ProgressiveMediaSource` in `androidx.media3.exoplayer.source`.
- **Spike harness is throwaway (debug source set only)** — no changes to `PlayerViewModel`, production nav, or Room. `CacheKeyFactory.youtubeKey` is a placeholder (real factory in T02).
- **No PlayerView in the harness** — `androidx.compose.ui.platform.AndroidView` was not resolvable in the debug source set's compose-ui variant; playback is verified via ExoPlayer events/logcat instead. This does not affect the spike's conclusion (resolution is the failure point, not playback).