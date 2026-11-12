package com.streamhub.tv.screens.login

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.streamhub.core.model.DevicePairingStatus
import com.streamhub.core.net.StreamHubApi
import com.streamhub.core.net.StreamHubException
import com.streamhub.tv.focus.LocalTvFocus
import com.streamhub.tv.focus.TvKey
import com.streamhub.tv.focus.TvScreenKeys
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.focus.rememberTvFocusTarget
import com.streamhub.tv.focus.tvFocusable
import com.streamhub.tv.screens.common.ADMIN_REFUSAL
import com.streamhub.tv.screens.common.friendlyError
import com.streamhub.tv.ui.components.TvBrand
import com.streamhub.tv.ui.components.TvButton
import com.streamhub.tv.ui.components.TvButtonStyle
import com.streamhub.tv.ui.components.TvTextField
import com.streamhub.tv.ui.theme.TvColors
import com.streamhub.tv.ui.theme.TvMotion
import com.streamhub.tv.ui.theme.TvText
import com.streamhub.tv.ui.theme.TvType
import com.streamhub.tv.ui.theme.linearTween
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Signing in (`tizen/app/src/screens/login.rs`, CSS `styles.css:760-902`,
 * PARITY §3.1). One method at a time, centred: a QR and a code a phone
 * approves (the default), or username and password. Pairing keeps polling in
 * both modes, so a phone approval signs in even while someone is typing.
 */

/** `RETRY_MS`: wait after a failed `/auth/device/start`. */
internal const val RETRY_MS = 5_000L

/** The pairing loop's sleep slice, so leaving or renewing takes effect quickly. */
private const val SLICE_MS = 250L

enum class LoginMode { Pair, Password }

/** What the pair panel shows. `deviceCode` is deliberately not here: it never reaches the screen or the QR. */
data class PairingCode(
    val userCode: String,
    /** `verificationUrl` without `https://` / `http://`. */
    val verificationUrl: String,
    /** What the QR encodes. */
    val verificationUrlComplete: String,
)

/** The screen's signals (`login.rs` `LoginScreen`). Lives exactly as long as the screen. */
@Stable
class LoginState(initialNow: Long) {
    var mode by mutableStateOf(LoginMode.Pair)
    var username by mutableStateOf(TextFieldValue())
    var password by mutableStateOf(TextFieldValue())
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var pairing by mutableStateOf<PairingCode?>(null)
    var pairMessage by mutableStateOf<String?>(null)
    /** (issued, expires) on the monotonic clock. */
    var expiry by mutableStateOf<Pair<Long, Long>?>(null)
    /** Ticks once a second for the countdown. */
    var now by mutableLongStateOf(initialNow)
    /** OK on the panel with no code showing: get one now. */
    @Volatile var renew: Boolean = false

    /** Time left on the code, ms, or null with no code. */
    val leftMs: Long? get() = expiry?.let { (_, end) -> max(0L, end - now) }

    /** The countdown bar's scaleX. */
    val leftFraction: Float
        get() = expiry?.let { (start, end) -> ((end - now).toFloat() / max(1L, end - start)).coerceIn(0f, 1f) } ?: 0f
}

/** "m:ss" of ceil(ms / 1000) seconds (`mmss`). */
internal fun mmss(ms: Long): String {
    val t = ceil(max(0L, ms) / 1000.0).toLong()
    return "${t / 60}:${(t % 60).toString().padStart(2, '0')}"
}

/**
 * Keeps a usable code on screen for as long as the screen is up
 * (`pairing_loop`, `login.rs:73-161`): get one, poll it until answered or
 * expired, and on anything but approval get another. Cancelled with the
 * screen. [clock] is monotonic, as `performance.now()` is in Tizen.
 */
internal suspend fun pairingLoop(api: StreamHubApi, state: LoginState, clock: () -> Long, onSignedIn: () -> Unit) {
    while (true) {
        val start = try {
            api.startDevicePairing()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The set may be on before the router is: say so and retry.
            state.pairing = null
            state.pairMessage = "正在等待伺服器…"
            delay(RETRY_MS)
            continue
        }
        if (start.deviceCode.isEmpty()) {
            state.pairMessage = "無法取得配對碼"
            delay(RETRY_MS)
            continue
        }
        state.pairing = PairingCode(
            userCode = start.userCode,
            verificationUrl = start.verificationUrl.removePrefix("https://").removePrefix("http://"),
            verificationUrlComplete = start.verificationUrlComplete,
        )
        state.pairMessage = null
        state.renew = false

        val interval = max(1, start.intervalSeconds) * 1000L
        val expiresIn = max(5, start.expiresInSeconds) * 1000L
        val issuedAt = clock()
        val expiresAt = issuedAt + expiresIn
        state.expiry = issuedAt to expiresAt
        poll@ while (true) {
            val wake = clock() + interval
            while (clock() < wake && !state.renew) delay(SLICE_MS)
            // The code dies on schedule, so it is replaced on schedule.
            if (state.renew || clock() >= expiresAt) break
            val answer = try {
                api.pollDevicePairing(start.deviceCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamHubException) {
                // `:core` checks the approved body itself and throws for the two
                // answers Tizen's `accept` refuses; anything else is a dropped poll.
                when {
                    e.status == 403 && e.message.startsWith("This account cannot play anything") -> {
                        state.pairMessage = ADMIN_REFUSAL
                        break@poll
                    }
                    e.status == 502 && e.message.startsWith("The server approved") -> {
                        state.pairMessage = "伺服器回應不正確"
                        break@poll
                    }
                    else -> continue@poll
                }
            } catch (e: Exception) {
                // A dropped poll is not an answer: keep the code up.
                continue@poll
            }
            when (answer) {
                is DevicePairingStatus.Approved -> {
                    onSignedIn()
                    return
                }
                DevicePairingStatus.Denied, DevicePairingStatus.Expired -> break@poll
                DevicePairingStatus.Pending -> Unit
            }
        }
        state.pairing = null
    }
}

/**
 * The sign-in screen. [onSignedIn] runs once a session is stored (by the
 * password form or a phone's approval). [clock] is the monotonic clock the
 * code's expiry is measured on.
 */
@Composable
fun LoginScreen(
    api: StreamHubApi,
    onSignedIn: () -> Unit,
    clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    val engine = LocalTvFocus.current
    val scope = rememberCoroutineScope()
    val state = remember { LoginState(clock()) }
    val signedIn by rememberUpdatedState(onSignedIn)

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            state.now = clock()
        }
    }
    LaunchedEffect(Unit) { pairingLoop(api, state, clock) { signedIn() } }

    fun show(m: LoginMode) {
        state.mode = m
        state.error = null
        when (m) {
            LoginMode.Password -> engine.focusLater(TvSelect.action("username"))
            LoginMode.Pair -> engine.focusLater(TvSelect.action("use-password"))
        }
    }

    // Back from the password card returns to pairing, not to the exit prompt.
    TvScreenKeys { press ->
        if (press.key == TvKey.Back && state.mode == LoginMode.Password) {
            show(LoginMode.Pair)
            true
        } else {
            false
        }
    }

    fun signIn() {
        if (state.busy) return
        val login = state.username.text.trim()
        val pass = state.password.text
        if (login.isEmpty() || pass.isEmpty()) {
            state.error = "請輸入帳號與密碼"
            return
        }
        state.busy = true
        state.error = null
        scope.launch {
            val result = runCatching { api.login(login, pass) }
            state.busy = false
            result.onSuccess { signedIn() }.onFailure { e ->
                if (e is CancellationException) throw e
                // 401 wrong password, 403 admin refusal, 429 with the wait.
                state.error = friendlyError(e)
            }
        }
    }

    LoginContent(
        state = state,
        onPairClick = { if (state.pairing == null) state.renew = true },
        onUsePassword = { show(LoginMode.Password) },
        onUsePairing = { show(LoginMode.Pair) },
        onSubmit = ::signIn,
    )
}

/** The screen's layout, driven by [state]. Separate from the loops so tests can render any state. */
@Composable
fun LoginContent(
    state: LoginState,
    onPairClick: () -> Unit = {},
    onUsePassword: () -> Unit = {},
    onUsePairing: () -> Unit = {},
    onSubmit: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(TvColors.Bg)) {
        LoginBlobs()
        TvBrand(Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
        // `.tv-login-stage`: margin-top 40px, centred with it; enters on every mode switch.
        key(state.mode) {
            val enter = remember { Animatable(0f) }
            LaunchedEffect(Unit) { enter.animateTo(1f, TvMotion.focus()) }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = 20.dp)
                    .graphicsLayer {
                        alpha = enter.value
                        translationY = (1f - enter.value) * 8.dp.toPx()
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (state.mode) {
                    LoginMode.Pair -> PairStage(state, onPairClick, onUsePassword)
                    LoginMode.Password -> PasswordStage(state, onUsePairing, onSubmit)
                }
            }
        }
    }
}

@Composable
private fun PairStage(state: LoginState, onPairClick: () -> Unit, onUsePassword: () -> Unit) {
    val panel = rememberTvFocusTarget(focusId = "login-pair", action = "pair", onClick = { onPairClick() })
    val ring by animateFloatAsState(if (panel.isFocused) 0.9f else 0f, TvMotion.focus(), label = "pair-ring")
    val pairing = state.pairing
    Row(
        Modifier
            .tvFocusable(panel)
            .size(550.dp, 260.dp)
            .loginPanel { ring }
            .padding(start = 30.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(30.dp),
    ) {
        Box(
            Modifier.size(200.dp).clip(RoundedCornerShape(10.dp)).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (pairing != null && pairing.verificationUrlComplete.isNotEmpty()) {
                TvQrCode(pairing.verificationUrlComplete, 190.dp)
            } else {
                TvText(
                    state.pairMessage ?: "正在取得配對碼…",
                    QrWaitStyle,
                    Modifier.padding(12.dp),
                    maxLines = 6,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            TvText("用手機登入", EyebrowStyle)
            TvText("掃描 QR 碼登入", TvType.Title, Modifier.padding(top = 5.dp, bottom = 9.dp))
            TvText("1. 用手機相機掃描左邊的 QR 碼", StepStyle)
            TvText("2. 在手機上按「核准」", StepStyle)
            TvText(
                pairing?.let { "或前往 ${it.verificationUrl} 輸入" } ?: "或在手機上輸入代碼",
                AltStyle,
                Modifier.padding(top = 13.dp),
            )
            // Exactly the server's text, on one line.
            Box(Modifier.padding(top = 3.dp).heightIn(min = (36 * 1.1).dp)) {
                TvText(pairing?.userCode.orEmpty(), UserCodeStyle, maxLines = 1, softWrap = false)
            }
            Countdown(state, Modifier.padding(top = 7.dp))
            val message = state.pairMessage
            if (pairing != null && message != null) {
                TvText(message, TvType.Error, Modifier.heightIn(min = (12 * 1.3).dp), maxLines = 3)
            }
        }
    }
    TvButton(
        "改用帳號密碼登入",
        onClick = { onUsePassword() },
        modifier = Modifier.padding(top = 20.dp),
        autofocus = true,
        action = "use-password",
        focusId = "login-use-password",
    )
}

@Composable
private fun Countdown(state: LoginState, modifier: Modifier) {
    // `transition: transform 1s linear` on the fill.
    val fraction by animateFloatAsState(state.leftFraction, linearTween(1000), label = "countdown")
    val left = state.leftMs
    Row(
        // `visibility: hidden` keeps its space.
        modifier.graphicsLayer { alpha = if (state.pairing == null) 0f else 1f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(150.dp, 2.dp).clip(RoundedCornerShape(1.dp)).background(Color.White.copy(alpha = 0.12f))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = fraction
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(TvColors.Text2),
            )
        }
        TvText(left?.let { "${mmss(it)} 後更新代碼" }.orEmpty(), CountdownStyle)
    }
}

@Composable
private fun PasswordStage(state: LoginState, onUsePairing: () -> Unit, onSubmit: () -> Unit) {
    Column(
        Modifier
            .width(320.dp)
            .loginPanel { 0f }
            .padding(start = 32.dp, end = 32.dp, top = 26.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        TvText("帳號密碼登入", TvType.Title, Modifier.padding(bottom = 3.dp))
        TvTextField(
            state.username,
            { state.username = it },
            onSubmit = onSubmit,
            label = "帳號",
            action = "username",
            focusId = "login-username",
            autofocus = true,
        )
        TvTextField(
            state.password,
            { state.password = it },
            onSubmit = onSubmit,
            label = "密碼",
            action = "password",
            focusId = "login-password",
            password = true,
        )
        TvButton(
            if (state.busy) "登入中…" else "登入",
            onClick = { onSubmit() },
            modifier = Modifier.padding(top = 6.dp),
            style = TvButtonStyle.Primary,
            wide = true,
            action = "login",
        )
        TvText(
            state.error.orEmpty(),
            TvType.Error,
            Modifier.fillMaxWidth().heightIn(min = (12 * 1.3).dp),
            maxLines = 3,
            textAlign = TextAlign.Center,
        )
        TvButton(
            "改用手機登入",
            onClick = { onUsePairing() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            style = TvButtonStyle.Text,
            action = "use-pairing",
        )
    }
}

// ── styles ───────────────────────────────────────────────────────────────────

private val EyebrowStyle = TvType.Body.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.24.em, color = TvColors.Accent)
private val StepStyle = TvType.Body.copy(fontSize = 13.sp, lineHeight = 1.6.em, color = TvColors.Text2)
private val AltStyle = TvType.Body.copy(fontSize = 11.sp, color = TvColors.Text3)
private val UserCodeStyle = TvType.Body.copy(
    fontFamily = FontFamily.Monospace,
    fontSize = 36.sp,
    lineHeight = 1.1.em,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.14.em,
    fontFeatureSettings = TvType.TabularNums,
)
private val CountdownStyle = TvType.Caption.copy(color = TvColors.Text3, fontFeatureSettings = TvType.TabularNums)
private val QrWaitStyle = TvType.Body.copy(fontSize = 13.sp, color = TvColors.QrWait)

// ── pieces ───────────────────────────────────────────────────────────────────

/**
 * `.tv-pair` / `.tv-login-card`: radius 28px, `radial-gradient(ellipse at 0%
 * 0%, rgba(255,255,255,.07) 0%, transparent 55%)` over `--surface`, and the
 * inset 3px white ring (`::after`) at [ring] alpha.
 *
 * An ellipse at a corner with the default `farthest-corner` size has radii
 * w·√2 and h·√2, so the gradient is a circle of radius w·√2 scaled by h/w.
 */
private fun Modifier.loginPanel(ring: () -> Float): Modifier = this
    .clip(RoundedCornerShape(LoginPanelRadius))
    .drawWithCache {
        val w = size.width
        val h = size.height
        val glow = Brush.radialGradient(
            0f to Color.White.copy(alpha = 0.07f),
            0.55f to Color.White.copy(alpha = 0f),
            center = Offset.Zero,
            radius = w * sqrt(2f),
        )
        val stroke = 1.5.dp.toPx()
        val r = LoginPanelRadius.toPx()
        onDrawWithContent {
            drawRect(TvColors.Surface)
            scale(1f, h / w, pivot = Offset.Zero) { drawRect(glow, size = Size(w, w)) }
            drawContent()
            val a = ring()
            if (a > 0f) {
                drawRoundRect(
                    TvColors.Focus.copy(alpha = a),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(w - stroke, h - stroke),
                    cornerRadius = CornerRadius(r - stroke / 2),
                    style = Stroke(stroke),
                )
            }
        }
    }

private val LoginPanelRadius = 14.dp

/**
 * The two drifting colour blobs (`.tv-blobs`, `styles.css:763-790`): 1200px
 * circles of a closest-side radial gradient, drifting with CSS `ease-in-out`,
 * infinite and alternating (26 s and 34 s).
 */
@Composable
private fun LoginBlobs() {
    val t = rememberInfiniteTransition(label = "blobs")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(androidx.compose.animation.core.tween(26_000, easing = TvMotion.CssEaseInOut), RepeatMode.Reverse), label = "blob-a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(androidx.compose.animation.core.tween(34_000, easing = TvMotion.CssEaseInOut), RepeatMode.Reverse), label = "blob-b")
    Box(Modifier.fillMaxSize().clipToBounds()) {
        Blob(Modifier.offset(x = (-180).dp, y = (-260).dp), TvColors.Accent.copy(alpha = 0.2f)) { Offset(160f * a, 90f * a) }
        // bottom -560px / right -320px: top-left at (960 + 160 − 600, 540 + 280 − 600).
        Blob(Modifier.offset(x = 520.dp, y = 220.dp), TvColors.LoginBlobB) { Offset(-140f * b, -100f * b) }
    }
}

@Composable
private fun Blob(modifier: Modifier, color: Color, drift: () -> Offset) {
    Box(
        modifier
            .size(600.dp)
            .graphicsLayer {
                val d = drift()
                translationX = d.x.dp.toPx()
                translationY = d.y.dp.toPx()
            }
            .drawBehind { drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)))) },
    )
}

/**
 * The QR as Tizen renders it (`login.rs` `qr_svg`): error correction M (the
 * `qrcode` crate's default), the standard 4-module quiet zone, black on
 * white, scaled to fill [size]. Module edges are snapped to whole pixels so
 * the square stays crisp at any density.
 */
@Composable
fun TvQrCode(content: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(content) { encodeQr(content) }
    Canvas(modifier.size(size)) {
        val m = matrix ?: return@Canvas
        drawRect(Color.White)
        val n = m.width
        val px = this.size.width
        fun edge(i: Int) = (i * px / n).roundToInt().toFloat()
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (!m[x, y]) continue
                val l = edge(x)
                val t = edge(y)
                drawRect(Color.Black, Offset(l, t), Size(edge(x + 1) - l, edge(y + 1) - t))
            }
        }
    }
}

internal fun encodeQr(content: String): BitMatrix? = runCatching {
    QRCodeWriter().encode(
        content,
        BarcodeFormat.QR_CODE,
        0,
        0,
        // No character-set hint: an ECI header costs bytes and confuses some readers.
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4),
    )
}.getOrNull()
