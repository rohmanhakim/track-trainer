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
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import io.github.rohmanhakim.tracktrainer.cache.CacheKeyFactory
import io.github.rohmanhakim.tracktrainer.data.local.SourceType
import io.github.rohmanhakim.tracktrainer.data.local.Track
import io.github.rohmanhakim.tracktrainer.data.repo.TrackRepository
import io.github.rohmanhakim.tracktrainer.youtube.Extractor
import io.github.rohmanhakim.tracktrainer.youtube.YtDlpExtractor
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Throwaway T01b spike harness (yt-dlp, the D2 primary extractor).
 *
 * Plays through the PRODUCTION cache wiring (Hilt-provided SimpleCache + CacheDataSource.Factory
 * from [CacheModule]) so the T01 resolve->cache->play path is verified on real code, not a
 * throwaway cache instance.
 *
 * adb: am start -n io.github.rohmanhakim.tracktrainer/io.github.rohmanhakim.tracktrainer.youtube.spike.SpikeActivity
 *   -e url "<watch url>"      # auto-resolve on launch (battery mode)
 *   -e update 1               # refresh bundled yt-dlp binary before resolving
 *
 * Buttons:
 *   Resolve & play (YouTube)  - yt-dlp resolve -> play progressive URL under key yt|videoId|itag
 *   Play direct as-is         - play the pasted stream URL under key direct|sha256(normalized url)
 *   Insert direct track       - store a DIRECT track in Room so the real library/player path
 *                               (PlayerViewModel + offline dot) can be exercised end-to-end
 */
@AndroidEntryPoint
class SpikeActivity : ComponentActivity() {
    @Inject lateinit var cacheDataSourceFactory: CacheDataSource.Factory
    @Inject lateinit var repository: TrackRepository

    private lateinit var extractor: Extractor
    private lateinit var player: ExoPlayer
    private val _status = mutableStateOf("Paste a YouTube watch URL and tap Resolve.")
    val status: String get() = _status.value
    /** URL to auto-resolve when launched via intent extra ("url"). */
    private var urlToResolve: String? = null
    /** Whether to update the bundled yt-dlp binary before resolving ("1" = yes). */
    private var doUpdate: Boolean = false
    /** Last resolved progressive URL / video id / itag, for the play buttons. */
    private var lastUrl = ""
    private var lastVideoId = ""
    private var lastItag = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        doUpdate = intent.getStringExtra("update") == "1"
        extractor = YtDlpExtractor(applicationContext)
        player = ExoPlayer.Builder(this).build()
        // Intent-extra driven auto-resolve for the battery:
        //   am start -n .../SpikeActivity -e url "<watch url>" [-e update 1]
        val intentUrl = intent.getStringExtra("url")
        if (intentUrl != null && intentUrl.isNotEmpty()) {
            urlToResolve = intentUrl
        }
        // R1 mitigation: refresh the bundled yt-dlp binary from yt-dlp upstream before resolving.
        if (doUpdate) {
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
                    SpikeUi(extractor = extractor, onStatus = { s -> _status.value = s })
                }
            }
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    private suspend fun play(mediaUrl: String, cacheKey: String) {
        withContext(Dispatchers.Main) {
            val mediaItem = MediaItem.Builder()
            .setUri(mediaUrl)
            .setCustomCacheKey(cacheKey)
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .createMediaSource(mediaItem)
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
        _status.value = "Playing ... key=$cacheKey"
        Log.d("T01SPIKE", "PLAY key=$cacheKey url=$mediaUrl")
        }
    }

    @Composable
    private fun SpikeUi(extractor: Extractor, onStatus: (String) -> Unit) {
        val scope = rememberCoroutineScope()
        var url by remember { mutableStateOf("") }
        var lastUrl by remember { mutableStateOf("") }
        var lastVideoId by remember { mutableStateOf("") }
        var lastItag by remember { mutableStateOf("") }

        // Auto-resolve when launched via intent extra.
        LaunchedEffect(urlToResolve) spike@ {
            val target = urlToResolve
            if (target != null && target.isNotEmpty()) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val start = System.currentTimeMillis()
                        val resolved = extractor.resolve(target)
                        lastUrl = resolved.progressiveUrl
                        lastVideoId = resolved.videoId
                        lastItag = resolved.itag
                        "RESOLVED in ${System.currentTimeMillis() - start} ms\nTitle: ${resolved.title}\nVideoId: ${resolved.videoId}\n" +
                            "Duration: ${resolved.durationMs} ms\nItag: ${resolved.itag}\nURL: ${resolved.progressiveUrl}"
                    } catch (e: Exception) {
                        lastUrl = ""
                        lastVideoId = ""
                        "RESOLVE FAILED: ${e.message}"
                    }
                }
                onStatus(result)
                Log.d("T01SPIKE", "RESULT: $result")
                // Unattended battery mode.
                if (intent.getStringExtra("play") == "1") {
                    val directTarget = if (lastUrl.isEmpty()) urlToResolve else null
                    launch {
                        if (lastUrl.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                play(lastUrl, CacheKeyFactory.youtubeKey(lastVideoId, lastItag))
                            }
                        } else {
                            directTarget?.let { target ->
                                withContext(Dispatchers.IO) {
                                    play(target, CacheKeyFactory.directKey(target))
                                }
                            }
                        }
                    }
                }
                // Store a DIRECT track in Room so the real library/player path can be exercised.
                if (intent.getStringExtra("insert") == "1") {
                    urlToResolve?.let { target ->
                        launch {
                            val id = withContext(Dispatchers.IO) {
                                repository.add(
                                    Track(
                                        sourceType = SourceType.DIRECT,
                                        sourceRef = target,
                                        title = "Direct track",
                                        durationMs = 0L,
                                    ),
                                )
                            }
                            Log.d("T01SPIKE", "INSERTED direct track id=$id")
                        }
                    }
                }
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
                Text(text = "T01b Spike — yt-dlp -> CacheDataSource -> play", style = MaterialTheme.typography.headlineMedium)
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
                                        lastVideoId = resolved.videoId
                                        lastItag = resolved.itag
                                        "RESOLVED in ${System.currentTimeMillis() - start} ms\nTitle: ${resolved.title}\nVideoId: ${resolved.videoId}\n" +
                                            "Duration: ${resolved.durationMs} ms\nItag: ${resolved.itag}\nURL: ${resolved.progressiveUrl}"
                                    } catch (e: Exception) {
                                        lastUrl = ""
                                        lastVideoId = ""
                                        "RESOLVE FAILED: ${e.message}"
                                    }
                                }
                                onStatus(result)
                                Log.d("T01SPIKE", "RESULT: $result")
                            }
                        },
                    ) { Text(text = "Resolve & play (YouTube)") }
                    Button(
                        onClick = {
                            if (lastUrl.isNotEmpty()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        play(
                                            lastUrl,
                                            CacheKeyFactory.youtubeKey(lastVideoId, lastItag),
                                        )
                                    }
                                }
                            }
                        },
                        enabled = lastUrl.isNotEmpty(),
                    ) { Text(text = "Play resolved") }
                    Button(
                        onClick = {
                            if (url.isNotEmpty()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        play(url, CacheKeyFactory.directKey(url))
                                    }
                                }
                            }
                        },
                        enabled = url.isNotEmpty(),
                    ) { Text(text = "Play direct as-is") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (url.isNotEmpty()) {
                                scope.launch {
                                    val id = withContext(Dispatchers.IO) {
                                        repository.add(
                                            Track(
                                                sourceType = SourceType.DIRECT,
                                                sourceRef = url,
                                                title = "Direct track",
                                                durationMs = 0L,
                                            ),
                                        )
                                    }
                                    onStatus("Inserted direct track id=$id — open it from the library")
                                }
                            }
                        },
                        enabled = url.isNotEmpty(),
                    ) { Text(text = "Insert direct track into library") }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = status, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = "No PlayerView in this throwaway harness — check logcat for ExoPlayer events.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

}
