package io.github.rohmanhakim.tracktrainer.youtube.spike

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.rohmanhakim.tracktrainer.cache.CacheKeyFactory
import io.github.rohmanhakim.tracktrainer.youtube.Extractor
import io.github.rohmanhakim.tracktrainer.youtube.NewPipeYoutubeExtractor

/**
 * Throwaway T01 spike harness.
 *
 * adb: am start -n io.github.rohmanhakim.tracktrainer/io.github.rohmanhakim.tracktrainer.youtube.spike.SpikeActivity
 *
 * Paste a YouTube watch URL -> Resolve (calls NewPipe Extractor) -> Play (feeds progressive URL into
 * Media3 CacheDataSource -> ExoPlayer). Status shown on screen for the spike session notes.
 */
class SpikeActivity : ComponentActivity() {
    private lateinit var extractor: Extractor
    private lateinit var player: ExoPlayer
    private val _status = mutableStateOf("Paste a YouTube watch URL and tap Resolve.")
    val status: String get() = _status.value
    /** URL to auto-resolve when launched via intent extra ("url"). */
    private var urlToResolve: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extractor = NewPipeYoutubeExtractor(applicationContext)
        player = ExoPlayer.Builder(this).build()
        // Intent-extra driven auto-resolve for the battery:
        //   am start -n .../SpikeActivity -e url "<watch url>"
        val intentUrl = intent.getStringExtra("url")
        if (intentUrl != null && intentUrl.isNotEmpty()) {
            urlToResolve = intentUrl
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpikeUi(extractor = extractor, onStatus = { s -> _status.value = s })
                }
            }
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private fun play(progressiveUrl: String, itag: String) {
        val cache = SimpleCache(
            applicationContext.cacheDir,
            LeastRecentlyUsedCacheEvictor(1024L * 1024L * 512L),
        )
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(progressiveUrl))
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        _status.value = "Playing $itag ... (watch logcat / player view)"
    }

    @Composable
    private fun SpikeUi(extractor: Extractor, onStatus: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf("") }
        var lastUrl by remember { mutableStateOf("") }
        var lastItag by remember { mutableStateOf("") }

        // Auto-resolve when launched via intent extra.
        LaunchedEffect(urlToResolve) {
            val target = urlToResolve
            if (target != null && target.isNotEmpty()) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val start = System.currentTimeMillis()
                        val resolved = extractor.resolve(target)
                        lastUrl = resolved.progressiveUrl
                        lastItag = resolved.itag
                        "RESOLVED in ${System.currentTimeMillis() - start} ms\nTitle: ${resolved.title}\nVideoId: ${resolved.videoId}\n" +
                            "Duration: ${resolved.durationMs} ms\nItag: ${resolved.itag}\nURL: ${resolved.progressiveUrl}"
                    } catch (e: Exception) {
                        lastUrl = ""
                        "RESOLVE FAILED: ${e.message}"
                    }
                }
                onStatus(result)
                Log.d("T01SPIKE", "RESULT: $result")
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text(text = "T01 Spike — NewPipe -> CacheDataSource -> play", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                TextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(text = "YouTube watch URL") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val start = System.currentTimeMillis()
                                        val resolved = extractor.resolve(url)
                                        lastUrl = resolved.progressiveUrl
                                        lastItag = resolved.itag
                                        "RESOLVED in ${System.currentTimeMillis() - start} ms\nTitle: ${resolved.title}\nVideoId: ${resolved.videoId}\n" +
                                            "Duration: ${resolved.durationMs} ms\nItag: ${resolved.itag}\nURL: ${resolved.progressiveUrl}"
                                    } catch (e: Exception) {
                                        lastUrl = ""
                                        "RESOLVE FAILED: ${e.message}"
                                    }
                                }
                                onStatus(result)
                                Log.d("T01SPIKE", "RESULT: $result")
                            }
                        },
                    ) { Text(text = "Resolve") }
                    Button(
                        onClick = {
                            if (lastUrl.isNotEmpty()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { play(lastUrl, lastItag) }
                                }
                            }
                        },
                        enabled = lastUrl.isNotEmpty(),
                    ) { Text(text = "Play") }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "No PlayerView in this throwaway harness — check logcat for ExoPlayer events.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

}
