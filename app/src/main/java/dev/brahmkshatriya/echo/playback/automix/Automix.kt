package dev.brahmkshatriya.echo.playback.automix

import dev.brahmkshatriya.echo.common.models.Track

/**
 * Picks which radio candidates to append so an automix stays coherent instead of
 * jumping between unrelated artists.
 *
 * Candidates are dropped if they are already in the queue or are the track that
 * started the radio, then ranked by overlap with the current track: shared
 * artists weigh most, then a shared album, then shared genres. The tail of the
 * queue gets a smaller artist bonus so consecutive picks do not drift apart.
 */
object Automix {

    const val KEY_ENABLED = "automix_enabled"
    const val KEY_LOOKAHEAD = "automix_lookahead"
    const val KEY_BATCH = "automix_batch"

    const val DEFAULT_LOOKAHEAD = 2
    const val DEFAULT_BATCH = 5
    const val MIN_LOOKAHEAD = 1
    const val MAX_LOOKAHEAD = 5
    const val MIN_BATCH = 1
    const val MAX_BATCH = 20

    /**
     * @param candidates radio tracks to choose from
     * @param current the track that is playing, used as the primary reference
     * @param queued tracks already in the queue, used for de-duplication and as a
     *   secondary reference
     * @param limit how many tracks to return
     */
    fun select(
        candidates: List<Track>,
        current: Track?,
        queued: List<Track>,
        limit: Int,
    ): List<Track> {
        if (limit <= 0) return emptyList()
        val seen = HashSet<String>((queued.size + 1) * 2)
        current?.let { seen.add(it.id) }
        queued.forEach { seen.add(it.id) }

        val currentArtists = current?.artists?.map { it.id }?.toSet().orEmpty()
        val currentGenres = current?.genres?.toSet().orEmpty()
        val currentAlbum = current?.album?.id
        val lastQueued = queued.lastOrNull()
        val lastArtists = lastQueued?.artists?.map { it.id }?.toSet().orEmpty()

        return candidates.asSequence()
            .filter { seen.add(it.id) }
            .map { it to score(it, currentArtists, currentGenres, currentAlbum, lastArtists) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(
        track: Track,
        currentArtists: Set<String>,
        currentGenres: Set<String>,
        currentAlbum: String?,
        lastArtists: Set<String>,
    ): Int {
        if (currentArtists.isEmpty() && currentGenres.isEmpty() && currentAlbum == null) return 0
        val artists = track.artists.map { it.id }.toSet()
        var score = 3 * currentArtists.intersect(artists).size
        if (currentAlbum != null && currentAlbum == track.album?.id) score += 2
        score += currentGenres.intersect(track.genres.toSet()).size
        score += 2 * lastArtists.intersect(artists).size
        return score
    }
}
