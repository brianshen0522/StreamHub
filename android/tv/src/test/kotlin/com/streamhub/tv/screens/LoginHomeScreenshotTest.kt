package com.streamhub.tv.screens

import android.view.KeyEvent
import androidx.compose.ui.test.junit4.createComposeRule
import com.streamhub.tv.FakeServer
import com.streamhub.tv.Library
import com.streamhub.tv.Library.library
import com.streamhub.tv.ScreenRig
import com.streamhub.tv.screens.home.HomeScreen
import com.streamhub.tv.screens.login.LoginScreen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Login and Home at 1920×1080 with fake data, for comparison with the Tizen
 * references (PARITY §3.1-3.2). PNGs: android/tv/build/outputs/roborazzi/.
 *
 * Run: ./build-env/gradle.sh :tv:testDebugUnitTest --tests '*LoginHomeScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-xhdpi")
class LoginHomeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var rig: ScreenRig

    @Before
    fun setUp() {
        rig = ScreenRig(compose, font = true)
        ScreenRig.installFakePosters()
    }

    private fun pairingServer() {
        rig.server.json(
            "POST", "/auth/device/start",
            """{"deviceCode":"dev-secret","userCode":"WCNU-KSB2","verificationUrl":"https://streamhub.gugulu.tw/link","verificationUrlComplete":"https://streamhub.gugulu.tw/link?code=WCNUKSB2","expiresInSeconds":600,"intervalSeconds":5}""",
        )
        rig.server.json("POST", "/auth/device/poll", """{"status":"pending"}""")
    }

    private fun login() {
        rig.setContent { LoginScreen(rig.server.api, onSignedIn = {}, clock = { compose.mainClock.currentTime }) }
    }

    @Test
    fun loginPairing() {
        pairingServer()
        login()
        rig.settleUntil { rig.focused == "login-use-password" }
        rig.settle(5_000)
        rig.capture("login_pairing")
    }

    @Test
    fun loginWaitingForServer() {
        rig.server.down("POST", "/auth/device/start")
        login()
        rig.settle(1_500)
        rig.capture("login_waiting")
    }

    @Test
    fun loginPassword() {
        pairingServer()
        login()
        rig.settleUntil { rig.focused == "login-use-password" }
        rig.ok()
        rig.settleUntil { rig.focused == "login-username" }
        rig.settle(500)
        rig.capture("login_password")
    }

    @Test
    fun loginPasswordError() {
        pairingServer()
        login()
        rig.settleUntil { rig.focused == "login-use-password" }
        rig.ok()
        rig.settleUntil { rig.focused == "login-username" }
        rig.down()
        rig.down()
        assertEquals("login", rig.focused)
        rig.ok()
        rig.settle(500)
        rig.capture("login_password_error")
    }

    private fun home(empty: Boolean = false) {
        if (empty) {
            rig.server.library(cont = """{"items":[]}""", favs = """{"favorites":[]}""", hist = """{"history":[]}""")
        } else {
            rig.server.library()
        }
        rig.setContent { HomeScreen(rig.server.api, userName = "Tizen QA", onSignedOut = {}) }
    }

    @Test
    fun homeLoading() {
        val gate = java.util.concurrent.CountDownLatch(1)
        rig.server.library()
        rig.server.on("GET", "/me/continue-watching") {
            gate.await()
            FakeServer.Reply(body = Library.cont())
        }
        rig.setContent { HomeScreen(rig.server.api, userName = "Tizen QA", onSignedOut = {}) }
        rig.settle(1_000)
        rig.capture("home_loading")
        gate.countDown()
    }

    @Test
    fun homeTopBarAndBrowsing() {
        home()
        rig.settleUntil { rig.focused == "cw:p1" }
        rig.settle(1_000)
        // Up into the top bar: the bar returns, the rows rest at .72, then 音效 as in sfx-home.png.
        rig.up()
        rig.right()
        assertEquals("home-sfx", rig.focused)
        rig.settle(600)
        rig.capture("home_rails")
        rig.down()
        rig.settle(600)
        rig.capture("home_browsing")
        rig.right()
        rig.right()
        rig.settle(800)
        rig.capture("home_browsing_moved")
        rig.down()
        rig.settle(800)
        rig.capture("home_browsing_row2")
    }

    @Test
    fun homeCardMenu() {
        home()
        rig.settleUntil { rig.focused == "cw:p1" }
        rig.right()
        rig.press(KeyEvent.KEYCODE_DPAD_CENTER, holdMs = 800)
        rig.settle(600)
        assertEquals("menu-remove", rig.compose.runOnIdle { rig.engine.focused?.action })
        rig.capture("home_card_menu")
    }

    @Test
    fun homeSignOutConfirm() {
        home()
        rig.settleUntil { rig.focused == "cw:p1" }
        rig.up()
        rig.right()
        rig.right()
        assertEquals("home-signout", rig.focused)
        rig.ok()
        rig.settle(600)
        assertEquals("signout-cancel", rig.focused)
        rig.capture("home_signout_confirm")
    }

    @Test
    fun homeEmpty() {
        home(empty = true)
        rig.settleUntil { rig.focused == "home-search" }
        rig.settle(600)
        rig.capture("home_empty")
    }

    @Test
    fun homeExitPrompt() {
        home()
        rig.settleUntil { rig.focused == "cw:p1" }
        rig.settle(600)
        rig.back()
        assertEquals("exit-cancel", rig.compose.runOnIdle { rig.engine.focused?.action })
        rig.capture("home_exit_prompt")
    }
}
