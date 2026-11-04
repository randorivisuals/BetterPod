package com.corpsave.betterpod

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * YouTube Music inside BetterPod.
 *
 * How it works (same idea as apps like NouTube, but our own code):
 *  - The real music.youtube.com website runs in a hidden WebView (a built-in browser).
 *  - assets/ytmusic.js is slipped into the page before YouTube's code. It keeps music playing
 *    with the screen off, reports "what's playing" every second, presses next/previous, skips ads,
 *    and fetches search results / your library the same way the website does.
 *  - YtMusicService wraps all of this as a normal Android music player, so the click wheel,
 *    Now Playing screen, lock screen and Bluetooth buttons all work like they do for local songs.
 */
object YtMusic {
    const val HOME = "https://music.youtube.com/"
    private const val LOGIN = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true" +
        "&continue=https%3A%2F%2Fmusic.youtube.com%2F"

    /** One row from search or your library: a song (videoId) or something to open (browseId). */
    data class Item(
        val title: String,
        val sub: String,
        val videoId: String,
        val playlistId: String,
        val browseId: String,
        val thumb: String,
    )

    /** What the page says is playing right now. */
    data class State(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val art: String = "",
        val videoId: String = "",
        val playing: Boolean = false,
        val loading: Boolean = false,
        val posMs: Long = 0,
        val durMs: Long = 0,
        val ad: Boolean = false,
        val ready: Boolean = false,
    )

    /** The hidden browser. Null until YouTube Music is first used. */
    var view by mutableStateOf<WebView?>(null)
        private set
    var state by mutableStateOf(State())
        private set
    var skipAds by mutableStateOf(true)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val nextId = AtomicInteger()
    private var appContext: Context? = null

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun update(s: State) {
        state = s
        listeners.toList().forEach { it() }
    }

    /** Creates the hidden browser once and opens YouTube Music. Call on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun init(context: Context): WebView {
        view?.let { return it }
        val app = context.applicationContext
        appContext = app
        skipAds = prefs(app).getBoolean("yt_skip_ads", true)

        // MutableContextWrapper lets the same WebView move between the app screen and the background.
        val wv = WebView(MutableContextWrapper(app))
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false   // songs start without tapping the page
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            // Look like desktop Chrome: the desktop site plays in the background and Google sign-in accepts it.
            val chrome = Regex("Chrome/([\\d.]+)").find(userAgentString)?.groupValues?.get(1) ?: "130.0.0.0"
            userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.addJavascriptInterface(Bridge, "BP")

        val script = app.assets.open("ytmusic.js").bufferedReader().use { it.readText() }
        val earlyInject = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (earlyInject) WebViewCompat.addDocumentStartJavaScript(wv, script, setOf("https://music.youtube.com"))

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            // Stay on the web. Blocks "open in the YouTube app" links (intent://, market://).
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: return true
                return scheme != "https" && scheme != "http"
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!url.startsWith(HOME)) update(state.copy(ready = false))
                // Old WebViews without early injection: add the script as soon as the page starts.
                else if (!earlyInject) view.evaluateJavascript(script, null)
            }
        }
        wv.loadUrl(HOME)
        view = wv
        return wv
    }

    /** Point the browser at the visible screen (for the keyboard during sign-in) or back at the app. */
    fun setHost(context: Context?) {
        val wrapper = view?.context as? MutableContextWrapper ?: return
        wrapper.baseContext = context ?: appContext ?: return
    }

    // ---------------------------------------------------------------- Controls
    private fun js(code: String) = main.post { view?.evaluateJavascript("window.__bp && __bp.$code", null) }

    fun play() { update(state.copy(playing = true)); js("play()") }
    fun pause() { update(state.copy(playing = false, loading = false)); js("pause()") }
    fun seekTo(ms: Long) { update(state.copy(posMs = ms)); js("seek($ms)") }
    fun next() = js("next()")
    fun prev() = js("prev()")

    /** Open a song. With a playlistId, next/previous move through that playlist; without, YouTube plays a radio mix. */
    fun playSong(videoId: String, playlistId: String?) {
        update(state.copy(videoId = videoId, playing = true, loading = true, posMs = 0, durMs = 0))
        val url = HOME + "watch?v=" + videoId + (playlistId?.takeIf { it.isNotEmpty() }?.let { "&list=$it" } ?: "")
        main.post { view?.loadUrl(url) }
    }

    fun toggleSkipAds(on: Boolean) {
        skipAds = on
        appContext?.let { prefs(it).edit().putBoolean("yt_skip_ads", on).apply() }
    }

    // ---------------------------------------------------------------- Account
    fun signedIn(): Boolean = CookieManager.getInstance().getCookie(HOME)?.contains("SAPISID=") == true
    fun openLogin() { main.post { view?.loadUrl(LOGIN) } }
    fun closeLogin() {
        CookieManager.getInstance().flush()
        main.post { view?.loadUrl(HOME) }
    }
    fun signOut() {
        update(State())
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies { main.post { view?.loadUrl(HOME) } }
    }

    // ---------------------------------------------------------------- Lists
    suspend fun search(query: String) = request("search", query)
    /** "VLLM" = Liked songs, "FEmusic_liked_playlists" = your playlists, "FEmusic_home" = Home, or any album/playlist id. */
    suspend fun browse(id: String) = request("browse", id)

    private suspend fun request(kind: String, arg: String): List<Item> {
        withTimeoutOrNull(20_000) { while (!state.ready) delay(250) }
            ?: error("YouTube Music is still loading. Try again in a moment.")
        val id = nextId.incrementAndGet()
        val answer = CompletableDeferred<String>()
        pending[id] = answer
        withContext(Dispatchers.Main) {
            view?.evaluateJavascript("__bp.api($id, ${JSONObject.quote(kind)}, ${JSONObject.quote(arg)})", null)
        }
        val json = withTimeoutOrNull(20_000) { answer.await() }
            .also { pending.remove(id) }
            ?: error("YouTube Music didn't answer. Check your internet.")
        val obj = JSONObject(json)
        obj.optString("error").takeIf { it.isNotEmpty() }?.let { error(it) }
        val arr = obj.optJSONArray("items") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Item(
                title = o.optString("title"),
                sub = o.optString("sub"),
                videoId = o.optString("videoId"),
                playlistId = o.optString("playlistId"),
                browseId = o.optString("browseId"),
                thumb = o.optString("thumb"),
            )
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("betterpod", Context.MODE_PRIVATE)

    /** Bigger album art: YouTube's image links carry their size, e.g. =w60-h60. */
    private fun bigArt(url: String) = url.replace(Regex("=w\\d+-h\\d+"), "=w544-h544")

    /** The page calls these (from a background thread, so hop to the main thread). */
    private object Bridge {
        @JavascriptInterface
        fun state(json: String) {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return
            main.post {
                update(
                    State(
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        art = bigArt(o.optString("art")),
                        videoId = o.optString("videoId"),
                        playing = o.optBoolean("playing"),
                        loading = o.optBoolean("loading"),
                        posMs = o.optLong("pos"),
                        durMs = o.optLong("dur"),
                        ad = o.optBoolean("ad"),
                        ready = o.optBoolean("ready"),
                    )
                )
            }
        }

        @JavascriptInterface
        fun reply(id: Int, json: String) { pending[id]?.complete(json) }

        @JavascriptInterface
        fun skipAds(): Boolean = YtMusic.skipAds
    }
}
