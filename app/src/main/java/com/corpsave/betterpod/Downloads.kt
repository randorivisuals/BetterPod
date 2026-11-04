package com.corpsave.betterpod

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline videos: copies Drive videos onto the phone so they play with no internet.
 *
 * Where: the app's own Movies folder (no storage permission needed). Uninstalling BetterPod
 * deletes them. File names start with the Drive file id, so the player can find the local copy.
 * Downloads keep going while you browse, as long as BetterPod stays open.
 */
object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Drive id -> 0..1 while downloading. The screen redraws as these change. */
    val progress = mutableStateMapOf<String, Float>()

    /** Drive id -> full path of every finished download (phone or SD card). */
    val done = mutableStateMapOf<String, String>()

    /**
     * BetterPod's own folders on every storage the phone has: [0] = phone, [1] = microSD (if inserted).
     * Apps can always write to their own folder on the SD card, no permission needed.
     */
    private fun dirs(c: Context): List<File> =
        c.getExternalFilesDirs(Environment.DIRECTORY_MOVIES)
            .filterNotNull()
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            .map { File(it, "BetterPod").apply { mkdirs() } }

    fun hasSdCard(c: Context) = dirs(c).size > 1

    private fun prefs(c: Context) = c.getSharedPreferences("betterpod", Context.MODE_PRIVATE)

    /** true = save new downloads to the SD card. */
    fun useSdCard(c: Context) = prefs(c).getBoolean("dl_sd", false)

    fun setUseSdCard(c: Context, on: Boolean) = prefs(c).edit().putBoolean("dl_sd", on).apply()

    /** Where new downloads go. Falls back to the phone if the SD card is missing. */
    private fun dir(c: Context): File {
        val all = dirs(c)
        return if (useSdCard(c) && all.size > 1) all[1] else all.first()
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_")

    /** Scan phone + SD card once at start-up. */
    fun refresh(c: Context) {
        done.clear()
        for (d in dirs(c)) {
            d.listFiles()?.filter { it.name.contains("__") && !it.name.endsWith(".part") }?.forEach {
                done[it.name.substringBefore("__")] = it.absolutePath
            }
        }
    }

    fun isDownloaded(id: String) = done.containsKey(id)

    fun localFile(c: Context, id: String): File? = done[id]?.let { File(it) }?.takeIf { it.exists() }

    /** Everything downloaded, newest first, shaped like Drive files so the menus can reuse them. */
    fun all(c: Context): List<DriveFile> =
        done.entries.mapNotNull { (id, path) ->
            val f = File(path)
            if (!f.exists()) null else DriveFile(id, f.name.substringAfter("__"), "video/mp4", f.length(), 0L) to f.lastModified()
        }.sortedByDescending { it.second }.map { it.first }

    fun delete(c: Context, id: String) {
        localFile(c, id)?.delete()
        done.remove(id)
    }

    /** Free space left for downloads, in bytes. */
    fun freeBytes(c: Context): Long = dir(c).usableSpace

    fun start(c: Context, token: String, file: DriveFile, onFinished: (ok: Boolean, error: String?) -> Unit) {
        if (progress.containsKey(file.id) || isDownloaded(file.id)) return
        val app = c.applicationContext
        if (file.sizeBytes > 0 && file.sizeBytes > freeBytes(app) - 200_000_000L) {
            onFinished(false, "Not enough space on the phone")
            return
        }
        progress[file.id] = 0f
        DownloadService.ensureRunning(app) // keeps going if you switch apps or lock the phone
        val target = dir(app)
        scope.launch {
            val finalName = file.id + "__" + safe(file.name)
            val finalFile = File(target, finalName)
            val part = File(target, "$finalName.part") // half-finished file; renamed when complete
            val result = runCatching {
                val conn = URL(file.streamUrl).openConnection() as HttpURLConnection
                try {
                    conn.setRequestProperty("Authorization", "Bearer $token")
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    if (conn.responseCode != 200) throw IOException("Drive error ${conn.responseCode}")
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: file.sizeBytes
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(256 * 1024)
                            var got = 0L
                            var lastUpdate = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastUpdate > 300) {
                                    progress[file.id] = (got.toFloat() / total).coerceIn(0f, 1f)
                                    lastUpdate = now
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
                if (!part.renameTo(finalFile)) throw IOException("Couldn't save the file")
            }
            progress.remove(file.id)
            if (result.isSuccess) done[file.id] = finalFile.absolutePath else part.delete()
            withContext(Dispatchers.Main) { onFinished(result.isSuccess, result.exceptionOrNull()?.message) }
        }
    }
}
