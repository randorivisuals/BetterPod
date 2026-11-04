package com.corpsave.betterpod

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The current Google sign-in token (about 1 hour). Refreshed each time you open Videos. */
object DriveSession {
    @Volatile var token: String? = null
}

/** A file or folder on your Google Drive. */
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
) {
    val isFolder get() = mimeType == "application/vnd.google-apps.folder"
    val isVideo get() = mimeType.startsWith("video/")

    /** Direct download link. Video players can stream from it (with the sign-in token attached). */
    val streamUrl get() = "https://www.googleapis.com/drive/v3/files/$id?alt=media&supportsAllDrives=true"

    fun sizeLabel(): String = when {
        sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1e9)
        sizeBytes >= 1_000_000 -> "%.0f MB".format(sizeBytes / 1e6)
        else -> ""
    }
}

/**
 * Talks to the Google Drive REST API directly (plain HTTPS + JSON), so we don't need
 * Google's big client library. Read-only: BetterPod can list and play, never change files.
 * All functions do network work: call them off the main thread.
 */
object DriveApi {
    const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private const val FOLDER = "application/vnd.google-apps.folder"
    private const val SHORTCUT = "application/vnd.google-apps.shortcut"
    private const val FIELDS = "nextPageToken,files(id,name,mimeType,size,videoMediaMetadata(durationMillis),shortcutDetails(targetId,targetMimeType))"

    /** Folders, videos, and shortcuts (which we follow to their real folder/video). */
    private const val BROWSABLE = "(mimeType='$FOLDER' or mimeType='$SHORTCUT' or mimeType contains 'video/')"

    /** Folders + videos inside one folder ("root" = My Drive). Folders first, then A–Z. */
    fun listFolder(token: String, folderId: String = "root"): List<DriveFile> =
        query(token, "'$folderId' in parents and trashed=false and $BROWSABLE", "folder,name")

    /** Every video in My Drive AND all shared drives you belong to, newest first. */
    fun allVideos(token: String): List<DriveFile> =
        query(token, "mimeType contains 'video/' and trashed=false", "modifiedTime desc", corpora = "allDrives")

    /** Videos and folders whose name contains the text, anywhere your account can see. */
    fun search(token: String, text: String): List<DriveFile> {
        // Each word separately, so "ryan gordon" still finds "Gordon Ryan".
        // Drive matches the START of words: "gord" works, "ordon" doesn't, typos don't.
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val nameFilter = words.joinToString(" and ") { w ->
            val safe = w.replace("\\", "\\\\").replace("'", "\\'") // escape for Drive's query language
            "name contains '$safe'"
        }
        return query(token, "$nameFilter and trashed=false and $BROWSABLE", "folder,name", max = 200, corpora = "allDrives")
    }

    /** The shared drives (team drives) your account is a member of. Returned as folders. */
    fun sharedDrives(token: String): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val url = "https://www.googleapis.com/drive/v3/drives?pageSize=100&fields=" + enc("nextPageToken,drives(id,name)") +
                (pageToken?.let { "&pageToken=" + enc(it) } ?: "")
            val json = get(token, url)
            val drives = json.optJSONArray("drives")
            if (drives != null) for (i in 0 until drives.length()) {
                val d = drives.getJSONObject(i)
                out += DriveFile(d.getString("id"), d.optString("name", "Shared drive"), FOLDER, 0L, 0L)
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out.sortedBy { it.name.lowercase() }
    }

    /** Folders and videos other people shared with you (open a shared folder to browse inside). */
    fun sharedVideos(token: String): List<DriveFile> =
        query(token, "sharedWithMe and trashed=false and $BROWSABLE", "folder,name")

    private fun query(token: String, q: String, orderBy: String, max: Int = 1000, corpora: String? = null): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("https://www.googleapis.com/drive/v3/files?pageSize=200")
                // Without these two flags Google hides everything that lives in shared drives.
                append("&supportsAllDrives=true&includeItemsFromAllDrives=true")
                corpora?.let { append("&corpora=").append(it) }
                append("&q=").append(enc(q))
                append("&orderBy=").append(enc(orderBy))
                append("&fields=").append(enc(FIELDS))
                pageToken?.let { append("&pageToken=").append(enc(it)) }
            }
            val json = get(token, url)
            val files = json.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    var id = f.getString("id")
                    var mime = f.optString("mimeType")
                    // A shortcut points at the real file/folder: use the target instead.
                    if (mime == SHORTCUT) {
                        val target = f.optJSONObject("shortcutDetails") ?: continue
                        id = target.optString("targetId")
                        mime = target.optString("targetMimeType")
                        if (mime != FOLDER && !mime.startsWith("video/")) continue
                    }
                    out += DriveFile(
                        id = id,
                        name = f.optString("name", "Untitled"),
                        mimeType = mime,
                        sizeBytes = f.optString("size", "0").toLongOrNull() ?: 0L,
                        durationMs = f.optJSONObject("videoMediaMetadata")?.optString("durationMillis")?.toLongOrNull() ?: 0L,
                    )
                }
            }
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null && out.size < max)
        return out
    }

    private fun get(token: String, url: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            if (code == 401) throw IOException("Google sign-in expired. Open Videos again.")
            if (code != 200) throw IOException("Drive error $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
