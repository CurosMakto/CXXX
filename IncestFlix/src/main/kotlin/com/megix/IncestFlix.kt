package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document

class IncestFlix : MainAPI() {
    override var mainUrl = "https://www.incestflix.com"
    override var name = "IncestFlix"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    // Dynamic homepage: a single entry that triggers building sections from /alltags
    override val mainPage = mainPageOf(
        "ALL_TAGS" to "All Tags"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "ALL_TAGS") {
            // Build multiple sections from /alltags
            val tagDoc = app.get("$mainUrl/alltags").document
            // Collect tag name + href; limit to avoid heavy homepage
            val tags = tagDoc.select("a[href^=/tag/]")
                .map { it.text() to normalizeUrl(it.attr("href")) }
                .distinctBy { it.second.lowercase() }
                .take(30)

            val lists = arrayListOf<HomePageList>()
            for ((tagName, tagUrl) in tags) {
                val tagPageUrl = if (page <= 1) tagUrl else "$tagUrl/page/$page"
                val doc = app.get(tagPageUrl).document
                val items = doc.select("a[href*=/watch/]")
                    .mapNotNull { it.toSearchResultWithPoster() }
                    .distinctBy { it.url }
                    .take(12)
                if (items.isNotEmpty()) {
                    lists += HomePageList(
                        name = tagName,
                        list = items,
                        isHorizontalImages = true
                    )
                }
            }
            // Multiple section response
            return newHomePageResponse(list = lists, hasNext = true)
        } else {
            val url = if (page <= 1) request.data else "${request.data}/page/$page"
            val document = app.get(url).document

            val items = document.select("a[href*=/watch/]")
                .mapNotNull { it.toSearchResultWithPoster() }
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
    }

    private fun Element.toSearchResultWithPoster(): SearchResponse? {
        val href = this.attr("abs:href").ifBlank { return null }
        val title = this.text().ifBlank { return null }

        // Try to infer poster from nearby elements
        val card = this.parent() ?: this
        val posterCandidates = sequence<String?> {
            // Common patterns: background-image style on parent/siblings
            yield(card.attr("style"))
            yield(card.parent()?.attr("style"))
            yield(card.selectFirst("[style*=background-image]")?.attr("style"))
            // Direct images
            yield(card.selectFirst("img[src]")?.attr("abs:src"))
            yield(card.selectFirst("img[data-src]")?.attr("abs:data-src"))
        }.mapNotNull { it }.toList()

        val poster = posterCandidates.firstNotNullOfOrNull { extractBgUrl(it) } ?: posterCandidates.firstOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // WordPress-style search pagination
        val out = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val url = if (i == 1) "$mainUrl/?s=${query}" else "$mainUrl/page/$i/?s=${query}"
            val doc = app.get(url).document
            val results = doc.select("a[href*=/watch/]")
                .mapNotNull { it.toSearchResultWithPoster() }
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
            .mapNotNull { it.toSearchResultWithPoster() }
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

        // PRIORITY: dedicated player element
        doc.selectFirst("video#incflix-player")?.let { v ->
            val poster = v.attr("poster").takeIf { it.isNotBlank() }?.let { normalizeUrl(it) }
            val src = v.selectFirst("source[src]")?.attr("src")?.let { normalizeUrl(it) }
            if (!src.isNullOrBlank()) {
                candidates += src
            }
            // Also emit poster-based preview if needed (not a stream)
            // poster is handled in load() via og:image, so no callback here
        }

        // video > source[src]
        candidates += doc.select("video source[src]").map { normalizeUrl(it.attr("src")) }
        // video[src]
        candidates += doc.select("video[src]").map { normalizeUrl(it.attr("src")) }
        // data-src/data-video on video/source
        candidates += doc.select("video[data-src], source[data-src]").map { normalizeUrl(it.attr("data-src")) }
        candidates += doc.select("video[data-video]").map { normalizeUrl(it.attr("data-video")) }
        // iframes
        candidates += doc.select("iframe[src]").map { normalizeUrl(it.attr("src")) }
        // anchors that look like media links
        candidates += doc.select("a[href]")
            .map { normalizeUrl(it.attr("href")) }
            .filter { it.contains(".m3u8") || it.contains(".mp4") }

        // Parse inline scripts for direct sources
        runCatching {
            val scriptText = doc.select("script").joinToString("\n") { it.data() }
            val m3u8Regex = Regex("https?:\\/\\/[^'\"\\s)]+\\.m3u8")
            val mp4Regex = Regex("https?:\\/\\/[^'\"\\s)]+\\.mp4")
            candidates += m3u8Regex.findAll(scriptText).map { it.value }.toList()
            candidates += mp4Regex.findAll(scriptText).map { it.value }.toList()
        }

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

    private fun extractBgUrl(styleOrUrl: String): String? {
        val style = styleOrUrl.trim()
        if (style.startsWith("http")) return style
        val match = Regex("background-image\\s*:\\s*url\\((['\"]?)(.*?)\\1\\)", RegexOption.IGNORE_CASE)
            .find(style)
        return match?.groupValues?.getOrNull(2)
    }

    private fun normalizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return when {
            url.startsWith("//") -> "https:" + url
            url.startsWith("http") -> url
            else -> fixUrl(url)
        }
    }
}
