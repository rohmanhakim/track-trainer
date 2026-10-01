# 04 — Risk Register

Owner reviews at each phase grooming. Probability/impact: L / M / H.

| ID | Risk | P | I | Mitigation | Trigger / fallback |
|---|---|---|---|---|---|
| R1 | **YouTube breaks extraction** (player JS changes, new protections like PO-Token) | H | M | **Fallback ladder** (02-architecture §3.8): cache hits need no extraction → yt-dlp fallback (updates within hours) → direct-URL paste → local file. Extraction isolated behind `youtube/Extractor` interface; spike in Phase 2 | NewPipe fails on ≥2 test videos → activate yt-dlp spike (G1); both fail → ladder rungs 4–5 |
| R2 | **Audio latency / sync quality worse than expected** — count-in start alignment off; click audible glitch when processor chain active | M | H | Measure output latency with `AudioTrack.getTimestamp`; ±10 ms user nudge; Phase 5 spike before committing; keep hybrid (one-shot) count-in separate from continuous metronome so one can fail without the other | G2 fails → keep parallel-AudioTrack metronome with periodic re-anchoring (accept ±50 ms) |
| R3 | **Metronome drift over long loops** | L | H | Sample-exact in-chain mixing (position → sample index on player's own stream); verify with 5-min loop recording test (P5-3) | If chain approach slips → re-anchored parallel AudioTrack |
| R4 | **Volume boost clips/distorts** on loud sources | M | M | Soft limiter mandatory; conservative defaults (+6 dB); UI warning at high settings; honest scope — boost targets quiet sources | Accept as limitation; tune limiter |
| R5 | **Loop seam pop / inaccurate loop point** | M | M | ~5 ms fade at seam; 30–50 ms polling; `CLOSEST_SYNC` seek; measure deviation in P3-2 | If audible → increase fade, tighten polling to ~15 ms |
| R6 | **Cache misses after URL expiry** (wrong cache key) | M | H | Key = `videoId + itag` (never URL); explicit P2-4 test replaying after >6 h | Unit-test key factory; re-extract on miss |
| R7 | **Storage bloat** from cached video | M | M | No cap initially (owner decision); LRU still required for implementation correctness; pin exemption; add cache-size setting when usage shows a need (Phase 7+) | Owner adds a cap retroactively |
| R8 | **Pitch artifacts at extreme speeds** (<0.5×) | M | L | Verify on device in Phase 1 (P1-3); Sonic-based processing generally OK | Acceptable degradation at extremes |
| R9 | **Scope creep** (V2 ideas pulled into V1) | M | M | V2 icebox in 01-product-overview; scope rule (roadmap §11); phases never grow mid-phase | Push back at grooming |
| R10 | **Device/API fragmentation** (OEM audio pipelines bypass processor chain, offload mode) | M | H | minSdk 26; test on owner's actual device from Phase 1; disable audio offload/exclusive mode paths if detected | Log processor-chain bypass; fallback path documented in R2 |
| R11 | **Extractor legal/ToS exposure** | L | L | Personal use only, no redistribution, sideload only (D1) | Accepted by owner |
| R12 | **Abandoned-mid-project** (motivation) | M | M | Phase 1 is deliberately minimal — a useful app exists after ~2 weeks; every phase ships standalone value | Re-groom priorities; Phases 6↔7 swappable |
| R13 | **Local reference broken** (file moved/deleted/renamed, cloud placeholder offline, persisted-grant cap ~128 for huge libraries) | M | L | Reference-in-place + persistable grant; health check on open → *"file missing — re-link"*; cloud-source one-time warning. **Accepted as user responsibility (D11)** — no copying by design | Re-pick the file (re-link action); owner keeps files organized |
| R14 | **Frame-step duration unknown** for some containers | L | L | Fallback ~33 ms (30 fps) | Good enough for technique study |
| R15 | **Direct media URL expires** before the track is (re)watched — cache empty | M | M | `needsRefresh` flag on Track; replay from cache unaffected (normalized-hash key); on cache miss → explicit *"URL expired — paste a new one"* prompt (P2-8) | Re-paste fresh URL from desktop `yt-dlp -g` |

## Spike schedule summary

| Spike | Phase | Question it answers | Decision gate |
|---|---|---|---|
| S-1 | 2 (start) | Can NewPipe reliably resolve playable progressive URLs? | G1 → D2 |
| S-2 | 5 (start) | Can a custom `AudioProcessorChain` mix clicks sample-exactly with no audible glitches? | G2 → count-in rework need |
| S-3 (inline) | 1 | Does speed change preserve pitch acceptably on device? | Feeds P1-3; informs F4 |

## Open questions

~~1. Confirm `minSdk` against owner's device (D8).~~ → **Resolved: `minSdk 26`.**
~~2. Default cache max bytes.~~ → **Resolved: no default sized now — start with no meaningful cap; add a cache-size setting when real usage shows a need (Phase 7 or later).**
~~3. Portrait/landscape for Phase 1 exit.~~ → **Resolved: Phase 1 is portrait-only; landscape focus layout deferred (groom into a later phase).**
~~4. Count-in default (fixed clicks vs. BPM bars).~~ → **Resolved: default = fixed 4 clicks. A BPM-following mode ("follow my tapped tempo") is a later addition — owner wants both, fixed is often enough.**
