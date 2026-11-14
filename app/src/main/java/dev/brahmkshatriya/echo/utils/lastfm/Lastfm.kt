package dev.brahmkshatriya.echo.utils.lastfm

import android.content.Context
import androidx.core.content.edit
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.utils.ContextUtils.getSettings
import dev.brahmkshatriya.echo.utils.Serializer.toData
import dev.brahmkshatriya.echo.utils.Serializer.toJson
import dev.brahmkshatriya.echo.utils.crypto.SecretStores
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest

@Serializable
data class LastfmSession(val name: String = "", val key: String = "")

/**
 * Last.fm API status envelope. Every method returns one; a non-null [error]
 * means the call was rejected.
 */
@Serializable
data class LastfmStatus(
    val session: LastfmSession? = null,
    val error: Int? = null,
    val message: String? = null,
)

/**
 * Thin client for the Last.fm 2.0 API.
 *
 * Requests are form POSTs signed with an MD5 of the sorted `keyvalue` pairs
 * plus the API secret. See https://www.last.fm/api/mobile
 */
class LastfmClient(private val apiKey: String, private val apiSecret: String) {

    /** Exchanges a username + password for a long-lived session key. */
    suspend fun login(username: String, password: String): LastfmSession {
        val status = post(
            mapOf(
                "method" to "auth.getMobileSession",
                "username" to username,
                "password" to password,
            )
        )
        status.session?.key?.takeIf { it.isNotEmpty() }?.let { return status.session }
        throw Exception(status.message ?: "Last.fm refused the login")
    }

    suspend fun updateNowPlaying(
        sessionKey: String,
        track: String,
        artist: String,
        album: String?,
        durationSec: Long?
    ): LastfmStatus = post(
        mapOf(
            "method" to "track.updateNowPlaying",
            "sk" to sessionKey,
            "track" to track,
            "artist" to artist,
            "album" to album.orEmpty(),
            "duration" to durationSec?.toString().orEmpty(),
        )
    )

    suspend fun scrobble(
        sessionKey: String,
        track: String,
        artist: String,
        album: String?,
        timestampSec: Long
    ): LastfmStatus = post(
        mapOf(
            "method" to "track.scrobble",
            "sk" to sessionKey,
            "track" to track,
            "artist" to artist,
            "album" to album.orEmpty(),
            "timestamp" to timestampSec.toString(),
        )
    )

    private suspend fun post(params: Map<String, String>): LastfmStatus =
        withContext(Dispatchers.IO) {
            val signed = params + ("api_key" to apiKey)
            val body = FormBody.Builder().apply {
                signed.forEach { (key, value) -> add(key, value) }
                add("api_sig", sign(signed))
            }.build()
            val request = Request.Builder().url(ENDPOINT).post(body).build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Last.fm returned HTTP ${response.code}")
                }
                response.body.string().toData<LastfmStatus>().getOrThrow()
            }
        }

    private fun sign(params: Map<String, String>): String {
        // Sorted by parameter name, concatenated as key+value, secret appended.
        val joined = params.toSortedMap().entries
            .joinToString("") { "${it.key}${it.value}" }
        return MessageDigest.getInstance("MD5")
            .digest((joined + apiSecret).toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

        /** Shared by every client so scrobbles reuse one connection/thread pool. */
        private val client = OkHttpClient()
    }
}

/** Everything needed to scrobble, persisted as one encrypted blob. */
@Serializable
data class LastfmCredentials(
    val apiKey: String = "",
    val apiSecret: String = "",
    val username: String = "",
    val sessionKey: String = ""
)

/**
 * Stores the Last.fm account and submits now-playing / scrobble updates.
 *
 * The whole credential set -- including the session key and API secret -- is
 * held in a single AES-GCM encrypted preference via [SecretStores]. The
 * password is used once at connect time and never written to disk.
 */
class LastfmRepository(context: Context) {

    private val prefs = context.getSettings()

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    private var credentials: LastfmCredentials
        get() = prefs.getString(KEY_CREDENTIALS, null)
            ?.let { SecretStores.current?.decrypt(it) }
            ?.toData<LastfmCredentials>()?.getOrNull()
            ?: LastfmCredentials()
        set(value) {
            val plain = value.toJson()
            val encrypted = SecretStores.current?.encrypt(plain) ?: return
            prefs.edit {
                putString(KEY_CREDENTIALS, encrypted)
            }
        }

    val username: String get() = credentials.username
    val isConnected: Boolean get() = credentials.sessionKey.isNotEmpty()

    /** @return the account name that was connected. */
    suspend fun connect(
        username: String, password: String, apiKey: String, apiSecret: String
    ): String {
        val session = LastfmClient(apiKey, apiSecret).login(username.trim(), password)
        credentials = LastfmCredentials(
            apiKey = apiKey,
            apiSecret = apiSecret,
            username = session.name.ifEmpty { username.trim() },
            sessionKey = session.key
        )
        enabled = true
        return this.username
    }

    fun disconnect() {
        credentials = LastfmCredentials()
        enabled = false
    }

    suspend fun nowPlaying(track: Track) = submit { client, key ->
        client.updateNowPlaying(
            key, track.title, track.artistName, track.album?.title, track.duration?.let { it / 1000 }
        )
    }

    suspend fun scrobble(track: Track, timestampSec: Long) = submit { client, key ->
        client.scrobble(key, track.title, track.artistName, track.album?.title, timestampSec)
    }

    private suspend fun submit(block: suspend (LastfmClient, String) -> LastfmStatus) {
        if (!enabled || !isConnected) return
        val credentials = credentials
        // Scrobbling must never surface errors to the user; it is best-effort.
        runCatching {
            block(LastfmClient(credentials.apiKey, credentials.apiSecret), credentials.sessionKey)
        }
    }

    companion object {
        const val KEY_ENABLED = "lastfm_enabled"
        const val KEY_CREDENTIALS = "lastfm_credentials"
    }
}

/** Last.fm takes a single artist string; join multi-artist tracks. */
val Track.artistName: String get() = artists.joinToString(", ") { it.name }
