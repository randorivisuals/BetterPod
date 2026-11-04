package com.corpsave.betterpod

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** One song on the phone. */
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
    val fileName: String = "",
    /** The folder the file sits in, e.g. "Music/Chill Mix/". Each folder becomes a playlist. */
    val folderPath: String = "",
) {
    /** Just the last part of the folder path: "Chill Mix". */
    val folder: String get() = folderPath.trimEnd('/').substringAfterLast('/').ifEmpty { "Phone" }

    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri: Uri get() = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)

    /** What the player needs: where the file is + what to show on the lock screen. */
    fun toMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(uri) // the song file itself; Android reads its embedded cover art
                .build()
        )
        .build()
}

/**
 * Reads every song Android knows about (MediaStore = the phone's built-in media index).
 * Put music in the phone's Music folder (USB from your PC works) and it shows up here.
 */
object Library {
    fun load(context: Context): List<Song> {
        val songs = ArrayList<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DISPLAY_NAME,   // file name, e.g. "03 - Song.mp3"
            MediaStore.Audio.Media.RELATIVE_PATH,  // folder, e.g. "Music/Chill Mix/"
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val track = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val file = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                songs += Song(
                    id = c.getLong(id),
                    title = c.getString(title) ?: "Unknown",
                    artist = c.getString(artist)?.takeUnless { it == "<unknown>" } ?: "Unknown Artist",
                    album = c.getString(album) ?: "Unknown Album",
                    albumId = c.getLong(albumId),
                    durationMs = c.getLong(duration),
                    // MediaStore stores disc*1000 + track; keep just the track number.
                    track = c.getInt(track) % 1000,
                    fileName = c.getString(file) ?: "",
                    folderPath = c.getString(path) ?: "",
                )
            }
        }
        return songs
    }

    /**
     * Every folder that holds music becomes a playlist, named after the folder.
     * Songs keep the folder's file order (natural sort, so "2 x" comes before "10 x").
     * Two different folders with the same name get their parent added: "Gym / Chill" vs "Road Trip / Chill".
     */
    fun playlists(songs: List<Song>): List<Pair<String, List<Song>>> {
        val groups = songs.groupBy { it.folderPath }
        val nameCount = groups.values.groupingBy { it.first().folder }.eachCount()
        return groups.map { (path, tracks) ->
            val parts = path.trimEnd('/').split('/').filter { it.isNotEmpty() }
            val short = tracks.first().folder
            val name = if ((nameCount[short] ?: 0) > 1 && parts.size >= 2) parts.takeLast(2).joinToString(" / ") else short
            name to tracks.sortedWith { a, b -> naturalCompare(a.fileName, b.fileName) }
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.first })
    }

    /** Compares text so that numbers inside it sort by value: "track2" < "track10". */
    private fun naturalCompare(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val x = re.findAll(a.lowercase()).map { it.value }.toList()
        val y = re.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit())
                compareValues(p.trimStart('0').length, q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
            else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
