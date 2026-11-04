package com.corpsave.betterpod

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

// LCD palette: dark "ink" pixels on warm paper, like an old handheld screen.
private val Paper = Color(0xFFEDEBE4)
private val Ink = Color(0xFF1C1C1E)
private val InkSoft = Color(0xFF7A776F)

/** Every button a game can receive, from the wheel or the pad. */
enum class Btn { UP, DOWN, LEFT, RIGHT, A, B, START, SELECT }

enum class GameState { Ready, Playing, Paused, Over }

/**
 * Base for all games. A game only has to:
 *  - reset() its level,  update(dt) its physics (dt = seconds since last frame),
 *  - draw() itself, and react to onSpin / onPress.
 * Everything else (pausing, scores, high scores, start/game-over) lives here.
 */
abstract class Game(val name: String, private val context: Context) {
    var score by mutableIntStateOf(0)
    var state by mutableStateOf(GameState.Ready)
    var best by mutableIntStateOf(prefs().getInt("best_$name", 0))
    protected val held = HashSet<Btn>()
    protected var w = 0f
    protected var h = 0f

    abstract fun reset()
    abstract fun update(dt: Float)
    abstract fun draw(d: DrawScope)
    open fun onSpin(steps: Int) {}
    open fun onPress(b: Btn) {}

    private fun prefs() = context.getSharedPreferences("betterpod_games", Context.MODE_PRIVATE)

    fun resize(nw: Float, nh: Float) {
        if (nw == w && nh == h) return
        w = nw; h = nh
        if (state != GameState.Playing) reset()
    }

    fun start() {
        if (w == 0f) return
        score = 0
        reset()
        state = GameState.Playing
    }

    fun togglePause() {
        state = when (state) {
            GameState.Playing -> GameState.Paused
            GameState.Paused -> GameState.Playing
            else -> state
        }
    }

    fun press(b: Btn) {
        held += b
        when {
            b == Btn.START -> if (state == GameState.Ready || state == GameState.Over) start() else togglePause()
            state == GameState.Ready || state == GameState.Over -> if (b == Btn.A) start()
            state == GameState.Paused -> if (b == Btn.A) togglePause()
            else -> onPress(b)
        }
    }

    fun release(b: Btn) { held -= b }

    /** Wheel taps: a press that lets go straight away. */
    fun tap(b: Btn) { press(b); release(b) }

    fun spin(steps: Int) { if (state == GameState.Playing) onSpin(steps) }

    protected fun gameOver() {
        state = GameState.Over
        if (score > best) {
            best = score
            prefs().edit().putInt("best_$name", best).apply()
        }
    }
}

// ============================================================== Breaker

/** Spin to slide the paddle, ● launches the ball, clear every brick. */
class Breaker(context: Context) : Game("Breaker", context) {
    private val cols = 8
    private val rows = 5
    private val bricks = BooleanArray(cols * rows)
    private var paddleX = 0f
    private var ballX = 0f
    private var ballY = 0f
    private var vx = 0f
    private var vy = 0f
    private var stuck = true
    private var lives = 3
    private var speed = 1f

    private val paddleW get() = w * 0.24f
    private val paddleY get() = h * 0.9f
    private val ballR get() = w * 0.018f
    private val top get() = h * 0.1f
    private val brickH get() = h * 0.05f

    override fun reset() {
        lives = 3; speed = 1f
        bricks.fill(true)
        paddleX = w / 2
        stuck = true
    }

    override fun onSpin(steps: Int) {
        paddleX = (paddleX + steps * w * 0.07f).coerceIn(paddleW / 2, w - paddleW / 2)
    }

    override fun onPress(b: Btn) {
        if ((b == Btn.A || b == Btn.UP) && stuck) {
            stuck = false
            vx = w * 0.3f * speed * (if (Random.nextBoolean()) 1 else -1)
            vy = -h * 0.55f * speed
        }
    }

    override fun update(dt: Float) {
        // Pad left/right held = smooth slide.
        if (Btn.LEFT in held) paddleX -= w * 1.1f * dt
        if (Btn.RIGHT in held) paddleX += w * 1.1f * dt
        paddleX = paddleX.coerceIn(paddleW / 2, w - paddleW / 2)

        if (stuck) { ballX = paddleX; ballY = paddleY - ballR * 1.5f; return }

        ballX += vx * dt
        ballY += vy * dt
        if (ballX < ballR) { ballX = ballR; vx = abs(vx) }
        if (ballX > w - ballR) { ballX = w - ballR; vx = -abs(vx) }
        if (ballY < ballR) { ballY = ballR; vy = abs(vy) }

        // Paddle: where it hits decides the bounce angle (edges = sharper).
        if (vy > 0 && ballY + ballR >= paddleY && ballY < paddleY + brickH && abs(ballX - paddleX) <= paddleW / 2 + ballR) {
            vy = -abs(vy)
            vx = (ballX - paddleX) / (paddleW / 2) * w * 0.6f * speed
        }

        // Bricks: find which cell the ball is in.
        val bw = w / cols
        val col = (ballX / bw).toInt()
        val row = ((ballY - top) / brickH).toInt()
        if (ballY >= top && row in 0 until rows && col in 0 until cols && bricks[row * cols + col]) {
            bricks[row * cols + col] = false
            vy = -vy
            score += 10
            if (bricks.none { it }) { // level cleared: faster ball, new wall
                bricks.fill(true); speed *= 1.12f; stuck = true
            }
        }

        if (ballY > h) {
            lives--
            if (lives <= 0) gameOver() else stuck = true
        }
    }

    override fun draw(d: DrawScope): Unit = with(d) {
        val bw = w / cols
        for (r in 0 until rows) for (c in 0 until cols) if (bricks[r * cols + c]) {
            drawRoundRect(Ink, Offset(c * bw + 2f, top + r * brickH + 2f), Size(bw - 4f, brickH - 4f), CornerRadius(3f))
        }
        drawRoundRect(Ink, Offset(paddleX - paddleW / 2, paddleY), Size(paddleW, brickH * 0.6f), CornerRadius(6f))
        drawCircle(Ink, ballR, Offset(ballX, ballY))
        for (i in 0 until lives) drawCircle(InkSoft, ballR * 0.8f, Offset(w - (i + 1) * ballR * 3f, top * 0.45f))
    }
}

// ============================================================== Snake

/** Spin = turn left/right. D-pad = point in a direction. Eat dots, don't hit walls or yourself. */
class Snake(context: Context) : Game("Snake", context) {
    private val cols = 16
    private var rowsN = 16
    private val body = ArrayDeque<Pair<Int, Int>>()
    private var dx = 1
    private var dy = 0
    private var nextDx = 1
    private var nextDy = 0
    private var food = 0 to 0
    private var timer = 0f
    private var stepTime = 0.16f

    private val cell get() = w / cols

    override fun reset() {
        rowsN = max(8, (h / cell).toInt())
        body.clear()
        val cy = rowsN / 2
        for (i in 0 until 4) body.addLast((5 - i) to cy)
        dx = 1; dy = 0; nextDx = 1; nextDy = 0
        stepTime = 0.16f; timer = 0f
        placeFood()
    }

    private fun placeFood() {
        do { food = Random.nextInt(cols) to Random.nextInt(rowsN) } while (food in body)
    }

    private fun turnTo(ndx: Int, ndy: Int) {
        if (ndx == -dx && ndy == -dy) return // can't reverse into yourself
        nextDx = ndx; nextDy = ndy
    }

    override fun onSpin(steps: Int) {
        // Clockwise = turn right. On screen y points down, so right of (dx,dy) is (-dy, dx).
        if (steps > 0) turnTo(-dy, dx) else turnTo(dy, -dx)
    }

    override fun onPress(b: Btn) {
        when (b) {
            Btn.UP -> turnTo(0, -1)
            Btn.DOWN -> turnTo(0, 1)
            Btn.LEFT -> turnTo(-1, 0)
            Btn.RIGHT -> turnTo(1, 0)
            else -> {}
        }
    }

    override fun update(dt: Float) {
        timer += dt
        if (timer < stepTime) return
        timer = 0f
        dx = nextDx; dy = nextDy
        val (hx, hy) = body.first()
        val head = (hx + dx) to (hy + dy)
        if (head.first !in 0 until cols || head.second !in 0 until rowsN || head in body) { gameOver(); return }
        body.addFirst(head)
        if (head == food) {
            score++
            stepTime = max(0.07f, stepTime * 0.96f) // a little faster every bite
            placeFood()
        } else body.removeLast()
    }

    override fun draw(d: DrawScope): Unit = with(d) {
        val c = cell
        val offY = (h - rowsN * c) / 2
        drawRect(InkSoft, Offset(0f, offY), Size(cols * c, rowsN * c), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        drawCircle(Ink, c * 0.35f, Offset(food.first * c + c / 2, offY + food.second * c + c / 2))
        body.forEachIndexed { i, (x, y) ->
            drawRoundRect(if (i == 0) Ink else Ink.copy(alpha = 0.8f), Offset(x * c + 1.5f, offY + y * c + 1.5f), Size(c - 3f, c - 3f), CornerRadius(3f))
        }
    }
}

// ============================================================== Stack

/** A block slides back and forth: ● drops it. Overhang gets cut off. How tall can you go? */
class Stack(context: Context) : Game("Stack", context) {
    private val levels = ArrayList<Pair<Float, Float>>() // (left x, width) of each placed block
    private var x = 0f
    private var width = 0f
    private var dir = 1f
    private var speed = 0f
    private var camera = 0f

    private val blockH get() = h / 14f

    override fun reset() {
        levels.clear()
        width = w * 0.6f
        levels.add((w - width) / 2 to width)
        x = 0f; dir = 1f; speed = w * 0.55f; camera = 0f
    }

    override fun onPress(b: Btn) { if (b == Btn.A || b == Btn.DOWN) drop() }

    private fun drop() {
        val (px, pw) = levels.last()
        val left = max(x, px)
        val right = min(x + width, px + pw)
        val overlap = right - left
        if (overlap <= 0f) { gameOver(); return }
        levels.add(left to overlap)
        width = overlap
        score++
        speed *= 1.04f
        x = if (score % 2 == 0) 0f else w - width
        dir = if (score % 2 == 0) 1f else -1f
    }

    override fun update(dt: Float) {
        x += dir * speed * dt
        if (x < 0f) { x = 0f; dir = 1f }
        if (x + width > w) { x = w - width; dir = -1f }
        // Camera follows the tower up once it passes the middle.
        val target = max(0f, (levels.size + 1) * blockH - h * 0.6f)
        camera += (target - camera) * min(1f, dt * 6f)
    }

    override fun draw(d: DrawScope): Unit = with(d) {
        levels.forEachIndexed { i, (lx, lw) ->
            val y = h - (i + 1) * blockH + camera
            if (y < h && y > -blockH) drawRoundRect(if (i % 2 == 0) Ink else InkSoft, Offset(lx, y + 1f), Size(lw, blockH - 2f), CornerRadius(3f))
        }
        if (state == GameState.Playing || state == GameState.Paused) {
            val y = h - (levels.size + 1) * blockH + camera
            drawRoundRect(Ink, Offset(x, y + 1f), Size(width, blockH - 2f), CornerRadius(3f))
        }
    }
}

// ============================================================== Screen + pad

/**
 * The game's screen. Runs the game loop (every display frame), shows Ready / Paused / Game Over,
 * tap = start or pause, swipe sideways = exit.
 */
@Composable
fun GameScreen(game: Game, onExit: () -> Unit) {
    var frame by remember { mutableLongStateOf(0L) }
    val exit by rememberUpdatedState(onExit)
    val swipePx = with(LocalDensity.current) { 70.dp.toPx() }

    LaunchedEffect(game) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0f else ((t - last) / 1_000_000_000f).coerceAtMost(0.05f)
                last = t
                if (game.state == GameState.Playing) game.update(dt)
                frame++
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Paper)
            .pointerInput(game) {
                var acc = 0f
                detectHorizontalDragGestures(onDragStart = { acc = 0f }) { change, dx ->
                    change.consume()
                    acc += dx
                    if (abs(acc) > swipePx) { acc = 0f; exit() }
                }
            }
            .pointerInput(game) {
                detectTapGestures { if (game.state == GameState.Playing) game.togglePause() else game.tap(Btn.A) }
            }
    ) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            frame // read so the canvas redraws every frame
            game.resize(size.width, size.height)
            game.draw(this)
        }
        val overlay = when (game.state) {
            GameState.Ready -> "${game.name}\n\nPress ● to start\nSwipe sideways to exit\n\nBest  ${game.best}"
            GameState.Paused -> "Paused\n\nTap or press ● to resume"
            GameState.Over -> "Game over\n\nScore  ${game.score}\nBest  ${game.best}\n\nPress ● to play again"
            GameState.Playing -> null
        }
        if (overlay != null) {
            Box(Modifier.fillMaxSize().background(Paper.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
                Text(overlay, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            }
        }
    }
}

private val PadBody = Color(0xFF26262A)
private val PadKey = Color(0xFF3C3C41)
private val PadKeyTop = Color(0xFF4A4A50)
private val PadLabel = Color(0xFFB8B8BE)
private val PadA = Color(0xFF8E2C3A)   // muted red A/B, the classic handheld look

/** Game Boy style control panel in BetterPod colors. Buttons report press AND release (for holding). */
@Composable
fun GamePad(modifier: Modifier, onPress: (Btn) -> Unit, onRelease: (Btn) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)

    @Composable
    fun Key(b: Btn, mod: Modifier, color: Color = PadKey, label: String = "", round: Boolean = false) {
        Box(
            mod.clip(if (round) CircleShape else RoundedCornerShape(6.dp))
                .background(Brush.verticalGradient(listOf(if (color == PadKey) PadKeyTop else color, color)))
                .border(1.dp, Color(0x22FFFFFF), if (round) CircleShape else RoundedCornerShape(6.dp))
                .pointerInput(b) {
                    awaitEachGesture {
                        awaitFirstDown().consume()
                        press(b)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        waitForUpOrCancellation()
                        release(b)
                    }
                },
            contentAlignment = Alignment.Center
        ) { if (label.isNotEmpty()) Text(label, color = PadLabel, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
    }

    Column(
        modifier.clip(RoundedCornerShape(28.dp)).background(PadBody).padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            // D-pad: a plus shape made of 4 keys around a center block
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Key(Btn.UP, Modifier.size(48.dp), label = "▲")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Key(Btn.LEFT, Modifier.size(48.dp), label = "◀")
                    Box(Modifier.size(48.dp).background(PadKey))
                    Key(Btn.RIGHT, Modifier.size(48.dp), label = "▶")
                }
                Key(Btn.DOWN, Modifier.size(48.dp), label = "▼")
            }
            // B and A on a slant
            Row(verticalAlignment = Alignment.Bottom) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(28.dp))
                    Key(Btn.B, Modifier.size(62.dp), PadA, "B", round = true)
                }
                Spacer(Modifier.width(14.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Key(Btn.A, Modifier.size(62.dp), PadA, "A", round = true)
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Key(Btn.SELECT, Modifier.width(56.dp).height(16.dp))
                Text("SELECT", color = PadLabel, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Key(Btn.START, Modifier.width(56.dp).height(16.dp))
                Text("START", color = PadLabel, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
