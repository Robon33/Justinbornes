package com.pubbornes.player

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Orchestration de la synchro : au lancement, toutes les 30 min, ou à la demande.
 * Une erreur n'est jamais fatale : on garde le cache et on réessaie plus tard.
 */
class SyncManager(
    private val repository: MediaRepository,
    private val settingsStore: SettingsStore,
    private val appScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "SyncManager"
        const val PERIOD_MS = 30L * 60 * 1000      // synchro normale
        const val RETRY_MS = 5L * 60 * 1000        // nouvel essai après une erreur
    }

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    @Volatile private var lastAttemptMillis = 0L
    @Volatile private var lastFailed = false

    /** Lance une synchro si l'intervalle est écoulé (0 au démarrage => synchro immédiate). */
    suspend fun syncIfDue() {
        val interval = if (lastFailed) RETRY_MS else PERIOD_MS
        if (lastAttemptMillis == 0L || System.currentTimeMillis() - lastAttemptMillis >= interval) {
            sync()
        }
    }

    /** Synchro manuelle depuis le menu admin (indépendante de l'écran affiché). */
    fun syncNowInBackground() {
        appScope.launch { sync() }
    }

    suspend fun sync() {
        lastAttemptMillis = System.currentTimeMillis()
        val settings = settingsStore.current()
        if (!settings.isDriveConfigured) {
            lastFailed = false
            settingsStore.recordSyncResult(System.currentTimeMillis(), false,
                "Dossier Drive ou clé API non configuré (menu admin > Réglages)")
            return
        }
        _running.value = true
        try {
            val report = repository.sync(settings.driveFolderId, settings.apiKey)
            lastFailed = report.failures.isNotEmpty()
            val error = if (report.failures.isEmpty()) "" else
                "${report.failures.size} fichier(s) non téléchargé(s) : " + report.failures.joinToString(" ; ")
            settingsStore.recordSyncResult(System.currentTimeMillis(), true, error)
            Log.i(TAG, "Synchro terminée : ${report.fileCount} fichier(s), ${report.failures.size} échec(s)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailed = true
            val msg = when (e) {
                is java.net.UnknownHostException, is java.net.ConnectException,
                is java.net.SocketTimeoutException -> "Pas de connexion internet (${e.javaClass.simpleName})"
                else -> e.message ?: e.javaClass.simpleName
            }
            Log.w(TAG, "Synchro échouée : $msg", e)
            settingsStore.recordSyncResult(System.currentTimeMillis(), false, msg)
        } finally {
            _running.value = false
        }
    }
}
