package com.example

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * MyCloudStream - HDFilmCehennemi catalogue test provider.
 *
 * This version reads public catalogue/search pages only.
 * No video URL extraction, DRM handling or access-control bypass.
 * Page selectors and availability have not been verified against the live site.
 */
class ExampleProvider : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.nl"
    override var name = "HDFilmCehennemi (Test)"
    override var lang = "tr"

    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true
    override val hasDownloadSupport = false
    override val mainPage = mainPageOf(
        "$mainUrl/category/film-izle-2/" to "Filmler"
    )

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded").document
        return readCards(document)
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page <= 1) "$base/" else "$base/page/$page/"
        val document = app.get(url).document
        val results = readCards(document)
        return newHomePageResponse(request, results, hasNext = results.isNotEmpty())
    }

    private fun readCards(document: Document): List<SearchResponse> {
        // These are candidate selectors, not verified against the live site.
        val selectors = "article, .movies-list .item, .items .item, .listing .item, " +
            ".movie-item, .ml-item, .film-item, .poster-item"
        val seen = mutableSetOf<String>()
        return document.select(selectors).mapNotNull { item ->
            val linkElement = item.selectFirst("a[href]:has(img)")
                ?: item.selectFirst("h2 a[href], h3 a[href], a[href]")
                ?: return@mapNotNull null

            val href = linkElement.attr("href").trim()
            if (href.isEmpty()) return@mapNotNull null
            val url = fixUrl(href)
            val path = url.removePrefix(mainUrl).substringBefore('?')
            if (!url.startsWith("$mainUrl/") || path == "/" ||
                listOf("/category/", "/tur/", "/yil/", "/ulke/", "/dizi/")
                    .any { path.startsWith(it) } ||
                !seen.add(url)) return@mapNotNull null

            val img: Element? = item.selectFirst("img")
            val title = listOfNotNull(
                item.selectFirst("h2, h3, .title, .name")?.text(),
                linkElement.attr("title"),
                img?.attr("alt"),
                linkElement.text()
            ).firstOrNull { it.isNotBlank() }?.trim() ?: return@mapNotNull null
            if (title.length < 3) return@mapNotNull null

            val posterRaw = listOfNotNull(
                img?.attr("data-src"),
                img?.attr("data-original"),
                img?.attr("data-lazy-src"),
                img?.attr("src")
            ).firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
            val poster = posterRaw?.let { fixUrl(it) }

            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")
                ?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val image = document.selectFirst("meta[property=og:image]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
        val description = document.selectFirst("meta[name=description]")
            ?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst(".description, .film-description, .plot")
                ?.text()?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = image?.let { fixUrl(it) }
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // No playback sources are implemented in this catalogue test.
        return false
    }
}
