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
import androidx.lifecycle.lifecycleScope
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
import io.github.rohmanhakim.tracktrainer.youtube.YtDlpExtractor
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Throwaway T01/T01b spike harness.
 *
 * adb: am start -n io.github.rohmanhakim.tracktrainer/io.github.rohmanhakim.tracktrainer.youtube.spike.SpikeActivity
 *   -e url "<watch url>"      # auto-resolve on launch (battery mode)
 *   -e impl "ytdlp"           # pick yt-dlp extractor instead of NewPipe (default: newpipe)
 *
 * Paste a YouTube watch URL -> Resolve (calls Extractor impl) -> Play (feeds progressive URL into
 * Media3 CacheDataSource -> ExoPlayer). Status shown on screen for the spike session notes.
 */
class SpikeActivity : ComponentActivity() {
    private lateinit var extractor: Extractor
    private lateinit var player: ExoPlayer
    private val _status = mutableStateOf("Paste a YouTube watch URL and tap Resolve.")
    val status: String get() = _status.value
    /** URL to auto-resolve when launched via intent extra ("url"). */
    private var urlToResolve: String? = null
    /** Which Extractor impl to use ("ytdlp" or anything else -> NewPipe). */
    private var implName: String = "newpipe"
    /** Whether to update the bundled yt-dlp binary before resolving ("1" = yes). */
    private var doUpdate: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intentImpl = intent.getStringExtra("impl")
        implName = if (intentImpl != null && intentImpl.equals("ytdlp", ignoreCase = true)) "ytdlp" else "newpipe"
        doUpdate = intent.getStringExtra("update") == "1"
        extractor = when (implName) {
            "ytdlp" -> YtDlpExtractor(applicationContext)
            else -> NewPipeYoutubeExtractor(applicationContext)
        }
        player = ExoPlayer.Builder(this).build()
        // Intent-extra driven auto-resolve for the battery:
        //   am start -n .../SpikeActivity -e url "<watch url>" [-e impl ytdlp] [-e update 1]
        val intentUrl = intent.getStringExtra("url")
        if (intentUrl != null && intentUrl.isNotEmpty()) {
            urlToResolve = intentUrl
        }
        // R1 mitigation: refresh the bundled yt-dlp binary from yt-dlp upstream before resolving.
        if (doUpdate && implName == "ytdlp") {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    try {
                        YoutubeDL.getInstance().init(this@SpikeActivity)
                        val status = YoutubeDL.getInstance().updateYoutubeDL(this@SpikeActivity, YoutubeDL.UpdateChannel.STABLE)
                        _status.value = "yt-dlp update: $status (resolving next launch)"
                        Log.d("T01SPIKE", "yt-dlp update: $status")
                    } catch (e: Exception) {
                        _status.value = "yt-dlp update failed: ${e.message}"
                        Log.e("T01SPIKE", "yt-dlp update failed", e)
                    }
                }
            }
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SpikeUi(extractor = extractor, implName = implName, onStatus = { s -> _status.value = s })
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
    private fun SpikeUi(extractor: Extractor, implName: String, onStatus: (String) -> Unit) {
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
                        "[$implName] RESOLVED in ${System.currentTimeMillis() - start} ms\nTitle: ${resolved.title}\nVideoId: ${resolved.videoId}\n" +
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
                Text(text = "T01 Spike — $implName -> CacheDataSource -> play", style = MaterialTheme.typography.headlineMedium)
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
