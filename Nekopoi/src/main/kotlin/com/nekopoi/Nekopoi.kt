package com.nekopoi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Nekopoi : MainAPI() {
    override var mainUrl = "https://nekopoi.care"
    override var name = "Nekopoi"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "" to "Episode Terbaru",
        "series" to "Hentai Terbaru",
        "/category/hentai/" to "Hentai",
        "/category/2d-animation/" to "2D Animation",
        "/category/3d-hentai/" to "3D Hentai",
        "/category/jav/" to "JAV",
        "/category/jav-cosplay/" to "JAV Cosplay",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when (request.data) {
            "" -> if (page == 1) mainUrl else "$mainUrl/page/$page/"
            "series" -> mainUrl
            else -> buildPagedUrl(request.data, page)
        }
        val document = app.get(url, headers = headers).document
        val results = when (request.data) {
            "series" -> document.select(".nk-hentai-grid a.nk-series-link[href]")
                .mapNotNull { it.toSeriesSearchResponse() }
                .distinctBy { it.url }
            else -> parsePostCards(document.body())
        }
        val hasNext = document.selectFirst("a.next.page-numbers[href]") != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encoded&post_type=anime", headers = headers).document
        return (document.select("a.nk-search-item[href]").mapNotNull { it.toSearchItem() } +
            parsePostCards(document.body()) +
            document.select(".nk-hentai-grid a.nk-series-link[href]").mapNotNull { it.toSeriesSearchResponse() })
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1, .entry-title")?.ownText()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1, .entry-title")?.text()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" – NekoPoi")
            ?: document.title().substringBefore(" – NekoPoi").takeIf { it.isNotBlank() }
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.absoluteUrlSafe()
            ?: document.selectFirst(".nk-series-poster")?.styleImage()
            ?: document.selectFirst(".separator img, .nk-player-series-thumb, img")?.imageAttr()?.absoluteUrlSafe()
        val plot = document.selectFirst(".nk-series-synopsis")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
        val tags = document.select(".nk-series-meta-list li:has(b:contains(Genre)) a, a[rel=tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val episodeCards = document.select("a.nk-episode-card[href]")
        if (episodeCards.isNotEmpty() || url.contains("/hentai/", ignoreCase = true)) {
            val episodes = episodeCards.mapNotNull { it.toEpisodeItem() }.distinctBy { it.data }.reversed()
            return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                document.selectFirst(".nk-series-meta-list li:has(b:contains(Status))")?.text()?.substringAfter(":")?.trim()?.let {
                    this.showStatus = getStatus(it)
                }
            }
        }

        val recommendations = document.select(".nk-related-list a[href], a.nk-search-item[href], .nk-post-card")
            .mapNotNull {
                when {
                    it.hasClass("nk-search-item") -> it.toSearchItem()
                    it.hasClass("nk-post-card") -> it.toPostCard()
                    else -> it.toRelatedItem()
                }
            }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val embeds = document.select(".nk-player-frame iframe[src], #nk-player iframe[src], iframe[src]")
            .mapNotNull { it.attr("src").absoluteUrlSafe(mainUrl) }
            .filter { isSupportedEmbed(it) }
            .distinct()
            .take(3)

        var emitted = 0
        val countedCallback: (ExtractorLink) -> Unit = { link ->
            emitted++
            callback.invoke(link)
        }

        for (embed in embeds) {
            if (loadExtractor(embed, data, subtitleCallback, countedCallback) && emitted > 0) return true
        }
        return emitted > 0
    }

    private fun buildPagedUrl(path: String, page: Int): String {
        val base = if (path.startsWith("http")) path.trimEnd('/') else mainUrl + path
        return if (page == 1) base.trimEnd('/') + "/" else base.trimEnd('/') + "/page/$page/"
    }

    private fun parsePostCards(root: Element?): List<SearchResponse> {
        if (root == null) return emptyList()
        return (root.select(".nk-post-card").mapNotNull { it.toPostCard() } +
            root.select("a.nk-search-item[href]").mapNotNull { it.toSearchItem() })
            .distinctBy { it.url }
    }

    private fun Element.toPostCard(): SearchResponse? {
        val anchor = selectFirst("h2 a[href], a[href]") ?: return null
        val href = anchor.attr("href").absoluteUrlSafe(mainUrl) ?: return null
        val title = anchor.text().trim().takeIf { it.isNotBlank() } ?: return null
        val poster = selectFirst(".nk-thumb-crop")?.styleImage()
            ?: selectFirst("img")?.imageAttr()?.absoluteUrlSafe(mainUrl)
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun Element.toSearchItem(): SearchResponse? {
        val href = attr("href").absoluteUrlSafe(mainUrl) ?: return null
        val title = selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst(".nk-search-thumb")?.styleImage()
            ?: selectFirst("img")?.imageAttr()?.absoluteUrlSafe(mainUrl)
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun Element.toSeriesSearchResponse(): SearchResponse? {
        val href = attr("href").absoluteUrlSafe(mainUrl) ?: return null
        val title = selectFirst(".title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: ownText().trim().takeIf { it.isNotBlank() }
            ?: attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst(".nk-hentai-thumb")?.styleImage()
            ?: Regex("""<img\s+src=&quot;([^&]+)&quot;""").find(attr("original-title"))?.groupValues?.getOrNull(1)?.absoluteUrlSafe(mainUrl)
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun Element.toRelatedItem(): SearchResponse? {
        val href = attr("href").absoluteUrlSafe(mainUrl) ?: return null
        val title = selectFirst(".nk-related-title, .nk-episode-card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: text().trim().takeIf { it.isNotBlank() }
            ?: return null
        val poster = selectFirst(".nk-related-thumb-crop, .nk-episode-card-thumb")?.styleImage()
            ?: selectFirst("img")?.imageAttr()?.absoluteUrlSafe(mainUrl)
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun Element.toEpisodeItem(): Episode? {
        val href = attr("href").absoluteUrlSafe(mainUrl) ?: return null
        val title = selectFirst(".nk-episode-card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: text().trim().takeIf { it.isNotBlank() }
        val poster = selectFirst(".nk-episode-card-thumb")?.styleImage()
            ?: selectFirst("img")?.imageAttr()?.absoluteUrlSafe(mainUrl)
        val episodeNumber = selectFirst(".nk-episode-badge")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""(?i)episode\s+(\d+)""").find(title ?: "")?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newEpisode(href) {
            this.name = title
            this.posterUrl = poster
            this.episode = episodeNumber
        }
    }

    private fun isSupportedEmbed(url: String): Boolean {
        return url.contains("playmogo.com", ignoreCase = true) ||
            url.contains("streampoi.com", ignoreCase = true) ||
            url.contains("streamruby", ignoreCase = true) ||
            url.contains("dood", ignoreCase = true) ||
            url.contains(".m3u8", ignoreCase = true) ||
            url.contains(".mp4", ignoreCase = true)
    }

    private fun Element.styleImage(): String? {
        return Regex("""url\(['\"]?([^)'\"]+)""").find(attr("style"))?.groupValues?.getOrNull(1)?.absoluteUrlSafe(mainUrl)
    }

    private fun Element.imageAttr(): String? {
        return attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
    }

    private fun String?.absoluteUrlSafe(base: String = mainUrl): String? {
        val raw = this?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("/") -> base.trimEnd('/') + raw
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrDefault(base.trimEnd('/') + "/$raw")
        }
    }

    private fun getStatus(t: String?): ShowStatus {
        return when (t?.trim()?.lowercase()) {
            "ongoing" -> ShowStatus.Ongoing
            "completed" -> ShowStatus.Completed
            else -> ShowStatus.Completed
        }
    }
}
