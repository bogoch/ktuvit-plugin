package com.yonatan.ktuvit

/**
 * Walks the whole chain step by step and reports where it stops.
 *
 * Without this there is no way to tell a failed injection (the provider is never
 * asked) from a failed search (it is asked and finds nothing).
 */
object KtuvitDiagnostics {

    suspend fun report(title: String, season: Int?, episode: Int?): String {
        val lines = mutableListOf<String>()

        // 1. Is the provider actually registered in the app right now?
        val providers = Injector.currentProviders()
        lines += if (providers.isEmpty()) {
            "1. providers: COULD NOT READ THE FIELD"
        } else {
            "1. providers: ${providers.joinToString(", ")}"
        }
        lines += if (providers.contains("ktuvit")) {
            "   -> injection OK"
        } else {
            "   -> KTUVIT IS NOT REGISTERED"
        }

        // 2. Credentials
        val cookie = try {
            KtuvitStore.resolveCookie()
        } catch (e: Throwable) {
            lines += "2. login: EXCEPTION ${e.javaClass.simpleName}: ${e.message}"
            null
        }

        if (cookie.isNullOrBlank()) {
            lines += "2. login: no cookie"
            return lines.joinToString("\n")
        }
        lines += "2. login: cookie ok (${cookie.length} chars)"

        val isSeries = (season ?: 0) > 0

        // 3. Search
        val films = try {
            KtuvitClient.search(cookie, title, null, isSeries)
        } catch (e: Throwable) {
            lines += "3. search: EXCEPTION ${e.javaClass.simpleName}: ${e.message}"
            return lines.joinToString("\n")
        }

        lines += "3. search '$title' (${if (isSeries) "series" else "movie"}): ${films.size} results"
        films.take(3).forEach { film ->
            lines += "   - ${film.displayName} | id=${film.id} | imdb=${film.imdbId}"
        }
        if (films.isEmpty()) return lines.joinToString("\n")

        val film = KtuvitClient.matchFilm(films, null)
        val filmId = film?.id
        if (filmId.isNullOrBlank()) {
            lines += "4. match: no usable film id"
            return lines.joinToString("\n")
        }
        lines += "4. match: ${film.displayName} (id=$filmId)"

        // 5. Subtitle list
        val subtitles = try {
            if (isSeries) {
                KtuvitClient.episodeSubtitles(cookie, filmId, season ?: 0, episode ?: 0)
            } else {
                KtuvitClient.movieSubtitles(cookie, filmId)
            }
        } catch (e: Throwable) {
            lines += "5. subtitles: EXCEPTION ${e.javaClass.simpleName}: ${e.message}"
            return lines.joinToString("\n")
        }

        lines += "5. subtitles: ${subtitles.size} found"
        subtitles.take(3).forEach { subtitle ->
            lines += "   - ${subtitle.name} | id=${subtitle.id}"
        }

        return lines.joinToString("\n")
    }
}
