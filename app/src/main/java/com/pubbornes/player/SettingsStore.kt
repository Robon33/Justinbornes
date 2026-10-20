package com.pubbornes.player

import android.content.Context
import android.graphics.Color
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Mode de mise à l'échelle des médias. */
enum class ScaleMode { FILL, FIT }

/** Réglages de la borne (modifiables uniquement depuis le menu admin, derrière le PIN). */
data class Settings(
    val driveFolderId: String = BuildConfig.DEFAULT_DRIVE_FOLDER_ID,
    val apiKey: String = BuildConfig.DEFAULT_API_KEY,
    val pin: String = "1234",
    val imageDurationSec: Int = 8,
    val soundEnabled: Boolean = false,
    val scaleMode: ScaleMode = ScaleMode.FILL,
    val backgroundColor: String = "#000000",
    val posPackage: String = BuildConfig.DEFAULT_POS_PACKAGE,
) {
    /** Couleur de fond, noir si la valeur saisie est invalide. */
    val backgroundColorInt: Int
        get() = parseColorOrNull(backgroundColor) ?: Color.BLACK

    val isDriveConfigured: Boolean
        get() = driveFolderId.isNotBlank() && apiKey.isNotBlank()

    companion object {
        fun parseColorOrNull(value: String): Int? =
            runCatching { Color.parseColor(value.trim()) }.getOrNull()
    }
}

/** État de la dernière synchronisation, affiché dans le menu admin. */
data class SyncStatus(
    val lastSuccessMillis: Long = 0L,
    val lastError: String = "",
    val lastErrorMillis: Long = 0L,
)

private val Context.dataStore by preferencesDataStore(name = "pub_bornes")

/** Stockage local (DataStore) des réglages et de l'état de synchro. */
class SettingsStore(private val context: Context) {

    private object Keys {
        val FOLDER_ID = stringPreferencesKey("drive_folder_id")
        val API_KEY = stringPreferencesKey("api_key")
        val PIN = stringPreferencesKey("pin")
        val IMAGE_DURATION = intPreferencesKey("image_duration_sec")
        val SOUND = booleanPreferencesKey("sound_enabled")
        val SCALE_MODE = stringPreferencesKey("scale_mode")
        val BG_COLOR = stringPreferencesKey("background_color")
        val POS_PACKAGE = stringPreferencesKey("pos_package")

        val LAST_SUCCESS = longPreferencesKey("sync_last_success")
        val LAST_ERROR = stringPreferencesKey("sync_last_error")
        val LAST_ERROR_TIME = longPreferencesKey("sync_last_error_time")
    }

    /** Lecture tolérante : en cas de fichier corrompu on repart des valeurs par défaut. */
    private val prefs: Flow<Preferences> = context.dataStore.data.catch { emit(emptyPreferences()) }

    val settings: Flow<Settings> = prefs.map { p ->
        val d = Settings()
        Settings(
            driveFolderId = p[Keys.FOLDER_ID] ?: d.driveFolderId,
            apiKey = p[Keys.API_KEY] ?: d.apiKey,
            pin = p[Keys.PIN] ?: d.pin,
            imageDurationSec = (p[Keys.IMAGE_DURATION] ?: d.imageDurationSec).coerceIn(1, 3600),
            soundEnabled = p[Keys.SOUND] ?: d.soundEnabled,
            scaleMode = runCatching { ScaleMode.valueOf(p[Keys.SCALE_MODE] ?: "") }.getOrDefault(d.scaleMode),
            backgroundColor = p[Keys.BG_COLOR] ?: d.backgroundColor,
            posPackage = p[Keys.POS_PACKAGE] ?: d.posPackage,
        )
    }.distinctUntilChanged()

    val syncStatus: Flow<SyncStatus> = prefs.map { p ->
        SyncStatus(
            lastSuccessMillis = p[Keys.LAST_SUCCESS] ?: 0L,
            lastError = p[Keys.LAST_ERROR] ?: "",
            lastErrorMillis = p[Keys.LAST_ERROR_TIME] ?: 0L,
        )
    }.distinctUntilChanged()

    suspend fun current(): Settings = settings.first()

    suspend fun save(s: Settings) {
        runCatching {
            context.dataStore.edit { p ->
                p[Keys.FOLDER_ID] = s.driveFolderId
                p[Keys.API_KEY] = s.apiKey
                p[Keys.PIN] = s.pin
                p[Keys.IMAGE_DURATION] = s.imageDurationSec
                p[Keys.SOUND] = s.soundEnabled
                p[Keys.SCALE_MODE] = s.scaleMode.name
                p[Keys.BG_COLOR] = s.backgroundColor
                p[Keys.POS_PACKAGE] = s.posPackage
            }
        }
    }

    /**
     * Synchro terminée. [listedOk] = le dossier Drive a pu être lu (synchro effectuée,
     * éventuellement partielle). [error] vide = aucune erreur.
     */
    suspend fun recordSyncResult(nowMillis: Long, listedOk: Boolean, error: String) {
        runCatching {
            context.dataStore.edit { p ->
                if (listedOk) p[Keys.LAST_SUCCESS] = nowMillis
                if (error.isEmpty()) {
                    p[Keys.LAST_ERROR] = ""
                } else {
                    p[Keys.LAST_ERROR] = error
                    p[Keys.LAST_ERROR_TIME] = nowMillis
                }
            }
        }
    }
}
