package io.github.rohmanhakim.tracktrainer.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

// Top-level delegate = one DataStore instance per process (DataStore forbids
// multiple instances for the same file).
private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

/**
 * App-wide preferences — deliberately tiny for now. First real use of the DataStore
 * dependency that landed declared-but-unused in T02: the one-time cloud-source
 * hint flag (01-product-overview §5.1). More settings arrive with later phases.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Has the owner already seen the "file isn't stored on this device" hint? */
    val cloudHintShown: Flow<Boolean> = context.appDataStore.data
        // Corrupt prefs file reads as "not shown" — never crash over a hint flag.
        // Only IOException is swallowed; anything else must surface (structure
        // borrowed from the DataStore corruption-recovery pattern).
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it[CLOUD_HINT_SHOWN] ?: false }

    suspend fun markCloudHintShown() {
        context.appDataStore.edit { it[CLOUD_HINT_SHOWN] = true }
    }

    private companion object {
        val CLOUD_HINT_SHOWN = booleanPreferencesKey("cloud_hint_shown")
    }
}