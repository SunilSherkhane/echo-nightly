package dev.brahmkshatriya.echo.playback.listener

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.playback.MediaItemUtils.track
import dev.brahmkshatriya.echo.utils.lastfm.LastfmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Submits Last.fm now-playing and scrobble updates.
 *
 * Scrobble rule (Last.fm's own): a track counts once it has played at least
 * half its length or four minutes, whichever comes first. Only actually-played
 * time is counted -- pausing stops the clock.
 */
class ScrobbleListener(
    private val context: Context,
    private val scope: CoroutineScope,
    private val session: MediaSession
) : Player.Listener {

    private val player get() = session.player
    private val repository by lazy { LastfmRepository(context) }

    private var pending: Pending? = null
    private var lastPositionMs = 0L

    private data class Pending(
        val track: Track,
        val playedMs: Long,
        val startedAtSec: Long
    )

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        accumulate()
        commit()
        val track = mediaItem?.track ?: run { pending = null; return }
        lastPositionMs = 0
        pending = Pending(track, 0, System.currentTimeMillis() / 1000)
        nowPlaying(track)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            lastPositionMs = position()
        } else {
            accumulate()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            accumulate()
            commit()
        }
    }

    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        accumulate()
    }

    private fun accumulate() {
        val current = pending ?: return
        val position = position()
        val delta = (position - lastPositionMs).coerceAtLeast(0)
        lastPositionMs = position
        pending = current.copy(playedMs = current.playedMs + delta)
    }

    private fun position(): Long =
        runCatching { player.currentPosition }.getOrDefault(lastPositionMs)

    private fun commit() {
        val current = pending ?: return
        pending = null
        val durationMs = current.track.duration ?: 0
        val half = durationMs / 2
        val qualifies = durationMs > 0 && (current.playedMs >= half || current.playedMs >= FOUR_MINUTES)
        if (!qualifies) return
        scope.launch {
            runCatching { repository.scrobble(current.track, current.startedAtSec) }
        }
    }

    private fun nowPlaying(track: Track) {
        scope.launch {
            runCatching { repository.nowPlaying(track) }
        }
    }

    companion object {
        private const val FOUR_MINUTES = 4 * 60 * 1000L
    }
}
