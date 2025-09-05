package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class IncestFlix : MainAPI() {
    override var mainUrl = "https://www.incestflix.com"
    override var name = "IncestFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // Main page sections are tag URLs, as requested. Add more as needed.
    override val mainPage = mainPageOf(
        "$mainUrl/tag/Cosplay" to "Cosplay",
        "$mainUrl/tag/FD" to "FD Father, Daughter",
        "$mainUrl/tag/MS" to "MS Mother, Son",
        "$mainUrl/tag/BS" to "BS Brother, Sister",
        "$mainUrl/tag/Not-Incest" to "Not Incest",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}/page/$page"
        val document = app.get(url).document

        val items = document.select("a[href*=/watch/]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("abs:href").ifBlank { return null }
        val title = this.text().ifBlank { return null }
        return newMovieSearchResponse(title, href, TvType.Movie) {
            // Poster usually not present on tag list; resolve on load
            this.posterUrl = null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // WordPress-style search pagination
        val out = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/?s=${query}" else "$mainUrl/page/$i/?s=${query}"
            val doc = app.get(url).document
            val results = doc.select("a[href*=/watch/]")
                .mapNotNull { it.toSearchResult() }
            out.addAll(results)
            if (results.isEmpty()) break
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("title")?.text()
            ?: name
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        // Collect some related items if available
        val recommendations = document.select("a[href*=/watch/]")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(20)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Try common patterns: direct <video><source>, iframes to hosts, or anchors to files
        val candidates = mutableListOf<String>()

        // video > source[src]
        candidates += doc.select("video source[src]").map { it.attr("abs:src") }
        // video[src]
        candidates += doc.select("video[src]").map { it.attr("abs:src") }
        // iframes
        candidates += doc.select("iframe[src]").map { it.attr("abs:src") }
        // anchors that look like media links
        candidates += doc.select("a[href]")
            .map { it.attr("abs:href") }
            .filter { it.contains(".m3u8") || it.contains(".mp4") }

        val unique = candidates.filter { it.isNotBlank() }.distinct()

        if (unique.isEmpty()) return false

        unique.forEach { link ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = link
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
