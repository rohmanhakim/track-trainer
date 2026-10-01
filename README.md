# track-trainer

A personal-use Android app for practicing guitar with video tracks: variable-speed playback, A-B looping, count-in, tap-tempo metronome, and transparent offline caching of YouTube videos.

**Status:** Docs complete; Phase 1 scaffold generated (empty Android Studio template — first task is converting it to the Compose stack, see roadmap Phase 1 S1).

## Documentation

| Doc | Purpose |
|---|---|
| [AGENTS.md](AGENTS.md) | Instructions for AI coding agents (status, rules, skills) |
| [docs/01-product-overview.md](docs/01-product-overview.md) | Vision, users, feature inventory, UX layouts, non-goals |
| [docs/02-architecture.md](docs/02-architecture.md) | Tech stack, module layout, audio/video pipelines, data model, key decisions |
| [docs/03-roadmap.md](docs/03-roadmap.md) | Agile phase plan: Phase 1 = minimal deliverable, stories + acceptance criteria per phase |
| [docs/04-risks.md](docs/04-risks.md) | Risk register with mitigations |

## Constraints

- Fully personal use (single device / sideload APK). No Play Store, accounts, analytics, or cloud.
- Distribution and ToS implications of YouTube caching are accepted by the owner for private use.
