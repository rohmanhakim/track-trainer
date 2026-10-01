# 01 — Product Overview

## 1. Vision

A **practice companion for guitarists** built around video "play-along" lessons (videos that display tablature on screen). The app is fundamentally a video player supercharged with the tools a musician needs while practicing: slow-down without pitch change, tight A-B loops, a click that is locked to the track, and guaranteed offline playback in low-coverage areas.

> One-sentence goal: *Pause on any frame of any lesson video, loop any passage at any speed, with a metronome that never drifts — even with no network.*

## 2. Target user

- **Primary (only) user:** the project owner — an Android-using guitarist who practices with play-along/tab videos.
- Context: phone or tablet, often with high-impedance headphones, sometimes in areas with poor network coverage. One hand is usually busy holding a guitar.

## 3. Core use cases (primary user stories)

1. As a guitarist, I **paste a YouTube link** so the video is available offline later without thinking about "downloads".
2. As a guitarist, I **open a local video file** so I can practice my own recordings.
3. As a guitarist, I **change playback speed (0.25×–2×) without changing pitch** so I can learn fast passages at tempo.
4. As a guitarist, I **set A-B loop points** so a difficult passage repeats until I nail it.
5. As a guitarist, I **get a count-in (3…2…1 animation + clicks) before the loop** so I can start playing exactly with the track on each repetition.
6. As a guitarist, I **tap the beat of the song** so the app derives the BPM/phase and gives me a metronome locked to the track.
7. As a guitarist, I **run a metronome alongside the track** so my timing stays honest during play-alongs.
8. As a guitarist with high-Z headphones, I **boost volume beyond the device maximum** so quiet sources reach a usable level.
9. As a guitarist in a low-coverage area, I **play a previously-opened video with no network** seamlessly.

## 4. Feature inventory

### 4.1 V1 — in scope (see roadmap for phasing)

| # | Feature | Notes |
|---|---|---|
| F1 | Local video/audio reference & playback | Picked via SAF and referenced in place — **never copied** (owner decision: user owns file placement) |
| F2 | YouTube link → playback with transparent offline cache | No "download" UI; subtle offline indicator only. Same pipeline accepts a **direct media URL** (see F16) |
| F3 | Playback speed 0.25×–2×, pitch preserved | `PlaybackParameters` |
| F4 | Independent pitch/transpose ±12 semitones | Optional, after speed is proven |
| F5 | A-B loop with seam fade | Multiple named loop points, repeat counts |
| F6 | Count-in: animated 3…2…1 overlay + clicks, before 00:00 or before A | Player frozen on frame; default = fixed 4 clicks; optional later: follow tapped-tempo mode (both modes kept) |
| F7 | Tap-to-beat → BPM + phase grid | Outlier rejection, half/double-time detection, ±ms nudge |
| F8 | Metronome mixed with track (sample-accurate) | Custom `AudioProcessor` in `DefaultAudioSink` chain |
| F9 | Volume boost (0–+18 dB) with soft limiter | Per-output-device profile (headphones vs speaker) |
| F10 | Frame stepping (±1 frame) | Technique study |
| F11 | Per-track presets | speed, A/B, BPM, count-in, boost — saved in Room |
| F12 | Adaptive portrait/landscape layouts | Portrait first; landscape focus mode deferred (see §5) |
| F13 | Background playback: MediaSession, notification, keep-screen-on | |
| F14 | Picture-in-Picture | |
| F15 | Cache management (usage, clear, pin/"keep offline") | Size cap setting only if needed later — none initially |
| F16 | **Add-track 3-source chooser**: local video / YouTube link / direct media URL | See §5.1; direct URL = escape hatch when both extractors fail (fallback ladder, 02-architecture §3.8) |

### 4.2 V2 — icebox

- Self-recording along the track (`AudioPlaybackCapture`, Android 10+)
- Karaoke / center-channel cancellation (needs audio extraction at cache time)
- Waveform seek bar + tap-grid visualization
- Beat-snapped loop points (snap A/B to detected beat)
- Visual beat flash during playback (free once count-in clock exists — may land in V1 if trivial)
- Practice statistics / streaks, export-import presets, audio-only screen-off mode, YouTube playlists, in-app search

### 4.3 Non-goals

- No Play Store distribution, no accounts, no cloud sync, no analytics.
- No in-app YouTube search (paste-a-link only).
- No streaming-only mode — caching for offline is a core goal, not optional.
- No WebView/embedded-player fallback when extraction fails — it would bypass the audio pipeline (cache, metronome mix, volume boost) that the whole app is built around (decision D9).
- No copy of local video files into app storage — the app references files in place; keeping them available is the user's responsibility (decision D11).
- No support for 1080p+ adaptive (separate A/V streams) in V1 — 720p progressive is the quality ceiling by design.
- No tablet-specific design beyond fluid layouts.

## 5. UX layouts

### 5.1 Add-track chooser (F16)

Library screen → **[ + Add track ]** → three explicit options (no auto-detect — decision D10):

| Option | Input | What happens |
|---|---|---|
| 📁 **Local video** | SAF picker (`video/*`) | URI taken with `takePersistableUriPermission`, stored as reference — **instant, no copying** |
| ▶ **YouTube link** | paste watch URL | Extractor resolves → standard pipeline (streams + caches) |
| 🔗 **Direct media URL** | paste stream URL (e.g. desktop `yt-dlp -g`) | Same standard pipeline, URL used as-is; expires ~6 h → track prompts re-paste when needed |

- Paste fields are tolerance-checked: a YouTube URL pasted into Direct (or vice versa) shows a friendly hint, not an error.
- If the picked local file lives in a cloud provider (Drive etc.): one-time hint *"This file isn't stored on this device — offline playback won't work."* Not blocking.

### 5.2 Layouts

Layout switches by orientation via the same composables (state shared, arrangement differs). **Sequencing (owner decision): portrait study mode is the priority and covers Phase 1; landscape focus mode is deferred to a later phase (graceful portrait-derived fallback until then).**

#### Portrait — "study mode"
```
┌───────────────────────┐
│                       │
│      VIDEO (~40%)     │   ← frozen frame during count-in,
│                       │      count-in overlay on top
├───────────────────────┤
│  [◀◀] [▶] [▶▶]  speed │
│  A ●───────● B   LOOP │
│                       │
│   ┌────────────────┐  │
│   │   TAP TEMPO    │  │   ← thumb-sized, primary action
│   └────────────────┘  │
│  count-in | metronome │
│  presets chips        │
└───────────────────────┘
```
- Video pinned top ~40 %, control surface below.
- TAP TEMPO pad is the hero control: big, unmissable, reachable with a picking-hand thumb.

#### Landscape — "focus session"
```
┌────────────────────────────────────┐
│                                    │
│         VIDEO full-bleed           │
│                          [tap] ⏸ ⏯ │  ← minimal auto-hiding HUD overlay
│                                    │     (transport, speed, loop status)
└────────────────────────────────────┘
```
- Video full-bleed; HUD auto-hides during playback.
- Tap-tempo collapses to a small corner button (ergonomically secondary; if it gets constant use in landscape, portrait is the intended default).

#### Count-in overlay (both orientations)
- Large centered numeral over dimmed frozen frame, punch-in animation (scale 0.8→1.0 + fade) per beat.
- Beat-progress ring / flashing dot for multi-bar counts.
- Visual schedule shares the click's absolute time base (minus output latency) so number flips coincide with the heard click.

## 6. Success criteria

- Phase releases are installable APKs that do one thing well (see roadmap).
- Loop seam is not audible; metronome drift over a 5-minute loop is imperceptible.
- A previously-opened YouTube video plays fully offline.
- Count-in lands within one beat of track start on every loop iteration.

