package com.streamhub.tv.screens.common

import androidx.compose.runtime.mutableStateMapOf
import com.streamhub.core.net.StreamHubApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Provider display names ("MovieFFM"), fetched once per process from
 * `/me/providers?all=true`. Port of Tizen's `ensure_provider_names` /
 * `provider_name` (tizen/app/src/screens/common.rs).
 */
object ProviderNames {
    private val names = mutableStateMapOf<String, String>()
    @Volatile private var asked = false

    /** Fire-and-forget; runs at most once, and again on a later call after a failure. */
    fun ensure(api: StreamHubApi, scope: CoroutineScope) {
        if (asked) return
        asked = true
        scope.launch {
            runCatching { api.providers(includeUnavailable = true) }
                .onSuccess { list -> list.forEach { names[it.key] = it.name } }
                // Try again on the next screen.
                .onFailure { asked = false }
        }
    }

    /** The display name, falling back to the key. Reactive: reads snapshot state. */
    fun name(key: String): String = names[key] ?: key
}
