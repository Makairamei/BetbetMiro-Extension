package com.sad25kag.BioskopKeren

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
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
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class BioskopKerenPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BioskopKeren())
    }
}

class BioskopKeren : MainAPI() {
    override var mainUrl = "https://kebioskop21.cfd"
    override var name = "BioskopKeren"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "category/movie/" to "Movie",
        "category/serial-asia/" to "TV Series Asia",
        "category/k-movie/" to "Film Korea",
        "category/korea/" to "Drama Korea",
        "category/west/" to "TV Series West",
        "category/box-office/" to "Box Office",

        "category/action/" to "Action",
        "category/adventure/" to "Adventure",
        "category/animasi/" to "Animation",
        "category/biography/" to "Biography",
        "category/comedy/" to "Comedy",
        "category/crime/" to "Crime",
        "category/documentary/" to "Documentary",
        "category/drama/" to "Drama",
        "category/family/" to "Family",
        "category/fantasy/" to "Fantasy",
        "category/history/" to "History",
        "category/horor/" to "Horror",
        "category/mystery/" to "Mystery",
        "category/romance/" to "Romance",
        "category/sci-fi/" to "Sci-Fi",
        "category/sport/" to "Sport",
        "category/thriller/" to "Thriller",
        "category/mandarin/" to "Chinese",
        "category/india/" to "Hindi",
        "category/japan/" to "Japan",
        "category/thailand/" to "Thailand",

        "category/2026/" to "2026",
        "category/film-tahun-2025-terbaru/" to "2025",
        "category/2024/" to "2024",
        "category/2023/" to "2023",
        "category/2022/" to "2022",
        "category/2021/" to "2021",
        "category/2020/" to "2020"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val siteHosts = listOf(
        "kebioskop21.cfd",
        "bioskop-keren.com",
        "bioskopkeren.now",
        "bioskop-keren.com.now"
    )

    private val playerHosts = listOf(
        "streaming.kebioskop21.pro",
        "abyss.to",
        "short.icu",
        "abysscdn.com",
        "iamcdn.net"
    )

    private fun isSiteUrl(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return siteHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun stripOrigin(url: String): String {
        return url.replace(Regex("""^https?://[^/]+/?""", RegexOption.IGNORE_CASE), "")
    }

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

        val items = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            items,
            hasNext = hasNextPage(document, page)
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

    private fun hasNextPage(
        document: Document,
        page: Int
    ): Boolean {
        return document.selectFirst(
            "a.next, " +
                "a[rel=next], " +
                ".pagination a:contains(Next), " +
                ".nav-links a:contains(Next), " +
                ".page-numbers.next, " +
                "a[href*='/page/${page + 1}/']"
        ) != null
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article:has(a), " +
                ".post:has(a), " +
                ".item:has(a), " +
                ".movie:has(a), " +
                ".series:has(a), " +
                ".ml-item:has(a), " +
                ".result-item:has(a), " +
                ".list-film:has(a), " +
                ".film-list:has(a), " +
                ".items article:has(a), " +
                ".content article:has(a), " +
                ".grid article:has(a), " +
                ".box:has(a), " +
                ".moviefilm:has(a), " +
                ".movief:has(a)"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        if (results.isEmpty()) {
            document.select(
                "h2 a[href], " +
                    "h3 a[href], " +
                    "a[href]:has(img)"
            ).forEach { element ->
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
                    "a[href]:contains(Watch), " +
                    "a[href]:has(img), " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!isSiteUrl(href)) return null
        if (isBlockedUrl(href)) return null

        val image = selectFirst("img") ?: anchor.selectFirst("img")

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
                !it.equals("Watch", true) &&
                !it.equals("Trailer", true) &&
                !it.equals("Download", true) &&
                !it.equals("Home", true) &&
                !it.equals("Tümünü Gör", true)
        }?.cleanTitle() ?: return null

        if (title.length < 2) return null

        val poster = fixUrlNull(image?.getImageAttr())
            ?.takeIf { !isBadImage(it) }

        val type = guessType(
            url = href,
            text = text(),
            title = title
        )

        return if (type == TvType.Movie) {
            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                href,
                type
            ) {
                posterUrl = poster
                year = extractYear(title) ?: extractYear(text())
            }
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = stripOrigin(url).trim('/').lowercase()

        if (path.isBlank()) return true

        val blockedPrefixes = listOf(
            "category/",
            "genre/",
            "country/",
            "year/",
            "tag/",
            "author/",
            "page/",
            "search",
            "wp-content/",
            "wp-json/",
            "wp-admin/",
            "dmca",
            "privacy",
            "contact",
            "sitemap",
            "feed"
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
            hasNext = hasNextPage(document, page)
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

        val poster = getPoster(document)
        val text = document.text()
        val meta = parseMetadata(document)

        val plot = document.selectFirst(
            ".entry-content p, " +
                ".sinopsis p, " +
                ".summary p, " +
                ".desc p, " +
                ".description p, " +
                ".entry-content, " +
                ".sinopsis, " +
                ".summary, " +
                ".desc, " +
                ".description"
        )?.text()
            ?.trim()
            ?.takeIf { it.length > 30 }

        val year = meta["Year"]?.toIntOrNull()
            ?: meta["Tahun"]?.toIntOrNull()
            ?: extractYear(title)
            ?: extractYear(text)

        val duration = parseDuration(meta["Duration"] ?: meta["Durasi"] ?: text)
        val rating = meta["IMDb"] ?: meta["Rating"] ?: parseRating(text)

        val tags = (document.select(
            "a[href*='/genre/'], " +
                "a[href*='/tag/']"
        ).map { it.text().trim() } + meta["Genre"].orEmpty().split(","))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = (document.select(
            "a[href*='/cast/'], " +
                "a[href*='/actor/'], " +
                "a[href*='/pemain/']"
        ).map { it.text().trim() } + meta["Cast"].orEmpty().split(","))
            .map { it.trim() }
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

        val imdbId = Regex("""tt\d{6,10}""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value

        val recommendations = document.select(
            ".related article:has(a), " +
                ".film-terkait article:has(a), " +
                ".items article:has(a), " +
                ".content article:has(a), " +
                "article:has(a)"
        ).mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .filter { it.url != url }

        val episodes = parseEpisodeLinks(document, url, poster)

        val type = when {
            url.contains("series", true) -> TvType.TvSeries
            text.contains("Season", true) || text.contains("Episode", true) || episodes.size > 1 -> {
                if (text.contains("Korea", true)) TvType.AsianDrama else TvType.TvSeries
            }
            else -> TvType.Movie
        }

        return if (type == TvType.Movie && episodes.size <= 1) {
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
                addImdbId(imdbId)
            }
        } else {
            val finalEpisodes = episodes.ifEmpty {
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
                finalEpisodes
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
                "a[href*='season'], " +
                ".episodios a[href], " +
                ".episodes a[href], " +
                ".eplister a[href], " +
                ".les-content a[href], " +
                ".season a[href], " +
                ".serial a[href], " +
                ".entry-content a[href]"
        ).forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

            if (!isSiteUrl(href)) return@forEachIndexed
            if (isBlockedUrl(href)) return@forEachIndexed

            val label = element.text().trim()
            val epNumber = extractEpisodeNumber(label, href) ?: index + 1
            val seasonNumber = extractSeasonNumber(label, href)

            links[href] = newEpisode(href) {
                name = label.ifBlank { "Episode $epNumber" }.cleanTitle()
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

    private fun parseMetadata(document: Document): Map<String, String> {
        val output = linkedMapOf<String, String>()

        document.select("li, p, div, span").forEach { element ->
            val line = element.text()
                .replace(Regex("""\s+"""), " ")
                .trim()

            val parts = line.split(":", limit = 2)
            if (parts.size != 2) return@forEach

            val key = parts[0].trim()
            val value = parts[1].trim()

            if (
                key.matches(Regex("""(?i)(genre|cast|year|tahun|duration|durasi|type|country|rating|imdb|score|director|episode|season)""")) &&
                value.isNotBlank() &&
                value.length < 500
            ) {
                output[key] = value
            }
        }

        return output
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val visited = mutableSetOf<String>()
        val directLinks = linkedSetOf<Pair<String, String>>()
        val embedLinks = ArrayDeque<Pair<String, String>>()
        var found = false

        fun queueEmbed(raw: String?, baseUrl: String, referer: String = baseUrl) {
            if (raw.isNullOrBlank()) return
            val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
                .replace(".txt", ".m3u8")
                .trim()

            if (fixed.isBlank() || isAdUrl(fixed) || isBadPlayableUrl(fixed)) return

            when {
                isDirectMedia(fixed) -> directLinks.add(fixed to referer)
                fixed.startsWith("http", true) && isLikelyPlayable(fixed) -> embedLinks.add(fixed to referer)
            }
        }

        suspend fun parsePage(pageUrl: String, referer: String): String {
            val response = app.get(
                pageUrl,
                headers = headers,
                referer = referer,
                timeout = 30L
            )

            val document = response.document
            val html = response.text.cleanEscaped()

            extractSubtitles(pageUrl, html, subtitleCallback)

            document.select(
                "iframe#player[src], " +
                    "iframe[src], " +
                    "iframe[data-src], " +
                    "iframe[data-litespeed-src], " +
                    "embed[src], " +
                    "source[src], " +
                    "video[src], " +
                    "video source[src], " +
                    "a[data-video], " +
                    "[data-video], " +
                    "[data-src], " +
                    "[data-url]"
            ).forEach { element ->
                queueEmbed(element.attr("data-video"), pageUrl)
                queueEmbed(element.attr("data-url"), pageUrl)
                queueEmbed(element.attr("data-litespeed-src"), pageUrl)
                queueEmbed(element.attr("data-src"), pageUrl)
                queueEmbed(element.attr("src"), pageUrl)
                queueEmbed(element.attr("href"), pageUrl)
            }

            document.select("a[href]").forEach { element ->
                val href = element.attr("href").trim()
                val label = element.text().lowercase()

                if (
                    href.startsWith("#") ||
                    href.startsWith("javascript", true) ||
                    href.contains("youtube.com", true) ||
                    href.contains("youtu.be", true) ||
                    label.contains("trailer")
                ) {
                    return@forEach
                }

                if (isLikelyPlayable(href) || isLikelyPlayableText(label)) {
                    queueEmbed(href, pageUrl)
                }
            }

            extractPlayableUrls(html).forEach { raw ->
                queueEmbed(raw, pageUrl)
            }

            return html
        }

        parsePage(data, mainUrl)

        var safety = 0
        while (embedLinks.isNotEmpty() && safety++ < 80) {
            val (embed, referer) = embedLinks.removeFirst()
            if (!visited.add(embed)) continue

            if (isDirectMedia(embed)) {
                directLinks.add(embed to referer)
                continue
            }

            if (isAbyssLike(embed) || isKnownExtractorHost(embed)) {
                val success = runCatching {
                    loadExtractor(embed, referer, subtitleCallback, callback)
                }.getOrDefault(false)

                if (success) found = true
            }

            val nestedHtml = when {
                embed.contains("/apidrive.php", true) -> resolveApiDrivePage(embed, referer)
                shouldCrawlEmbed(embed) -> runCatching { parsePage(embed, referer) }.getOrDefault("")
                else -> ""
            }

            if (nestedHtml.isNotBlank()) {
                extractPlayableUrls(nestedHtml).forEach { raw ->
                    queueEmbed(raw, embed, embed)
                }
            }
        }

        directLinks.distinct().forEach { (link, referer) ->
            emitDirectLink(
                link = link,
                referer = referer,
                callback = callback
            )
            found = true
        }

        return found
    }

    private suspend fun resolveApiDrivePage(
        url: String,
        referer: String
    ): String {
        val pages = mutableListOf<String>()

        runCatching {
            app.get(
                url,
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull()?.let { pages.add(it) }

        runCatching {
            app.post(
                url,
                data = mapOf("play" to "play"),
                headers = headers,
                referer = referer,
                timeout = 30L
            ).text.cleanEscaped()
        }.getOrNull()?.let { pages.add(it) }

        return pages.joinToString("\n")
    }

    private fun extractSubtitles(
        pageUrl: String,
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val candidates = linkedSetOf<String>()
        candidates.add(pageUrl)
        Regex("""[?&]sub=([^"'&<>\s]+)""", RegexOption.IGNORE_CASE)
            .findAll("$pageUrl\n$html")
            .map { it.groupValues[1] }
            .forEach { raw ->
                val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
                candidates.add(decoded)
            }

        candidates
            .filter { it.contains(".srt", true) || it.contains(".vtt", true) }
            .map { normalizeUrl(it, pageUrl) }
            .distinct()
            .forEach { sub -> subtitleCallback.invoke(SubtitleFile("Indonesia", sub)) }
    }

    private fun resolveNestedLinks(
        text: String,
        baseUrl: String
    ): List<String> {
        return extractPlayableUrls(text).map { normalizeUrl(it, baseUrl) }
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

        if (fixed.isBlank() || isAdUrl(fixed) || isBadPlayableUrl(fixed)) return

        when {
            isDirectMedia(fixed) -> directLinks.add(fixed)
            fixed.startsWith("http", true) -> embedLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (isAdUrl(link) || isBadPlayableUrl(link)) return

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
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to runCatching {
                        val uri = URI(referer)
                        "${uri.scheme}://${uri.host}"
                    }.getOrDefault(mainUrl)
                )
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
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.txt|apidrive\.php)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching {
                    URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoSource|videoUrl|embedUrl|embed_url|data-video|data-src)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("apidrive.php", true) ||
                    isKnownHost(it)
            }
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex(
            """https?://[^"'\\\s<>]+?(?:apidrive\.php|embed|player|stream|filemoon|streamwish|wishfast|dood|streamtape|vidhide|vidguard|voe|mixdrop|mp4upload|lulustream|lulu|hglink|hgcloud|acefile|krakenfiles|gdrive|drive\.google|ok\.ru|odnoklassniki|terabox|mega|abyss|short\.icu)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped() }
            .filterNot { isAdUrl(it) || isBadPlayableUrl(it) }
            .forEach { urls.add(it) }

        Regex("""const\s+datas\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { encoded ->
                val decoded = runCatching { String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)) }.getOrNull()
                val slug = Regex(""""slug"\s*:\s*"([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                    .find(decoded.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
                if (!slug.isNullOrBlank()) {
                    urls.add("https://short.icu/$slug")
                    urls.add("https://abyss.to/?v=$slug")
                    urls.add("https://abysscdn.com/?v=$slug")
                }
            }

        Regex("""[?&]v=([A-Za-z0-9_-]{9,})""", RegexOption.IGNORE_CASE)
            .findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .forEach { slug ->
                urls.add("https://short.icu/$slug")
                urls.add("https://abyss.to/?v=$slug")
            }

        return urls.toList()
    }

    private fun isKnownHost(url: String): Boolean {
        val value = url.lowercase()

        return listOf(
            "streaming.kebioskop21.pro",
            "apidrive.php",
            "abyss.to",
            "short.icu",
            "abysscdn",
            "embed",
            "player",
            "stream",
            "filemoon",
            "streamwish",
            "wishfast",
            "dood",
            "streamtape",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "mp4upload",
            "lulustream",
            "hglink",
            "hgcloud",
            "acefile",
            "krakenfiles",
            "gdrive",
            "drive.google",
            "ok.ru",
            "odnoklassniki",
            "terabox",
            "mega.nz"
        ).any { value.contains(it) }
    }

    private fun isKnownExtractorHost(url: String): Boolean {
        val value = url.lowercase()
        return listOf(
            "abyss.to",
            "short.icu",
            "abysscdn",
            "filemoon",
            "streamwish",
            "wishfast",
            "dood",
            "streamtape",
            "vidhide",
            "vidguard",
            "voe",
            "mixdrop",
            "mp4upload",
            "lulustream",
            "hglink",
            "hgcloud",
            "acefile",
            "krakenfiles",
            "drive.google",
            "ok.ru",
            "odnoklassniki"
        ).any { value.contains(it) }
    }

    private fun isLikelyPlayable(url: String): Boolean {
        return url.contains(".m3u8", true) ||
            url.contains(".mp4", true) ||
            url.contains("apidrive.php", true) ||
            isKnownHost(url)
    }

    private fun isLikelyPlayableText(text: String): Boolean {
        return text.contains("download") ||
            text.contains("stream") ||
            text.contains("nonton") ||
            text.contains("watch") ||
            text.contains("server") ||
            text.contains("play") ||
            text.contains("360p") ||
            text.contains("480p") ||
            text.contains("720p") ||
            text.contains("1080p")
    }

    private fun shouldCrawlEmbed(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val lower = url.lowercase()
        return playerHosts.any { host == it || host.endsWith(".$it") } ||
            lower.contains("/apidrive.php") ||
            lower.contains("embed") ||
            lower.contains("player")
    }

    private fun isDirectMedia(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("blogger.googleusercontent.com") ||
            lower.contains("video-downloads.googleusercontent.com")
    }

    private fun isAbyssLike(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("abyss.to") ||
            lower.contains("short.icu") ||
            lower.contains("abysscdn")
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

    private fun getPoster(document: Document): String? {
        return fixUrlNull(
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
            value.contains("banner") ||
            value.endsWith(".svg")
    }

    private fun guessType(
        url: String,
        text: String,
        title: String
    ): TvType {
        val value = "$url $text $title"

        return when {
            value.contains("korea", true) -> TvType.AsianDrama
            value.contains("series", true) -> TvType.TvSeries
            value.contains("season", true) -> TvType.TvSeries
            value.contains("episode", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun extractYear(text: String?): Int? {
        return Regex("""\b(19|20)\d{2}\b""")
            .find(text.orEmpty())
            ?.value
            ?.toIntOrNull()
    }

    private fun parseDuration(text: String?): Int? {
        return Regex("""(\d+)\s*(?:min|menit)""", RegexOption.IGNORE_CASE)
            .find(text.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseRating(text: String): String? {
        return Regex("""(?:imdb|rating|score)?\s*([0-9](?:\.[0-9])?|10(?:\.0)?)\s*/\s*10""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""\b([0-9](?:\.[0-9])?|10(?:\.0)?)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
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
        return url.contains(".m3u8", true)
    }

    private fun isAdUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains("vast") ||
            value.contains("preroll") ||
            value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("adsbygoogle") ||
            value.contains("banner") ||
            value.contains("pasang-iklan") ||
            value.contains("groggedrotl") ||
            value.contains("leatmansures") ||
            value.contains("decafeligiblyhad") ||
            value.contains("histats") ||
            value.contains("googletagmanager") ||
            value.contains("google-analytics") ||
            value.contains("cloudflareinsights") ||
            value.contains("pixel.morphify")
    }

    private fun isBadPlayableUrl(url: String): Boolean {
        val value = url.lowercase()
        return value.endsWith(".js") ||
            value.endsWith(".css") ||
            value.endsWith(".jpg") ||
            value.endsWith(".jpeg") ||
            value.endsWith(".png") ||
            value.endsWith(".webp") ||
            value.endsWith(".gif") ||
            value.endsWith(".svg") ||
            value.contains("/wp-content/uploads/") && !value.contains(".mp4") && !value.contains(".m3u8") ||
            value.contains("whatsapp.com") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("x.com/") ||
            value.contains("mailto:")
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("540", true) -> Qualities.P480.value
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
            .replace(Regex("""^\s*Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub\s*Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Subtitle\s+Indonesia.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+BioskopKeren.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\|\s+.*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}