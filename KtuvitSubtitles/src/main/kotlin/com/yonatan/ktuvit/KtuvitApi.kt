package com.yonatan.ktuvit

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleEntity
import com.lagradost.cloudstream3.subtitles.AbstractSubtitleEntities.SubtitleSearch
import com.lagradost.cloudstream3.subtitles.SubtitleResource
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.SubtitleAPI

/**
 * Hebrew subtitles from ktuvit.me.
 *
 * Credentials are kept by the plugin itself (see [KtuvitStore]) rather than by the
 * app's account system, so [requiresLogin] stays false and [auth] is always null.
 */
class KtuvitApi : SubtitleAPI() {
    override val name = "Ktuvit"
    override val idPrefix = "ktuvit"
    override val requiresLogin = false
    override val createAccountUrl = "https://www.ktuvit.me/"

    companion object {
        const val LANGUAGE = "he"

        /** SubtitleEntity.data carries both ids, the film and the subtitle itself. */
        private const val SEPARATOR = "|"

        fun encode(filmId: String, subtitleId: String) = "$filmId$SEPARATOR$subtitleId"
    }

    override suspend fun search(auth: AuthData?, query: SubtitleSearch): List<SubtitleEntity>? {
        // No language filter on purpose. The player passes whatever language is
        // selected in the subtitle dialog, and filtering on it made this provider
        // silently return nothing whenever that was not Hebrew. Ktuvit is Hebrew
        // only, and every entity below is tagged "he", so the UI labels them right.
        val title = query.query.trim()
        if (title.isBlank()) return emptyList()

        val cookie = KtuvitStore.resolveCookie() ?: return null

        val season = query.seasonNumber ?: 0
        val episode = query.epNumber ?: 0
        val isSeries = season > 0

        val films = KtuvitClient.search(cookie, title, query.year, isSeries)
        val film = KtuvitClient.matchFilm(films, query.imdbId) ?: return emptyList()
        val filmId = film.id?.takeIf { it.isNotBlank() } ?: return emptyList()

        val subtitles = if (isSeries) {
            KtuvitClient.episodeSubtitles(cookie, filmId, season, episode)
        } else {
            KtuvitClient.movieSubtitles(cookie, filmId)
        }

        return subtitles.map { subtitle ->
            SubtitleEntity(
                idPrefix = this.idPrefix,
                name = buildName(subtitle),
                lang = LANGUAGE,
                data = encode(filmId, subtitle.id),
                type = if (isSeries) TvType.TvSeries else TvType.Movie,
                source = this.name,
                epNumber = query.epNumber,
                seasonNumber = query.seasonNumber,
                year = query.year,
                isHearingImpaired = false,
            )
        }
    }

    private fun buildName(subtitle: KtuvitClient.KtuvitSubtitle): String {
        val credit = subtitle.credit?.takeIf { it.isNotBlank() }
        return if (credit == null) subtitle.name else "${subtitle.name} - $credit"
    }

    /**
     * Ktuvit needs a cookie and a one time token for every download, so the file is
     * fetched here and handed to the player as a local file instead of a URL.
     */
    override suspend fun SubtitleResource.getResources(auth: AuthData?, subtitle: SubtitleEntity) {
        val parts = subtitle.data.split(SEPARATOR)
        if (parts.size != 2) return

        val cookie = KtuvitStore.resolveCookie() ?: return
        val file = KtuvitClient.downloadSubtitle(cookie, parts[0], parts[1])
            ?: KtuvitStore.resolveCookie(forceRefresh = true)?.let { refreshed ->
                // An expired cookie looks like a failed download, so retry once.
                KtuvitClient.downloadSubtitle(refreshed, parts[0], parts[1])
            }
            ?: return

        this.addFile(file, subtitle.name)
    }
}
