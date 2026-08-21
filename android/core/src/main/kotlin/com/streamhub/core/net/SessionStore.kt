package com.streamhub.core.net

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.streamhub.core.model.Session
import kotlinx.serialization.json.Json

/**
 * Where the tokens live. An interface because the authenticator has to read and
 * replace them from a background thread, and because the encrypted
 * implementation needs a real device — tests use the in-memory one.
 */
interface SessionStore {
    fun load(): Session?
    fun save(session: Session)
    fun clear()
}

class InMemorySessionStore(initial: Session? = null) : SessionStore {
    @Volatile
    private var session: Session? = initial

    override fun load(): Session? = session
    override fun save(session: Session) { this.session = session }
    override fun clear() { session = null }
}

/**
 * Tokens at rest are encrypted with a key held in the Android keystore. A
 * 30-day refresh token is the whole session, so it does not belong in plain
 * SharedPreferences.
 *
 * EncryptedSharedPreferences is deprecated as of security-crypto 1.1.0 with no
 * drop-in replacement, so this is a known and deliberate piece of debt: it is
 * the battle-tested option today, and [SessionStore] exists precisely so
 * swapping it for a hand-rolled AES/GCM store over the Android keystore is one
 * file rather than a refactor.
 */
@Suppress("DEPRECATION")
class EncryptedSessionStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : SessionStore {

    private val prefs by lazy {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): Session? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        return runCatching { json.decodeFromString<Session>(raw) }.getOrNull()
    }

    override fun save(session: Session) {
        prefs.edit().putString(KEY_SESSION, json.encodeToString(session)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private companion object {
        const val PREFS_NAME = "streamhub.session"
        const val KEY_SESSION = "session"
    }
}
