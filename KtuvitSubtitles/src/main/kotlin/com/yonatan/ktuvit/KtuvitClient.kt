package com.yonatan.ktuvit

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Thin client for ktuvit.me.
 *
 * The flow mirrors the unofficial Ktuvit JS wrapper used by the Stremio addon:
 * login -> "Login" cookie, search by name/year, pull the subtitle rows out of the
 * HTML, ask for a one time download identifier, then download the file itself.
 */
object KtuvitClient {
    private const val BASE = "https://www.ktuvit.me/"
    private const val LOGIN_URL = BASE + "Services/MembershipService.svc/Login"
    private const val SEARCH_URL = BASE + "Services/ContentProvider.svc/SearchPage_search"
    private const val MOVIE_INFO_URL = BASE + "MovieInfo.aspx?ID="
    private const val EPISODE_MODULE_URL = BASE + "Services/GetModuleAjax.ashx?"
    private const val REQUEST_DOWNLOAD_URL =
        BASE + "Services/ContentProvider.svc/RequestSubtitleDownload"
    private const val DOWNLOAD_URL = BASE + "Services/DownloadFile.ashx?DownloadIdentifier="

    private const val ACCEPT_JSON = "application/json, text/javascript, */*; q=0.01"

    // ---------------------------------------------------------------- models

    data class Film(
        @JsonProperty("ID") val id: String? = null,
        @JsonProperty("EngName") val engName: String? = null,
        @JsonProperty("HebName") val hebName: String? = null,
        @JsonProperty("ImdbID") val imdbId: String? = null,
        @JsonProperty("IMDB_Link") val imdbLink: String? = null,
        @JsonProperty("ReleaseDate") val releaseDate: String? = null,
    ) {
        val displayName: String
            get() = engName?.takeIf { it.isNotBlank() } ?: hebName.orEmpty()
    }

    data class KtuvitSubtitle(
        val id: String,
        val name: String,
        val credit: String? = null,
        val downloads: Int = 0,
        val fileType: String? = null,
    )

    private data class DWrapper(@JsonProperty("d") val d: String? = null)

    private data class SearchResults(
        @JsonProperty("Films") val films: List<Film>? = null,
        @JsonProperty("ErrorMessage") val errorMessage: String? = null,
    )

    private data class DownloadIdentifier(
        @JsonProperty("DownloadIdentifier") val downloadIdentifier: String? = null,
    )

    // ----------------------------------------------------------------- auth

    private fun headers(cookie: String) = mapOf(
        "accept" to ACCEPT_JSON,
        "cookie" to "Login=$cookie",
    )

    /**
     * Ktuvit hashes the password in the browser before sending it, so [hashedPassword]
     * is the hashed value and not the plain one.
     *
     * @return the value of the "Login" cookie, or null when the credentials were refused.
     */
    suspend fun login(email: String, hashedPassword: String): String? {
        val response = app.post(
            LOGIN_URL,
            headers = mapOf("accept" to ACCEPT_JSON),
            json = mapOf("request" to mapOf("Email" to email, "Password" to hashedPassword)),
        )

        response.cookies["Login"]?.let { return it }

        // Fall back to reading the raw header in case the cookie jar skipped it.
        return response.okhttpResponse.headers("set-cookie")
            .firstOrNull { it.startsWith("Login=") }
            ?.substringAfter("Login=")
            ?.substringBefore(";")
            ?.takeIf { it.isNotBlank() }
    }

    // --------------------------------------------------------------- search

    /** Searches by title name, optionally narrowing by year and by movie/series. */
    suspend fun search(
        cookie: String,
        name: String,
        year: Int? = null,
        isSeries: Boolean = false,
    ): List<Film> {
        val request = mapOf(
            "FilmName" to name,
            "Actors" to emptyList<String>(),
            "Studios" to null,
            "Directors" to emptyList<String>(),
            "Genres" to emptyList<String>(),
            "Countries" to emptyList<String>(),
            "Languages" to emptyList<String>(),
            "Year" to (year?.toString() ?: ""),
            "Rating" to emptyList<String>(),
            "Page" to 1,
            "SearchType" to if (isSeries) "1" else "0",
            "WithSubsOnly" to false,
        )

        val body = app.post(
            SEARCH_URL,
            headers = headers(cookie),
            json = mapOf("request" to request),
        ).text

        val inner = tryParseJson<DWrapper>(body)?.d ?: return emptyList()
        val results = tryParseJson<SearchResults>(inner) ?: return emptyList()
        if (!results.errorMessage.isNullOrBlank()) return emptyList()
        return results.films.orEmpty()
    }

    /** Picks the film whose IMDB id matches, falling back to the first result. */
    fun matchFilm(films: List<Film>, imdbId: String?): Film? {
        if (films.isEmpty()) return null
        val wanted = imdbId?.trim()?.lowercase()
        if (!wanted.isNullOrBlank()) {
            films.firstOrNull { film ->
                val candidate = film.imdbId?.trim()?.lowercase()
                !candidate.isNullOrBlank() && (wanted.contains(candidate) || candidate.contains(wanted))
            }?.let { return it }
        }
        return films.firstOrNull()
    }

    // ------------------------------------------------------ subtitle lists

    suspend fun movieSubtitles(cookie: String, filmId: String): List<KtuvitSubtitle> {
        val html = app.get(MOVIE_INFO_URL + filmId, headers = headers(cookie)).text
        return parseSubtitleRows(html)
    }

    suspend fun episodeSubtitles(
        cookie: String,
        seriesId: String,
        season: Int,
        episode: Int,
    ): List<KtuvitSubtitle> {
        val url = EPISODE_MODULE_URL +
            "moduleName=SubtitlesList&SeriesID=$seriesId&Season=$season&Episode=$episode"
        val html = app.get(url, headers = headers(cookie)).text
        return parseSubtitleRows(html)
    }

    /**
     * The movie page returns a full document while the episode module returns bare
     * table rows, so the rows get wrapped before parsing.
     */
    private fun parseSubtitleRows(html: String): List<KtuvitSubtitle> {
        if (html.isBlank()) return emptyList()

        val document = if (html.contains("<!DOCTYPE html>", ignoreCase = true)) {
            Jsoup.parse(html)
        } else {
            Jsoup.parse("<table id=\"subtitlesList\"><tbody>$html</tbody></table>")
        }

        val table = document.getElementById("subtitlesList") ?: return emptyList()

        return table.select("tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 6) return@mapNotNull null

            // Rows without a <div> in the first cell are the "no subtitles" placeholder.
            val nameCell = cells[0].selectFirst("div") ?: return@mapNotNull null
            val id = cells[5].selectFirst("[data-subtitle-id]")
                ?.attr("data-subtitle-id")
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val name = Jsoup.parse(nameCell.html().substringBefore("<br")).text().trim()
            if (name.isBlank()) return@mapNotNull null

            KtuvitSubtitle(
                id = id,
                name = name,
                credit = nameCell.selectFirst("small")?.text()?.trim(),
                downloads = cells[4].text().filter { it.isDigit() }.toIntOrNull() ?: 0,
                fileType = cells[1].text().trim().ifBlank { null },
            )
        }
    }

    // ------------------------------------------------------------- download

    /**
     * Downloads a subtitle and writes it as UTF-8 to a temporary file.
     * Every download needs a fresh one time identifier.
     */
    suspend fun downloadSubtitle(cookie: String, filmId: String, subtitleId: String): File? {
        val identifierBody = app.post(
            REQUEST_DOWNLOAD_URL,
            headers = headers(cookie),
            json = mapOf(
                "request" to mapOf(
                    "FilmID" to filmId,
                    "SubtitleID" to subtitleId,
                    "FontSize" to 0,
                    "FontColor" to "",
                    "PredefinedLayout" to -1,
                )
            ),
        ).text

        val inner = tryParseJson<DWrapper>(identifierBody)?.d ?: return null
        val identifier = tryParseJson<DownloadIdentifier>(inner)?.downloadIdentifier
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val bytes = app.get(DOWNLOAD_URL + identifier, headers = headers(cookie)).body.bytes()
        if (bytes.isEmpty()) return null

        val subtitleBytes = if (isZip(bytes)) firstZipEntry(bytes) ?: return null else bytes

        val file = File.createTempFile("ktuvit-", ".srt")
        file.writeText(decode(subtitleBytes), Charsets.UTF_8)
        return file
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun firstZipEntry(bytes: ByteArray): ByteArray? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val lowerName = entry.name.lowercase()
                if (!entry.isDirectory &&
                    (lowerName.endsWith(".srt") || lowerName.endsWith(".sub") ||
                        lowerName.endsWith(".ass") || lowerName.endsWith(".ssa"))
                ) {
                    return zip.readBytes()
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /**
     * Ktuvit files are usually windows-1255, but some are already UTF-8.
     * A strict UTF-8 decode tells the two apart.
     */
    private fun decode(bytes: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            val hebrew = runCatching { Charset.forName("windows-1255") }
                .getOrElse { runCatching { Charset.forName("ISO-8859-8") }.getOrNull() }
                ?: return String(bytes, Charsets.UTF_8)
            String(bytes, hebrew)
        }
    }
}
