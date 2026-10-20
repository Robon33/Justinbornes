package com.pubbornes.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menu admin (accessible uniquement après le PIN). Se ferme seul après 60 s d'inactivité. */
class AdminActivity : AppCompatActivity() {

    companion object {
        private const val INACTIVITY_TIMEOUT_MS = 60_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val closeOnInactivity = Runnable { finish() } // => retour à la lecture

    private lateinit var editFolderId: EditText
    private lateinit var editApiKey: EditText
    private lateinit var editPin: EditText
    private lateinit var editImageDuration: EditText
    private lateinit var switchSound: SwitchCompat
    private lateinit var radioFill: RadioButton
    private lateinit var radioFit: RadioButton
    private lateinit var editBgColor: EditText
    private lateinit var editPosPackage: EditText
    private lateinit var statusText: TextView

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        editFolderId = findViewById(R.id.editFolderId)
        editApiKey = findViewById(R.id.editApiKey)
        editPin = findViewById(R.id.editPin)
        editImageDuration = findViewById(R.id.editImageDuration)
        switchSound = findViewById(R.id.switchSound)
        radioFill = findViewById(R.id.radioFill)
        radioFit = findViewById(R.id.radioFit)
        editBgColor = findViewById(R.id.editBgColor)
        editPosPackage = findViewById(R.id.editPosPackage)
        statusText = findViewById(R.id.statusText)
        findViewById<TextView>(R.id.versionText).text =
            "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        findViewById<Button>(R.id.btnLaunchPos).setOnClickListener { launchPos() }
        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            app.syncManager.syncNowInBackground()
            Toast.makeText(this, "Synchronisation lancée", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPickPos).setOnClickListener { pickPosApp() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.btnAndroidSettings).setOnClickListener {
            runCatching { startActivity(Intent(AndroidSettings.ACTION_SETTINGS)) }
                .onFailure { toast("Impossible d'ouvrir les paramètres") }
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnQuit).setOnClickListener { finishAffinity() }

        // La saisie au clavier virtuel ne passe pas par onUserInteraction : on écoute le texte.
        val resetOnTyping = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = resetInactivityTimer()
            override fun afterTextChanged(s: Editable?) {}
        }
        listOf(editFolderId, editApiKey, editPin, editImageDuration, editBgColor, editPosPackage)
            .forEach { it.addTextChangedListener(resetOnTyping) }

        lifecycleScope.launch { fillForm(app.settingsStore.current()) }

        // Statut de synchro, mis à jour en direct.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(app.syncManager.running, app.settingsStore.syncStatus) { running, status ->
                    running to status
                }.collect { (running, status) -> renderStatus(running, status) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
    }

    override fun onPause() {
        handler.removeCallbacks(closeOnInactivity)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }

    private fun resetInactivityTimer() {
        handler.removeCallbacks(closeOnInactivity)
        handler.postDelayed(closeOnInactivity, INACTIVITY_TIMEOUT_MS)
    }

    // --- Statut ----------------------------------------------------------------------------

    private suspend fun renderStatus(running: Boolean, status: SyncStatus) {
        val files = app.repository.loadPlaylist()
        val videos = files.count { it.isVideo }
        val sizeMb = app.repository.cacheSizeBytes() / (1024 * 1024)
        val lines = buildList {
            add(if (running) "⏳ Synchronisation en cours…" else "Synchronisation au repos")
            add("Fichiers en cache : ${files.size} ($videos vidéo(s), ${files.size - videos} image(s), $sizeMb Mo)")
            add("Dernière synchro : " +
                if (status.lastSuccessMillis > 0) dateFormat.format(Date(status.lastSuccessMillis)) else "jamais")
            add(if (status.lastError.isEmpty()) "Dernière erreur : aucune"
                else "Dernière erreur (${dateFormat.format(Date(status.lastErrorMillis))}) : ${status.lastError}")
        }
        statusText.text = lines.joinToString("\n")
    }

    // --- Réglages --------------------------------------------------------------------------

    private fun fillForm(s: Settings) {
        editFolderId.setText(s.driveFolderId)
        editApiKey.setText(s.apiKey)
        editPin.setText(s.pin)
        editImageDuration.setText(s.imageDurationSec.toString())
        switchSound.isChecked = s.soundEnabled
        radioFill.isChecked = s.scaleMode == ScaleMode.FILL
        radioFit.isChecked = s.scaleMode == ScaleMode.FIT
        editBgColor.setText(s.backgroundColor)
        editPosPackage.setText(s.posPackage)
    }

    private fun saveSettings() {
        val pin = editPin.text.toString().trim()
        if (!pin.matches(Regex("\\d{4,8}"))) return toast("Le PIN doit contenir 4 à 8 chiffres")
        val duration = editImageDuration.text.toString().trim().toIntOrNull()
        if (duration == null || duration !in 1..3600) return toast("Durée des images : entre 1 et 3600 s")
        val color = editBgColor.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
        if (Settings.parseColorOrNull(color) == null) return toast("Couleur invalide (exemple : #000000)")
        val folderId = extractFolderId(editFolderId.text.toString())

        val newSettings = Settings(
            driveFolderId = folderId,
            apiKey = editApiKey.text.toString().trim(),
            pin = pin,
            imageDurationSec = duration,
            soundEnabled = switchSound.isChecked,
            scaleMode = if (radioFit.isChecked) ScaleMode.FIT else ScaleMode.FILL,
            backgroundColor = color.uppercase(),
            posPackage = editPosPackage.text.toString().trim(),
        )
        lifecycleScope.launch {
            val old = app.settingsStore.current()
            app.settingsStore.save(newSettings)
            fillForm(newSettings)
            toast("Réglages enregistrés")
            // Source Drive modifiée : on resynchronise tout de suite.
            if (old.driveFolderId != newSettings.driveFolderId || old.apiKey != newSettings.apiKey) {
                app.syncManager.syncNowInBackground()
            }
        }
    }

    /** Accepte l'ID seul ou un lien complet ".../folders/<ID>?usp=sharing". */
    private fun extractFolderId(raw: String): String {
        val text = raw.trim()
        Regex("folders/([A-Za-z0-9_-]+)").find(text)?.let { return it.groupValues[1] }
        Regex("[?&]id=([A-Za-z0-9_-]+)").find(text)?.let { return it.groupValues[1] }
        return text
    }

    // --- Factory POS -----------------------------------------------------------------------

    private fun launchPos() {
        lifecycleScope.launch {
            val pkg = app.settingsStore.current().posPackage.trim()
            if (pkg.isEmpty()) return@launch toast("Package Factory POS non configuré (Réglages > Choisir…)")
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent == null) {
                toast("Application introuvable : $pkg")
                return@launch
            }
            runCatching {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }.onFailure { toast("Lancement impossible : ${it.message}") }
        }
    }

    /** Liste les applications installées pour choisir Factory POS sans connaître son package. */
    private fun pickPosApp() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }
            .filter { it.second != packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        if (apps.isEmpty()) return toast("Aucune application trouvée")
        AlertDialog.Builder(this)
            .setTitle("Choisir l'application de commande")
            .setItems(apps.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, which ->
                editPosPackage.setText(apps[which].second)
                toast("Pensez à enregistrer les réglages")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
