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
| T01 | Compose foundation | done | S1 checklist |
| T02 | Dependencies + Hilt + Room skeleton | done | S1 checklist |
| T03 | Library screen + local reference | done | P1-1 |
| T04 | Missing-file detection & re-link | done | P1-1a |
| T05 | Player core | done | P1-2, P1-5 |
| T06 | Speed control | done | P1-3, spike S-3 |
| T07 | Portrait study layout | done | P1-4 |

**Order:** T01→T02 foundation; T03→T04 data before player; T05→T06 features;
T07 only once controls exist to arrange. Environment reminder: every Gradle
command needs `export JAVA_HOME=/home/arif/android-studio/jbr` (AGENTS.md).

---

## T01 — Compose foundation

**Status:** done · **Depends on:** — · **Stories:** S1 checklist (partial)

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
- [x] Installs and launches on device showing a placeholder M3 screen *(emulator Android 17 — owner overrode DoD #3 for T01)*
- [x] No `appcompat`/`material` warnings for unused template deps

### Session notes

**Done 2026-10-02 — all acceptance boxes checked (emulator verification + DoD override below).**

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
- **Emulator verification (2026-10-02):** installed + launched on `emulator-5554`
  (Pixel_4 AVD, **Android 17 / API 37**): `installDebug` OK (2 s), LAUNCHER activity starts,
  process stays alive (no `FATAL`/`AndroidRuntime` hits in logcat), placeholder screen renders
  correctly in **light and dark** mode (screenshots reviewed: M3 background, centered
  headline, edge-to-edge with no action bar), dark-mode toggle recreates the activity without
  process death. **Owner override: DoD #3 waived for T01** (emulator verification accepted —
  launch-only task, no audio/extraction/caching behavior involved); T01 marked done.

---

## T02 — Dependencies + Hilt + Room skeleton

**Status:** done · **Depends on:** T01 · **Stories:** S1 checklist (partial)

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
- [x] All deps resolve; `assembleDebug` green
- [x] `testDebugUnitTest` + `lintDebug` green
- [x] On device: app launches (Hilt Application active, no DI crash) and DB file is created *(emulator-5554 — owner approved emulator for T02: "emulator is enough for now")*

### Session notes

**Done 2026-10-03 — all acceptance boxes checked (emulator verification, owner-approved).**

- **Versions pinned (all latest stable at session time):** KSP **2.3.12**, Hilt **2.60.1**,
  Room **2.8.5**, DataStore **1.2.1**, coroutines **1.11.0**, lifecycle **2.11.0**,
  Media3 **1.11.1**. DataStore + Media3 land declared-but-unused per S1 (first wired
  T05 / later). Compose BOM unchanged (no churn rule).
- **KSP under AGP 9 built-in Kotlin:** KSP decoupled its versioning from Kotlin at 2.3.0;
  **≥2.3.1 required for AGP 9 built-in Kotlin** (2.3.4+ recommended per JetBrains
  AGP-9 compat doc); old `-2.0.x` suffix = deprecated KSP1. Pinned 2.3.12 via catalog
  alias — **worked out of the box**: no `android.disallowKotlinSourceSets=false`,
  no buildscript classpath override needed.
- **Hilt:** plugin + `hilt-android`/`hilt-android-compiler` (KSP); `@HiltAndroidApp`
  `TrackTrainerApplication` in `app/`, manifest `android:name=".app.TrackTrainerApplication"`.
  The known Hilt 2.59 AGP-9/Jetifier `hiltJavaCompile` bug does not apply (jetifier
  never enabled in this project).
- **Room:** `androidx.room` Gradle plugin + `room { schemaDirectory("$projectDir/schemas") }`
  — schema export **enabled and committed** (owner decision #1). Output:
  `app/schemas/io.github.rohmanhakim.tracktrainer.data.local.AppDatabase/1.json`.
  AGP 9.3.3 + Room 2.8.5 plugin: no issues.
- **Track entity:** follows 02-architecture §4 **including `youtubeId`** — the task's
  field list omits it, but §4 has it; included now to avoid an early migration since
  schema v1 is already committed. Enum `SourceType { LOCAL, YOUTUBE, DIRECT }` +
  `TypeConverter`. DAO skeleton: `observeAll(): Flow`, `insert`, `count`.
- **Smoke bootstrap (deliberate):** Room creates its DB lazily and T02 has no UI to
  touch it, so `TrackTrainerApplication` injects `TrackDao` and runs `count()` on an
  unscoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)` in `onCreate` — proves
  Hilt wiring + Room file creation in one launch. T03 may replace it once
  `TrackRepository` exists.
- **Skeleton:** `data/local/` (Track, Converters, TrackDao, AppDatabase, DatabaseModule),
  `data/repo/`, `player/`, `library/` placeholders via `.gitkeep` (git ignores empty
  dirs); `app/` + `ui/` already existed. Note: `library/` isn't in 02-architecture §2's
  tree — added because this task mandates it (feature package for T03).
- **Builds:** first `assembleDebug` **5m 28s** (cold: KSP/Hilt/Room/Media3 downloads +
  config-cache re-store after daemon JVM change); warm `testDebugUnitTest` 6s,
  `lintDebug` 17s. Lint: **0 errors, 7 warnings — the same T01-era set** (OldTargetApi
  36, Gradle 9.5→9.8, AGP 9.3.3→9.4.1, core-ktx 1.19.1, compose-plugin 2.4.20,
  LockedOrientationActivity + DiscouragedApi); **zero warnings introduced by T02**.
- **Emulator verification (2026-10-03):** `installDebug` OK; `am start` → process alive
  (stable pid), **no FATAL/AndroidRuntime hits** in logcat; smoke log
  `TrackTrainer: Track DB ready, tracks=0` (Hilt injection + Room query executed);
  `run-as … ls databases/` → `track-trainer.db` (+ `-shm`/`-wal`) created.

---

## T03 — Library screen + local reference

**Status:** done · **Depends on:** T01, T02 · **Stories:** P1-1

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
- [x] P1-1: pick → row appears; survives app restart **and device reboot**; reference resolves at least via metadata read (playback itself waits for T05)
- [x] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [x] Verified on physical device *(owner-approved emulator override — same standing
  approval as T01/T02; physical re-confirmation rolls into T07's full DoD sweep)*

### Session notes

**Done 2026-10-03 — all acceptance boxes checked (emulator verification, owner-approved).**

- **Files:** `data/repo/TrackRepository.kt` (thin DAO wrapper: `observeTracks()`,
  `add()`); `library/LibraryViewModel.kt` (`@HiltViewModel`, StateFlow via
  `stateIn(WhileSubscribed)`, one-shot `Channel` messages → snackbar);
  `library/LibraryScreen.kt` (Scaffold + TopAppBar action, `LazyColumn key = id`,
  `ListItem` rows, empty state); `MainActivity` → `@AndroidEntryPoint` +
  `LibraryScreen()`; `strings.xml` +3 keys. `.gitkeep` removed from `repo/`+`library/`.
  No new dependencies, no manifest changes (SAF needs none), T02 smoke bootstrap kept.
- **Hilt ↔ Compose wiring:** `@AndroidEntryPoint` on MainActivity + plain
  `viewModel()` (lifecycle-viewmodel-compose) — the activity's default factory becomes
  `HiltViewModelFactory`, so no `hilt-navigation-compose`/`hiltViewModel()` dep needed.
- **Decisions recorded per task "How":** **duration read at insert time** via
  `MediaMetadataRetriever` on `Dispatchers.IO` (cheap at personal-list scale; 0 on
  failure → row renders without duration); **no thumbnails** (Coil not a declared
  dep — no churn); **top-bar text action `[+ Add track]`** (not FAB) + identical
  empty-state button.
- **Deviation 1 — icons dropped:** planned `Icons.Default.Add` didn't compile — M3
  1.4.0 (BOM 2026.09.00) no longer ships `material-icons-core` transitively. Fix:
  text-only buttons (`"+ Add track"`), no new dep. UX unchanged in substance.
- **Deviation 2 — grant flags:** task text specifies
  `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_PERSISTABLE_URI_PERMISSION` as
  `takePersistableUriPermission` modeFlags, but that parameter accepts **only**
  READ/WRITE (lint `WrongConstant` **error** — real API contract, not a false
  positive). Passing `FLAG_GRANT_READ_URI_PERMISSION` alone is correct; the
  persistable capability comes from the picker intent's grant. Fix applied; lint clean.
- **Builds:** `assembleDebug` 10s / `testDebugUnitTest` 9s / `lintDebug` 23s warm
  (config cache reused). Lint: **0 errors, 7 warnings — byte-identical T01/T02
  baseline set** (OldTargetApi, Gradle 9.8, AGP 9.4.1, core-ktx 1.19.1,
  compose-plugin 2.4.20, LockedOrientationActivity, DiscouragedApi); **zero
  introduced by T03**.
- **Emulator verification (2026-10-03, `emulator-5554`, Pixel_4 API 37):**
  - Test asset: `bb.mp4` (7.2 MB, 0:25) owner-pushed to `/sdcard/Movies/`.
  - Empty state renders (screenshot); **+ Add track** → DocumentsUI
    (`PickActivity`) → `sdk_gphone16k_x86_64/Movies/bb.mp4` → pick → back in app,
    row **`bb.mp4 / 0:25`** appears instantly (title from `OpenableColumns`,
    duration from `MediaMetadataRetriever` — cross-checked: MediaStore reports
    `duration=25424 ms = 0:25`).
  - **Restart:** `force-stop` + relaunch → row intact.
  - **Reboot:** `adb reboot` → full boot → relaunch → row intact, no
    FATAL/AndroidRuntime hits (only a benign system VM exit line, code 0).
  - **Picker caveat:** `adb push` doesn't trigger a media scan, so DocumentsUI
    "Recent" was empty; browsing storage → `Movies` works (DocumentsUI resolves
    MIME from the file extension — no scan needed). Noted for future test-asset pushes.
- **Limitation (recorded honestly):** post-reboot *URI re-resolution* could not be
  re-probed from outside the app on this production image: `adb root` refused,
  `dumpsys uri_grants` prints nothing to shell, `content` CLI via `run-as` blocked
  by `ACCESS_CONTENT_PROVIDERS_EXTERNALLY`. Evidence chain instead: (a) grant call
  at pick time succeeded — no exception, no error snackbar, metadata read succeeded;
  (b) `takePersistableUriPermission` persists to the OS's `uri_grants` store by
  contract, which reboots don't clear; (c) Room DB (`track-trainer.db` + `-wal`)
  verified on disk post-reboot with the row on screen. **T04's health check and
  T05's playback are the first code that re-reads the URI for real** — if the grant
  were somehow missing they will surface it immediately; flagged so those sessions
  watch for it.
- **Testing:** owner decision this session — **manual verification only**, no new
  automated tests (task acceptance specifies build/test/lint + on-device only; avoids
  first-ever `connectedDebugAndroidTest` run mid-task).

---

## T04 — Missing-file detection & re-link

**Status:** done · **Depends on:** T03 · **Stories:** P1-1a

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
- [x] P1-1a: rename/delete source file → row flags missing, app never crashes
- [x] Re-link with a valid file → track restored (and playable once T05 exists)
- [x] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [x] Verified on physical device *(owner-approved emulator override — same
  standing approval as T01–T03; physical re-confirmation rolls into T07's sweep)*

### Session notes

**Done 2026-10-03 — all acceptance boxes checked (emulator verification, standing
owner override; physical re-confirmation deferred to T07's DoD sweep).**

- **Files:** new `data/repo/TrackHealthChecker.kt` (persistable-grant check +
  real `openFileDescriptor` probe; **never throws** — any failure ⇒ "missing";
  injectable so T05 can reuse it on track open); new `data/prefs/AppPreferences.kt`
  (**first real DataStore use** — one `cloud_hint_shown` boolean, `.catch` scoped
  to `IOException` only); `TrackDao`/`TrackRepository` (+`@Update`, +`getById` —
  **no schema change, no migration**); `LibraryViewModel` (`LibraryUiState
  (tracks, missingIds)` built via `observeTracks().flatMapLatest { check }`,
  `relinkTrack()`, `showCloudHintOnce()`, `releaseOldGrant()`); `LibraryScreen`
  (missing-row UI: ⚠️ error-colored line + trailing **Re-link** `TextButton`;
  `pendingRelinkId: Long?` via `rememberSaveable` routes one shared SAF launcher);
  `strings.xml` +2 (`library_missing`, `library_relink`).
- **Decision — computed state, not flag column** (task allowed either): a derived
  value doesn't justify a Room migration (schema v1 is committed) and a stored
  flag would go stale — recomputed on every load and after add/re-link, no poller.
- **Decision — checker in `data/repo/`:** it resolves `Track.sourceRef`
  (repository-layer concern) rather than being a library-UI detail.
- **Deviation — one launcher, not two:** plan wording said "second launcher", but
  a single `OpenDocument` launcher + `pendingRelinkId` (null = add, set = re-link,
  cleared before dispatch so a cancelled pick can't hijack the next add) is fewer
  moving parts for identical behavior. Also: plan listed a `library_relink_failed`
  string key — kept VM snackbar messages **hardcoded** per T03's convention
  (only composable-referenced text lives in `strings.xml`).
- **⚠️ Empirical finding — the grant dies with the file.** On this API 37 image,
  deleting the source made `persistedUriPermissions` stop listing the URI
  (log: `Source check: grant not held for …`), and **restoring the file did not
  bring the grant back** — the row stays flagged until Re-link. AOSP docs don't
  document revocation-on-delete (searched — nothing found). Behavior is *correct*:
  without the grant, T05 playback would hit `SecurityException` anyway, and
  Re-link re-takes the grant (verified healing). **Never-throw contract earned its
  keep:** `releasePersistableUriPermission` on the dead grant threw
  `SecurityException` ("No permission grants found"), *not* the documented
  `IllegalArgumentException` — the broad `catch (e: Exception)` saved the re-link.
  Same-URI re-pick skips release via the `oldRef == newRef` guard (both paths
  exercised on device). Added decision logging (`grant not held` / `open returned
  no descriptor`) after the first silent run made the branch impossible to tell —
  worth keeping for T05 debugging.
- **Cloud hint (owner chose: include now):** authority allowlist
  (`com.android.externalstorage.documents`, `media`,
  `com.android.providers.downloads.documents` = local; anything else ⇒ maybe-cloud)
  + one-time snackbar persisted in DataStore. **Untestable on the emulator** (no
  Drive app → picker only shows local roots) — verified by code review; heuristic
  tradeoff recorded (false positive = one ignored toast, once).
- **Lint:** first run flagged 2× `UseKtx` (`Uri.parse` → `String.toUri()`) from
  the new code → fixed both → **0 errors, 7 warnings** on final source,
  byte-identical to the T01–T03 baseline. Note: the compiler emits KT-73255
  `@ApplicationContext` annotation-target warnings for constructor params —
  same pattern T03's ViewModel already had (compiler-level, not lint).
- **Builds:** `assembleDebug` 23s warm; `testDebugUnitTest` 4–6s; `lintDebug`
  2m 55s cold (config-cache store for the lint graph) then warm.
- **Emulator verification (2026-10-03, `emulator-5554`, API 37) — final binary:**
  - Baseline healthy (file present, no warning, no false positive).
  - `rm /sdcard/Movies/bb.mp4` → force-stop + relaunch → row still renders from
    cache, **⚠️ File missing + Re-link**; log `Source check: grant not held`;
    **zero FATAL/AndroidRuntime, crash buffer empty**.
  - File restored → still flagged (grant dead, see finding) → **Re-link** →
    DocumentsUI (opens straight into Movies) → picked `bb-relink.mp4` (new name ⇒
    `sourceRef` change observable) → title/duration re-read, warning cleared.
  - Restart → healthy persists (`Track DB ready` only, silent passing check);
    DB WAL shows updated `sourceRef`; delete cycle repeated on the final build →
    same flagged state, no crash. Device left in a clean healthy state.
- **Tooling gotchas for future sessions:** (1) the emulator display slept
  mid-session — `screencap` kept returning a **frozen frame** (tell: stale
  status-bar clock vs `adb shell date`) and `uiautomator dump` failed silently;
  fixed with `KEYCODE_WAKEUP` + `wm dismiss-keyguard`. **When screenshot clock
  and logcat disagree, trust uiautomator dump + logcat.** (2) Two parallel adb
  batches force-stopped the app into contradictory states — device verification
  must be strictly sequential.
- **Testing:** manual verification only (owner decision carried over from T03).

---

## T05 — Player core

**Status:** done · **Depends on:** T03 (T04 nice-to-have) · **Stories:** P1-2, P1-5

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
- [x] P1-2: play/pause responds <100 ms; seek lands ≈ target; no crash on rapid seeking
- [x] P1-5: screen stays awake while playing; sleeps after pause
- [x] Video + audio render (speaker or headphones)
- [x] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [x] Verified on physical device *(emulator pass 2026-10-04; **physical pass 2026-10-26**
  on Redmi Note 12 Turbo "fire" / Android 15 — full AC sweep in Session notes below.
  T01–T04 physical re-confirmations still pending: only the T04 re-link cycle + reboot
  check remain for T07's sweep)*

### Session notes

**Done 2026-10-04 — all acceptance boxes checked (emulator verification, owner-approved).**

- **Files:** new `player/PlayerViewModel.kt` (owns ExoPlayer, open/release/transport
  API, position polling) + `player/PlayerScreen.kt` (`AndroidView { PlayerView }`
  surface + Compose transport + keep-screen-on + lifecycle pause);
  `app/MainActivity.kt` (manual two-destination nav state);
  `library/LibraryScreen.kt` (+`onTrackClick`, clickable rows incl. missing ones);
  `strings.xml` +3 (`player_back/play/pause`); `player/.gitkeep` removed.
  **No new dependencies, no manifest changes, no Room schema change.**
- **Decision 1 — navigation = manual state, no nav-compose:** `rememberSaveable`
  `openTrackId` in MainActivity (sentinel `-1` = library), `BackHandler` inside
  PlayerScreen. Zero new deps; nav-compose revisit at Phase 2 grooming (3rd screen).
  **The catch that had to be solved:** `viewModel()` is activity-scoped, so
  PlayerScreen's VM survives leaving composition — release therefore can't rely on
  ViewModel-store clearing: BackHandler calls `releasePlayer()` explicitly and
  `onCleared()` covers activity teardown (belt and braces).
- **Decision 2 — surface:** `AndroidView { PlayerView }` (media3-ui already a dep) with
  `useController=false` and our own Compose transport below; `media3-ui-compose`
  rejected (new dep, no-churn). Fixed 16:9 letterbox box for T05 — T07 owns the real
  study-mode arrangement.
- **Decision 3 — lifecycle:** player built on main thread inside `openTrack` after the
  IO health check; `openTrack` is **idempotent per trackId** so the LaunchedEffect
  re-run after rotation does NOT restart playback; released on back + `onCleared`.
- **Decision 4 — keep-screen-on (P1-5):** `view.keepScreenOn` toggled via
  `DisposableEffect(isPlaying)` with `onDispose { false }` reset so the library screen
  can never inherit a stale flag.
- **Decision 5 — backgrounding:** ON_STOP → `pause()`, **skipping
  `isChangingConfigurations`** — a plain ON_STOP pause would fire on every activity
  recreation and break rotation continuity (P1-4). No MediaSession in Phase 1, so
  background audio would be uncontrolled; pause is the minimal correct behavior.
- **Decision 6 — audio focus:** `setAudioAttributes(USAGE_MEDIA/MUSIC,
  handleAudioFocus=true)` — the cheap half of the Phase 7 MediaSession story.
- **Decision 7 — health check on open:** `TrackHealthChecker.findMissing(listOf(track))`
  before building the player (per its KDoc "T05 should reuse this on track open") —
  this was T03's flagged *first real URI re-read*; a dead grant surfaces as an error
  state, never a `SecurityException`.
- **Decision 8 — position feed:** 250 ms poll in viewModelScope + immediate state
  update on seek (label would look laggy otherwise); slider drag holds a local
  `scrubMs` override so polling can't fight the thumb; seek clamped to known duration.

- **Bug found & fixed on device:** at `STATE_ENDED`, `play()` is a no-op
  (`playWhenReady` already true) — observed: after the 25 s clip ended, tapping Play
  kept showing `0:25 / Play`. Fix: `seekTo(0)` before `play()` when
  `playbackState == STATE_ENDED`. Re-verified: ENDED → tap Play → `0:02 / Pause`
  (restarted from the top).
- **Lint journey (3 rounds → clean):** (1) 3× `UnsafeOptInUsageError` —
  `AspectRatioFrameLayout.RESIZE_MODE_FIT` is still `@UnstableApi` in Media3 1.11
  (`PlayerView` itself is stable) → dropped the line entirely, **FIT is PlayerView's
  default resize mode anyway**. (2) `EmptySuperCall` on `onCleared()` → removed
  `super.onCleared()` (base is empty). (3) `AutoboxingStateCreation` *hint* on
  `mutableStateOf(-1L)` → `mutableLongStateOf(-1L)` (hint would have been a new
  finding vs. baseline — DoD #1). Final lint: **0 errors / 7 warnings byte-identical
  to the T01–T04 baseline / 0 hints** — zero findings introduced by T05.
- **Builds:** first compile 17 s (Media3 usage is first-compile of new sources);
  final full trio `assembleDebug testDebugUnitTest lintDebug` green at 5–12 s warm.
- **Emulator verification (2026-10-04, `emulator-5554`, API 37) — final binary:**
  - **Open:** tap row → PlayerScreen autoplays; position advances (`0:02 → 0:03 → …`);
    title in TopAppBar; **video frame renders** (screenshot reviewed: Big Buck Bunny
    frame, 16:9 letterbox, transport below); audio evidence:
    `AudioPlaybackConfiguration … u/pid:…/<app> state:started usage=USAGE_MEDIA`.
  - **Transport (P1-2):** Pause/Play label + state flip lands inside a 0.4 s probe
    window; seek to slider 50 % → label `0:14` (target 0:12.7 + ~1 s dump latency ⇒
    within the ~1 s AC); **×20 rapid seeks → no crash** (AndroidRuntime 0, crash
    buffer clean for the package).
  - **P1-5 (first-run settings gotcha):** emulator defaults neutralize the test
    (`stay_on_while_plugged_in=1`, `screen_off_timeout=2147483647`) — temporarily set
    stay_on=0 + timeout=10 s for the test, restored after: playing → 13 s idle →
    `mWakefulness=Awake / mScreenState=ON` ✓; paused (Play label confirmed before the
    wait) → 13 s idle → `Asleep / OFF` ✓. Both settings restored to original values.
  - **Rotation (P1-4 partial):** playing `0:02` → forced landscape → `0:07` still
    playing (audio started=1) → back to portrait → `0:11` still playing; activity
    recreated **twice**, playback uninterrupted, 0 crashes. Also confirms T01's
    prediction: manifest `screenOrientation=portrait` is indeed ignored on API 37.
  - **Back:** returns to library; `started` audio configs = **0** (player released,
    no leaked audio).
  - **Regression sweep (DoD #3):** renamed source away → ⚠️ File missing + Re-link
    still renders with the modified (clickable) row; tapping the missing row → player
    shows `File missing — re-link it in the library`, 0 crashes; file restored → row
    stays flagged (grant revocation-on-rename = same T04 finding, reproduced);
    **Re-link via UI → healthy row, survives cold relaunch, plays** — i.e. T03's
    flagged "first real URI re-read" works end-to-end after a re-link.

- **⚠️ Anomaly (honest record):** once, a fresh open auto-played then sat **paused at
  `0:02`** before any rotation test. **Did not reproduce ×3** (immediate replay, cold
  force-stop open, post-re-link open — all play continuously to end); logcat shows
  **zero** AudioFocus/AudioBecomingNoisy/suppression events across all retries; no
  crash; recovered by tapping Play. Suspected transient emulator audio-focus blip —
  **watch-item for the physical-device test at phase end.**
- **Tooling gotchas (new this session):** (1) With a 10 s test timeout the screen
  sleeps **between agent batches** — taps land on a dark digitizer and uiautomator
  dumps return empty; run each *wake → gesture → wait → check → restore-settings*
  sequence inside **one** batch (a 30 s command timeout killed my first combined
  run before it restored the settings — repaired immediately; batch must stay <30 s).
  (2) `logcat -d | grep "…"` **self-matches**: adbd echoes the shell command line
  into the buffer, so counts like `grep -c "Source check"` returned 1 from their own
  echo — verify instead with the tag filter `logcat -d -s TrackTrainer:*`. (3) Broad
  `*:F` filters surface unrelated system native crashes (a UWB HAL crash appeared
  mid-session) — scope crash checks to the package / `AndroidRuntime:E` /
  `-b crash | grep <pkg>`.
- **Testing:** manual verification only (owner decision carried from T03/T04 —
  confirmed again at session start for T05; the template `ExampleUnitTest` remains
  the whole `testDebugUnitTest` suite and stayed green).
- **Data note:** the emulator's single row points to **`bb-relink.mp4`** (not `bb.mp4`
  as believed pre-session — T04's re-link re-read the title). Both files exist,
  identical content; row healthy before and after the session (device left at the
  library screen, player released, zero audio configs).
- **Post-done follow-up (UNCOMMITTED, owner-requested): decoder-throughput debug log**
  in `PlayerViewModel.startPositionPolling` — every ~5 s while playing, logs
  `decoder: ~X fps dropped=… skipped=… maxConsecutiveDrop=… renderedTotal=…` from
  `ExoPlayer.videoDecoderCounters` (Media3 1.11: returns nullable `DecoderCounters`;
  `VideoDecoderCounters` no longer exists — use `renderedOutputBufferCount` +
  `ensureUpdated()`). This is the **in-app assert for playback smoothness**. Measured
  on emulator: owner's 4K AV1 track (`c2.android.av1-dav1d` = software decode) →
  **~7 fps rendered, ~120 drops/5 s, 26-frame drop bursts** vs H.264 720p control →
  **~24 fps rendered (= source rate), 0 drops** — confirms jank is decoder-bound
  (emulator has no AV1 hw codec), not an app bug. Screenrecord cross-check agreed
  (9 frames displayed in 10 s for AV1). Watch-item for phase-end physical device
  (hw-AV1 SoCs should be fine; older → same SW path).
- **Physical-device verification (2026-10-26) — full AC sweep on Redmi Note 12 Turbo
  (23053RN02A "fire", Android 15 / API 35, HyperOS), sideloaded `app-debug.apk`:**
  - **Install/launch:** fresh DB (`tracks=0`), Hilt+Room bootstrap, empty state ✓
    (T01/T02 physically re-confirmed). Both test assets pushed to `/sdcard/Movies/`,
    added via SAF (root drawer → "Redmi 12" → Movies; picker persists location),
    durations read ✓ (T03 add-flow on hardware).
  - **P1-2:** bb.mp4 autoplays; video frame renders (screenshot reviewed); audio
    `state:started usage=USAGE_MEDIA`; pause/play flips inside probe windows;
    ENDED→Play restarts from `0:02` (the T05 ENDED fix confirmed on hw); 50% seek →
    `0:14`; **×15 rapid seeks → no crash**, pid alive; Back → library with
    **0 started audio configs** (release verified on hw).
  - **P1-5:** neutralized Xiaomi `stay_on_while_plugged_in=15` (charging default —
    same trap class as the emulator) + 10 s timeout: playing → +11 s idle →
    `Awake/ON`; **literally paused at `0:04`** → +11 s idle → `Asleep/OFF`; both
    settings restored (600000 / 15).
  - **AV1 verdict on hardware:** created codec = `c2.android.av1-dav1d` (**software**)
    — no hw AV1 component offered for `video/av01` on this device → **~6 fps rendered,
    drops 68→188 per 5 s, 38-frame bursts** ≈ the emulator's numbers. Jaggy AV1 =
    device capability, now confirmed on **two** devices; H.264 control = ~24 fps /
    0 drops on both. (Owner's pre-session instinct was right; the earlier "hw AV1
    should fix it" hope is disproven for this phone.)
  - **Rotation (advisory):** manifest portrait lock **holds on API 35** — forced
    `user_rotation` leaves `mDisplayRotation=ROTATION_0`, no config change possible
    on this device; the P1-4 rotation-continuity AC remains covered by the emulator
    pass (API 37, lock ignored there — T01's DiscouragedApi warning materialized only
    on 16+).
  - **0 `AndroidRuntime` errors across the entire session.**
  - **HyperOS tooling gotchas:** `wm dismiss-keyguard` is a no-op → use
    `input keyevent 82`; uiautomator dumps fail on off-screen/lockscreen (empty
    output); **writing a short `screen_off_timeout` while the screen is already idle
    sleeps it instantly** — always wake/unlock first, then set the timeout, then
    gesture immediately (each input resets the idle timer).
  - **Owner confirmations (2026-10-26):** ✓ **audio audible** — owner had muted the
    phone initially then raised volume (explains the early `mutedState:streamVolume`
    flag; the app's audio path was fine all along); ✓ **AV1 clip subjectively jaggy**,
    matching the measured ~6 fps software decode. P1-2's "video + audio render" and
    the AV1 hardware verdict now both have owner-eyewitness backing.


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
**Code complete 2026-10-26 — all build gates green:**

- `PlayerViewModel`: `currentSpeed` (session-level, clamped 0.25×–2×), `setSpeed()` applies `PlaybackParameters(speed)` live to the `ExoPlayer` and persists `PlayerUiState.speed`; fresh players in `startPlayer()` get the stored speed applied (pitch defaults to 1× → preserved). Speed preserved across track opens (fixed `startPlayer`/`openTrack` to copy existing `uiState` rather than resetting `speed` to 1f).
- `PlayerScreen`: new `SpeedControls` composable — preset chips (0.5× / 0.75× / 1×) + fine slider (0.25×–2×, 0.05× steps) wired to `viewModel::setSpeed`, placed below `Transport`.
- No new Gradle deps (Media3 `PlaybackParameters` already available); everything in the `player` package, package-by-feature preserved.

**On-device verification (Redmi 23053RN02A, Android 15, headphones):**
- [x] P1-3a: 0.25×–2× applies live (slider + chips respond immediately)
- [x] P1-3b: preset chips 0.5× / 0.75× / 1× work
- [x] P1-3c: fine slider works (set to ~1.5× via slider tap)
- [x] P1-3d: speed survives pause/play (stayed at 0.5× through cycle)
- [x] P1-3e: speed carries across track open (second track opened at 0.5×)
- [x] Spike S-3: listen check at 0.5× and 1.5× for pitch preservation / artifacts — **human auditory test required**; write verdict + device used (feeds R8 / F4)
- [x] Mark T06 `done` once S-3 verdict recorded

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
- [x] P1-4: portrait shows documented arrangement; landscape = graceful fallback; playback uninterrupted through rotation
- [x] All Phase 1 stories (P1-1, P1-1a, P1-2, P1-3, P1-5) re-verified as done
- [x] `assembleDebug` + `testDebugUnitTest` + `lintDebug` green
- [x] Full Definition of Done sweep on physical device → **Phase 1 exit** (roadmap §10)

### Session notes

**Done 2026-10-26 — all acceptance boxes checked.**

- **Files:** new `ui/study/StudyScreen.kt` (orientation-aware root: portrait study mode + graceful landscape fallback), `ui/study/PlayerSurface.kt`, `ui/study/PlaceholderSlot.kt`; `ui/shared/Transport.kt` and `ui/shared/SpeedControls.kt` extracted from `player/PlayerScreen.kt` (T05/T06 composables, now shared across orientations); `player/PlayerScreen.kt` reduced to a thin adapter (playback lifecycle, keep-screen-on, pause-on-background, manual nav unchanged). Removed `android:screenOrientation="portrait"` from `AndroidManifest.xml` so rotation drives orientation per product §5.2 / P1-4 (T01's lock was a temporary Phase 1 dev constraint). No new Gradle deps; no Room schema change.
- **Portrait study mode (01-product-overview §5.2):** video pinned top ~40 % (16:9 letterboxed black box), control surface below with real Transport + SpeedControls, then disabled placeholder slots for Loop (A–B) / Tap tempo / Count-in & metronome (Phases 3–5 fill them).
- **Landscape fallback:** full-bleed video with Transport + SpeedControls stacked at the bottom (full HUD deferred to a later phase).
- **P1-4 rotation continuity:** ViewModel-owned ExoPlayer survives recreation; ON_STOP excludes config changes (unchanged from T05). **Verified live on both devices after removing the manifest lock:** emulator API 37 — SurfaceView full-bleed landscape, playback continued at `0:08`/`Pause`; Redmi API 35 (HyperOS) — landscape orientation active, transport (`0:25`/`Play`, ENDED state) + speed controls at bottom.
- **On-device verification:** portrait study layout + autoplay/transport/speed/placeholders confirmed via uiautomator on **emulator-5554 (API 37)** and **Redmi 94d32c7b7d78 (API 35, HyperOS)**; landscape fallback confirmed on both; no `AndroidRuntime`/FATAL for the package on either device.
- **Builds:** `assembleDebug` + `testDebugUnitTest` + `lintDebug` green; lint findings now **7** (was 9): removing the portrait lock dropped the two `LockedOrientationActivity`/`DiscouragedApi` findings; zero new warnings introduced by T07.
- **T06 close-out:** spike S-3 listen check examined by owner and marked done; speed control (0.25×–2×, pitch preserved, survives pause/play, carries across tracks) physically verified on Redmi with headphones in T06.
- **Phase 1 review:** all P1 stories (P1-1, P1-1a, P1-2, P1-3, P1-5) re-verified as done; Definition of Done (§10) sweep complete → **Phase 1 exit**. Leftovers (YouTube, cache, loop, count-in, metronome, boost, landscape focus HUD, MediaSession) noted for Phase 2+ grooming per roadmap §3 ("Explicitly NOT in Phase 1").

