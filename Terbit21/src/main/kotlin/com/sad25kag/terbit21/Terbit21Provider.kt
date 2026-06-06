package com.sad25kag.terbit21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import java.net.URLEncoder
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Terbit21Provider : MainAPI() {
    override var mainUrl = "https://162.244.95.227"
    override var name = "Terbit21"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val desktopHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override val mainPage = mainPageOf(
        "/" to "Terbaru",
        "/category/film/page/%d/" to "Film",
        "/category/series/page/%d/" to "Series",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = desktopHeaders).document
        val results = document.select(CARD_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, results)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/?s=$encoded&post_type[]=post&post_type[]=tv",
            "$mainUrl/search/$encoded/",
        )

        return searchUrls.flatMap { url ->
            runCatching {
                app.get(url, headers = desktopHeaders).document
                    .select(CARD_SELECTOR)
                    .mapNotNull { it.toSearchResult() }
            }.getOrDefault(emptyList())
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = desktopHeaders).document
        val title = document.selectFirst("h1.entry-title, h1[itemprop=name], h1, .entry-title")
            ?.text()
            ?.substringBefore("Season", missingDelimiterValue = document.selectFirst("h1.entry-title, h1[itemprop=name], h1, .entry-title")?.text().orEmpty())
            ?.substringBefore("Episode")
            ?.trim()
            ?.ifBlank { null }
            ?: name

        val poster = document.selectFirst("figure img, .content-thumbnail img, .thumb img, .poster img, img[itemprop=image], .post-thumbnail img")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }
            ?.fixImageQuality()

        val description = document.selectFirst("div[itemprop=description] p, .entry-content p, .sinopsis, .desc, [itemprop=description]")
            ?.text()
            ?.trim()

        val tags = document.select(".gmr-moviedata strong:contains(Genre) ~ a, strong:contains(Genre) ~ a, .genxed a, .sgeneros a, .tags a, .categories a, a[rel=category tag]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.selectFirst(".gmr-moviedata strong:contains(Year) ~ a, strong:contains(Year) ~ a, .year, [itemprop=datePublished], .date")
            ?.text()
            ?.let { YEAR_REGEX.find(it)?.value }
            ?.toIntOrNull()

        val rating = document.selectFirst(".gmr-meta-rating [itemprop=ratingValue], [itemprop=ratingValue], .rating, .imdb")
            ?.text()
            ?.trim()

        val trailer = document.selectFirst("a[href*=youtube], iframe[src*=youtube]")
            ?.let { it.attr("href").ifBlank { it.getIframeAttr().orEmpty() } }
            ?.takeIf { it.isNotBlank() }

        val recommendations = document.select(CARD_SELECTOR)
            .mapNotNull { it.toSearchResult() }
            .filterNot { it.url == url }
            .distinctBy { it.url }

        val episodes = extractEpisodes(document, url)
        val isSeries = episodes.isNotEmpty() || url.contains("/tv/", true) || url.contains("/series/", true)

        return if (isSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                addScore(rating)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                plot = description
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                addScore(rating)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val baseUrl = getBaseUrl(data)
        val document = app.get(data, headers = desktopHeaders).document
        var delivered = false

        val directIframes = document.collectIframeUrls(data)
        for (iframe in directIframes) {
            if (loadExtractorSafe(iframe, data, subtitleCallback, callback)) delivered = true
        }

        val tabUrls = document.select("ul.muvipro-player-tabs a[href], .muvipro-player-tabs a[href], .player-tabs a[href], .gmr-player-nav a[href], a[href*='player'], a[href*='watch']")
            .mapNotNull { it.attr("href").trim().takeIf(String::isNotBlank) }
            .map { fixUrl(it) }
            .filter { it.startsWith(baseUrl) && it != data }
            .distinct()

        for (tabUrl in tabUrls) {
            val tabDoc = runCatching { app.get(tabUrl, headers = desktopHeaders).document }.getOrNull() ?: continue
            for (iframe in tabDoc.collectIframeUrls(tabUrl)) {
                if (loadExtractorSafe(iframe, tabUrl, subtitleCallback, callback)) delivered = true
            }
        }

        val postId = document.selectFirst("#muvipro_player_content_id[data-id], [data-post-id], [data-id]")
            ?.attr("data-id")
            ?.ifBlank { document.selectFirst("[data-post-id]")?.attr("data-post-id") }
            ?.trim()

        if (!postId.isNullOrBlank()) {
            val tabIds = document.select("div.tab-content-ajax[id], .tab-content-ajax[id], [data-tab][id], a[data-tab]")
                .mapNotNull { element ->
                    element.attr("id").ifBlank { element.attr("data-tab") }.trim().takeIf(String::isNotBlank)
                }
                .distinct()

            for (tabId in tabIds) {
                val ajaxDoc = runCatching {
                    app.post(
                        "$baseUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "muvipro_player_content",
                            "tab" to tabId,
                            "post_id" to postId,
                        ),
                        headers = desktopHeaders + mapOf(
                            "Referer" to data,
                            "Origin" to baseUrl,
                            "X-Requested-With" to "XMLHttpRequest",
                        ),
                    ).document
                }.getOrNull() ?: continue

                for (iframe in ajaxDoc.collectIframeUrls(data)) {
                    if (loadExtractorSafe(iframe, data, subtitleCallback, callback)) delivered = true
                }
            }
        }

        val scriptLinks = document.select("script")
            .flatMap { URL_REGEX.findAll(it.data()).map { match -> match.value }.toList() }
            .map { it.trimEnd('\\', '/', '\'', '"', ',', ';') }
            .map { httpsify(it) }
            .filter { it.isLikelyPlayableHost(baseUrl) }
            .distinct()

        for (link in scriptLinks) {
            if (loadExtractorSafe(link, data, subtitleCallback, callback)) delivered = true
        }

        val downloadLinks = document.select("ul.gmr-download-list a[href], .download a[href], a[href*='/download/'], a[href*='/dl/']")
            .mapNotNull { it.attr("href").trim().takeIf(String::isNotBlank) }
            .map { fixUrl(it) }
            .distinct()

        for (link in downloadLinks) {
            if (loadExtractorSafe(link, data, subtitleCallback, callback)) delivered = true
        }

        return delivered
    }

    private fun buildPageUrl(data: String, page: Int): String {
        val path = when {
            data.contains("%d") -> data.format(page)
            data == "/" && page > 1 -> "/page/$page/"
            else -> data
        }
        return if (path.startsWith("http")) path else "$mainUrl/${path.trimStart('/')}"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("h2.entry-title > a[href], h3.entry-title > a[href], .entry-title a[href], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (!href.startsWith(mainUrl)) return null

        val title = selectFirst("h2.entry-title > a, h3.entry-title > a, .entry-title a, img[alt]")
            ?.let { element -> element.attr("alt").ifBlank { element.text() } }
            ?.trim()
            ?: anchor.attr("title").trim().ifBlank { anchor.text().trim() }
        if (title.isBlank()) return null

        val poster = selectFirst("img[data-src], img[data-lazy-src], img[srcset], img[src]")
            ?.getImageAttr()
            ?.let { fixUrlNull(it) }
            ?.fixImageQuality()

        val quality = select(".gmr-qual, .gmr-quality-item, .quality, .mli-quality, .jtip-quality")
            .text()
            .trim()
            .replace("-", "")

        val tvType = inferType(href, title, quality)
        return newMovieSearchResponse(title, href, tvType) {
            posterUrl = poster
            if (quality.isNotBlank()) addQuality(quality)
        }
    }

    private suspend fun extractEpisodes(document: Document, detailUrl: String): List<Episode> {
        val inlineEpisodes = document.select(EPISODE_SELECTOR).toEpisodes(detailUrl)
        if (inlineEpisodes.isNotEmpty()) return inlineEpisodes

        val seriesUrl = document.selectFirst("a.button.button-shadow.active[href], a[href*='/tv/'][href*='season'], a[href*='/series/']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?.takeIf { it != detailUrl }
            ?: return emptyList()

        val seriesDoc = runCatching { app.get(seriesUrl, headers = desktopHeaders).document }.getOrNull() ?: return emptyList()
        return seriesDoc.select(EPISODE_SELECTOR).toEpisodes(seriesUrl)
    }

    private fun List<Element>.toEpisodes(sourceUrl: String): List<Episode> {
        return mapNotNull { element ->
            val href = element.attr("href").trim().takeIf(String::isNotBlank)?.let { fixUrl(it) } ?: return@mapNotNull null
            val rawName = element.text().trim().ifBlank { element.attr("title").trim() }
            if (href == sourceUrl) return@mapNotNull null
            if (rawName.contains("view all", true) || rawName.contains("trailer", true) || rawName.contains("download", true)) return@mapNotNull null
            if (!href.contains("/eps/", true) && !href.contains("episode", true) && !rawName.contains("eps", true) && !rawName.contains("episode", true)) return@mapNotNull null

            val episodeNumber = EPISODE_NUMBER_REGEX.find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: EPISODE_NUMBER_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val seasonNumber = SEASON_NUMBER_REGEX.find(rawName)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: SEASON_NUMBER_REGEX.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                name = rawName.ifBlank { episodeNumber?.let { "Episode $it" } ?: "Episode" }
                episode = episodeNumber
                season = seasonNumber
            }
        }.distinctBy { it.data }
    }

    private fun Document.collectIframeUrls(referer: String): List<String> {
        val iframeUrls = select("iframe[src], iframe[data-src], iframe[data-litespeed-src]")
            .mapNotNull { it.getIframeAttr() }

        val htmlUrls = URL_REGEX.findAll(html()).map { it.value }.toList()
            .filter { it.contains("embed", true) || it.contains("player", true) || it.contains("stream", true) }

        return (iframeUrls + htmlUrls)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { if (it.startsWith("//")) "https:$it" else it }
            .map { if (it.startsWith("http")) it else fixUrl(it) }
            .map { httpsify(it) }
            .filter { it != referer }
            .distinct()
    }

    private suspend fun loadExtractorSafe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (url.isBlank() || url.startsWith(mainUrl)) return false
        return runCatching { loadExtractor(url, referer, subtitleCallback, callback) }.getOrDefault(false)
    }

    private fun inferType(href: String, title: String, quality: String): TvType {
        return if (
            href.contains("/tv/", true) ||
            href.contains("/series/", true) ||
            href.contains("/eps/", true) ||
            title.contains("episode", true) ||
            title.contains("season", true) ||
            title.contains("eps", true) ||
            quality.isBlank()
        ) TvType.TvSeries else TvType.Movie
    }

    private fun Element.getImageAttr(): String {
        return attr("abs:data-src").ifBlank { attr("abs:data-lazy-src") }
            .ifBlank { attr("abs:srcset").substringBefore(" ") }
            .ifBlank { attr("abs:src") }
            .ifBlank { attr("data-src") }
            .ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("srcset").substringBefore(" ") }
            .ifBlank { attr("src") }
    }

    private fun Element.getIframeAttr(): String? {
        return attr("data-litespeed-src").ifBlank { attr("data-src") }
            .ifBlank { attr("src") }
            .takeIf { it.isNotBlank() }
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("-\\d+x\\d+(?=\\.[a-zA-Z]{3,4}(?:$|[?]))"), "")
    }

    private fun String.isLikelyPlayableHost(baseUrl: String): Boolean {
        if (!startsWith("http") || startsWith(baseUrl) || startsWith(mainUrl)) return false
        return PLAYABLE_HOST_HINTS.any { contains(it, ignoreCase = true) }
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault(mainUrl)
    }

    companion object {
        private const val CARD_SELECTOR = "article.item, article, .item, .post, .result-item, .ml-item, .movie-item, .film-list .film, .movies-list .movie"
        private const val EPISODE_SELECTOR = "div.gmr-listseries a.button.button-shadow[href], .eplister a[href], .episodelist a[href], .se-c .episodios li a[href], a[href*='/eps/'], a[href*='episode']"
        private val URL_REGEX = Regex("https?:\\/\\/[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        private val YEAR_REGEX = Regex("(?:19|20)\\d{2}")
        private val EPISODE_NUMBER_REGEX = Regex("(?:Episode|Eps|Ep|E)[\\s.-]*(\\d+)", RegexOption.IGNORE_CASE)
        private val SEASON_NUMBER_REGEX = Regex("(?:Season|S)[\\s.-]*(\\d+)", RegexOption.IGNORE_CASE)
        private val PLAYABLE_HOST_HINTS = listOf(
            "embed", "player", "stream", "m3u8", "mp4", "dood", "filemoon", "filelions",
            "streamtape", "mixdrop", "vid", "drive", "ok.ru", "uqload", "short", "cdn"
        )
    }
}
