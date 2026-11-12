package com.streamhub.tv.nav

/**
 * "Open this title and start playing" (`detail::request_autoplay` /
 * `take_autoplay`, `tizen/app/src/screens/detail.rs:257-275`). The Play key on
 * a Home card requests it and pushes Detail. Detail takes it once the episode
 * is resolved and replaces itself with the Player.
 *
 * One slot. [take] clears it whether or not it matched, so a stale request
 * never fires on a later, different title.
 */
object TvAutoplay {
    private var pending: Pair<String, String>? = null

    fun request(provider: String, url: String) {
        pending = provider to url
    }

    /** True (once) when the pending request is for this title. Clears any request. */
    fun take(provider: String, url: String): Boolean {
        val p = pending
        val hit = p != null && p.first == provider && p.second == url
        pending = null
        return hit
    }
}
