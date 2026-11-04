package com.corpsave.betterpod

import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Makes YouTube Music look like a normal Android music player.
 *
 * Why: BetterPod's Now Playing screen, click wheel, lock screen and Bluetooth buttons already know
 * how to drive a "Player". So we dress the hidden YouTube page up as one, and everything just works.
 */
class YtMusicService : MediaSessionService() {
    private var session: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        YtMusic.init(this)
        // Each player in the app needs its own session id (the local-music one uses the default).
        session = MediaSession.Builder(this, YtPlayer()).setId("youtube-music").build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: keep playing if a song is on, otherwise shut down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

/**
 * A "player" whose real work happens in the YouTube page.
 *
 * Trick: we always report three items — [previous, CURRENT, next] — with CURRENT in the middle.
 * Android only offers next/previous buttons when there's somewhere to go, and choosing item 0 or 2
 * becomes YouTube's own previous/next.
 */
@OptIn(UnstableApi::class)
private class YtPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    private val onChange: () -> Unit = { invalidateState() }

    init { YtMusic.addListener(onChange) }

    override fun getState(): SimpleBasePlayer.State {
        val s = YtMusic.state
        val commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_RELEASE,
        ).build()

        if (s.videoId.isEmpty()) {
            return SimpleBasePlayer.State.Builder().setAvailableCommands(commands).setPlaybackState(Player.STATE_IDLE).build()
        }

        val meta = MediaMetadata.Builder()
            .setTitle(if (s.ad) "Skipping ad…" else s.title.ifEmpty { "Loading…" })
            .setArtist(if (s.ad) "" else s.artist)
            .setAlbumTitle(if (s.ad) "" else s.album)
            .setArtworkUri(s.art.takeIf { it.startsWith("http") }?.let { Uri.parse(it) })
            .build()

        fun item(uid: String, md: MediaMetadata, durMs: Long) = SimpleBasePlayer.MediaItemData.Builder(uid)
            .setMediaItem(MediaItem.Builder().setMediaId(uid).build())
            .setMediaMetadata(md)
            .setDurationUs(if (durMs > 0) durMs * 1000 else C.TIME_UNSET)
            .setIsSeekable(true)
            .build()

        return SimpleBasePlayer.State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(
                item("yt:prev", MediaMetadata.EMPTY, 0),
                item("yt:" + s.videoId, meta, s.durMs),
                item("yt:next", MediaMetadata.EMPTY, 0),
            ))
            .setCurrentMediaItemIndex(1)
            .setContentPositionMs(s.posMs)
            .setPlayWhenReady(s.playing || s.loading, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(if (s.loading) Player.STATE_BUFFERING else Player.STATE_READY)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) YtMusic.play() else YtMusic.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when {
            mediaItemIndex >= 2 -> YtMusic.next()
            mediaItemIndex == 0 -> YtMusic.prev()
            else -> YtMusic.seekTo(if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0))
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        YtMusic.removeListener(onChange)
        return Futures.immediateVoidFuture()
    }
}
