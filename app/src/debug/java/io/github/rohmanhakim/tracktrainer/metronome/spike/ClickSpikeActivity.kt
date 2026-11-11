package io.github.rohmanhakim.tracktrainer.metronome.spike

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import io.github.rohmanhakim.tracktrainer.data.local.ClickSound
import io.github.rohmanhakim.tracktrainer.metronome.BurstMeasurement
import io.github.rohmanhakim.tracktrainer.metronome.ClickSchedule
import io.github.rohmanhakim.tracktrainer.metronome.ClickTrack
import io.github.rohmanhakim.tracktrainer.metronome.OutputLatency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debug-only T01 smoke harness for the click engine (phase-4 T01).
 *
 * adb (fully automatable):
 *   am start -n io.github.rohmanhakim.tracktrainer/io.github.rohmanhakim.tracktrainer.metronome.spike.ClickSpikeActivity
 *     -e run 1        # one burst
 *     -e soak 1       # 60 s of back-to-back bursts (the ≥60 s acceptance)
 *     -e probe 1      # 3 latency probes (the stability acceptance)
 *     -e bpm 180 -e ticks 4 -e speed 1.5 -e sound BEEP -e subdivision 1
 *
 * Greppable log tags: ClickTrack (per-tick + summary lines), ClickSpike (soak/probe),
 * OutputLatency (probe values).
 */
class ClickSpikeActivity : ComponentActivity() {

    private val bpm = mutableStateOf("120")
    private val ticks = mutableStateOf("4")
    private val subdivision = mutableStateOf(false)
    private val speed = mutableStateOf(1f)
    private val sound = mutableStateOf(ClickSound.CLICK)
    private val volume = mutableStateOf(0.7f)
    private val status = mutableStateOf("Ready — or launch with -e run/soak/probe")
    private val running = mutableStateOf(false)
    private val clickTrack = ClickTrack(logTag = "ClickTrack")
    private val micRate = 48_000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyIntentExtras()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        Text("T01 click engine spike", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextField(
                                value = bpm.value,
                                onValueChange = { bpm.value = it },
                                label = { Text("BPM") },
                                modifier = Modifier.width(100.dp),
                                singleLine = true,
                            )
                            Spacer(Modifier.padding(8.dp))
                            TextField(
                                value = ticks.value,
                                onValueChange = { ticks.value = it },
                                label = { Text("Ticks") },
                                modifier = Modifier.width(100.dp),
                                singleLine = true,
                            )
                            Spacer(Modifier.padding(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = subdivision.value, onCheckedChange = { subdivision.value = it })
                                Text("subdiv")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row {
                            listOf(0.5f, 1f, 1.5f, 2f).forEach { s ->
                                Button(
                                    onClick = { speed.value = s },
                                    modifier = Modifier.padding(end = 4.dp),
                                ) { Text("${s}x") }
                            }
                        }
                        Row {
                            ClickSound.entries.forEach { s ->
                                Button(
                                    onClick = { sound.value = s },
                                    modifier = Modifier.padding(end = 4.dp),
                                ) { Text(s.name) }
                            }
                        }
                        Text("Volume: ${"%.2f".format(volume.value)}")
                        Slider(
                            value = volume.value,
                            onValueChange = { volume.value = it },
                            valueRange = 0f..1f,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = { runBurst() }, enabled = !running.value) { Text("Run burst") }
                            Spacer(Modifier.padding(4.dp))
                            Button(onClick = { runSoak() }, enabled = !running.value) { Text("Soak 60s") }
                            Spacer(Modifier.padding(4.dp))
                            Button(onClick = { runProbe() }, enabled = !running.value) { Text("Probe x3") }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(status.value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        autoRunFromIntent()
    }

    private fun applyIntentExtras() {
        intent.getStringExtra("bpm")?.let { bpm.value = it }
        intent.getStringExtra("ticks")?.let { ticks.value = it }
        intent.getStringExtra("speed")?.toFloatOrNull()?.let { speed.value = it }
        intent.getStringExtra("sound")?.let { raw ->
            ClickSound.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?.let { sound.value = it }
        }
        intent.getStringExtra("subdivision")?.let { subdivision.value = it == "1" || it == "true" }
    }

    private fun autoRunFromIntent() {
        when {
            intent.hasExtra("probe") -> runProbe()
            intent.hasExtra("soak") -> runSoak()
            intent.hasExtra("run") -> runBurst()
        }
    }

    private fun currentTicks(): List<ClickSchedule.Tick> {
        val n = ticks.value.toIntOrNull()?.coerceIn(1, 16) ?: 4
        val period = ClickSchedule.periodMs(bpm.value.toFloatOrNull() ?: 120f)
        return ClickSchedule.countTicks(n, period, subdivision.value)
    }

    private suspend fun playOneBurst(): BurstMeasurement {
        val anchor = SystemClock.elapsedRealtimeNanos() + 500_000_000L
        return clickTrack.playBurst(
            ticks = currentTicks(),
            anchorWallNanos = anchor,
            anchorMediaMs = 0L,
            playbackSpeed = speed.value,
            sound = sound.value,
            volume = volume.value,
        )
    }

    private fun runBurst() {
        if (running.value) return
        running.value = true
        lifecycleScope.launch {
            try {
                val mic = MicRecorder()
                val micOn = mic.start(this)
                val recStart = SystemClock.elapsedRealtimeNanos()
                val m = playOneBurst()
                // The playback head leads audible time (pipeline fill), so the
                // burst returns before the last click is heard — keep rolling
                // to capture the tail.
                delay(600)
                val acoustic = acousticOf(if (micOn) mic.stop() else null, m, recStart)
                Log.i("ClickSpike", "acoustic: $acoustic")
                status.value = summaryLine(m) + acousticLine(acoustic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ClickSpike", "burst failed", e)
                status.value = "FAILED: ${e.message}"
            } finally {
                running.value = false
            }
        }
    }

    private fun acousticOf(
        pcm: ShortArray?,
        m: BurstMeasurement,
        recStartNanos: Long,
    ): LoopbackAnalysis.BurstAcoustics? {
        if (pcm == null) return null
        val onsetsMs = LoopbackAnalysis.detectOnsets(pcm, micRate).map { it * 1000L / micRate }
        val scheduledMs = m.ticks.map { (it.scheduledNanos - recStartNanos) / 1_000_000L }
        val playAtMs = (m.ticks.first().writtenNanos - recStartNanos) / 1_000_000L
        Log.i(
            "ClickSpike",
            "acoustic raw: onsets=$onsetsMs scheduled=$scheduledMs playAt=$playAtMs " +
                "pcmMs=${pcm.size * 1000L / micRate} stats=${pcmStats(pcm, playAtMs.toInt())}",
        )
        return LoopbackAnalysis.analyzeBurst(onsetsMs, scheduledMs, playAtMs)
    }

    /** max/RMS before and during playback — tells a noise floor from real clicks. */
    private fun pcmStats(pcm: ShortArray, playAtMs: Int): String {
        var max = 0
        var preSq = 0L
        var postSq = 0L
        var preN = 0
        var postN = 0
        for (i in pcm.indices) {
            val a = kotlin.math.abs(pcm[i].toInt())
            if (a > max) max = a
            if (i < playAtMs * micRate / 1000) {
                preSq += a.toLong() * a
                preN++
            } else {
                postSq += a.toLong() * a
                postN++
            }
        }
        val preRms = if (preN > 0) kotlin.math.sqrt(preSq.toDouble() / preN).toInt() else 0
        val postRms = if (postN > 0) kotlin.math.sqrt(postSq.toDouble() / postN).toInt() else 0
        return "max=$max preRms=$preRms postRms=$postRms"
    }

    private fun acousticLine(a: LoopbackAnalysis.BurstAcoustics?): String {
        if (a == null) return " | acoustic: n/a"
        val mark = if (a.withinBudget) "OK" else "OVER"
        val chain = a.firstChainMs?.let { "${"%.1f".format(it)}ms" } ?: "?"
        return " | acoustic ${a.matchedOnsets}/${a.expectedTicks} $mark " +
            "max=${"%.1f".format(a.spacingErrorMaxMs)}ms chain0=$chain"
    }

    private fun runSoak() {
        if (running.value) return
        running.value = true
        lifecycleScope.launch {
            try {
                val mic = MicRecorder()
                val micOn = mic.start(this)
                val recStart = SystemClock.elapsedRealtimeNanos()
                var bursts = 0
                var worst = 0f
                val perBurst = mutableListOf<BurstMeasurement>()
                while (SystemClock.elapsedRealtimeNanos() - recStart < 60_000_000_000L) {
                    val m = playOneBurst()
                    perBurst += m
                    bursts++
                    worst = maxOf(worst, m.maxAbsSpacingErrorMs)
                    status.value = "soak: $bursts bursts, head worst ${"%.1f".format(worst)}ms"
                    delay(150)
                }
                // ditto: the last burst's final click is still in the pipeline
                delay(1_500)
                val pcm = if (micOn) mic.stop() else null
                var acousticWorst = 0f
                var allMatched = pcm != null
                var lines = 0
                if (pcm != null) {
                    val onsetsMs = LoopbackAnalysis.detectOnsets(pcm, micRate)
                        .map { it * 1000L / micRate }
                    for (m in perBurst) {
                        val scheduledMs = m.ticks.map { (it.scheduledNanos - recStart) / 1_000_000L }
                        val playAtMs = (m.ticks.first().writtenNanos - recStart) / 1_000_000L
                        val a = LoopbackAnalysis.analyzeBurst(onsetsMs, scheduledMs, playAtMs)
                        if (!a.withinBudget) allMatched = false
                        acousticWorst = maxOf(acousticWorst, a.spacingErrorMaxMs)
                        if (lines < 3) {
                            Log.i("ClickSpike", "soak burst ${lines + 1}: $a")
                            lines++
                        }
                    }
                }
                Log.i(
                    "ClickSpike",
                    "soak done: $bursts bursts over 60s; head worst=${"%.1f".format(worst)}ms; " +
                        "ACOUSTIC worst=${"%.1f".format(acousticWorst)}ms allMatched=$allMatched",
                )
                status.value = "Soak $bursts bursts | acoustic worst ${"%.1f".format(acousticWorst)}ms " +
                    if (allMatched) "OK" else "OVER/mismatch"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ClickSpike", "soak failed", e)
                status.value = "FAILED: ${e.message}"
            } finally {
                running.value = false
            }
        }
    }

    private fun runProbe() {
        if (running.value) return
        running.value = true
        lifecycleScope.launch {
            try {
                val values = mutableListOf<Long>()
                repeat(3) { i ->
                    val ns = OutputLatency.measure(this@ClickSpikeActivity)
                    values += ns
                    Log.i("ClickSpike", "probe run ${i + 1}: ${"%.1f".format(ns / 1e6)}ms")
                    status.value = "probe ${i + 1}/3: ${"%.1f".format(ns / 1e6)}ms"
                    delay(500)
                }
                val text = values.joinToString(" / ") { "${"%.1f".format(it / 1e6)}ms" }
                Log.i("ClickSpike", "probe stability: $text")
                status.value = "Probes: $text"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ClickSpike", "probe failed", e)
                status.value = "FAILED: ${e.message}"
            } finally {
                running.value = false
            }
        }
    }

    private fun summaryLine(m: BurstMeasurement): String =
        "n=${m.ticks.size} speed=${speed.value}: spacing max=${"%.1f".format(m.maxAbsSpacingErrorMs)}ms " +
            "mean=${"%.1f".format(m.meanAbsSpacingErrorMs)}ms; start=${"%.1f".format(m.meanWriteLatenessMs)}ms; " +
            "chain=${"%.1f".format(m.meanPresentDelayMs)}ms"
}
