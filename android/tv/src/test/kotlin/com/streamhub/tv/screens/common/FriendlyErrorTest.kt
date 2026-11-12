package com.streamhub.tv.screens.common

import com.streamhub.core.net.StreamHubException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** `friendly_error` (common.rs:313-359), rule by rule. */
class FriendlyErrorTest {
    private fun f(status: Int, msg: String) = friendlyError(status, msg)

    @Test
    fun chineseMessagesPassThrough() = assertEquals("伺服器忙碌", f(500, "伺服器忙碌"))

    @Test
    fun timeoutsAndOffline() {
        assertEquals("連線逾時，請再試一次", f(0, "The operation was aborted due to timeout"))
        assertEquals("連線逾時，請再試一次", friendlyError(SocketTimeoutException("x")))
        assertEquals("無法連線到伺服器", f(0, "Failed to fetch"))
        assertEquals("無法連線到伺服器", friendlyError(IOException("Failed to connect")))
        assertEquals("無法連線到伺服器", f(0, ""))
    }

    @Test
    fun credentialsAndRoles() {
        assertEquals("帳號或密碼不正確", f(401, "Invalid credentials."))
        assertEquals(ADMIN_REFUSAL, f(403, "Admin accounts cannot sign in here."))
        assertEquals(ADMIN_REFUSAL, friendlyError(StreamHubException(403, "This account cannot play anything. Sign in with a viewer account.")))
    }

    @Test
    fun rateLimits() {
        assertEquals("嘗試次數過多，請在 2 分鐘後再試", f(429, "Too many attempts. Try again in 61 seconds."))
        assertEquals("嘗試次數過多，請在 30 秒後再試", f(429, "Too many attempts. Try again in 30 seconds."))
        assertEquals("嘗試次數過多，請在 5 分鐘後再試", f(429, "Too many attempts, wait 5 minutes"))
        assertEquals("嘗試次數過多，請稍後再試", f(429, "Slow down"))
    }

    @Test
    fun statuses() {
        assertEquals("代碼已過期，請重新取得", f(400, "Code expired."))
        assertEquals("找不到這個內容", f(404, "Request failed (404)."))
        assertEquals("登入已失效，請重新登入", f(401, "Session ended."))
        assertEquals("沒有權限", f(403, "Forbidden."))
        assertEquals("要求的內容不正確：Bad body", f(400, "Bad body"))
        assertEquals("伺服器發生錯誤：Request failed (502).", f(502, "Request failed (502)."))
        assertEquals("伺服器發生錯誤", f(500, ""))
        assertEquals("發生錯誤（418）：teapot", f(418, "teapot"))
    }

    @Test
    fun aBodyWithoutAnErrorFieldReadsAsTizensFallback() {
        assertEquals("伺服器錯誤（502）", friendlyError(StreamHubException(502, "Request failed (502).")))
        assertEquals("伺服器發生錯誤：Upstream down", friendlyError(StreamHubException(502, "Upstream down")))
    }
}
