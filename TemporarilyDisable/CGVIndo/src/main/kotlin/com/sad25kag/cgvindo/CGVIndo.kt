package com.sad25kag.cgvindo

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CGVIndo : MainAPI() {
    override var mainUrl = "https://cgvindo2.baby"
    override var name = "CGVIndo"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "trending" to "Trending",
        "movie" to "Movies",
        "tv-series" to "TV Series",
        "series" to "Series",
        "anime" to "Anime",
        "semi" to "Semi",

        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/animation" to "Animation",
        "genre/comedy" to "Comedy",
        "genre/crime" to "Crime",
        "genre/drama" to "Drama",
        "genre/fantasy" to "Fantasy",
        "genre/horror" to "Horror",
        "genre/mystery" to "Mystery",
        "genre/romance" to "Romance",
        "genre/sci-fi" to "Sci-Fi",
        "genre/thriller" to "Thriller"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url).document

        val home = document.select(
            "article, " +
                ".item, " +
                ".ml-item, " +
                ".post, " +
                ".movie-item, " +
                ".film-poster, " +
                ".grid article, " +
                ".content article, " +
                "a[href]"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    ".pagination a:contains(Berikutnya), " +
                    "a[href*='/page/${page + 1}/'], " +
                    "a[href*='paged=${page + 1}']"
            ) != null || home.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            page <= 1 && cleanPath.isBlank() -> mainUrl
            page <= 1 -> "$mainUrl/$cleanPath/"
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst("a[href]") ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val title = listOf(
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst(".title")?.text()?.trim(),
            selectFirst(".jt")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            selectFirst(".name")?.text()?.trim(),
            anchor.attr("title").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Next", true) &&
                !it.equals("Previous", true) &&
                !it.equals("Search", true) &&
                !it.equals("Movies", true) &&
                !it.equals("TV Series", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

        val type = getTypeFromUrl(href)

        return if (type == TvType.Movie || type == TvType.NSFW) {
            newMovieSearchResponse(
                title,
                href,
                type
            ) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                type
            ) {
                posterUrl = poster
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).lowercase()

        if (path.isBlank() || path == "/") return true

        val blockedPrefixes = listOf(
            "/genre/",
            "/category/",
            "/tag/",
            "/page/",
            "/search",
            "/privacy",
            "/dmca",
            "/contact",
            "/terms",
            "/trending",
            "/movie/",
            "/tv-series/",
            "/series/",
            "/anime/",
            "/semi/"
        )

        return blockedPrefixes.any { path == it.trimEnd('/') || path.startsWith("$it/page") }
    }

    private fun getTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/semi/", true) -> TvType.NSFW
            url.contains("/anime/", true) -> TvType.Anime
            url.contains("/tv-series/", true) -> TvType.TvSeries
            url.contains("/series/", true) -> TvType.TvSeries
            url.contains("/episode/", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = if (page <= 1) {
            "$mainUrl/?s=$q"
        } else {
            "$mainUrl/page/${page.coerceAtLeast(1)}/?s=$q"
        }

        val document = app.get(searchUrl).document

        val results = document.select(
            "article, " +
                ".item, " +
                ".ml-item, " +
                ".post, " +
                ".movie-item, " +
                ".film-poster, " +
                ".grid article, " +
                ".content article, " +
                "a[href]"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    ".pagination a:contains(Berikutnya), " +
                    "a[href*='/page/${page + 1}/']"
            ) != null
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(
            "h1.title, " +
                "h1[itemprop=name], " +
                ".s-title, " +
                "h1.entry-title, " +
                "h1, " +
                "meta[property=og:title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "div.poster img, " +
                    ".s-cover img, " +
                    ".poster img, " +
                    "img.wp-post-image"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "div.wp-content, " +
                ".s-desc, " +
                ".sinopsis, " +
                ".entry-content p, " +
                ".entry-content"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/category/'], " +
                ".genre a, " +
                ".genres a, " +
                ".category a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val type = getTypeFromUrl(url)

        val recommendations = document.select(
            "article, " +
                ".related a[href], " +
                ".related-posts a[href], " +
                ".item, " +
                ".ml-item, " +
                ".post"
        ).mapNotNull { element ->
            element.toSearchResult()
        }.filter { it.url != url }
            .distinctBy { it.url }

        return if (type == TvType.TvSeries || type == TvType.Anime) {
            val episodes = parseEpisodes(url)

            newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodes
            ) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                type,
                url
            ) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    private suspend fun parseEpisodes(url: String): List<Episode> {
        val document = app.get(url).document
        val episodes = linkedMapOf<String, Episode>()

        document.select(
            "ul.episodes li a[href], " +
                "div.episode-list a[href], " +
                ".episode-list a[href], " +
                ".episodes a[href], " +
                "a[href*='/episode/'], " +
                "a:contains(Episode)"
        ).forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

            val name = element.selectFirst(".title, .nama-episode, .ep-title")
                ?.text()
                ?.trim()
                ?: element.text().trim().ifBlank { "Episode ${index + 1}" }

            episodes[href] = newEpisode(href) {
                this.name = name.cleanTitle()
                this.episode = extractEpisodeNumber(name, href) ?: index + 1
            }
        }

        return episodes.values
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        document.select(
            "video source[src], " +
                "source[src], " +
                "video[src], " +
                "a[href$=.mp4], " +
                "a[href*=.mp4], " +
                "a[href*=.m3u8]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isBlank()) return@forEach

            val fixed = fixUrl(raw)

            if (fixed.contains(".m3u8", true) || fixed.contains(".mp4", true)) {
                directLinks.add(fixed)
            }
        }

        document.select(
            "iframe[src], " +
                "embed[src], " +
                "a[href*='jeniusplay'], " +
                "a[href*='majorplay'], " +
                "a[href*='dood'], " +
                "a[href*='streamtape'], " +
                "a[href*='filemoon'], " +
                "a[href*='vidhide'], " +
                "a[href*='voe'], " +
                "a[href*='mixdrop']"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .trim()

            if (raw.isNotBlank()) {
                embedLinks.add(fixUrl(raw))
            }
        }

        var found = false

        directLinks.forEach { link ->
            if (link.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = data
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = getQualityFromName(link).takeIf {
                            it != Qualities.Unknown.value
                        } ?: Qualities.Unknown.value
                    }
                )
            }

            found = true
        }

        embedLinks.forEach { embed ->
            loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )

            found = true
        }

        return found
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun extractEpisodeNumber(
        title: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(title)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(title)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+CGVIndo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Nonton\s+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}