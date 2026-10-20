package com.pubbornes.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Écran principal : lecture en boucle, mode kiosque.
 * Tous les touchers sont ignorés, sauf la zone secrète en haut à droite.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val CORNER_SIZE_PX = 150
        private const val TAPS_REQUIRED = 8
        private const val TAPS_WINDOW_MS = 5_000L
        private const val PIN_DIALOG_TIMEOUT_MS = 30_000L
    }

    private lateinit var playlistPlayer: PlaylistPlayer
    private val cornerTaps = ArrayDeque<Long>()
    private var pinDialog: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)
        hideSystemBars()

        // Bouton retour désactivé.
        onBackPressedDispatcher.addCallback(this) { /* ignoré */ }

        playlistPlayer = PlaylistPlayer(
            context = this,
            rootView = findViewById(R.id.root),
            playerView = findViewById<PlayerView>(R.id.playerView),
            imageView = findViewById<ImageView>(R.id.imageView),
            waitingView = findViewById<TextView>(R.id.waitingView),
            repository = app.repository,
            settingsFlow = app.settingsStore.settings,
        )

        // Synchro au lancement puis toutes les 30 min, uniquement quand la borne affiche la pub
        // (pas de téléchargement pendant que Factory POS tourne).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    app.syncManager.syncIfDue()
                    delay(60_000)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        playlistPlayer.start() // reprise automatique au retour au premier plan
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onStop() {
        playlistPlayer.stop()
        dismissPinDialog()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Plein écran immersif "sticky" : les barres réapparaissent au glissement puis se recachent. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // --- Verrouillage des touchers + sortie cachée ---------------------------------------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val width = window.decorView.width
            if (ev.x >= width - CORNER_SIZE_PX && ev.y <= CORNER_SIZE_PX) onCornerTap()
        }
        return true // on consomme tout : aucun toucher n'atteint l'écran de lecture
    }

    private fun onCornerTap() {
        val now = System.currentTimeMillis()
        cornerTaps.addLast(now)
        while (cornerTaps.isNotEmpty() && now - cornerTaps.first() > TAPS_WINDOW_MS) cornerTaps.removeFirst()
        if (cornerTaps.size >= TAPS_REQUIRED) {
            cornerTaps.clear()
            askPin()
        }
    }

    private fun askPin() {
        if (pinDialog?.isShowing == true) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 28f
            hint = "Code PIN"
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Accès administrateur")
            .setView(input)
            .setPositiveButton("Valider") { _, _ -> checkPin(input.text.toString()) }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            hideSystemBars()
        }
        // La touche "valider" du clavier soumet aussi le code.
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setOnEditorActionListener { _, _, _ ->
            checkPin(input.text.toString())
            dialog.dismiss()
            true
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
        pinDialog = dialog
        // Le dialogue se ferme seul s'il est oublié.
        handler.postDelayed({ dismissPinDialog() }, PIN_DIALOG_TIMEOUT_MS)
    }

    private fun checkPin(entered: String) {
        lifecycleScope.launch {
            val pin = app.settingsStore.current().pin
            if (entered.isNotEmpty() && entered == pin) {
                startActivity(Intent(this@PlayerActivity, AdminActivity::class.java))
            } else {
                Toast.makeText(this@PlayerActivity, "Code incorrect", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dismissPinDialog() {
        handler.removeCallbacksAndMessages(null)
        pinDialog?.takeIf { it.isShowing }?.dismiss()
        pinDialog = null
    }
}
