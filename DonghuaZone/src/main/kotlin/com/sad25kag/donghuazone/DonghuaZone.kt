package com.sad25kag.donghuazone

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class DonghuaZone : MainAPI() {
    override var mainUrl = "https://www.donghuazone.com"
    override var name = "DonghuaZone"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Episode",
        "$mainUrl/search/label/Episode?max-results=10" to "Episode",
        "$mainUrl/search/label/Series?max-results=10" to "Series",
        "$mainUrl/search/label/Movie?max-results=10" to "Movie",
        "$mainUrl/search/label/Action?max-results=10" to "Action",
        "$mainUrl/search/label/Adventure?max-results=10" to "Adventure",
        "$mainUrl/search/label/Fantasy?max-results=10" to "Fantasy",
        "$mainUrl/search/label/Romance?max-results=10" to "Romance",
        "$mainUrl/search/label/Ongoing?max-results=10" to "Ongoing",
        "$mainUrl/search/label/Completed?max-results=10" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data
        val document = app.get(url, headers = defaultHeaders, referer = mainUrl).document
        val items = document.toSearchResults(url)

        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = false
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = "label:Series $query".urlEncoded()
        val url = "$mainUrl/search?q=$encoded&max-results=10"

        return runCatching {
            app.get(url, headers = defaultHeaders, referer = mainUrl).document.toSearchResults(url)
        }.getOrDefault(emptyList()).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders, referer = mainUrl).document
        val rawTitle = document.selectFirst("h1.entry-title, h1.post-title, h1, .post-title, .entry-title")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("DonghuaZone title not found")

        val poster = document.findPoster(url)
            ?: document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
                ?.attr("content")
                ?.absoluteUrl(url)

        val bodyText = document.selectFirst(".post-body, .entry-content, article, .post")
            ?.text()
            ?.cleanText()
            .orEmpty()

        val description = bodyText
            .substringBefore("If the current server", missingDelimiterValue = bodyText)
            .cleanText()
            .takeIf { it.length > 30 }

        val labels = document.select("a[href*='/search/label/']")
            .map { DonghuaZoneLabel(it.text().cleanText(), it.attr("href").absoluteUrl(mainUrl)) }
            .filter { it.name.isNotBlank() }
            .distinctBy { it.name.lowercase(Locale.ROOT) }

        val seriesLabel = labels.firstOrNull { it.isSeriesTitleLabel() }
        val isMovie = labels.any { it.name.equals("Movie", true) } || rawTitle.contains("movie", true)
        val seriesTitle = seriesLabel?.name ?: rawTitle.cleanEpisodeTitle()
        val tags = labels.map { it.name }.filterNot { it.equals(seriesTitle, true) }

        val episodes = if (!isMovie && seriesLabel != null) {
            collectEpisodesFromSeries(seriesLabel.url, url).ifEmpty {
                listOf(document.toEpisode(url, rawTitle, poster))
            }
        } else {
            emptyList()
        }

        val recommendations = document.select("a[href]")
            .mapNotNull { it.toSearchResponseFromAnchor(url) }
            .filterNot { it.url == url }
            .distinctBy { it.url }
            .take(12)

        return if (!isMovie && episodes.size > 1) {
            newTvSeriesLoadResponse(
                seriesTitle,
                url,
                TvType.Anime,
                episodes.sortedWith(compareBy(nullsLast()) { it.episode })
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val playableUrl = findFirstWatchLink(document, url) ?: url
            newMovieLoadResponse(
                if (isMovie) rawTitle else seriesTitle,
                url,
                if (isMovie) TvType.AnimeMovie else TvType.Anime,
                playableUrl
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = defaultHeaders, referer = mainUrl)
        val document = response.document
        val resolved = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String, label: String) {
            val finalUrl = rawUrl.decodeServerText().absoluteUrl(data)
            if (!finalUrl.isPlayableCandidate() || !resolved.add(finalUrl)) return

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label.ifBlank { name },
                    url = finalUrl,
                    type = inferType(finalUrl)
                ) {
                    this.referer = data
                    this.quality = getQualityFromName(label.ifBlank { finalUrl })
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to data,
                        "Origin" to mainUrl
                    )
                }
            )
        }

        suspend fun resolveUrl(rawUrl: String?, label: String = name) {
            val finalUrl = rawUrl
                ?.decodeServerText()
                ?.absoluteUrl(data)
                ?.takeIf { it.isPlayableCandidate() }
                ?: return

            if (resolved.contains(finalUrl)) return

            if (finalUrl.isDirectMedia()) {
                emitDirect(finalUrl, label)
            } else {
                val ok = runCatching {
                    loadExtractor(finalUrl, data, subtitleCallback, callback)
                }.getOrDefault(false)

                if (ok) resolved.add(finalUrl)
            }
        }

        // Main source-backed dynamic server path: server option/value -> decode -> iframe/player URL -> loadExtractor().
        document.select(serverOptionSelector).forEach { option ->
            val label = option.text().cleanText().ifBlank {
                option.attr("title").ifBlank { option.attr("data-title") }.cleanText()
            }.ifBlank { name }

            option.serverValues().forEach { rawValue ->
                decodeCandidates(rawValue).forEach { decoded ->
                    decoded.extractPlayableUrls(data).forEach { resolveUrl(it, label) }
                }
            }
        }

        // Plain iframe/source fallback for pages that render the selected player directly.
        document.select("iframe[src], embed[src], video[src], video source[src], source[src]").forEach { element ->
            resolveUrl(element.attr("src"), element.attr("title").ifBlank { element.attr("label") }.ifBlank { name })
        }

        // Script fallback keeps dynamic behavior: collect embedded iframe/media URLs only, no hardcoded IDs.
        response.text.extractPlayableUrls(data).forEach { resolveUrl(it, name) }

        return resolved.isNotEmpty()
    }

    private suspend fun collectEpisodesFromSeries(seriesUrl: String, referer: String): List<com.lagradost.cloudstream3.Episode> {
        return runCatching {
            val document = app.get(seriesUrl, headers = defaultHeaders, referer = referer).document
            document.select("a[href]")
                .mapNotNull { anchor ->
                    val href = anchor.attr("href").absoluteUrl(seriesUrl)
                    if (!href.isPostUrl()) return@mapNotNull null

                    val context = anchor.meaningfulParent()
                    val title = anchor.extractTitle(context)
                    if (title.isBlank()) return@mapNotNull null

                    val poster = context.findPoster(seriesUrl)
                    newEpisode(href) {
                        this.name = title
                        this.episode = title.extractEpisodeNumber()
                        this.posterUrl = poster
                    }
                }
                .distinctBy { it.data }
        }.getOrDefault(emptyList())
    }

    private fun Document.toSearchResults(baseUrl: String): List<SearchResponse> {
        return select("a[href]")
            .mapNotNull { it.toSearchResponseFromAnchor(baseUrl) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResponseFromAnchor(baseUrl: String): SearchResponse? {
        val href = attr("href").absoluteUrl(baseUrl)
        if (!href.isPostUrl()) return null

        val context = meaningfulParent()
        val title = extractTitle(context)
        if (title.isBlank() || title.length < 3) return null

        val poster = context.findPoster(baseUrl)
        val type = if (title.contains("movie", true) || context.text().contains(" Movie ", true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    private fun Element.extractTitle(context: Element): String {
        return attr("title").cleanText()
            .ifBlank { text().cleanText() }
            .ifBlank { selectFirst("img[alt]")?.attr("alt")?.cleanText().orEmpty() }
            .ifBlank {
                context.selectFirst("h1, h2, h3, .post-title, .entry-title, .title, .tt")
                    ?.text()
                    ?.cleanText()
                    .orEmpty()
            }
            .removePrefix("Image:")
            .cleanText()
    }

    private fun Element.meaningfulParent(): Element {
        var current: Element = this
        repeat(7) {
            val parent = current.parent() ?: return current
            val tag = parent.tagName().lowercase(Locale.ROOT)
            val classes = parent.className().lowercase(Locale.ROOT)
            current = parent
            if (
                tag == "article" ||
                tag == "li" ||
                "post" in classes ||
                "item" in classes ||
                "hentry" in classes ||
                "blog" in classes ||
                "card" in classes ||
                "bs" == classes
            ) {
                return current
            }
        }
        return current
    }

    private fun Document.toEpisode(url: String, title: String, poster: String?): com.lagradost.cloudstream3.Episode {
        return newEpisode(url) {
            this.name = title
            this.episode = title.extractEpisodeNumber()
            this.posterUrl = poster ?: findPoster(url)
        }
    }

    private fun findFirstWatchLink(document: Document, pageUrl: String): String? {
        val selectors = listOf(
            "a[href*='/watch/']",
            "a[href*='episode'][href$='.html']",
            "a[href*='play'][href]",
            ".episode a[href]",
            ".eplister a[href]"
        )

        return selectors.asSequence()
            .mapNotNull { selector ->
                document.selectFirst(selector)?.attr("href")?.absoluteUrl(pageUrl)
            }
            .firstOrNull { it.isPostUrl() || it.contains("/watch/", true) }
    }

    private fun Element.serverValues(): List<String> {
        val attrs = listOf(
            "value",
            "data-value",
            "data-src",
            "data-url",
            "data-video",
            "data-embed",
            "data-iframe",
            "data-player",
            "href",
            "src"
        )

        return attrs.map { attr(it).trim() }
            .filter { it.isNotBlank() && it != "#" }
            .distinct()
    }

    private fun decodeCandidates(rawValue: String): List<String> {
        val decoded = linkedSetOf<String>()
        val value = rawValue.trim().decodeServerText()
        if (value.isBlank()) return emptyList()

        decoded.add(value)
        decoded.add(runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value).decodeServerText())

        Regex("""atob\((['\"])([^'\"]+)\1\)""", RegexOption.IGNORE_CASE)
            .findAll(value)
            .mapNotNull { it.groupValues.getOrNull(2) }
            .forEach { encoded -> decoded.addBase64Candidate(encoded) }

        decoded.addBase64Candidate(value)
        decoded.addBase64Candidate(value.substringAfter("base64,", value))

        return decoded.filter { it.isNotBlank() }
    }

    private fun MutableSet<String>.addBase64Candidate(rawValue: String) {
        val cleaned = rawValue.trim()
            .substringBefore("&")
            .replace("-", "+")
            .replace("_", "/")
            .replace("\\n", "")
            .replace("\\r", "")
            .replace(" ", "")

        if (!cleaned.looksLikeBase64()) return
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        runCatching { base64Decode(padded).decodeServerText() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { add(it) }
    }

    private fun String.extractPlayableUrls(baseUrl: String): List<String> {
        val values = linkedSetOf<String>()
        val decoded = decodeServerText()

        val parsed = Jsoup.parse(decoded, baseUrl)
        parsed.select("iframe[src], embed[src], video[src], video source[src], source[src], a[href]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            val finalUrl = raw.decodeServerText().absoluteUrl(baseUrl)
            if (finalUrl.isPlayableCandidate()) values.add(finalUrl)
        }

        val regexes = listOf(
            Regex("""(?i)<iframe[^>]+src=[\"']([^\"']+)[\"']"""),
            Regex("""(?i)(?:data-src|data-url|data-video|data-embed|data-iframe|src|file|source|url)\s*[:=]\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?i)[\"'](https?://[^\"'<>\s]+)[\"']"""),
            Regex("""(?i)(https?:\\/\\/[^\"'<>\s]+)""")
        )

        regexes.forEach { regex ->
            regex.findAll(decoded).forEach { match ->
                val raw = match.groupValues.getOrNull(1).orEmpty().decodeServerText()
                val finalUrl = raw.absoluteUrl(baseUrl)
                if (finalUrl.isPlayableCandidate()) values.add(finalUrl)
            }
        }

        return values.toList()
    }

    private fun Element.findPoster(baseUrl: String): String? {
        val boxes = generateSequence(this as Element?) { it.parent() }.take(5).toList()
        boxes.forEach { box ->
            box.extractImage(baseUrl)?.let { return it }
            box.select("img, source, div, span, a").forEach { child ->
                child.extractImage(baseUrl)?.let { return it }
            }
        }
        return null
    }

    private fun Element.extractImage(baseUrl: String): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-image", "data-img", "src", "poster")
        attrs.forEach { attr ->
            val value = attr(attr).trim()
            if (value.isImageCandidate()) return value.absoluteUrl(baseUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            attr(attr).split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isImageCandidate() }
                ?.let { return it.absoluteUrl(baseUrl) }
        }

        Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it.absoluteUrl(baseUrl) }

        return null
    }

    private fun DonghuaZoneLabel.isSeriesTitleLabel(): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        val generic = setOf(
            "episode", "series", "sub", "english sub", "indo sub", "indonesian sub", "donghua", "movie",
            "action", "adventure", "fantasy", "romance", "ongoing", "completed"
        )
        return lower !in generic && !lower.contains("sub")
    }

    private fun String.cleanEpisodeTitle(): String {
        return replace(Regex("""(?i)\s+episode\s*\d+.*$"""), "")
            .replace(Regex("""(?i)\s+eps?\.?\s*\d+.*$"""), "")
            .replace(Regex("""\s+\[[^]]+]"""), "")
            .cleanText()
    }

    private fun String.extractEpisodeNumber(): Int? {
        return Regex("""(?i)(?:episode|eps|ep|e)\s*\.?\s*(\d{1,4})""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String.isPostUrl(): Boolean {
        return Regex("""https?://(?:www\.)?donghuazone\.com/\d{4}/\d{2}/[^?#]+\.html""", RegexOption.IGNORE_CASE)
            .containsMatchIn(this)
    }

    private fun String.isDirectMedia(): Boolean {
        return contains(".m3u8", true) || contains(".mp4", true) || contains(".mpd", true)
    }

    private fun String.isPlayableCandidate(): Boolean {
        if (isBlank()) return false
        val lower = lowercase(Locale.ROOT)
        if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return false
        if (lower.contains("/feeds/posts/") || lower.contains("/search/label/")) return false
        if (lower.contains("whatsapp.com") || lower.contains("telegram") || lower.contains("twitter.com")) return false
        if (lower.contains("doubleclick") || lower.contains("googlesyndication") || lower.contains("google-analytics")) return false
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) return false
        if (lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".ico") || lower.endsWith(".svg")) return false
        return isDirectMedia() || playableHostHints.any { lower.contains(it) }
    }

    private fun String.isImageCandidate(): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (lower.isBlank() || lower.startsWith("data:")) return false
        if (lower.contains("blank") || lower.contains("placeholder") || lower.contains("spacer")) return false
        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains("/s1600/") ||
            lower.contains("blogger.googleusercontent.com")
    }

    private fun String.looksLikeBase64(): Boolean {
        val value = trim()
        if (value.length < 16) return false
        if (value.contains("<") || value.contains("http", true)) return false
        return value.matches(Regex("""^[A-Za-z0-9+/=_-]+$"""))
    }

    private fun String.absoluteUrl(baseUrl: String): String {
        val value = decodeServerText().trim()
        return when {
            value.isBlank() -> ""
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            value.startsWith("data:", true) -> value
            else -> {
                val base = baseUrl.substringBeforeLast("/", mainUrl)
                "$base/$value"
            }
        }
    }

    private fun String.decodeServerText(): String {
        return replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .trim()
    }

    private fun String.cleanText(): String {
        return replace(Regex("""\s+"""), " ").trim()
    }

    private fun String.urlEncoded(): String = runCatching {
        URLEncoder.encode(this, "UTF-8")
    }.getOrDefault(this)

    private fun inferType(url: String): ExtractorLinkType {
        return when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            url.contains(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    private data class DonghuaZoneLabel(val name: String, val url: String)

    private val serverOptionSelector = listOf(
        "option[value]",
        ".mobius option[value]",
        ".mirror option[value]",
        ".server option[value]",
        ".player option[value]",
        ".servers [data-src]",
        ".servers [data-url]",
        ".servers [data-video]",
        ".servers [data-embed]",
        ".servers [data-iframe]",
        ".mirror [data-src]",
        ".mirror [data-url]",
        ".mirror [data-video]",
        ".mirror [data-embed]",
        ".mirror [data-iframe]",
        ".player [data-src]",
        ".player [data-url]",
        ".player [data-video]",
        ".player [data-embed]",
        ".player [data-iframe]",
        "[data-src*='iframe']",
        "[data-url*='iframe']",
        "[data-video*='iframe']",
        "[data-embed]",
        "[data-iframe]"
    ).joinToString(",")

    private val playableHostHints = listOf(
        "dailymotion.com",
        "dai.ly",
        "geo.dailymotion.com",
        "ok.ru",
        "odnoklassniki.ru",
        "rumble.com",
        "dood.",
        "doodstream",
        "filemoon.",
        "mega.nz",
        "streamtape.",
        "vidhide",
        "abyssplayer",
        "short.icu",
        "short.ink",
        "turbovid",
        "filelions",
        "vidmoly",
        "mp4upload",
        "uqload",
        "streamwish",
        "wishfast",
        "waaw",
        "voe.sx"
    )

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )
}
