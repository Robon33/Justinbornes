package com.pubbornes.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Lecture en boucle de la playlist (vidéos + images), sans coupure noire.
 *
 * Fonctionnement : trois couches superposées (vidéo, image, écran d'attente).
 * - vidéo -> vidéo : la suivante est ajoutée à la file d'ExoPlayer => enchaînement direct ;
 * - vidéo -> image : la dernière image de la vidéo reste affichée, l'image (déjà décodée
 *   pendant la vidéo) passe par-dessus ;
 * - image -> vidéo : la vidéo est préparée en pause SOUS l'image ; l'image n'est retirée
 *   qu'une fois la première image de la vidéo affichée.
 * Un média illisible est simplement sauté. La playlist est relue à la fin de chaque cycle.
 */
@OptIn(UnstableApi::class)
class PlaylistPlayer(
    private val context: Context,
    private val rootView: View,
    private val playerView: PlayerView,
    private val imageView: ImageView,
    private val waitingView: TextView,
    private val repository: MediaRepository,
    private val settingsFlow: Flow<Settings>,
) {
    companion object {
        private const val TAG = "PlaylistPlayer"
        private const val FIRST_FRAME_TIMEOUT_MS = 10_000L // vidéo qui ne démarre pas => sautée
        private const val STALL_TIMEOUT_MS = 15_000L       // vidéo bloquée => sautée
        private const val EMPTY_RETRY_MS = 5_000L          // cache vide : on revérifie souvent
        private const val ALL_FAILED_RETRY_MS = 10_000L    // aucun média lisible
        private const val VIDEO_PREPARE_LEAD_MS = 2_000L   // préparation avant fin d'image
        private const val QUICK_FAILURE_MS = 3_000L        // échec "immédiat" => 2e essai
        private const val MAX_BITMAP_PIXELS = 1080L * 1920 * 3 // garde-fou mémoire
    }

    /** Un média à une position précise de la boucle ([token] unique par passage). */
    private class Slot(val entry: MediaEntry, val token: String)

    private var scope: CoroutineScope? = null
    private var player: ExoPlayer? = null
    private var settings = Settings()

    /** Réveille la boucle d'attente dès qu'ExoPlayer signale un événement. */
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var firstFrameToken: String? = null
    private var nextImage: Pair<String, Deferred<Bitmap?>>? = null

    /** Position dans la playlist, pour reprendre au même média après une interruption. */
    private var resumeIndex = 0

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            firstFrameToken = player?.currentMediaItem?.mediaId
            signal.trySend(Unit)
        }
        override fun onPlaybackStateChanged(playbackState: Int) { signal.trySend(Unit) }
        override fun onIsPlayingChanged(isPlaying: Boolean) { signal.trySend(Unit) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { signal.trySend(Unit) }
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Erreur de lecture : ${error.errorCodeName}", error)
            signal.trySend(Unit)
        }
    }

    /** Démarre (ou reprend) la lecture. Appelé dans onStart. */
    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        firstFrameToken = null
        createPlayer()
        s.launch { settingsFlow.collect { applySettings(it) } }
        s.launch { runForever() }
    }

    /** Arrête la lecture et libère le décodeur vidéo. Appelé dans onStop. */
    fun stop() {
        scope?.cancel()
        scope = null
        nextImage = null
        releasePlayer()
    }

    private fun createPlayer() {
        val p = ExoPlayer.Builder(context).build().apply {
            // Pas de gestion du focus audio : la borne ne doit rien "voler" au système.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
            repeatMode = Player.REPEAT_MODE_OFF
            volume = if (settings.soundEnabled) 1f else 0f
            addListener(listener)
        }
        player = p
        playerView.player = p
    }

    private fun releasePlayer() {
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        playerView.player = null
    }

    // --- Réglages ----------------------------------------------------------------------

    private fun applySettings(s: Settings) {
        settings = s
        val bg = s.backgroundColorInt
        rootView.setBackgroundColor(bg)
        playerView.setBackgroundColor(bg)
        playerView.setShutterBackgroundColor(Color.TRANSPARENT)
        imageView.setBackgroundColor(bg)
        waitingView.setBackgroundColor(bg)
        waitingView.setTextColor(if (Color.luminance(bg) > 0.5f) Color.BLACK else Color.WHITE)
        if (s.scaleMode == ScaleMode.FILL) {
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
            imageView.scaleType = ImageView.ScaleType.FIT_CENTER
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        player?.volume = if (s.soundEnabled) 1f else 0f
    }

    // --- Boucle principale -------------------------------------------------------------

    /** Ne s'arrête jamais (sauf stop()) : toute erreur imprévue relance la boucle. */
    private suspend fun runForever() {
        while (true) {
            try {
                runCycles()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Erreur inattendue dans la boucle, redémarrage", t)
                showWaiting()
                delay(EMPTY_RETRY_MS)
            }
        }
    }

    private suspend fun runCycles() {
        var cycle = 0L
        var list = repository.loadPlaylist()
        var startAt = resumeIndex
        while (true) {
            // Nouveau cycle : on peut supprimer les fichiers qui ne sont plus utilisés.
            repository.cleanup(list)

            if (list.isEmpty()) {
                showWaiting()
                delay(EMPTY_RETRY_MS)
                list = repository.loadPlaylist()
                startAt = 0
                continue
            }

            val slots = list.mapIndexed { i, e -> Slot(e, "$cycle-$i") }
            var nextList: List<MediaEntry>? = null
            var successes = 0
            val first = if (startAt in slots.indices) startAt else 0

            for (i in first until slots.size) {
                resumeIndex = i
                // Le média suivant sert au préchargement. Au dernier média, on lit déjà la
                // playlist du prochain cycle (qui intègre la dernière synchro).
                val next = if (i + 1 < slots.size) slots[i + 1] else {
                    val nl = repository.loadPlaylist()
                    nextList = nl
                    nl.firstOrNull()?.let { Slot(it, "${cycle + 1}-0") }
                }
                if (playSafely(slots[i], next)) successes++
            }

            startAt = 0
            resumeIndex = 0
            cycle++
            list = nextList ?: repository.loadPlaylist()
            if (successes == 0) {
                Log.w(TAG, "Aucun média lisible dans ce cycle")
                showWaiting()
                delay(ALL_FAILED_RETRY_MS)
            }
        }
    }

    /** Joue un média ; retourne false s'il a été sauté (illisible, bloqué...). */
    private suspend fun playSafely(slot: Slot, next: Slot?): Boolean = try {
        Log.i(TAG, "Lecture : ${slot.entry.name}")
        if (slot.entry.isVideo) {
            val start = SystemClock.elapsedRealtime()
            playVideo(slot, next) || run {
                // Échec immédiat : souvent un décodeur capricieux. Un 2e essai avec un
                // lecteur neuf (recréé par playVideo) avant de passer au média suivant.
                if (SystemClock.elapsedRealtime() - start < QUICK_FAILURE_MS) {
                    Log.i(TAG, "Nouvel essai : ${slot.entry.name}")
                    playVideo(slot, next)
                } else false
            }
        } else {
            showImage(slot, next)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Log.w(TAG, "Média ignoré : ${slot.entry.name}", t)
        recreatePlayer()
        false
    }

    // --- Images --------------------------------------------------------------------------

    private suspend fun showImage(slot: Slot, next: Slot?): Boolean {
        val preloaded = nextImage?.takeIf { it.first == slot.token }?.second
        nextImage = null
        val bitmap = preloaded?.await() ?: decodeImageAsync(repository.fileOf(slot.entry))
        if (bitmap == null) {
            Log.w(TAG, "Image illisible, ignorée : ${slot.entry.name}")
            return false
        }
        imageView.setImageBitmap(bitmap)
        imageView.visibility = View.VISIBLE
        waitingView.visibility = View.GONE
        val durationMs = settings.imageDurationSec * 1000L
        if (next?.entry?.isVideo == true) {
            // La vidéo suivante n'est préparée que peu avant la fin de l'image : certains
            // décodeurs n'aiment pas rester longtemps en pause.
            val lead = min(VIDEO_PREPARE_LEAD_MS, durationMs / 2)
            delay(durationMs - lead)
            prepareNext(next, currentIsVideo = false)
            delay(lead)
        } else {
            prepareNext(next, currentIsVideo = false)
            delay(durationMs)
        }
        return true
    }

    private suspend fun decodeImageAsync(file: File): Bitmap? =
        withContext(Dispatchers.IO) { decodeImage(file) }

    /** Décode une image en limitant sa taille à ce que l'écran peut afficher. */
    private fun decodeImage(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) null else {
            val dm = context.resources.displayMetrics
            val reqW = min(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
            val reqH = max(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
            var sample = 1
            while (true) {
                val stillBigEnough = w / (sample * 2) >= reqW && h / (sample * 2) >= reqH
                val tooBig = (w / sample).toLong() * (h / sample) > MAX_BITMAP_PIXELS
                if (stillBigEnough || tooBig) sample *= 2 else break
            }
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (t: Throwable) { // y compris OutOfMemoryError
        Log.w(TAG, "Décodage impossible : ${file.name}", t)
        null
    }

    // --- Vidéos --------------------------------------------------------------------------

    private suspend fun playVideo(slot: Slot, next: Slot?): Boolean {
        val p = player ?: return false
        val alreadyLoaded = p.playerError == null &&
            p.playbackState != Player.STATE_IDLE &&
            p.currentMediaItem?.mediaId == slot.token
        if (!alreadyLoaded) {
            p.playWhenReady = false
            p.setMediaItem(mediaItem(slot))
            p.prepare()
        }
        // Retire de la file les vidéos déjà jouées.
        while (p.currentMediaItemIndex > 0) p.removeMediaItem(0)
        p.volume = if (settings.soundEnabled) 1f else 0f
        p.playWhenReady = true

        val start = SystemClock.elapsedRealtime()
        // Si une image (ou l'écran d'attente) est affichée, on attend la 1re image de la vidéo
        // avant de la retirer. Sinon (vidéo -> vidéo), rien à masquer.
        var revealed = imageView.visibility != View.VISIBLE && waitingView.visibility != View.VISIBLE
        var nextPrepared = false
        var lastPos = -1L
        var lastProgress = start

        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (p.playerError != null) {
                Log.w(TAG, "Vidéo illisible, ignorée : ${slot.entry.name}")
                recreatePlayer()
                return false
            }
            // La file d'ExoPlayer est passée à la vidéo suivante : enchaînement sans coupure.
            if (p.currentMediaItem?.mediaId != slot.token) return true
            if (p.playbackState == Player.STATE_ENDED) return true

            if (!revealed) {
                if (firstFrameToken == slot.token && p.isPlaying) {
                    imageView.visibility = View.GONE
                    imageView.setImageDrawable(null)
                    waitingView.visibility = View.GONE
                    revealed = true
                } else if (now - start > FIRST_FRAME_TIMEOUT_MS) {
                    Log.w(TAG, "Vidéo qui ne démarre pas, ignorée : ${slot.entry.name}")
                    recreatePlayer()
                    return false
                }
            }
            if (revealed && !nextPrepared) {
                prepareNext(next, currentIsVideo = true)
                nextPrepared = true
            }

            // Garde-fou : vidéo figée trop longtemps => on passe à la suite.
            val pos = p.currentPosition
            if (pos != lastPos) {
                lastPos = pos
                lastProgress = now
            } else if (revealed && now - lastProgress > STALL_TIMEOUT_MS) {
                Log.w(TAG, "Vidéo bloquée, ignorée : ${slot.entry.name}")
                recreatePlayer()
                return false
            }

            withTimeoutOrNull(250) { signal.receive() }
        }
    }

    /** Précharge le média suivant pendant que le média courant est affiché. */
    private fun prepareNext(next: Slot?, currentIsVideo: Boolean) {
        next ?: return
        val s = scope ?: return
        if (!next.entry.isVideo) {
            val file = repository.fileOf(next.entry)
            nextImage = next.token to s.async(Dispatchers.IO) { decodeImage(file) }
            return
        }
        val p = player ?: return
        if (currentIsVideo) {
            // Ajout en file : ExoPlayer enchaînera directement. ExoPlayer précharge la vidéo
            // en file : si elle était corrompue, son erreur interromprait la vidéo EN COURS.
            // On vérifie donc d'abord qu'elle est lisible ; sinon elle n'est pas mise en file
            // et sera simplement sautée à son tour.
            val currentToken = p.currentMediaItem?.mediaId
            val file = repository.fileOf(next.entry)
            s.launch {
                val ok = withContext(Dispatchers.IO) { isReadableVideo(file) }
                val pl = player ?: return@launch
                if (ok && pl.currentMediaItem?.mediaId == currentToken &&
                    pl.mediaItemCount == pl.currentMediaItemIndex + 1
                ) {
                    pl.addMediaItem(mediaItem(next))
                }
            }
        } else {
            // On prépare la vidéo en pause, cachée sous l'image affichée.
            p.playWhenReady = false
            p.setMediaItem(mediaItem(next))
            p.prepare()
        }
    }

    /** Vérifie rapidement qu'un fichier contient bien une piste vidéo lisible. */
    private fun isReadableVideo(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Vidéo illisible (vérification) : ${file.name}")
            false
        } finally {
            extractor.release()
        }
    }

    private fun mediaItem(slot: Slot): MediaItem =
        MediaItem.Builder()
            .setUri(Uri.fromFile(repository.fileOf(slot.entry)))
            .setMediaId(slot.token)
            .build()

    /**
     * Après un échec, on repart d'un lecteur neuf : un décodeur matériel en erreur peut
     * rester dans un état instable et faire échouer les vidéos suivantes.
     */
    private fun recreatePlayer() {
        if (player == null) return
        releasePlayer()
        createPlayer()
    }

    private fun resetPlayer() {
        player?.run {
            stop()
            clearMediaItems()
        }
    }

    /** Écran "Chargement du contenu…" (jamais d'écran noir). */
    private fun showWaiting() {
        waitingView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        imageView.setImageDrawable(null)
        nextImage = null
        resetPlayer()
    }
}
