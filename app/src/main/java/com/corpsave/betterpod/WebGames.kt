package com.corpsave.betterpod

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs

/** A keyboard key, the way web pages see it (name, physical code, old-style number). */
data class Key(val key: String, val code: String, val keyCode: Int)

private val K_LEFT = Key("ArrowLeft", "ArrowLeft", 37)
private val K_UP = Key("ArrowUp", "ArrowUp", 38)
private val K_RIGHT = Key("ArrowRight", "ArrowRight", 39)
private val K_DOWN = Key("ArrowDown", "ArrowDown", 40)
private val K_SPACE = Key(" ", "Space", 32)
private val K_P = Key("p", "KeyP", 80)
private val K_N = Key("n", "KeyN", 78)

/**
 * An open-source web game bundled inside the app (app/src/main/assets/games/...).
 * Each one says which keyboard key the A / B / START buttons and the wheel spin should press.
 */
data class WebGameDef(
    val name: String,
    val folder: String,
    val credit: String,
    val a: Key?,
    val b: Key? = null,
    val start: Key? = null,
    val spinLeft: Key? = K_LEFT,
    val spinRight: Key? = K_RIGHT,
)

val WEB_GAMES = listOf(
    WebGameDef("Hextris", "hextris", "Hextris team · GPL-3.0", a = K_SPACE, start = K_P),
    WebGameDef("Tetris", "tetris", "Jake Gordon · MIT", a = K_UP, b = K_DOWN, start = K_SPACE),
    WebGameDef("2048", "g2048", "Gabriele Cirulli · MIT", a = K_UP, b = K_DOWN),
    WebGameDef("Pac-Man", "pacman", "Dale Harvey · WTFPL", a = K_N, start = K_P, spinLeft = null, spinRight = null),
    WebGameDef("Floppy Bird", "floppybird", "Nebez Briefkani · Apache-2.0", a = K_SPACE, b = K_SPACE, start = K_SPACE, spinLeft = K_SPACE, spinRight = K_SPACE),
)

/**
 * Turns BetterPod buttons into key presses inside the game page.
 * Web games listen for keyboard events, so we create real-looking ones with JavaScript.
 */
class WebGameController(val def: WebGameDef) {
    var view: WebView? = null

    private fun keyFor(b: Btn): Key? = when (b) {
        Btn.UP -> K_UP
        Btn.DOWN -> K_DOWN
        Btn.LEFT -> K_LEFT
        Btn.RIGHT -> K_RIGHT
        Btn.A -> def.a
        Btn.B -> def.b
        Btn.START -> def.start
        Btn.SELECT -> null
    }

    private fun send(type: String, k: Key) {
        val js = "(function(){$INSTALL;window.__bp('$type',${q(k.key)},${q(k.code)},${k.keyCode});})()"
        view?.evaluateJavascript(js, null)
    }

    fun press(b: Btn) { keyFor(b)?.let { send("keydown", it); if (b != Btn.LEFT && b != Btn.RIGHT && b != Btn.UP && b != Btn.DOWN) send("keypress", it) } }
    fun release(b: Btn) { keyFor(b)?.let { send("keyup", it) } }
    fun tap(b: Btn) { press(b); release(b) }

    fun spin(steps: Int) {
        val k = (if (steps > 0) def.spinRight else def.spinLeft) ?: return
        repeat(minOf(abs(steps), 4)) { send("keydown", k); send("keyup", k) }
    }

    private fun q(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    companion object {
        /** Defines window.__bp once: builds a KeyboardEvent that old and new games both understand. */
        private const val INSTALL = "if(!window.__bp){window.__bp=function(t,k,c,n){" +
            "var e=new KeyboardEvent(t,{key:k,code:c,bubbles:true,cancelable:true});" +
            "Object.defineProperty(e,'keyCode',{get:function(){return n;}});" +
            "Object.defineProperty(e,'which',{get:function(){return n;}});" +
            "Object.defineProperty(e,'charCode',{get:function(){return t==='keypress'?n:0;}});" +
            "(document.activeElement||document.body||document).dispatchEvent(e);};}"
    }
}

/**
 * Shows a bundled web game. Works offline; links that try to leave the game are blocked.
 * Exit: swipe in from the left or right EDGE (the middle is left free for the game's own swipes).
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun WebGameScreen(ctrl: WebGameController, onExit: () -> Unit) {
    val exit by rememberUpdatedState(onExit)
    DisposableEffect(ctrl) {
        onDispose { ctrl.view?.destroy(); ctrl.view = null }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true            // games save high scores here
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                        !request.url.toString().startsWith("file:///android_asset/")
                }
                var downX = 0f
                var downY = 0f
                var fromEdge = false
                setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = e.x; downY = e.y
                            fromEdge = downX < v.width * 0.1f || downX > v.width * 0.9f
                        }
                        MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                            if (fromEdge && abs(e.x - downX) > v.width * 0.35f && abs(e.y - downY) < v.height * 0.3f) {
                                fromEdge = false
                                exit()
                                return@setOnTouchListener true
                            }
                        }
                    }
                    false // let the game handle the touch too
                }
                loadUrl("file:///android_asset/games/${ctrl.def.folder}/index.html")
                ctrl.view = this
            }
        }
    )
}
