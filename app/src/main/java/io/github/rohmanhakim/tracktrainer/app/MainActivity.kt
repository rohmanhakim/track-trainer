package io.github.rohmanhakim.tracktrainer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.github.rohmanhakim.tracktrainer.library.LibraryScreen
import io.github.rohmanhakim.tracktrainer.player.PlayerScreen
import io.github.rohmanhakim.tracktrainer.ui.theme.TrackTrainerTheme

// Hilt entry point required so ViewModels can be created via the
// activity's default ViewModel factory (no navigation-compose in Phase 1).
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackTrainerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    // Manual two-destination nav (T05 decision): -1 = library, else the
                    // open track's id. PlayerViewModel is activity-scoped, so PlayerScreen
                    // releases its player on back itself rather than relying on store clearing.
                    var openTrackId by rememberSaveable { mutableLongStateOf(-1L) }
                    if (openTrackId == -1L) {
                        LibraryScreen(onTrackClick = { openTrackId = it })
                    } else {
                        PlayerScreen(trackId = openTrackId, onBack = { openTrackId = -1L })
                    }
                }
            }
        }
    }
}

