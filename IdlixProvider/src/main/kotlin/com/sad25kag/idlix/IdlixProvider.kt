package com.sad25kag.idlix

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://ryangoslingfrance.com"
    override var name = "Idlix"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "category/movies/" to "Movies",
        "category/serial-tv/" to "Serial TV",
        "category/animation/" to "Animation",
        "best-rating/" to "Best Rating",

        "category/action/" to "Action",
        "category/adventure/" to "Adventure",
        "category/action-adventure/" to "Action & Adventure",
        "category/comedy/" to "Comedy",
        "category/crime/" to "Crime",
        "category/documentary/" to "Documentary",
        "category/drama/" to "Drama",
        "category/family/" to "Family",
        "category/fantasy/" to "Fantasy",
        "category/horror/" to "Horror",
        "category/mystery/" to "Mystery",
        "category/romance/" to "Romance",
        "category/science-fiction/" to "Science Fiction",
        "category/sci-fi-fantasy/" to "Sci-Fi & Fantasy",
        "category/thriller/" to "Thriller",
        "category/war-politics/" to "War & Politics",

        "year/2024/" to "2024",
        "year/2023/" to "2023",
        "year/2022/" to "2022",
        "year/2021/" to "2021",
        "year/2020/" to "2020",
        "year/2019/" to "2019",
        "year/2018/" to "2018",
        "year/2017/" to "2017",
        "year/2016/" to "2016",
        "year/2015/" to "2015",

        "country/philippines/" to "Philippines",
        "country/korea/" to "Korea",
        "country/australia/" to "Australia",
        "country/canada/" to "Canada",
        "country/china/" to "China",
        "country/usa/" to "USA",
        "country/united-kingdom/" to "United Kingdom",
        "country/thailand/" to "Thailand",
        "country/indonesia/" to "Indonesia",
        "country/japan/" to "Japan",
        "country/hong-kong/" to "Hong Kong",
        "country/india/" to "India",
        "country/singapore/" to "Singapore",
        "country/malaysia/" to "Malaysia"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            results,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    ".nav-links a:contains(Next), " +
                    ".page-numbers.next, " +
                    "a[href*='/page/${page + 1}/']"
            ) != null
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val clean = path.trim('/')

        return when {
            clean.isBlank() && page <= 1 -> mainUrl
            clean.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$clean/"
            else -> "$mainUrl/$clean/page/$page/"
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(h2 a):has(img), " +
                "article:has(h3 a):has(img), " +
                ".post:has(h2 a):has(img), " +
                ".post:has(h3 a):has(img), " +
                ".result-item:has(a):has(img), " +
                ".movie-item:has(a):has(img), " +
                ".ml-item:has(a):has(img), " +
                ".items article:has(a):has(img), " +
                ".content article:has(a):has(img), " +
                ".module article:has(a):has(img), " +
                ".box article:has(a):has(img)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select("a[href]:has(img)").forEach { element ->
                element.toSearchResult()?.let { item ->
                    results[item.url] = item
                }
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "h2 a[href], " +
                    "h3 a[href], " +
                    ".entry-title a[href], " +
                    ".title a[href], " +
                    "a[href]:contains(Tonton), " +
                    "a[href]:has(img)"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")
        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }
            ?: return null

        val title = listOf(
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            selectFirst(".entry-title")?.text(),
            selectFirst(".title")?.text(),
            anchor.attr("title"),
            image?.attr("alt"),
            anchor.text()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Tonton", true) &&
                !it.equals("Tonton Film", true) &&
                !it.equals("Trailer", true) &&
                !it.equals("Download", true) &&
                !it.equals("Home", true) &&
                !it.equals("Iklan", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val type = guessType(href, text(), title)

        return if (type == TvType.TvSeries || type == TvType.Anime || type == TvType.AsianDrama) {
            newTvSeriesSearchResponse(
                title,
                href,
                type
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        } else {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "category/",
            "tag/",
            "genre/",
            "country/",
            "year/",
            "author/",
            "page/",
            "search",
            "iklan",
            "live-stream",
            "privacy",
            "dmca",
            "contact",
            "sitemap",
            "feed",
            "wp-content/",
            "wp-json/",
            "wp-admin/"
        )

        return blockedPrefixes.any {
            path == it.trimEnd('/') || path.startsWith(it)
        }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()

        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }

        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val results = parseCards(document)
            .distinctBy { it.url }

        return newSearchResponseList(
            results,
            hasNext = document.selectFirst(
                "a.next, " +
                    "a[rel=next], " +
                    ".pagination a:contains(Next), " +
                    ".nav-links a:contains(Next), " +
                    ".page-numbers.next, " +
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

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = headers,
            timeout = 30L
        ).document

        val title = document.selectFirst("h1.entry-title, h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/")
                .replace("-", " ")
                .cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    ".poster img, " +
                    ".thumb img, " +
                    ".post-thumbnail img, " +
                    "article img, " +
                    "img.wp-post-image, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )?.takeIf { !isBadImage(it) }

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".summary p, " +
                ".sinopsis, " +
                ".desc, " +
                ".description, " +
                "article p"
        )?.text()
            ?.trim()
            ?.takeIf { it.length > 30 }

        val htmlText = document.text()
        val year = extractYear(title) ?: extractYear(htmlText)
        val duration = parseDuration(htmlText)
        val rating = parseRating(htmlText)

        val tags = document.select(
            "a[href*='/category/'], " +
                "a[href*='/genre/'], " +
                "a[href*='/tag/']"
        ).map { it.text().trim() }
            .filter {
                it.isNotBlank() &&
                    !it.equals("Movies", true) &&
                    !it.equals("Serial TV", true) &&
                    !it.equals("Home", true) &&
                    !it.equals("Iklan", true)
            }
            .distinct()

        val actors = document.select(
            "a[href*='/cast/'], " +
                "a[href*='/pemain/'], " +
                "a[href*='/actor/']"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val trailer = document.selectFirst(
            "a[href*='youtube.com'], " +
                "a[href*='youtu.be'], " +
                "iframe[src*='youtube.com'], " +
                "iframe[src*='youtu.be']"
        )?.let { element ->
            element.attr("href").ifBlank { element.attr("src") }
        }?.takeIf { it.isNotBlank() }

        val recommendations = document.select(
            ".related article:has(a):has(img), " +
                ".film-terkait article:has(a):has(img), " +
                ".items article:has(a):has(img)"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val episodeLinks = parseEpisodeLinks(document, url, poster)

        val type = when {
            episodeLinks.size > 1 -> TvType.TvSeries
            tags.any { it.contains("Anime", true) } -> TvType.Anime
            tags.any { it.contains("Serial TV", true) || it.contains("TV Show", true) } -> TvType.TvSeries
            htmlText.contains("Eps:", true) || htmlText.contains("Episode", true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (type == TvType.Movie && episodeLinks.size <= 1) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val episodes = episodeLinks.ifEmpty {
                listOf(
                    newEpisode(url) {
                        name = title
                        episode = 1
                        posterUrl = poster
                        description = plot
                        duration?.let { runTime = it }
                    }
                )
            }

            newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodes
            ) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = Score.from10(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    private fun parseEpisodeLinks(
        document: Document,
        currentUrl: String,
        poster: String?
    ): List<Episode> {
        val links = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode'], " +
                "a[href*='eps'], " +
                "a[href*='/season-'], " +
                ".episodios a[href], " +
                ".episodes a[href], " +
                ".eplister a[href], " +
                ".les-content a[href], " +
                ".season a[href], " +
                ".serial a[href]"
        ).forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

            if (!href.startsWith(mainUrl)) return@forEachIndexed
            if (isBlockedUrl(href)) return@forEachIndexed

            val text = element.text().trim()
            val epNumber = extractEpisodeNumber(text, href) ?: index + 1
            val seasonNumber = extractSeasonNumber(text, href)

            links[href] = newEpisode(href) {
                name = text.ifBlank { "Episode $epNumber" }.cleanTitle()
                episode = epNumber
                season = seasonNumber
                posterUrl = poster
            }
        }

        if (links.isEmpty()) {
            links[currentUrl] = newEpisode(currentUrl) {
                name = "Movie"
                episode = 1
                posterUrl = poster
            }
        }

        return links.values
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            data,
            headers = headers,
            referer = mainUrl,
            timeout = 30L
        )

        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        collectDooplayAjaxLinks(
            document = document,
            pageUrl = data,
            directLinks = directLinks,
            embedLinks = embedLinks
        )

        document.select(
            "iframe[src], " +
                "iframe[data-src], " +
                "iframe[data-litespeed-src], " +
                "embed[src], " +
                "source[src], " +
                "video[src], " +
                "video source[src]"
        ).forEach { element ->
            val raw = element.attr("data-litespeed-src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .trim()

            addCandidate(raw, data, directLinks, embedLinks)
        }

        document.select("a[href]").forEach { element ->
            val href = element.attr("href").trim()
            val text = element.text().lowercase()

            if (
                text.contains("trailer") ||
                href.contains("youtube.com", true) ||
                href.contains("youtu.be", true) ||
                href.startsWith("#") ||
                href.startsWith("javascript", true)
            ) {
                return@forEach
            }

            if (
                href.contains(".m3u8", true) ||
                href.contains(".mp4", true) ||
                href.contains("embed", true) ||
                href.contains("player", true) ||
                href.contains("stream", true) ||
                href.contains("pm21", true) ||
                href.contains("dm21", true) ||
                href.contains("4meplayer", true) ||
                href.contains("veev", true) ||
                href.contains("minochinos", true) ||
                href.contains("dingtezuni", true) ||
                href.contains("dintezuvio", true) ||
                href.contains("mivalyo", true) ||
                href.contains("movearnpre", true) ||
                href.contains("hgcloud", true) ||
                href.contains("hglink", true) ||
                href.contains("luluvdoo", true) ||
                href.contains("majorplay", true)
            ) {
                addCandidate(href, data, directLinks, embedLinks)
            }
        }

        extractPlayableUrls(html).forEach { url ->
            addCandidate(url, data, directLinks, embedLinks)
        }

        var found = false

        directLinks.distinct().forEach { link ->
            emitDirectLink(
                link = link,
                referer = data,
                callback = callback
            )
            found = true
        }

        embedLinks.distinct().forEach { embed ->
            val success = loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )

            if (success) {
                found = true
            } else {
                resolveNestedLinks(embed, data).forEach { nested ->
                    val fixed = normalizeUrl(nested, embed).replace(".txt", ".m3u8")

                    when {
                        isAdUrl(fixed) -> Unit

                        isHlsLike(fixed) || fixed.contains(".mp4", true) -> {
                            emitDirectLink(
                                link = fixed,
                                referer = embed,
                                callback = callback
                            )
                            found = true
                        }

                        fixed.startsWith("http", true) -> {
                            val nestedSuccess = loadExtractor(
                                fixed,
                                embed,
                                subtitleCallback,
                                callback
                            )

                            if (nestedSuccess) found = true
                        }
                    }
                }
            }
        }

        return found
    }

    private suspend fun collectDooplayAjaxLinks(
        document: Document,
        pageUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        val options = document.select(
            ".dooplay_player_option[data-post][data-nume][data-type], " +
                ".player-option[data-post][data-nume][data-type], " +
                "li[data-post][data-nume][data-type], " +
                "div[data-post][data-nume][data-type], " +
                "span[data-post][data-nume][data-type]"
        )

        if (options.isEmpty()) return

        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

        options.forEach { option ->
            val post = option.attr("data-post").trim()
            val nume = option.attr("data-nume").trim()
            val type = option.attr("data-type").trim()

            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

            val ajaxText = runCatching {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = pageUrl,
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    timeout = 30L
                ).text.cleanEscaped()
            }.getOrNull().orEmpty()

            if (ajaxText.isBlank()) return@forEach

            val embedUrl = runCatching {
                AppUtils.parseJson<DooplayAjaxResponse>(ajaxText).embedUrlFinal
            }.getOrNull()

            if (!embedUrl.isNullOrBlank()) {
                val decoded = decodeIframe(embedUrl)
                addCandidate(decoded, pageUrl, directLinks, embedLinks)

                Jsoup.parse(decoded).select("iframe[src], iframe[data-src], source[src], video[src]").forEach { element ->
                    val raw = element.attr("data-src")
                        .ifBlank { element.attr("src") }
                        .trim()

                    addCandidate(raw, pageUrl, directLinks, embedLinks)
                }
            }

            extractPlayableUrls(ajaxText).forEach { raw ->
                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }

            Jsoup.parse(ajaxText).select("iframe[src], iframe[data-src], source[src], video[src]").forEach { element ->
                val raw = element.attr("data-src")
                    .ifBlank { element.attr("src") }
                    .trim()

                addCandidate(raw, pageUrl, directLinks, embedLinks)
            }
        }
    }

    private fun decodeIframe(value: String): String {
        val clean = value.cleanEscaped()

        return when {
            clean.contains("<iframe", true) -> clean
            clean.startsWith("http", true) -> clean
            else -> runCatching {
                URLDecoder.decode(clean, "UTF-8")
            }.getOrDefault(clean)
        }
    }

    private suspend fun resolveNestedLinks(
        url: String,
        referer: String
    ): List<String> {
        val text = runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull().orEmpty()

        if (text.isBlank()) return emptyList()

        return extractPlayableUrls(text)
    }

    private fun addCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        embedLinks: MutableSet<String>
    ) {
        if (raw.isBlank()) return

        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")

        if (fixed.isBlank() || isAdUrl(fixed)) return

        when {
            isHlsLike(fixed) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
            fixed.startsWith("http", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(link)) return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = if (isHlsLike(link)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:majorplay|e2e\.majorplay|pm21|dm21|4meplayer|veev|hglink|hgcloud|luluvdoo|minochinos|dingtezuni|dintezuvio|mivalyo|movearnpre)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.txt|majorplay|pm21|dm21|4meplayer|veev|hglink|hgcloud|luluvdoo|minochinos|dingtezuni|dintezuvio|mivalyo|movearnpre)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|embedUrl|embed_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("majorplay", true) ||
                    it.contains("pm21", true) ||
                    it.contains("dm21", true) ||
                    it.contains("4meplayer", true) ||
                    it.contains("veev", true) ||
                    it.contains("hglink", true) ||
                    it.contains("hgcloud", true) ||
                    it.contains("luluvdoo", true) ||
                    it.contains("minochinos", true) ||
                    it.contains("dingtezuni", true) ||
                    it.contains("dintezuvio", true) ||
                    it.contains("mivalyo", true) ||
                    it.contains("movearnpre", true)
            }
            .filterNot { isAdUrl(it) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun normalizeUrl(
        url: String,
        baseUrl: String
    ): String {
        val clean = url.cleanEscaped()

        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""")
                    .find(baseUrl)
                    ?.value
                    ?: mainUrl
                "$origin$clean"
            }

            else -> runCatching {
                URI(baseUrl).resolve(clean).toString()
            }.getOrDefault(clean)
        }
    }

    private fun Element.getImageAttr(): String? {
        fun fromSrcSet(value: String?): String? {
            if (value.isNullOrBlank()) return null

            return value
                .split(",")
                .map { it.trim().substringBefore(" ") }
                .lastOrNull {
                    it.isNotBlank() &&
                        !isBadImage(it)
                }
        }

        val raw = fromSrcSet(attr("data-srcset"))
            ?: fromSrcSet(attr("data-lazy-srcset"))
            ?: fromSrcSet(attr("srcset"))
            ?: attr("abs:data-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("abs:data-original").takeIf { it.isNotBlank() }
            ?: attr("abs:data-full").takeIf { it.isNotBlank() }
            ?: attr("abs:src").takeIf { it.isNotBlank() }
            ?: attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }

        return raw
            ?.trim()
            ?.takeIf { !isBadImage(it) }
    }

    private fun isBadImage(url: String): Boolean {
        val value = url.lowercase()

        return value.isBlank() ||
            value.startsWith("data:image") ||
            value.contains("blank") ||
            value.contains("placeholder") ||
            value.contains("default") ||
            value.contains("no-image") ||
            value.contains("noimage") ||
            value.contains("loader") ||
            value.contains("loading") ||
            value.contains("lazy") ||
            value.contains("spacer") ||
            value.contains("logo") ||
            value.contains("favicon") ||
            value.contains("klikzeus") ||
            value.contains("ibosport") ||
            value.contains("dewasport") ||
            value.contains("qq288") ||
            value.contains("sngine") ||
            value.endsWith(".svg")
    }

    private fun guessType(
        url: String,
        body: String,
        title: String
    ): TvType {
        val text = "$url $body $title"

        return when {
            text.contains("anime", true) -> TvType.Anime
            text.contains("serial-tv", true) ||
                text.contains("tv show", true) ||
                text.contains("eps:", true) ||
                text.contains("episode", true) -> TvType.TvSeries
            text.contains("drama china", true) ||
                text.contains("korea", true) ||
                text.contains("china", true) ||
                text.contains("thailand", true) -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun extractYear(text: String?): Int? {
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text.orEmpty())
            ?.value
            ?.toIntOrNull()
    }

    private fun parseDuration(text: String): Int? {
        return Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseRating(text: String): String? {
        return Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\b""")
            .findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull()
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d{1,4})\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractSeasonNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:season|s)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find("$text $href")
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun isHlsLike(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            (
                url.contains("majorplay", true) &&
                    url.contains("config", true) &&
                    url.contains(".json", true)
                )
    }

    private fun isAdUrl(url: String): Boolean {
        return url.contains("vast", true) ||
            url.contains("preroll", true) ||
            url.contains("qq288", true) ||
            url.contains("sngine", true) ||
            url.contains("/content/uploads/videos/", true) ||
            url.contains("demo.sngine.com", true) ||
            url.contains("doubleclick", true) ||
            url.contains("googlesyndication", true)
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+IDLIX\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DooplayAjaxResponse(
    @JsonProperty("embed_url") val embedUrl: String? = null,
    @JsonProperty("embedUrl") val embedUrlAlt: String? = null,
    @JsonProperty("url") val url: String? = null
) {
    val embedUrlFinal: String?
        get() = embedUrl ?: embedUrlAlt ?: url
}