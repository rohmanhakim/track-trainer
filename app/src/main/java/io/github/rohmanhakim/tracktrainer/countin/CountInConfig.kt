package io.github.rohmanhakim.tracktrainer.countin

import io.github.rohmanhakim.tracktrainer.data.local.ClickSound

/**
 * Live count-in settings the controller reads at each trigger (phase-4 grooming
 * decision 5). T02 backs this with [HardcodedCountInConfig]; T04 swaps the source
 * to the track's `Preset` (Room migration 5→6 adds `countInEnabled` +
 * `countInSubdivision`) without touching the controller.
 */
interface CountInConfig {
    /** Master switch — when false the controller never fires (count-in off). */
    val enabled: Boolean
    /** Count-in bars (4/4; 1–4). Greater than 0 selects bars mode. */
    val countInBars: Int
    /** Clicks mode length (1–8), used when [countInBars] is 0. */
    val countInClicks: Int
    /** Audible half-beat off-beats between main ticks (no numerals; decision 2). */
    val subdivision: Boolean
    /** Beats per minute; 0 = unset → [io.github.rohmanhakim.tracktrainer.metronome.ClickSchedule.DEFAULT_BPM] (120). */
    val bpm: Float
    val clickSound: ClickSound
    /** 0f..1f. */
    val clickVolume: Float
}

/**
 * T02 stand-in: the phase-4 defaults, hardcoded until T04 persists them.
 * Constructor parameters stay overridable so tests (and T04) can build variants.
 */
class HardcodedCountInConfig(
    override val enabled: Boolean = true,
    override val countInBars: Int = 0,
    override val countInClicks: Int = 4,
    override val subdivision: Boolean = false,
    override val bpm: Float = 0f,
    override val clickSound: ClickSound = ClickSound.CLICK,
    override val clickVolume: Float = 1f,
) : CountInConfig
