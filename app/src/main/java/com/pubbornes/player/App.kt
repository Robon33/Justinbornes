package com.pubbornes.player

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Point d'entrée : crée les objets partagés et installe le filet de sécurité anti-crash. */
class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var repository: MediaRepository
        private set
    lateinit var syncManager: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashRestart()

        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val headers = buildMap {
            put("X-Android-Package", packageName)
            signingCertSha1()?.let { put("X-Android-Cert", it) }
        }
        settingsStore = SettingsStore(this)
        repository = MediaRepository(filesDir, DriveClient(http, headers))
        syncManager = SyncManager(repository, settingsStore, appScope)
    }

    /**
     * En cas de plantage inattendu, relance le lecteur 3 s plus tard.
     * (Si l'app est l'écran d'accueil, Android la relance de toute façon.)
     */
    private fun installCrashRestart() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Log.e("App", "Plantage inattendu, relance programmée", error)
            runCatching {
                val intent = Intent(this, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val pi = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                )
                val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.set(AlarmManager.RTC, System.currentTimeMillis() + 3000, pi)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Empreinte SHA-1 du certificat de signature (hexadécimal majuscule, sans « : »), pour la clé API restreinte. */
    @Suppress("DEPRECATION")
    private fun signingCertSha1(): String? = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
    }.getOrNull()
}

val Context.app: App get() = applicationContext as App
