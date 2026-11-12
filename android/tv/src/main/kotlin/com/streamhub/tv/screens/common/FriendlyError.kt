package com.streamhub.tv.screens.common

import com.streamhub.core.net.StreamHubException
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.math.ceil

/**
 * Tizen's role refusal (`login.rs` `accept`). `:core` throws an English one
 * from `login()` and `pollDevicePairing()` that rule 5 ("admin") would not
 * catch, so it is mapped explicitly (PARITY §9 item 10).
 */
const val ADMIN_REFUSAL = "管理員帳號無法在電視上觀看，請使用一般觀眾帳號。"

/**
 * `friendly_error` (`tizen/app/src/screens/common.rs:313-359`, PARITY §3.7):
 * the server and the network speak English, the screen speaks zh-TW. Known
 * messages and statuses get Chinese copy; a message already in Chinese is
 * shown as it is. [status] 0 means no answer; [offline] is Tizen's
 * `is_offline()`.
 */
fun friendlyError(status: Int, message: String, offline: Boolean = false): String {
    val msg = message.trim()
    val lower = msg.asciiLower()
    if (msg.any { it in '一'..'鿿' }) return msg
    if ("timeout" in lower || "timed out" in lower || "aborted" in lower) return "連線逾時，請再試一次"
    if (offline || "failed to fetch" in lower || "network" in lower) return "無法連線到伺服器"
    if ("invalid credentials" in lower) return "帳號或密碼不正確"
    if ("admin" in lower) return ADMIN_REFUSAL
    if ("too many" in lower || status == 429) {
        // The first run of ASCII digits, as Rust's split / find / parse::<u64>.
        val n = Regex("[0-9]+").find(lower)?.value?.toLongOrNull()
        return when {
            n != null && "second" in lower && n >= 60 -> "嘗試次數過多，請在 ${ceil(n / 60.0).toLong()} 分鐘後再試"
            n != null && "second" in lower -> "嘗試次數過多，請在 $n 秒後再試"
            n != null && "minute" in lower -> "嘗試次數過多，請在 $n 分鐘後再試"
            else -> "嘗試次數過多，請稍後再試"
        }
    }
    if ("expired" in lower) return "代碼已過期，請重新取得"
    if ("not found" in lower || status == 404) return "找不到這個內容"
    val detail = if (msg.isEmpty() || msg.startsWith("伺服器錯誤")) "" else "：$msg"
    return when (status) {
        401 -> "登入已失效，請重新登入"
        403 -> "沒有權限"
        400 -> "要求的內容不正確$detail"
        in 500..599 -> "伺服器發生錯誤$detail"
        0 -> "無法連線到伺服器"
        else -> "發生錯誤（$status）$detail"
    }
}

/**
 * [friendlyError] for whatever a `:core` call threw. A [StreamHubException]
 * carries the server's status and message. A timeout reads as one. Any other
 * [IOException] is "no answer" (status 0, offline). An unreadable body is
 * Tizen's "伺服器回應不正確".
 */
fun friendlyError(error: Throwable): String = when (error) {
    is StreamHubException ->
        if (error.status == 403 && error.message.startsWith("This account cannot play anything")) {
            ADMIN_REFUSAL
        } else {
            // A body with no `error` field: `:core` says "Request failed (N).", Tizen
            // "伺服器錯誤（N）" (api.rs:130-137), which rule 1 then shows as it is.
            val tizen = if (CORE_FALLBACK.matches(error.message)) "伺服器錯誤（${error.status}）" else error.message
            friendlyError(error.status, tizen)
        }
    is InterruptedIOException -> friendlyError(0, "timeout")
    is IOException -> friendlyError(0, error.message.orEmpty(), offline = true)
    // kotlinx.serialization's exceptions extend IllegalArgumentException.
    is IllegalArgumentException -> "伺服器回應不正確"
    else -> friendlyError(0, error.message.orEmpty())
}

private val CORE_FALLBACK = Regex("""Request failed \(\d+\)\.""")

private fun String.asciiLower(): String =
    String(CharArray(length) { i -> this[i].let { if (it in 'A'..'Z') it + 32 else it } })
