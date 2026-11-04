package com.corpsave.betterpod

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/** Builds a video player that streams from Google Drive, sending the sign-in token with each request. */
@OptIn(UnstableApi::class)
fun driveVideoPlayer(context: Context, file: DriveFile, startMs: Long = 0L): ExoPlayer {
    // Downloaded? Play the file on the phone: works offline, no sign-in needed.
    Downloads.localFile(context, file.id)?.let { local ->
        return ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(local)))
            if (startMs > 0) seekTo(startMs)
            prepare()
        }
    }
    val http = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${DriveSession.token}"))
    return ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(http))
        .build()
        .apply {
            setMediaItem(MediaItem.Builder().setUri(file.streamUrl).setMediaId(file.id).build())
            if (startMs > 0) seekTo(startMs)
            prepare()
        }
}

/**
 * Full-screen video: landscape, no status/navigation bars, standard on-screen controls
 * (tap the video to show play/pause, the seek bar and the timer).
 */
class VideoActivity : ComponentActivity() {

    private var player: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val file = DriveFile(
            id = intent.getStringExtra(EXTRA_ID) ?: return finish(),
            name = intent.getStringExtra(EXTRA_NAME) ?: "",
            mimeType = "video/*",
            sizeBytes = 0,
            durationMs = 0,
        )
        val p = driveVideoPlayer(this, file, intent.getLongExtra(EXTRA_POSITION, 0L))
        player = p

        val view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            player = p
            keepScreenOn = true
            setShowNextButton(false)
            setShowPreviousButton(false)
        }
        setContentView(view)
        p.play()
    }

    override fun onStop() {
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_POSITION = "position"

        fun open(context: Context, file: DriveFile, positionMs: Long) {
            context.startActivity(
                Intent(context, VideoActivity::class.java)
                    .putExtra(EXTRA_ID, file.id)
                    .putExtra(EXTRA_NAME, file.name)
                    .putExtra(EXTRA_POSITION, positionMs)
            )
        }
    }
}
