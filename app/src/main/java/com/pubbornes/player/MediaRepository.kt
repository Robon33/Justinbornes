package com.pubbornes.player

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** Un média présent dans le cache local. */
data class MediaEntry(
    val id: String,           // ID Drive
    val name: String,         // nom Drive (sert au tri de la playlist)
    val modifiedTime: String, // version Drive
    val fileName: String,     // nom du fichier local dans le dossier cache
) {
    val isVideo: Boolean get() = fileName.endsWith(".mp4", ignoreCase = true)
}

/** Résultat d'une synchro. */
data class SyncReport(val fileCount: Int, val failures: List<String>)

/**
 * Cache local des médias + synchronisation avec Drive.
 *
 * Principes de sécurité :
 * - le manifeste (liste des médias valides) est écrit de façon atomique ;
 * - chaque téléchargement va dans un fichier ".part", renommé seulement une fois complet ;
 * - une nouvelle version d'un fichier a un nouveau nom local : l'ancienne version reste
 *   lisible jusqu'à la fin du cycle en cours ;
 * - la synchro ne supprime jamais de média : c'est le lecteur qui appelle [cleanup] au
 *   début d'un nouveau cycle, quand plus rien d'ancien n'est en lecture.
 */
class MediaRepository(filesDir: File, private val drive: DriveClient) {

    private val mediaDir = File(filesDir, "media")
    private val manifestFile = File(filesDir, "manifest.json")
    private val lock = Mutex()

    companion object {
        private const val TAG = "MediaRepository"
        val SUPPORTED_EXTENSIONS = setOf("mp4", "jpg", "jpeg", "png", "webp")
        /** Marge d'espace disque à conserver libre. */
        private const val DISK_MARGIN_BYTES = 200L * 1024 * 1024

        fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()
    }

    fun fileOf(entry: MediaEntry) = File(mediaDir, entry.fileName)

    /** Playlist jouable : médias présents dans le cache, triés par nom de fichier Drive. */
    suspend fun loadPlaylist(): List<MediaEntry> = withContext(Dispatchers.IO) {
        (readManifest() ?: emptyList())
            .filter { fileOf(it).let { f -> f.isFile && f.length() > 0 } }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    /** Taille totale du cache, en octets. */
    suspend fun cacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        mediaDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    /**
     * Supprime les fichiers locaux qui ne sont ni dans le manifeste, ni dans [keep]
     * (la playlist en cours de lecture). Ne fait rien si une synchro est en cours.
     */
    suspend fun cleanup(keep: Collection<MediaEntry>) = withContext(Dispatchers.IO) {
        if (!lock.tryLock()) return@withContext
        try {
            // Manifeste illisible : on ne supprime RIEN (sinon tout le cache serait effacé).
            val manifest = readManifest() ?: return@withContext
            val referenced = (manifest + keep).map { it.fileName }.toSet()
            mediaDir.listFiles()?.forEach { f ->
                if (f.name !in referenced) {
                    Log.i(TAG, "Suppression du fichier obsolète ${f.name}")
                    f.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nettoyage impossible", e)
        } finally {
            lock.unlock()
        }
    }

    /**
     * Synchronise le cache avec le dossier Drive. Lève une exception si le dossier ne peut
     * pas être lu (hors ligne, clé invalide...) : le cache est alors laissé intact.
     */
    suspend fun sync(folderId: String, apiKey: String): SyncReport = lock.withLock {
        withContext(Dispatchers.IO) {
            mediaDir.mkdirs()
            // Restes d'un téléchargement interrompu.
            mediaDir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }

            val remote = drive.listFolder(folderId, apiKey).filter { rf ->
                !rf.mimeType.startsWith("application/vnd.google-apps") &&
                    extensionOf(rf.name) in SUPPORTED_EXTENSIONS
            }

            val entries = (readManifest() ?: emptyList()).associateBy { it.id }.toMutableMap()
            val failures = mutableListOf<String>()

            for (rf in remote) {
                coroutineContext.ensureActive()
                val local = entries[rf.id]
                val upToDate = local != null && local.modifiedTime == rf.modifiedTime &&
                    fileOf(local).let { it.isFile && it.length() > 0 }
                if (upToDate) {
                    // Simple renommage dans Drive : on met à jour le nom (ordre de tri).
                    if (local!!.name != rf.name) {
                        entries[rf.id] = local.copy(name = rf.name)
                        writeManifest(entries.values)
                    }
                    continue
                }
                try {
                    downloadOne(rf, apiKey)?.let { entry ->
                        entries[rf.id] = entry
                        // Sauvegarde après chaque fichier : rien n'est perdu si la synchro
                        // est interrompue (coupure réseau, passage à Factory POS...).
                        writeManifest(entries.values)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Échec du téléchargement de ${rf.name}", e)
                    failures += "${rf.name} : ${e.message ?: e.javaClass.simpleName}"
                }
            }

            // Fichiers retirés du dossier Drive : on les sort du manifeste
            // (le fichier local sera supprimé par cleanup() au prochain cycle).
            val remoteIds = remote.map { it.id }.toSet()
            entries.keys.retainAll(remoteIds)
            writeManifest(entries.values)

            SyncReport(entries.size, failures)
        }
    }

    /** Télécharge un fichier vers "<nom>.part" puis le renomme. Retourne la nouvelle entrée. */
    private suspend fun downloadOne(rf: RemoteFile, apiKey: String): MediaEntry? {
        if (rf.size > 0 && mediaDir.usableSpace < rf.size + DISK_MARGIN_BYTES) {
            throw DriveException("espace de stockage insuffisant")
        }
        // Nom local unique par version : id + empreinte de modifiedTime.
        val version = Integer.toHexString(rf.modifiedTime.hashCode())
        val fileName = "${rf.id}_$version.${extensionOf(rf.name)}"
        val target = File(mediaDir, fileName)
        val tmp = File(mediaDir, "$fileName.part")
        try {
            Log.i(TAG, "Téléchargement de ${rf.name} (${rf.size} octets)")
            drive.download(rf, apiKey, tmp)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw DriveException("renommage impossible")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return MediaEntry(rf.id, rf.name, rf.modifiedTime, fileName)
    }

    // --- Manifeste ---------------------------------------------------------------------

    /** Liste des médias du cache ; null si le manifeste existe mais est illisible. */
    private fun readManifest(): List<MediaEntry>? = try {
        if (!manifestFile.exists()) emptyList() else {
            val arr = JSONArray(manifestFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MediaEntry(o.getString("id"), o.getString("name"), o.getString("modifiedTime"), o.getString("fileName"))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Manifeste illisible", e)
        null
    }

    /** Écriture atomique : fichier temporaire puis renommage. */
    private fun writeManifest(entries: Collection<MediaEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("id", e.id).put("name", e.name)
                    .put("modifiedTime", e.modifiedTime).put("fileName", e.fileName)
            )
        }
        val tmp = File(manifestFile.parentFile, "manifest.json.tmp")
        tmp.outputStream().use { out ->
            out.write(arr.toString().toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(manifestFile)) {
            manifestFile.delete()
            tmp.renameTo(manifestFile)
        }
    }
}
