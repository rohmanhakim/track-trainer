package io.github.rohmanhakim.tracktrainer.app

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import io.github.rohmanhakim.tracktrainer.data.local.TrackDao
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class TrackTrainerApplication : Application() {

    @Inject lateinit var trackDao: TrackDao

    override fun onCreate() {
        super.onCreate()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            Log.d(TAG, "Track DB ready, tracks=${trackDao.count()}")
        }
    }

    companion object {
        private const val TAG = "TrackTrainer"
    }
}