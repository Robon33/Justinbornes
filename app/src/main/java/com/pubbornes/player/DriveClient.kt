package com.pubbornes.player

import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Fichier tel que listé par l'API Drive. */
data class RemoteFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: String,
    val size: Long, // -1 si inconnue
)

/** Erreur lisible (affichée dans le menu admin). */
class DriveException(message: String) : IOException(message)

/**
 * Accès en lecture seule à un dossier Drive partagé publiquement, avec une simple clé API.
 * [extraHeaders] : en-têtes X-Android-Package / X-Android-Cert, nécessaires si la clé API
 * est restreinte aux applications Android.
 */
class DriveClient(
    private val http: OkHttpClient,
    private val extraHeaders: Map<String, String>,
) {
    private val base = "https://www.googleapis.com/drive/v3/files"

    /** Liste tous les fichiers (non supprimés) du dossier, pagination comprise. */
    suspend fun listFolder(folderId: String, apiKey: String): List<RemoteFile> {
        require(folderId.matches(Regex("[A-Za-z0-9_-]+"))) { "ID de dossier invalide : $folderId" }
        val out = mutableListOf<RemoteFile>()
        var pageToken: String? = null
        do {
            coroutineContext.ensureActive()
            val url = base.toHttpUrl().newBuilder()
                .addQueryParameter("q", "'$folderId' in parents and trashed = false")
                .addQueryParameter("fields", "nextPageToken, files(id, name, mimeType, modifiedTime, size)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .addQueryParameter("key", apiKey)
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            http.newCall(request(url.toString())).execute().use { resp ->
                if (!resp.isSuccessful) throw DriveException(describeError(resp, "Lecture du dossier"))
                val json = JSONObject(resp.body?.string() ?: "{}")
                val files = json.optJSONArray("files")
                if (files != null) {
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        out += RemoteFile(
                            id = f.optString("id"),
                            name = f.optString("name"),
                            mimeType = f.optString("mimeType"),
                            modifiedTime = f.optString("modifiedTime"),
                            size = f.optString("size").toLongOrNull() ?: -1L,
                        )
                    }
                }
                pageToken = json.optString("nextPageToken").ifEmpty { null }
            }
        } while (pageToken != null)
        return out.filter { it.id.isNotEmpty() }
    }

    /**
     * Télécharge [file] dans [tmp]. Annulable : la copie s'arrête dès que la coroutine
     * est annulée. Vérifie la taille reçue si l'API l'a fournie.
     */
    suspend fun download(file: RemoteFile, apiKey: String, tmp: File) {
        val url = "$base/${file.id}".toHttpUrl().newBuilder()
            .addQueryParameter("alt", "media")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("key", apiKey)
            .build()
        http.newCall(request(url.toString())).execute().use { resp ->
            if (!resp.isSuccessful) throw DriveException(describeError(resp, "Téléchargement"))
            val body = resp.body ?: throw DriveException("Réponse vide")
            body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync() // données réellement écrites avant le renommage
                }
            }
        }
        if (file.size >= 0 && tmp.length() != file.size) {
            throw DriveException("Fichier incomplet (${tmp.length()} / ${file.size} octets)")
        }
    }

    private fun request(url: String): Request =
        Request.Builder().url(url).apply { extraHeaders.forEach { (k, v) -> header(k, v) } }.build()

    /** Transforme une réponse d'erreur Google en message clair en français. */
    private fun describeError(resp: Response, what: String): String {
        val apiMessage = runCatching {
            JSONObject(resp.body?.string() ?: "").getJSONObject("error").optString("message")
        }.getOrNull().orEmpty()
        val hint = when (resp.code) {
            400 -> "requête invalide (clé API ou ID de dossier ?)"
            401, 403 -> "accès refusé : vérifier la clé API, ses restrictions et que l'API Drive est activée"
            404 -> "introuvable : vérifier l'ID du dossier et son partage « Tous ceux qui ont le lien »"
            429 -> "quota dépassé, nouvel essai plus tard"
            else -> "erreur serveur"
        }
        return "$what : HTTP ${resp.code}, $hint" + if (apiMessage.isNotEmpty()) " ($apiMessage)" else ""
    }
}
