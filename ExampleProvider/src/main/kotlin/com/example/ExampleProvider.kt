package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.net.URLDecoder

/**
 * MyCloudStream: HDFilmCehennemi.nl public catalogue diagnostic provider.
 *
 * DOM selector `a.poster` and `.poster-title` are grounded in the archived
 * 2026-11-01 HTML snapshot contained in BTVault-main.zip. Live access and
 * current site structure have NOT been verified.
 *
 * No protected playback extraction or access-control circumvention.
 */
class ExampleProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi NL (Test)"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true
    override val hasDownloadSupport = false

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa",
        "$mainUrl/category/film-izle-2/" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        return try {
            val document = app.get(url).document
            val cards = parseCatalogue(document)
            val entries = if (cards.isEmpty()) {
                listOf(diagnosticCard("HTML alindi fakat kart bulunamadi. Baslik: ${document.title().take(70)}; a.poster: ${document.select("a.poster").size}"))
            } else cards
            // Limit the diagnostic release to the first page; no unverified pagination.
            newHomePageResponse(request, entries, hasNext = false)
        } catch (error: Exception) {
            newHomePageResponse(request,
                listOf(diagnosticCard("Sayfa istegi basarisiz: ${error.message.orEmpty().take(140)}")),
                hasNext = false
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val url = "$mainUrl/?s=${URLEncoder.encode(query.trim(), "UTF-8")}" 
        return try {
            val document = app.get(url).document
            val cards = parseCatalogue(document)
            if (cards.isEmpty()) listOf(diagnosticCard("Arama sayfasi bos veya farkli yapida: ${document.title().take(90)}"))
            else cards
        } catch (error: Exception) {
            listOf(diagnosticCard("Arama istegi basarisiz: ${error.message.orEmpty().take(140)}"))
        }
    }

    private fun parseCatalogue(document: Document): List<SearchResponse> {
        val unique = HashSet<String>()
        val candidates = document.select("a.poster[href], article.item, a[class*=group/poster][href]")

        return candidates.mapNotNull { entry: Element ->
            val link = if (entry.tagName().equals("a", ignoreCase = true)) entry
                else entry.selectFirst("div.poster a[href], a[href]") ?: return@mapNotNull null
            val rawUrl = link.attr("href").trim()
            if (rawUrl.isEmpty()) return@mapNotNull null
            val url = fixUrl(rawUrl)
            if (!url.startsWith("http") || !unique.add(url)) return@mapNotNull null
            val title = listOfNotNull(
                entry.selectFirst(".poster-title, h2.flbaslik, h3")?.text()?.trim(),
                link.attr("title").takeIf { it.isNotBlank() },
                link.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.trim()
            ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null

            val image = link.selectFirst("img") ?: entry.selectFirst("img")
            val poster = sequenceOf(
                image?.attr("data-src"), image?.attr("data-original"), image?.attr("src")
            ).filterNotNull().firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { fixUrl(it) }

            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        }.take(100)
    }

    private fun diagnosticCard(message: String): SearchResponse {
        val encoded = URLEncoder.encode(message, "UTF-8")
        return newMovieSearchResponse(
            "TANILAMA: ${message.take(80)}",
            "$mainUrl/__mycloudstream_diagnostic/?message=$encoded",
            TvType.Movie
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/__mycloudstream_diagnostic/")) {
            val message = URLDecoder.decode(url.substringAfter("message=", ""), "UTF-8")
            return newMovieLoadResponse("Tanilama", url, TvType.Movie, url) {
                plot = message
            }
        }

        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        val description = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster?.let { fixUrl(it) }
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = false
}
