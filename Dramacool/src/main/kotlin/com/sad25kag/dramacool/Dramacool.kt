package com.sad25kag.dramacool

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dramacool : MainAPI() {
    override var mainUrl = "https://asianctv.in"
    override var name = "Dramacool"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
    )

    override val mainPage = mainPageOf(
        "recently-added-drama/page/%d/" to "Recently Drama",
        "recently-added-movie/page/%d/" to "Recently Movie",
        "recently-added-kshow/page/%d/" to "Recently KShow",
        "most-popular-drama/page/%d/" to "Popular Drama",

        "genre/action/page/%d/" to "Action",
        "genre/adventure/page/%d/" to "Adventure",
        "genre/business/page/%d/" to "Business",
        "genre/comedy/page/%d/" to "Comedy",
        "genre/crime/page/%d/" to "Crime",
        "genre/drama/page/%d/" to "Drama",
        "genre/family/page/%d/" to "Family",
        "genre/fantasy/page/%d/" to "Fantasy",
        "genre/food/page/%d/" to "Food",
        "genre/historical/page/%d/" to "Historical",
        "genre/horror/page/%d/" to "Horror",
        "genre/law/page/%d/" to "Law",
        "genre/life/page/%d/" to "Life",
        "genre/mature/page/%d/" to "Mature",
        "genre/medical/page/%d/" to "Medical",
        "genre/melodrama/page/%d/" to "Melodrama",
        "genre/military/page/%d/" to "Military",
        "genre/music/page/%d/" to "Music",
        "genre/mystery/page/%d/" to "Mystery",
        "genre/political/page/%d/" to "Political",
        "genre/psychological/page/%d/" to "Psychological",
        "genre/romance/page/%d/" to "Romance",
        "genre/sci-fi/page/%d/" to "Sci-Fi",
        "genre/sitcom/page/%d/" to "Sitcom",
        "genre/sports/page/%d/" to "Sports",
        "genre/supernatural/page/%d/" to "Supernatural",
        "genre/thriller/page/%d/" to "Thriller",
        "genre/war/page/%d/" to "War",
        "genre/wuxia/page/%d/" to "Wuxia",
        "genre/youth/page/%d/" to "Youth"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val safePage = if (page < 1) 1 else page
        val url = buildMainPageUrl(request.data, safePage)

        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val forceMovie = request.name.contains("Movie", true) || request.data.contains("movie", true)
        val home = document.select(
            "ul.list-episode-item-2 li, div.left-tab-1 ul li, ul.switch-block li, " +
                "div.content-left ul li, div.content-left li, ul.list-film li, " +
                "article, .post, .item, .items li, .recently li, .list li, " +
                "a[href*=/series/], a[href*=episode-], a[href*=/drama-detail/]"
        )
            .mapNotNull { it.toSearchResult(forceMovie) }
            .distinctBy { it.url }

        val hasNext = document.select(
            "ul.pagination a:contains(Next), div.pagination a:contains(Next), " +
                "a.next, a.nextpostslink, a[href*='/page/${safePage + 1}/'], a[href*='page=${safePage + 1}']"
        ).isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext || home.isNotEmpty()
        )
    }

    private fun buildMainPageUrl(template: String, page: Int): String {
        val path = if (page <= 1) {
            template
                .replace("page/%d/", "")
                .replace("page/%d", "")
                .replace("?page=%d", "")
        } else {
            template.format(page)
        }.trimStart('/')

        return if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path"
    }

    private fun Element.toSearchResult(forceMovie: Boolean = false): SearchResponse? {
        val detail = selectFirst("a[href*=/series/], a[href*=/drama-detail/]")
        val episode = selectFirst("a[href*=episode-], a[href*=/video-watch/]")
        val anchor = detail ?: episode ?: if (tagName().equals("a", true) && hasAttr("href")) this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!isSupportedContentUrl(href) || isIgnoredUrl(href)) return null

        val title = anchor.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("h3.title, h2.title, .title, h3, h2")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: anchor.text().trim()

        if (title.isBlank() || title.equals("View more", true) || title.equals("Show all episodes", true)) return null

        val poster = getPosterUrl()
        val cleanTitle = title.cleanCardTitle()
        val year = selectFirst("span.year")?.text()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(cleanTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val type = when {
            forceMovie -> TvType.Movie
            href.contains("movie", true) -> TvType.Movie
            else -> TvType.AsianDrama
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.AsianDrama) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        if (encodedQuery.isBlank()) return emptyList()

        val searchUrls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/search?keyword=$encodedQuery",
            "$mainUrl/search?type=movies&keyword=$encodedQuery"
        )

        searchUrls.forEach { searchUrl ->
            val htmlResults = runCatching {
                app.get(searchUrl, headers = defaultHeaders, referer = mainUrl).document
                    .select(
                        "ul.list-episode-item-2 li, div.content-left ul li, div.content-left li, " +
                            "article, .post, .item, .items li, .search-results li, " +
                            "a[href*=/series/], a[href*=episode-], a[href*=/drama-detail/]"
                    )
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()

            if (htmlResults.isNotEmpty()) return htmlResults
        }

        val apiText = runCatching {
            app.get(
                "$mainUrl/api?a=search&keyword=$encodedQuery&type=drama",
                headers = defaultHeaders,
                referer = mainUrl
            ).text
        }.getOrNull() ?: return emptyList()

        val apiResults = runCatching {
            AppUtils.parseJson<List<SearchItem>>(apiText)
        }.getOrNull() ?: return emptyList()

        return apiResults.mapNotNull { item ->
            val title = item.name ?: item.value ?: return@mapNotNull null
            val url = fixUrlNull(item.url ?: return@mapNotNull null) ?: return@mapNotNull null
            val type = getTypeFromUrl(url)

            if (type == TvType.Movie) {
                newMovieSearchResponse(title.cleanCardTitle(), url, TvType.Movie) {
                    this.posterUrl = fixUrlNull(item.cover)
                    this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            } else {
                newTvSeriesSearchResponse(title.cleanCardTitle(), url, TvType.AsianDrama) {
                    this.posterUrl = fixUrlNull(item.cover)
                    this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val detailUrl = if (isDetailUrl(url)) url else getDetailUrl(url) ?: episodeUrlToSeriesUrl(url) ?: url

        val document = app.get(
            detailUrl,
            headers = defaultHeaders,
            referer = mainUrl
        ).document

        val title = document.selectFirst("div.info h1, h1.entry-title, h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.details div.img img, div.img img, .thumb img, .poster img, meta[property=og:image]")
                ?.let { element ->
                    if (element.tagName() == "meta") element.attr("content") else element.attr("data-src").takeIf { it.isNotBlank() }
                        ?: element.attr("data-original").takeIf { it.isNotBlank() }
                        ?: element.attr("src")
                }
        )

        val description = document.select("div.info p, .entry-content p, .description, .desc")
            .firstOrNull { it.selectFirst("span") == null && it.text().isNotBlank() }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val tags = document.select("div.info p:contains(Genre:) a, div.info p:contains(Country:) a, a[href*=/genre/], a[href*=/country/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val status = getStatus(document.selectFirst("div.info p:contains(Status:), .status")?.text())
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val episodes = document.select("a[href*=episode-], a[href*=/video-watch/]")
            .mapNotNull { it.toEpisode() }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        val recommendations = document.select(
            "div.content-right a[href*=/series/], ul.switch-block a[href*=/series/], " +
                "div.content-right a[href*=/drama-detail/], ul.switch-block a[href*=/drama-detail/], " +
                ".related a[href*=/series/], .popular a[href*=/series/]"
        )
            .mapNotNull { it.parent()?.toSearchResult() ?: it.toSearchResult() }
            .distinctBy { it.url }

        val isMovie = title.contains("movie", true) ||
            detailUrl.contains("movie", true) ||
            tags.any { it.contains("Movie", true) } ||
            episodes.size <= 1 && document.select("div.info p:contains(Type:), .type").text().contains("Movie", true)

        return if (isMovie) {
            newMovieLoadResponse(title.cleanCardTitle(), detailUrl, TvType.Movie, episodes.firstOrNull()?.data ?: detailUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title.cleanCardTitle(), detailUrl, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
            }
        }
    }

    private fun Element.toEpisode(): Episode? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!isSupportedContentUrl(href) || !isEpisodeUrl(href)) return null

        val name = selectFirst("h3.title, h2.title, .title, h3, h2")?.text()?.trim()
            ?: attr("title").takeIf { it.isNotBlank() }
            ?: text().trim()

        val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\bEP\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val dateText = selectFirst("span.time, .time, .date")?.text()
            ?: parent()?.selectFirst("span.time, .time, .date")?.text()

        return newEpisode(href) {
            this.name = name.cleanTitle()
            this.episode = epNum
            addDate(dateText)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = defaultHeaders,
            referer = mainUrl
        ).document

        val links = document.select(
            "div.muti_link li[data-video], ul.list-server li[data-video], li.linkserver[data-video], " +
                "li[data-video], a[data-video], div[data-video], button[data-video], " +
                "li[data-link], a[data-link], div[data-link], " +
                "iframe[src], source[src], video[src], a[href*='vid'], a[href*='embed'], a[href*='stream']"
        )
            .mapNotNull { element ->
                val raw = element.attr("data-video").takeIf { it.isNotBlank() }
                    ?: element.attr("data-link").takeIf { it.isNotBlank() }
                    ?: element.attr("data-src").takeIf { it.isNotBlank() }
                    ?: element.attr("src").takeIf { it.isNotBlank() }
                    ?: element.attr("href").takeIf { it.isNotBlank() }
                raw?.trim()?.let { fixUrlNull(it) }
            }
            .filterNot { isIgnoredUrl(it) }
            .distinct()

        val extractorLinks = links
            .filterNot { it.contains("javascript:", true) || it == data }
            .filterNot { it.contains("vidbasic", true) }
            .takeIf { it.isNotEmpty() }
            ?: links

        var delivered = false

        extractorLinks.amap { link ->
            runCatching {
                val success = loadExtractor(resolveExtractorUrl(link), data, subtitleCallback) { extractorLink ->
                    delivered = true
                    callback(extractorLink)
                }
                if (success) delivered = true
            }
        }

        if (!delivered) {
            Regex("""https?://[^"'<>\s]+\.(?:m3u8|mp4)(?:\?[^"'<>\s]*)?""", RegexOption.IGNORE_CASE)
                .findAll(document.html())
                .map { it.value.replace("\\/", "/") }
                .distinct()
                .forEach { direct ->
                    runCatching {
                        val success = loadExtractor(direct, data, subtitleCallback) { extractorLink ->
                            delivered = true
                            callback(extractorLink)
                        }
                        if (success) delivered = true
                    }
                }
        }

        return delivered
    }

    private suspend fun resolveExtractorUrl(url: String): String {
        return runCatching {
            when {
                url.contains("hglink.to", true) -> url
                    .replace("https://hglink.to/e/", "https://hanerix.com/e/")
                    .replace("http://hglink.to/e/", "https://hanerix.com/e/")
                else -> url
            }
        }.getOrDefault(url)
    }

    private suspend fun getDetailUrl(url: String): String? {
        return app.get(
            url,
            headers = defaultHeaders,
            referer = mainUrl
        ).document
            .selectFirst(
                "div.category a[href*=/series/], .category a[href*=/series/], a[href*=/series/], " +
                    "div.category a[href*=/drama-detail/], a[href*=/drama-detail/]"
            )
            ?.attr("href")
            ?.let { fixUrl(it) }
    }

    private fun episodeUrlToSeriesUrl(url: String): String? {
        if (!isEpisodeUrl(url)) return null
        val path = Regex("""https?://[^/]+/([^?#]+)/?""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: url.trim('/').substringAfterLast('/')

        val seriesSlug = path
            .replace(Regex("""-episode-\d+.*$""", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotBlank() }
            ?: return null

        return "$mainUrl/series/$seriesSlug/"
    }

    private fun Element.getPosterUrl(): String? {
        val img = selectFirst("img")
        return fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
        )
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ")
            .replace("SUB ", "", ignoreCase = true)
            .replace("DUB ", "", ignoreCase = true)
            .trim()
    }

    private fun String.cleanCardTitle(): String {
        return cleanTitle()
            .replace(Regex("""\s+Episode\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+EP\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\d+\s*(?:seconds?|minutes?|hours?|days?|weeks?|months?)\s+ago.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+about\s+\d+\s+\w+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+delayed.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun isDetailUrl(url: String): Boolean {
        return url.contains("/series/", true) || url.contains("/drama-detail/", true)
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return url.contains("episode-", true) || url.contains("/video-watch/", true)
    }

    private fun isSupportedContentUrl(url: String): Boolean {
        return isDetailUrl(url) || isEpisodeUrl(url)
    }

    private fun isIgnoredUrl(url: String): Boolean {
        return url.contains("/wp-content/", true) ||
            url.contains("/wp-admin/", true) ||
            url.contains("/wp-login", true) ||
            url.contains("/login", true) ||
            url.contains("/about", true) ||
            url.contains("/drama-list", true) ||
            url.contains("/popular-star", true) ||
            url.contains("/genres", true) ||
            url.contains("/genre/", true) ||
            url.contains("/country/", true) ||
            url.contains("/tag/", true) ||
            url.contains("/cdn-cgi/", true) ||
            url.contains("discord", true) ||
            url.contains("telegram", true) ||
            url.startsWith("mailto:", true) ||
            url.startsWith("javascript:", true) ||
            url == mainUrl || url == "$mainUrl/"
    }

    private fun getTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/country/") && url.contains("movie", true) -> TvType.Movie
            url.contains("movie", true) -> TvType.Movie
            else -> TvType.AsianDrama
        }
    }

    private fun getStatus(status: String?): ShowStatus? {
        return when {
            status?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            status?.contains("completed", true) == true -> ShowStatus.Completed
            else -> null
        }
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    data class SearchItem(
        @JsonProperty("value") val value: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("cover") val cover: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("status") val status: String?,
    )
}
