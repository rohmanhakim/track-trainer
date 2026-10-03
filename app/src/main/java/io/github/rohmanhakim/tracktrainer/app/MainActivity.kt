package io.github.rohmanhakim.tracktrainer.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dagger.hilt.android.AndroidEntryPoint
import io.github.rohmanhakim.tracktrainer.library.LibraryScreen
import io.github.rohmanhakim.tracktrainer.ui.theme.TrackTrainerTheme

// Hilt entry point required so LibraryViewModel can be created via the
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
                    LibraryScreen()
                }
            }
        }
    }
}

