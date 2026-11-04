package com.corpsave.betterpod

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import android.graphics.BitmapFactory
import android.view.ViewGroup
import android.widget.FrameLayout
import java.net.URL

// ------------------------------------------------------------------ Look
private val BodyTop = Color(0xFF1D1D20)
private val BodyBottom = Color(0xFF0B0B0D)
private val ScreenBg = Color(0xFFEDEBE4)      // warm "LCD" paper
private val ScreenInk = Color(0xFF1C1C1E)
private val ScreenDim = Color(0xFF7A776F)
private val SelectBg = Color(0xFF2B2B2E)
private val SelectInk = Color(0xFFF4F2EC)
private const val ROWS = 7                    // menu rows visible on the screen

// ------------------------------------------------------------------ Menu model
/** One line in a menu. label is a function so settings can show their live value. */
class MenuItem(val label: () -> String, val hasChildren: Boolean = false, val onSelect: () -> Unit)

/** A menu screen. Remembers which row is highlighted and the scroll window. */
class MenuPage(val title: String, val items: List<MenuItem>, val rows: Int = ROWS) {
    var selected by mutableIntStateOf(0)
    var first by mutableIntStateOf(0)

    /** Move the highlight (wheel click or finger swipe) and keep it on screen. */
    fun move(steps: Int) {
        if (items.isEmpty()) return
        selected = (selected + steps).coerceIn(0, items.size - 1)
        if (selected < first) first = selected
        if (selected >= first + rows) first = selected - rows + 1
    }
}

/** Marker for the Now Playing screen on the navigation stack. */
object NowPlayingPage

/** Search screen: a text box on top, live results underneath. */
/** A built-in wheel game (Breaker, Snake, Stack). */
class GamePage(val game: Game)

/** A bundled open-source web game. */
class WebGamePage(val ctrl: WebGameController)

class SearchPage(val drive: Boolean = false, val yt: Boolean = false) {
    var query by mutableStateOf("")
    var results by mutableStateOf(MenuPage("Search", listOf(MenuItem({ "(Type to search)" }) {}), rows = 5))
}

/** Shown while Drive is loading a folder. */
class LoadingPage(val title: String)

/** A Drive video playing on the small screen. Center button = full screen. */
class VideoPage(val file: DriveFile)

class MainActivity : ComponentActivity() {
    private var future: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var ytFuture: ListenableFuture<MediaController>? = null
    private var ytController by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { BetterPodApp(controller, ytController) { connectYt() } } }
    }

    /** Start YouTube Music (first time you open it; afterwards it starts with the app). */
    private fun connectYt() {
        getSharedPreferences("betterpod", MODE_PRIVATE).edit().putBoolean("yt_enabled", true).apply()
        YtMusic.init(this)
        YtMusic.setHost(this)
        if (ytFuture != null) return
        val token = SessionToken(this, ComponentName(this, YtMusicService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        ytFuture = f
        f.addListener({ ytController = f.get() }, ContextCompat.getMainExecutor(this))
    }

    // Connect to the background player while the app is visible.
    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({ controller = f.get() }, ContextCompat.getMainExecutor(this))
        if (getSharedPreferences("betterpod", MODE_PRIVATE).getBoolean("yt_enabled", false)) connectYt()
    }

    override fun onStop() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        ytFuture?.let { MediaController.releaseFuture(it) }
        ytFuture = null
        ytController = null
        YtMusic.setHost(null) // don't hold on to this screen while in the background
        super.onStop()
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
fun BetterPodApp(controllerArg: MediaController?, ytArg: MediaController? = null, connectYt: () -> Unit = {}) {
    // Two players: your songs (local) and YouTube Music. "controller" is whichever one you're using,
    // so Now Playing and the wheel buttons drive the right one. Connections are recreated each time
    // the app comes back to the front, so always use the latest.
    var useYt by remember { mutableStateOf(false) }
    val localController by rememberUpdatedState(controllerArg)
    val ytController by rememberUpdatedState(ytArg)
    val controller by rememberUpdatedState(if (useYt && ytArg != null) ytArg else controllerArg)
    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }

    // ---------------- Permission to read music files
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(permission) }

    // Android 13+: allow the playback notification (lock-screen / shade controls).
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(granted) {
        if (granted && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var songs by remember { mutableStateOf<List<Song>?>(null) }
    LaunchedEffect(granted) { if (granted) songs = withContext(Dispatchers.IO) { Library.load(context) } }

    // ---------------- Player state (kept in sync with the background player)
    var isPlaying by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var album by remember { mutableStateOf("") }
    var mediaId by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var index by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var shuffle by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var art by remember { mutableStateOf<Bitmap?>(null) }
    var artUrl by remember { mutableStateOf<String?>(null) }

    DisposableEffect(controller) {
        val c = controller
        fun sync(player: Player) {
                isPlaying = player.isPlaying
                val md = player.mediaMetadata
                title = md.title?.toString() ?: ""
                artist = md.artist?.toString() ?: ""
                album = md.albumTitle?.toString() ?: ""
                mediaId = player.currentMediaItem?.mediaId
                artUrl = md.artworkUri?.toString()
                duration = player.duration.coerceAtLeast(0L)
                index = player.currentMediaItemIndex
                count = player.mediaItemCount
                shuffle = player.shuffleModeEnabled
                repeatMode = player.repeatMode
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync(player)
        }
        if (c != null) {
            c.addListener(listener)
            sync(c)
        }
        onDispose { c?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) { position = controller?.currentPosition ?: 0L; delay(500) }
    }
    // Album art for the current song
    LaunchedEffect(mediaId, artUrl) {
        val id = mediaId?.toLongOrNull()
        val url = artUrl?.takeIf { it.startsWith("http") }
        art = withContext(Dispatchers.IO) {
            runCatching {
                when {
                    id != null -> context.contentResolver.loadThumbnail(
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id), Size(320, 320), null
                    )
                    url != null -> URL(url).openStream().use { BitmapFactory.decodeStream(it) } // YouTube Music cover
                    else -> null
                }
            }.getOrNull()
        }
    }

    var battery by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        val bm = context.getSystemService(BatteryManager::class.java)
        while (true) { battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY); delay(60_000) }
    }

    var haptics by remember { mutableStateOf(true) }
    var sdChoice by remember { mutableStateOf(Downloads.useSdCard(context)) }
    var scrubbing by remember { mutableStateOf(false) }
    var volumeTick by remember { mutableIntStateOf(0) }
    var showVolume by remember { mutableStateOf(false) }
    LaunchedEffect(volumeTick) { if (volumeTick > 0) { delay(1500); showVolume = false } }

    // ---------------- Navigation
    val stack = remember { mutableStateListOf<Any>() }
    fun push(page: Any) { stack.add(page) }
    fun back() { scrubbing = false; if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    // ---------------- Google Drive (videos)
    val scope = rememberCoroutineScope()
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    var videoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Downloads.refresh(context) } }

    // Google's sign-in screen returns here the first time; afterwards access is silent.
    val pendingAuth = remember { mutableStateOf<((String?) -> Unit)?>(null) }
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        // Show Google's real reason instead of a generic "cancelled", so setup problems are obvious.
        val token = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(res.data).accessToken
        } catch (e: com.google.android.gms.common.api.ApiException) {
            val why = when (e.statusCode) {
                10 -> "Code 10: app not recognized. Check the Android OAuth client's package name + SHA-1."
                12501, 16 -> "Code ${e.statusCode}: sign-in closed. If you didn't close it, check you're a Test user."
                7 -> "Code 7: no internet connection."
                else -> "Code ${e.statusCode}: ${e.message}"
            }
            toast("Google sign-in failed. $why")
            null
        } catch (e: Exception) {
            toast("Google sign-in failed (result ${res.resultCode}): ${e.message}")
            null
        }
        pendingAuth.value?.invoke(token)
        pendingAuth.value = null
    }

    // Which Google account to browse. Saved on the phone so it sticks between launches.
    val prefs = remember { context.getSharedPreferences("betterpod", android.content.Context.MODE_PRIVATE) }
    var driveAccount by remember { mutableStateOf(prefs.getString("drive_account", null)) }
    val chooseAccount = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val name = res.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (name != null) {
            prefs.edit().putString("drive_account", name).apply()
            driveAccount = name
            DriveSession.token = null
            toast("Using $name")
        }
    }
    fun pickAccount() {
        // Android's own account picker: lists every Google account on the phone.
        chooseAccount.launch(
            AccountManager.newChooseAccountIntent(
                driveAccount?.let { Account(it, "com.google") }, null, arrayOf("com.google"),
                null, null, null, null
            )
        )
    }

    /** Get a fresh Drive token (asks once per account, then silent), then run the action with it. */
    fun withDrive(action: (String) -> Unit) {
        val builder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveApi.SCOPE)))
        driveAccount?.let { builder.setAccount(Account(it, "com.google")) }
        val request = builder.build()
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { result ->
                val intent = result.pendingIntent
                if (result.hasResolution() && intent != null) {
                    pendingAuth.value = { t ->
                        if (t != null) { DriveSession.token = t; action(t) }
                    }
                    authLauncher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                } else {
                    val t = result.accessToken
                    if (t != null) { DriveSession.token = t; action(t) } else toast("Google sign-in failed")
                }
            }
            .addOnFailureListener { e ->
                val code = (e as? com.google.android.gms.common.api.ApiException)?.statusCode
                toast("Google sign-in failed" + (code?.let { " (code $it)" } ?: "") + ": ${e.message}")
            }
    }

    /** Label for a video in a list: ✓ = on the phone, ↓ 42% = downloading. */
    fun videoLabel(f: DriveFile): String {
        val p = Downloads.progress[f.id]
        return when {
            p != null -> "↓ ${(p * 100).toInt()}%  ${f.name}"
            Downloads.isDownloaded(f.id) -> "✓ ${f.name}"
            else -> f.name
        }
    }

    /** Play / Download / Remove for one video. */
    fun videoOptions(f: DriveFile) = MenuPage(f.name, listOf(
        MenuItem({ "Play" }, hasChildren = true) {
            controller?.pause() // stop the music while a video plays
            push(VideoPage(f))
        },
        MenuItem({
            val p = Downloads.progress[f.id]
            when {
                p != null -> "Downloading… ${(p * 100).toInt()}%"
                Downloads.isDownloaded(f.id) -> "Remove download"
                else -> "Download for offline" + (f.sizeLabel().takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
            }
        }) {
            when {
                Downloads.progress.containsKey(f.id) -> toast("Already downloading")
                Downloads.isDownloaded(f.id) -> { Downloads.delete(context, f.id); toast("Removed from phone") }
                else -> withDrive { token ->
                    toast("Downloading ${f.name}…")
                    Downloads.start(context, token, f) { ok, err ->
                        toast(if (ok) "Ready offline: ${f.name}" else "Download failed: ${err ?: "unknown error"}")
                    }
                }
            }
        },
    ))

    /** Videos saved on the phone. Opens without internet or sign-in. */
    fun downloadedPage(): MenuPage {
        val files = Downloads.all(context)
        val items = if (files.isEmpty()) listOf(MenuItem({ "(Nothing downloaded yet)" }) {})
        else files.map { f -> MenuItem({ "✓ ${f.name}" }, hasChildren = true) { push(videoOptions(f)) } }
        return MenuPage("Downloaded", items)
    }

    /** Load a Drive listing in the background, then show it as a menu. */
    fun openDrive(title: String, emptyText: String = "(No videos here)", fetch: (String) -> List<DriveFile>) {
        withDrive { token ->
            val loading = LoadingPage(title)
            push(loading)
            scope.launch {
                val result = runCatching { withContext(Dispatchers.IO) { fetch(token) } }
                if (stack.lastOrNull() !== loading) return@launch // you pressed MENU while it loaded
                stack.removeAt(stack.lastIndex)
                result.onFailure { toast(it.message ?: "Couldn't load Google Drive") }
                result.onSuccess { files ->
                    val items = if (files.isEmpty()) listOf(MenuItem({ emptyText }) {})
                    else files.map { f ->
                        if (f.isFolder) MenuItem({ "▸ " + f.name }, hasChildren = true) {
                            openDrive(f.name) { t -> DriveApi.listFolder(t, f.id) }
                        } else MenuItem({ videoLabel(f) }, hasChildren = true) { push(videoOptions(f)) }
                    }
                    push(MenuPage(title, items))
                }
            }
        }
    }

    // Drive search: waits until you pause typing, then asks Google once.
    var driveSearchJob by remember { mutableStateOf<Job?>(null) }
    fun driveSearch(page: SearchPage, q: String) {
        driveSearchJob?.cancel()
        val text = q.trim()
        if (text.length < 2) {
            page.results = MenuPage("Search Drive", listOf(MenuItem({ "(Type at least 2 letters)" }) {}), rows = 5)
            return
        }
        driveSearchJob = scope.launch {
            delay(500) // still typing? this gets cancelled and restarted
            page.results = MenuPage("Search Drive", listOf(MenuItem({ "Searching…" }) {}), rows = 5)
            withDrive { token ->
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { DriveApi.search(token, text) } }
                    if (page.query.trim() != text) return@launch // you've typed more since
                    val files = result.getOrElse { toast(it.message ?: "Search failed"); emptyList() }
                    val items = if (files.isEmpty()) listOf(MenuItem({ "(No matches)" }) {})
                    else files.map { f ->
                        if (f.isFolder) MenuItem({ "▸ " + f.name }, hasChildren = true) {
                            openDrive(f.name) { t -> DriveApi.listFolder(t, f.id) }
                        } else MenuItem({ videoLabel(f) }, hasChildren = true) { push(videoOptions(f)) }
                    }
                    page.results = MenuPage("Search Drive", items, rows = 5)
                }
            }
        }
    }

    fun videosPage() = MenuPage("Videos", listOf(
        MenuItem({ "Account: " + (driveAccount ?: "choose…") }, hasChildren = true) { pickAccount() },
        MenuItem({ "Search Drive" }, hasChildren = true) { push(SearchPage(drive = true)) },
        MenuItem({ "Downloaded (offline)" }, hasChildren = true) { push(downloadedPage()) },
        MenuItem({ "My Drive" }, hasChildren = true) { openDrive("My Drive") { DriveApi.listFolder(it) } },
        MenuItem({ "All Videos" }, hasChildren = true) { openDrive("All Videos") { DriveApi.allVideos(it) } },
        MenuItem({ "Shared Drives" }, hasChildren = true) { openDrive("Shared Drives", "(No shared drives on this account)") { DriveApi.sharedDrives(it) } },
        MenuItem({ "Shared with Me" }, hasChildren = true) { openDrive("Shared with Me") { DriveApi.sharedVideos(it) } },
    ))

    fun fullScreen(page: VideoPage) {
        val p = videoPlayer
        val position = p?.currentPosition ?: 0L
        p?.pause()
        VideoActivity.open(context, page.file, position)
    }

    fun play(list: List<Song>, start: Int, shuffled: Boolean = false) {
        val c = localController ?: return
        ytController?.pause()
        useYt = false
        c.shuffleModeEnabled = shuffled
        c.setMediaItems(list.map { it.toMediaItem() }, start, 0L)
        c.prepare()
        c.play()
        push(NowPlayingPage)
    }

    fun songsPage(name: String, list: List<Song>) =
        MenuPage(name, list.mapIndexed { i, s -> MenuItem({ s.title }) { play(list, i) } })

    fun albumsPage(name: String, list: List<Song>): MenuPage {
        val albums = list.groupBy { it.album }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        return MenuPage(name, albums.map { (albumName, tracks) ->
            MenuItem({ albumName }, hasChildren = true) { push(songsPage(albumName, tracks.sortedBy { it.track })) }
        })
    }

    // Songs removed from a playlist. Saved as "folder|songId" so the file itself is never touched:
    // it stays in Songs, Artists, Albums and Search, and still exists on the phone.
    var hidden by remember { mutableStateOf(prefs.getStringSet("playlist_hidden", emptySet())!!.toSet()) }
    fun hideKey(s: Song) = s.folderPath + "|" + s.id
    fun setHidden(s: Song, remove: Boolean) {
        hidden = if (remove) hidden + hideKey(s) else hidden - hideKey(s)
        prefs.edit().putStringSet("playlist_hidden", hidden).apply()
    }

    // Kotlin can't call a local function before it's written, and these two call each other,
    // so the editor reaches "open playlist" through this variable (filled in just below).
    var reopenPlaylist: (String, List<Song>) -> Unit = { _, _ -> }

    /** Click a song to remove it (✕) or put it back (✓). Done shows the updated playlist. */
    fun editPlaylistPage(name: String, tracks: List<Song>) = MenuPage("Edit: $name", listOf(
        MenuItem({ "✓ Done" }) { back(); reopenPlaylist(name, tracks) },
        MenuItem({ "Restore all songs" }) { tracks.forEach { setHidden(it, false) } },
    ) + tracks.map { s ->
        MenuItem({ (if (hideKey(s) in hidden) "✕  " else "✓  ") + s.title }) { setHidden(s, hideKey(s) !in hidden) }
    })

    /** One playlist: Play All, Shuffle, its songs, then Edit at the bottom. */
    fun openPlaylist(name: String, tracks: List<Song>) {
        val visible = tracks.filter { hideKey(it) !in hidden }
        val songItems = if (visible.isEmpty()) listOf(MenuItem({ "(All songs removed)" }) {})
            else songsPage(name, visible).items
        push(MenuPage(name, listOf(
            MenuItem({ "▶ Play All" }) { if (visible.isNotEmpty()) play(visible, 0) },
            MenuItem({ "⤮ Shuffle" }) { if (visible.isNotEmpty()) play(visible, visible.indices.random(), shuffled = true) },
        ) + songItems + MenuItem({ "✎ Edit Playlist" }, hasChildren = true) {
            back()                       // swap this page for the editor
            push(editPlaylistPage(name, tracks))
        }))
    }

    reopenPlaylist = { n, t -> openPlaylist(n, t) }

    /** Playlists = your music folders. */
    fun playlistsPage(list: List<Song>): MenuPage {
        val lists = Library.playlists(list)
        if (lists.isEmpty()) return MenuPage("Playlists", listOf(MenuItem({ "(No music folders found)" }) {}))
        return MenuPage("Playlists", lists.map { (name, tracks) ->
            MenuItem({ "$name  (${tracks.count { hideKey(it) !in hidden }})" }, hasChildren = true) { openPlaylist(name, tracks) }
        })
    }

    fun artistsPage(list: List<Song>): MenuPage {
        val artists = list.groupBy { it.artist }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        return MenuPage("Artists", artists.map { (artistName, tracks) ->
            MenuItem({ artistName }, hasChildren = true) {
                val allSongs = MenuItem({ "All Songs" }, hasChildren = true) { push(songsPage(artistName, tracks)) }
                push(MenuPage(artistName, listOf(allSongs) + albumsPage(artistName, tracks).items))
            }
        })
    }

    fun settingsPage() = MenuPage("Settings", listOf(
        MenuItem({ "Shuffle: " + if (shuffle) "On" else "Off" }) { localController?.shuffleModeEnabled = !shuffle },
        MenuItem({
            "Repeat: " + when (repeatMode) { Player.REPEAT_MODE_ALL -> "All"; Player.REPEAT_MODE_ONE -> "One"; else -> "Off" }
        }) {
            localController?.repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        },
        MenuItem({ "Wheel clicks: " + if (haptics) "On" else "Off" }) { haptics = !haptics },
        MenuItem({ "Save downloads to: " + if (sdChoice) "SD card" else "Phone" }) {
            if (!Downloads.hasSdCard(context)) {
                toast("No SD card found")
            } else {
                sdChoice = !sdChoice
                Downloads.setUseSdCard(context, sdChoice)
                toast("New downloads go to the " + if (sdChoice) "SD card" else "phone")
            }
        },
    ))

    /** Songs whose title, artist or album contains the search text. */
    fun searchResults(q: String): MenuPage {
        val text = q.trim()
        if (text.isEmpty()) return MenuPage("Search", listOf(MenuItem({ "(Type to search)" }) {}), rows = 5)
        val matches = (songs ?: emptyList()).filter {
            it.title.contains(text, true) || it.artist.contains(text, true) || it.album.contains(text, true)
        }.take(300)
        if (matches.isEmpty()) return MenuPage("Search", listOf(MenuItem({ "(No matches)" }) {}), rows = 5)
        return MenuPage("Search", matches.mapIndexed { i, song ->
            MenuItem({ song.title + "  ·  " + song.artist }) { play(matches, i) }
        }, rows = 5)
    }

    // ---------------- YouTube Music
    var showYtLogin by remember { mutableStateOf(false) }

    /** Play a YouTube Music song. With a playlist id, next/previous walk through that playlist. */
    fun ytPlay(videoId: String, playlistId: String?) {
        if (ytController == null) { connectYt(); toast("Starting YouTube Music… press again in a second"); return }
        localController?.pause()
        useYt = true
        YtMusic.playSong(videoId, playlistId)
        push(NowPlayingPage)
    }

    // Same trick: list rows open more lists, so they reach ytListPage through this variable.
    var openYtList: (String, String?, suspend () -> List<YtMusic.Item>) -> Unit = { _, _, _ -> }

    /** Song rows play; album / playlist / artist rows open another list. */
    fun ytMenuItems(list: List<YtMusic.Item>, playlistId: String?): List<MenuItem> =
        if (list.isEmpty()) listOf(MenuItem({ if (YtMusic.signedIn()) "(Nothing here)" else "(Sign in to see your library)" }) {})
        else list.map { item ->
            if (item.videoId.isNotEmpty()) {
                val artist = item.sub.substringBefore(" • ")
                MenuItem({ item.title + if (artist.isNotEmpty()) "  ·  $artist" else "" }) {
                    ytPlay(item.videoId, playlistId ?: item.playlistId.ifEmpty { null })
                }
            } else MenuItem({ "▸ " + item.title }, hasChildren = true) {
                // "VL…" ids are playlists: remember the list so next/previous stay inside it.
                val list2 = if (item.browseId.startsWith("VL")) item.browseId.removePrefix("VL") else null
                openYtList(item.title, list2) { YtMusic.browse(item.browseId) }
            }
        }

    /** Ask YouTube Music for a list in the background, then show it as a menu. */
    fun ytListPage(title: String, playlistId: String?, fetch: suspend () -> List<YtMusic.Item>) {
        connectYt()
        val loading = LoadingPage(title)
        push(loading)
        scope.launch {
            val result = runCatching { fetch() }
            if (stack.lastOrNull() !== loading) return@launch // you pressed MENU while it loaded
            stack.removeAt(stack.lastIndex)
            result.onFailure { toast(it.message ?: "Couldn't reach YouTube Music") }
            result.onSuccess { push(MenuPage(title, ytMenuItems(it, playlistId))) }
        }
    }

    openYtList = { t, p, f -> ytListPage(t, p, f) }

    // Search: waits until you pause typing, then asks once.
    var ytSearchJob by remember { mutableStateOf<Job?>(null) }
    fun ytSearch(page: SearchPage, q: String) {
        ytSearchJob?.cancel()
        val text = q.trim()
        if (text.length < 2) {
            page.results = MenuPage("Search", listOf(MenuItem({ "(Type a song or artist)" }) {}), rows = 5)
            return
        }
        ytSearchJob = scope.launch {
            delay(600)
            page.results = MenuPage("Search", listOf(MenuItem({ "Searching…" }) {}), rows = 5)
            val result = runCatching { YtMusic.search(text) }
            if (page.query.trim() != text) return@launch
            result.onFailure { page.results = MenuPage("Search", listOf(MenuItem({ it.message ?: "Search failed" }) {}), rows = 5) }
            result.onSuccess { page.results = MenuPage("Search", ytMenuItems(it, null), rows = 5) }
        }
    }

    fun ytPage(): MenuPage {
        connectYt()
        return MenuPage("YouTube Music", listOf(
            MenuItem({ "Now Playing" }, hasChildren = true) {
                if (YtMusic.state.videoId.isNotEmpty()) useYt = true
                push(NowPlayingPage)
            },
            MenuItem({ "Search" }, hasChildren = true) { push(SearchPage(yt = true)) },
            MenuItem({ "Liked Songs" }, hasChildren = true) { ytListPage("Liked Songs", "LM") { YtMusic.browse("VLLM") } },
            MenuItem({ "Your Playlists" }, hasChildren = true) { ytListPage("Your Playlists", null) { YtMusic.browse("FEmusic_liked_playlists") } },
            MenuItem({ "Home Mixes" }, hasChildren = true) { ytListPage("Home", null) { YtMusic.browse("FEmusic_home") } },
            MenuItem({ if (YtMusic.signedIn()) "Account: signed in" else "Sign in to Google" }, hasChildren = true) {
                YtMusic.openLogin()
                showYtLogin = true
            },
            MenuItem({ "Skip ads: " + if (YtMusic.skipAds) "On" else "Off" }) { YtMusic.toggleSkipAds(!YtMusic.skipAds) },
            MenuItem({ "Sign out" }) { YtMusic.signOut(); useYt = false; toast("Signed out of YouTube Music") },
        ))
    }

    fun gamesPage() = MenuPage("Games",
        listOf(
            MenuItem({ "Breaker" }, hasChildren = true) { push(GamePage(Breaker(context))) },
            MenuItem({ "Snake" }, hasChildren = true) { push(GamePage(Snake(context))) },
            MenuItem({ "Stack" }, hasChildren = true) { push(GamePage(Stack(context))) },
        ) + WEB_GAMES.map { def ->
            MenuItem({ def.name }, hasChildren = true) { push(WebGamePage(WebGameController(def))) }
        } + listOf(
            MenuItem({ "Credits" }, hasChildren = true) {
                push(MenuPage("Credits", WEB_GAMES.map { d -> MenuItem({ d.name + "  ·  " + d.credit }) {} }))
            },
        )
    )

    fun rootPage(all: List<Song>) = MenuPage("BetterPod", listOf(
        MenuItem({ "Now Playing" }, hasChildren = true) { push(NowPlayingPage) },
        MenuItem({ "Search" }, hasChildren = true) { push(SearchPage()) },
        MenuItem({ "Shuffle Songs" }) { if (all.isNotEmpty()) play(all, all.indices.random(), shuffled = true) },
        MenuItem({ "Playlists" }, hasChildren = true) { push(playlistsPage(all)) },
        MenuItem({ "Songs" }, hasChildren = true) { push(songsPage("Songs", all)) },
        MenuItem({ "Artists" }, hasChildren = true) { push(artistsPage(all)) },
        MenuItem({ "Albums" }, hasChildren = true) { push(albumsPage("Albums", all)) },
        MenuItem({ "YouTube Music" }, hasChildren = true) { push(ytPage()) },
        MenuItem({ "Videos" }, hasChildren = true) { push(videosPage()) },
        MenuItem({ "Games" }, hasChildren = true) { push(gamesPage()) },
        MenuItem({ "Settings" }, hasChildren = true) { push(settingsPage()) },
    ))

    LaunchedEffect(songs) {
        val all = songs ?: return@LaunchedEffect
        stack.clear()
        stack.add(rootPage(all))
    }

    BackHandler(enabled = stack.size > 1) { back() }

    val top = stack.lastOrNull()

    // ---------------- Wheel actions (depend on which screen is showing)
    fun onScroll(steps: Int) {
        when (top) {
            is MenuPage -> top.move(steps)
            is SearchPage -> top.results.move(steps)
            is GamePage -> top.game.spin(steps)
            is WebGamePage -> top.ctrl.spin(steps)
            NowPlayingPage -> {
                val c = controller ?: return
                if (scrubbing) {
                    c.seekTo((c.currentPosition + steps * 5000L).coerceIn(0L, duration))
                } else {
                    repeat(abs(steps)) {
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
                    }
                    showVolume = true
                    volumeTick++
                }
            }
            is VideoPage -> videoPlayer?.let { it.seekTo((it.currentPosition + steps * 10_000L).coerceAtLeast(0L)) }
        }
    }

    /** In a game, wheel taps become game buttons. Returns false when not in a game. */
    fun gameTap(b: Btn): Boolean {
        when (top) {
            is GamePage -> top.game.tap(b)
            is WebGamePage -> top.ctrl.tap(b)
            else -> return false
        }
        return true
    }
    val inGame = top is GamePage || top is WebGamePage
    var padMode by remember { mutableStateOf(false) }

    fun onSelect() {
        if (gameTap(Btn.A)) return
        when (top) {
            is MenuPage -> top.items.getOrNull(top.selected)?.onSelect?.invoke()
            is SearchPage -> top.results.items.getOrNull(top.results.selected)?.onSelect?.invoke()
            // Center = play / pause on Now Playing. (Tap the screen to scrub instead.)
            NowPlayingPage -> controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() }
            is VideoPage -> fullScreen(top)
        }
    }

    // Sign-in screen: Android's back button goes back a page in it, or closes it.
    fun closeYtLogin() {
        showYtLogin = false
        YtMusic.closeLogin()
        toast(if (YtMusic.signedIn()) "Signed in to YouTube Music" else "Not signed in. Search still works.")
    }
    BackHandler(enabled = showYtLogin) {
        val wv = YtMusic.view
        if (wv != null && wv.canGoBack()) wv.goBack() else closeYtLogin()
    }

    // ---------------- Layout: screen on top, wheel below
    Box(Modifier.fillMaxSize()) {
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(BodyTop, BodyBottom)))
            .systemBarsPadding()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))

        // ===== Screen
        Column(
            Modifier.fillMaxWidth().height(if (inGame) 400.dp else 290.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ScreenBg)
                .border(3.dp, Color(0xFF0E0E10), RoundedCornerShape(12.dp))
        ) {
            // Title bar
            Row(
                Modifier.fillMaxWidth().height(30.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xFFF7F6F1), Color(0xFFDCD9D0))))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(if (isPlaying) "▶" else if (count > 0) "❚❚" else "", color = ScreenInk, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                val heading = when (top) {
                    is MenuPage -> top.title
                    NowPlayingPage -> "Now Playing"
                    is LoadingPage -> top.title
                    is SearchPage -> if (top.drive) "Search Drive" else if (top.yt) "YouTube Music" else "Search"
                    is GamePage -> "${top.game.name}   ${top.game.score}"
                    is WebGamePage -> top.ctrl.def.name
                    is VideoPage -> "Video"
                    else -> "BetterPod"
                }
                Text(heading, color = ScreenInk, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                Text("$battery%", color = ScreenInk, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(30.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFB9B6AC)))

            Box(Modifier.fillMaxSize()) {
                when {
                    !granted -> Message("Allow access to your music\nto get started.")
                    songs == null -> Message("Loading library…")
                    top is LoadingPage -> Message("Loading ${top.title}…")
                    top is VideoPage -> VideoScreen(top.file, onPlayer = { videoPlayer = it }, onFullScreen = { fullScreen(top) })
                    top is GamePage -> GameScreen(top.game) { back() }
                    top is WebGamePage -> WebGameScreen(top.ctrl) { back() }
                    top is SearchPage -> SearchScreen(top, if (top.drive) "Video or folder name" else if (top.yt) "Song or artist" else "Songs, artists, albums") { q ->
                        top.query = q
                        if (top.drive) driveSearch(top, q) else if (top.yt) ytSearch(top, q) else top.results = searchResults(q)
                    }
                    top is MenuPage -> MenuList(top)
                    top == NowPlayingPage -> Box(Modifier.fillMaxSize().clickable { if (count > 0) scrubbing = !scrubbing }) { NowPlaying(
                        count = count, label = if (useYt) "YouTube Music" else "${index + 1} of $count", title = title, artist = artist, album = album, art = art,
                        position = position, duration = duration, scrubbing = scrubbing,
                        showVolume = showVolume,
                        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1),
                    ) }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // ===== In games: switch between the wheel and a Game Boy style pad
        if (inGame) {
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF2A2A2E)).padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                listOf("WHEEL" to false, "PAD" to true).forEach { (label, pad) ->
                    Text(
                        label,
                        color = if (padMode == pad) Color(0xFF1C1C1E) else Color(0xFFB8B8BE),
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (padMode == pad) Color(0xFFEDEBE4) else Color.Transparent)
                            .clickable { padMode = pad }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // ===== Wheel (or pad)
        if (inGame && padMode) {
            Spacer(Modifier.weight(1f))
            GamePad(
                Modifier.fillMaxWidth().height(250.dp),
                onPress = { b -> when (top) { is GamePage -> top.game.press(b); is WebGamePage -> top.ctrl.press(b); else -> {} } },
                onRelease = { b -> when (top) { is GamePage -> top.game.release(b); is WebGamePage -> top.ctrl.release(b); else -> {} } },
            )
        } else ClickWheel(
            modifier = Modifier.fillMaxWidth(if (inGame) 0.72f else 0.86f).aspectRatio(1f),
            haptics = haptics,
            onScroll = { onScroll(it) },
            onMenu = { if (!gameTap(Btn.UP)) back() },
            onPrev = {
                if (gameTap(Btn.LEFT)) return@ClickWheel
                val v = videoPlayer
                if (top is VideoPage && v != null) v.seekTo((v.currentPosition - 30_000L).coerceAtLeast(0L)) else controller?.seekToPrevious()
            },
            onNext = {
                if (gameTap(Btn.RIGHT)) return@ClickWheel
                val v = videoPlayer
                if (top is VideoPage && v != null) v.seekTo(v.currentPosition + 30_000L) else controller?.seekToNext()
            },
            onPlayPause = {
                if (gameTap(Btn.DOWN)) return@ClickWheel
                val v = videoPlayer
                if (top is VideoPage && v != null) { if (v.isPlaying) v.pause() else v.play() }
                else controller?.let { if (it.isPlaying) it.pause() else if (it.mediaItemCount > 0) it.play() }
            },
            onSelect = { onSelect() },
        )

        Spacer(Modifier.weight(1f))
    }

    // YouTube Music's hidden browser lives here: full screen while signing in, otherwise 1 dot, out of sight.
    if (showYtLogin) {
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().background(BodyTop).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sign in to YouTube Music", color = SelectInk, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Done", color = SelectInk, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(SelectBg)
                        .clickable { closeYtLogin() }.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            YtWebHost(Modifier.fillMaxSize())
        }
    } else {
        YtWebHost(Modifier.size(1.dp))
    }
    }
}

/** Shows YouTube Music's browser. It's moved here (not recreated) so music keeps playing. */
@Composable
private fun YtWebHost(modifier: Modifier) {
    val wv = YtMusic.view ?: return
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FrameLayout(ctx).apply {
                (wv.parent as? ViewGroup)?.removeView(wv)
                addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
        },
        onRelease = { it.removeAllViews() },
    )
}

// ------------------------------------------------------------------ Screen pieces

/** Video on the small screen, with a progress bar. Tap the picture (or press center) for full screen. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoScreen(file: DriveFile, onPlayer: (ExoPlayer?) -> Unit, onFullScreen: () -> Unit) {
    val context = LocalContext.current
    val exo = remember(file.id) { driveVideoPlayer(context, file).apply { playWhenReady = true } }
    DisposableEffect(exo) {
        onPlayer(exo)
        onDispose { onPlayer(null); exo.release() }
    }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(exo) {
        while (true) { pos = exo.currentPosition; dur = exo.duration.coerceAtLeast(0L); delay(500) }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = exo } },
                modifier = Modifier.fillMaxSize()
            )
            // Transparent layer on top so a tap always reaches us (the video view eats touches).
            Box(Modifier.fillMaxSize().clickable { onFullScreen() })
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(file.name, color = ScreenInk, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFD6D3CA))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f).background(SelectBg))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(fmt(pos), color = ScreenDim, fontSize = 11.sp)
                Text("● = full screen", color = ScreenDim, fontSize = 11.sp)
                Text("-" + fmt(dur - pos), color = ScreenDim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Text(text, color = ScreenDim, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun MenuList(page: MenuPage) {
    val end = minOf(page.first + page.rows, page.items.size)
    val rowPx = with(LocalDensity.current) { 36.dp.toPx() }
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxHeight()
                // Swipe: every row-height of finger travel moves the highlight one row.
                .pointerInput(page) {
                    var acc = 0f
                    detectVerticalDragGestures(onDragEnd = { acc = 0f }) { change, dy ->
                        change.consume()
                        acc += dy
                        while (acc >= rowPx) { page.move(1); acc -= rowPx }    // finger down = highlight moves down
                        while (acc <= -rowPx) { page.move(-1); acc += rowPx }  // finger up = highlight moves up
                    }
                }
        ) {
            for (i in page.first until end) {
                val item = page.items[i]
                val selected = i == page.selected
                Row(
                    Modifier.fillMaxWidth().height(36.dp)
                        .background(if (selected) SelectBg else Color.Transparent)
                        // Tap: highlight this row and open it (same as the center button).
                        .clickable { page.selected = i; item.onSelect() }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.label(),
                        color = if (selected) SelectInk else ScreenInk,
                        fontSize = 16.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (item.hasChildren) Text("›", color = if (selected) SelectInk else ScreenDim, fontSize = 20.sp)
                }
            }
        }
        // Scroll position bar (only when the list is longer than the screen)
        if (page.items.size > page.rows) {
            val before = page.first.toFloat()
            val visible = (end - page.first).toFloat()
            val after = (page.items.size - end).toFloat()
            Column(Modifier.width(6.dp).fillMaxHeight().background(Color(0xFFD6D3CA))) {
                if (before > 0f) Spacer(Modifier.weight(before))
                Box(Modifier.fillMaxWidth().weight(visible).background(ScreenDim))
                if (after > 0f) Spacer(Modifier.weight(after))
            }
        }
    }
}

/** Search box (keyboard pops up automatically) + results you can tap, swipe or wheel through. */
@Composable
private fun SearchScreen(page: SearchPage, hint: String, onQuery: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Column(Modifier.fillMaxSize()) {
        BasicTextField(
            value = page.query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(color = ScreenInk, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            modifier = Modifier.fillMaxWidth().padding(8.dp).focusRequester(focus),
            decorationBox = { field ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .border(1.dp, Color(0xFFB9B6AC), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⌕ ", color = ScreenDim, fontSize = 16.sp)
                    Box(Modifier.weight(1f)) {
                        if (page.query.isEmpty()) Text(hint, color = ScreenDim, fontSize = 16.sp)
                        field()
                    }
                }
            }
        )
        Box(Modifier.fillMaxWidth().weight(1f)) { MenuList(page.results) }
    }
}

@Composable
private fun NowPlaying(
    count: Int, label: String, title: String, artist: String, album: String, art: Bitmap?,
    position: Long, duration: Long, scrubbing: Boolean, showVolume: Boolean, volume: Float,
) {
    if (count == 0) { Message("Nothing playing.\nPress MENU and pick a song."); return }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = ScreenDim, fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(110.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFFD6D3CA)),
                contentAlignment = Alignment.Center
            ) {
                if (art != null) Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text("♪", color = ScreenDim, fontSize = 40.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = ScreenInk, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(artist, color = ScreenInk, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album, color = ScreenDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Column {
            val progress = if (showVolume && !scrubbing) volume else if (duration > 0) position.toFloat() / duration else 0f
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFD6D3CA))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(if (scrubbing) Color(0xFF8A6A2C) else SelectBg))
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                when {
                    showVolume && !scrubbing -> Text("Volume", color = ScreenDim, fontSize = 12.sp)
                    else -> {
                        Text(fmt(position), color = ScreenDim, fontSize = 12.sp)
                        if (scrubbing) Text("Scrubbing", color = Color(0xFF8A6A2C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("-" + fmt(duration - position), color = ScreenDim, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
