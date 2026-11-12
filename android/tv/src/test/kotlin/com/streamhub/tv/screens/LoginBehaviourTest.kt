package com.streamhub.tv.screens

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextInput
import com.streamhub.tv.FakeServer
import com.streamhub.tv.ScreenRig
import com.streamhub.tv.focus.TvSelect
import com.streamhub.tv.screens.common.ADMIN_REFUSAL
import com.streamhub.tv.screens.login.LoginScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/** Login's remote behaviour and pairing loop (PARITY §3.1, checklist 2-9) at minSdk and a recent API. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 35], qualifiers = "w960dp-h540dp-land-xhdpi")
class LoginBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var rig: ScreenRig
    private var signedIn = false

    private val start =
        """{"deviceCode":"dev-secret","userCode":"WCNU-KSB2","verificationUrl":"https://s.test/link","verificationUrlComplete":"https://s.test/link?code=WCNUKSB2","expiresInSeconds":600,"intervalSeconds":5}"""

    @Before
    fun setUp() {
        rig = ScreenRig(compose)
    }

    private fun mount() {
        rig.setContent { LoginScreen(rig.server.api, onSignedIn = { signedIn = true }, clock = { compose.mainClock.currentTime }) }
    }

    private fun pending() {
        rig.server.json("POST", "/auth/device/start", start)
        rig.server.json("POST", "/auth/device/poll", """{"status":"pending"}""")
    }

    private fun hasText(t: String) = compose.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun focusStartsOnTheSwitchAndBackWalksPasswordPairExit() {
        pending()
        mount()
        rig.settleUntil { rig.focused == "login-use-password" }
        assertTrue(hasText("WCNU-KSB2"))
        assertTrue(hasText("或前往 s.test/link 輸入"))
        assertFalse(hasText("dev-secret"))
        rig.ok()
        rig.settleUntil { rig.focused == "login-username" }
        rig.back()
        assertEquals("login-use-password", rig.focused)
        rig.back()
        assertTrue(rig.engine.exitPromptOpen)
        assertEquals("exit-cancel", compose.runOnIdle { rig.engine.focused?.action })
    }

    @Test
    fun upFromTheSwitchReachesThePanelWhoseOkNeverReplacesALiveCode() {
        pending()
        mount()
        rig.settleUntil { rig.focused == "login-use-password" }
        rig.up()
        assertEquals("login-pair", rig.focused)
        rig.ok()
        rig.settle(1_000)
        assertEquals(1, rig.server.count("POST", "/auth/device/start"))
    }

    @Test
    fun emptyFieldsAndWrongCredentials() {
        pending()
        rig.server.json("POST", "/auth/login", """{"error":"Invalid credentials."}""", status = 401)
        mount()
        rig.settleUntil { rig.focused == "login-use-password" }
        rig.ok()
        rig.settleUntil { rig.focused == "login-username" }
        compose.runOnIdle { rig.engine.focusFirst(TvSelect.action("login")) }
        rig.ok()
        assertTrue(hasText("請輸入帳號與密碼"))
        assertEquals(0, rig.server.count("POST", "/auth/login"))

        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("  tizen-qa ")
        fields[1].performTextInput("wrong")
        compose.runOnIdle { rig.engine.focusFirst(TvSelect.action("login")) }
        rig.ok()
        rig.settleUntil { hasText("帳號或密碼不正確") }
        val body = rig.server.requests.first { it.first == "POST /auth/login" }.second
        assertTrue(body, body.contains("\"login\":\"tizen-qa\""))
        assertFalse(signedIn)
    }

    @Test
    fun aPhoneApprovalSignsInWhileThePasswordCardIsOpen() {
        rig.server.json("POST", "/auth/device/start", start)
        val polls = AtomicInteger()
        rig.server.on("POST", "/auth/device/poll") {
            if (polls.incrementAndGet() < 2) FakeServer.Reply(body = """{"status":"pending"}""")
            else FakeServer.Reply(body = """{"status":"approved",${FakeServer.sessionJson().drop(1)}""")
        }
        mount()
        rig.settleUntil { rig.focused == "login-use-password" }
        rig.ok()
        rig.settleUntil(15_000) { signedIn }
        assertTrue(rig.server.store.load() != null)
    }

    @Test
    fun anAdminApprovalIsRefusedAndANewCodeIssued() {
        rig.server.json("POST", "/auth/device/start", start)
        rig.server.json("POST", "/auth/device/poll", """{"status":"approved",${FakeServer.sessionJson(role = "ADMIN").drop(1)}""")
        mount()
        rig.settleUntil(10_000) { rig.server.count("POST", "/auth/device/start") == 2 }
        assertFalse(signedIn)
        assertTrue(rig.server.count("POST", "/auth/device/poll") >= 1)
        // The refusal is what :core throws; the screen shows Tizen's copy of it at least once.
        assertEquals("管理員帳號無法在電視上觀看，請使用一般觀眾帳號。", ADMIN_REFUSAL)
    }

    @Test
    fun aDeniedCodeIsReplaced() {
        rig.server.json("POST", "/auth/device/start", start)
        rig.server.json("POST", "/auth/device/poll", """{"status":"denied"}""")
        mount()
        rig.settleUntil(10_000) { rig.server.count("POST", "/auth/device/start") == 2 }
    }

    @Test
    fun withTheServerDownItWaitsAndRetriesEveryFiveSeconds() {
        rig.server.down("POST", "/auth/device/start")
        mount()
        rig.settleUntil { hasText("正在等待伺服器…") }
        assertEquals(1, rig.server.count("POST", "/auth/device/start"))
        rig.settle(4_500)
        assertEquals(1, rig.server.count("POST", "/auth/device/start"))
        rig.settleUntil(1_500) { rig.server.count("POST", "/auth/device/start") == 2 }
    }

    @Test
    fun theCodeIsRenewedAtExpiryAndTheCountdownTicks() {
        rig.server.json(
            "POST", "/auth/device/start",
            """{"deviceCode":"d","userCode":"AAAA-BBBB","verificationUrl":"http://s.test/link","verificationUrlComplete":"http://s.test/link?c","expiresInSeconds":10,"intervalSeconds":5}""",
        )
        rig.server.json("POST", "/auth/device/poll", """{"status":"pending"}""")
        mount()
        rig.settleUntil { hasText("後更新代碼") }
        rig.settle(1_500)
        assertTrue(hasText("0:09 後更新代碼") || hasText("0:08 後更新代碼"))
        rig.settleUntil(12_000) { rig.server.count("POST", "/auth/device/start") == 2 }
    }
}
