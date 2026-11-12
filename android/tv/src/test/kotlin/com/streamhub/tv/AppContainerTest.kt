package com.streamhub.tv

import com.streamhub.tv.screens.search.SearchSession
import org.junit.Assert.assertTrue
import org.junit.Test

/** PARITY §10 item 39: the HTTP client must outlast Search's 20 s deadline, or a slow provider reads 連線逾時 at 10 s. */
class AppContainerTest {
    @Test
    fun theApiClientWaitsLongerThanTheSearchDeadline() {
        val client = AppContainer.apiBaseClient()
        assertTrue(client.readTimeoutMillis > SearchSession.DEADLINE_MS)
    }
}
