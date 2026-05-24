package com.iqiyi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class IqiyiProvider : MainAPI() {
    override var mainUrl = "https://www.iq.com"
    override var name = "iQIYI"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Cartoon,
        TvType.Others
    )

    private val locale = "id_id"

    override val mainPage = mainPageOf(
        "$mainUrl/?lang=$locale" to "Rekomendasi",
        "$mainUrl/free?lang=$locale" to "Gratis",
        "$mainUrl/trending?lang=$locale" to "Trending",
        "$mainUrl/drama?lang=$locale" to "Drama",
        "$mainUrl/kdrama?lang=$locale" to "K-Drama",
        "$mainUrl/movie?lang=$locale" to "Movie",
        "$mainUrl/variety-show?lang=$locale" to "Variety Show",
        "$mainUrl/anime?lang=$locale" to "Anime",
        "$mainUrl/kids?lang=$locale" to "Kids",
        "$mainUrl/documentary?lang=$locale" to "Documentary",

        // Clean website-backed broad filters, not a noisy full genre dump.
        "search:Chinese Mainland" to "Chinese Mainland",
        "search:Korean Drama" to "Korea",
        "search:Thai Drama" to "Thailand",
        "search:Romance" to "Romance",
        "search:Action" to "Action",
        "search:Fantasy Wuxia" to "Fantasy & Wuxia",
        "search:Mystery Thriller" to "Mystery & Thriller",
        "search:Comedy Variety" to "Comedy & Variety"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(request.name, emptyList()),
                hasNext = false
            )
        }

        val items = if (request.data.startsWith("search:")) {
            search(request.data.removePrefix("search:").trim())
                .take(36)
        } else {
            val document = app.get(
                request.data.withLocale(),
                headers = defaultHeaders,
                referer = "$mainUrl/?lang=$locale",
                timeout = 25L
            ).document

            parseCards(document)
                .distinctBy { it.url }
                .take(48)
        }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = false
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = keyword.urlEncoded()
        val candidates = listOf(
            "$mainUrl/search?query=$encoded&lang=$locale",
            "$mainUrl/search?keyword=$encoded&lang=$locale",
            "$mainUrl/search?q=$encoded&lang=$locale",
            "$mainUrl/intl-common/search.html?key=$encoded&lang=$locale"
        )

        for (url in candidates) {
            val results = runCatching {
                app.get(
                    url,
                    headers = defaultHeaders,
                    referer = "$mainUrl/?lang=$locale",
                    timeout = 25L
                ).document.let { parseCards(it) }
                    .distinctBy { it.url }
                    .filterNot { it.name.isUiText() }
                    .take(50)
            }.getOrDefault(emptyList())

            if (results.isNotEmpty()) return results
        }

        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = url.withLocale()
        val response = app.get(
            fixedUrl,
            headers = defaultHeaders,
            referer = "$mainUrl/?lang=$locale",
            timeout = 25L
        )
        val document = response.document

        val title = listOf(
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=title]")?.attr("content"),
            document.selectFirst("h1")?.text(),
            fixedUrl.substringBefore("?").substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !it.isUiText() }
            ?.cleanTitle()
            ?: throw ErrorLoadingException("Judul iQIYI tidak ditemukan")

        val poster = document.selectFirst("meta[property=og:image], meta[name=twitter:image]")
            ?.attr("content")
            ?.normalizeImageUrl(fixedUrl)
            ?: findPoster(document.body(), fixedUrl)

        val description = listOf(
            document.selectFirst("meta[property=og:description]")?.attr("content"),
            document.selectFirst("meta[name=description]")?.attr("content"),
            document.selectFirst("[class*=description], [class*=desc], .album-intro, .intro")?.text()
        ).firstOrNull { !it.isNullOrBlank() && it.trim().length > 20 }
            ?.trim()

        val tags = document.select(
            "a[href*='channel'], a[href*='genre'], a[href*='tag'], " +
                "a[href*='film-library'], a[href*='drama?'], a[href*='movie?'], span"
        ).map { it.text().trim().cleanTitle() }
            .filter { it.length in 2..35 }
            .filterNot { it.isUiText() }
            .distinct()
            .take(14)

        val rating = Regex("""\b(10(?:\.0)?|[0-9](?:\.[0-9])?)\b""")
            .find(document.text())
            ?.groupValues
            ?.getOrNull(1)

        val trailer = document.selectFirst("a[href*='youtube.com'], a[href*='youtu.be']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val episodes = parseEpisodes(document, fixedUrl)
            .distinctBy { it.data }
            .filterNot { it.name?.contains("Trailer", ignoreCase = true) == true }
            .take(500)

        val recommendations = parseCards(document)
            .distinctBy { it.url }
            .filterNot { it.url.substringBefore("?") == fixedUrl.substringBefore("?") }
            .take(24)

        val tvType = inferType(fixedUrl, title, tags, episodes.size)

        return if (episodes.size > 1 && tvType != TvType.Movie) {
            newTvSeriesLoadResponse(title, fixedUrl, tvType, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.score = Score.from10(rating)
                addTrailer(trailer)
            }
        } else {
            val movieData = episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, fixedUrl, tvType, movieData) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                this.score = Score.from10(rating)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.withLocale()

        val response = runCatching {
            app.get(
                pageUrl,
                headers = defaultHeaders,
                referer = "$mainUrl/?lang=$locale",
                timeout = 25L
            )
        }.getOrNull() ?: return false

        val directLinks = linkedSetOf<String>()
        val dashLinks = linkedSetOf<String>()
        val subtitles = linkedSetOf<Pair<String, String>>()

        collectPlayableFromText(response.text, pageUrl, directLinks, dashLinks, subtitles)
        collectPlayableFromDocument(response.document, pageUrl, directLinks, dashLinks, subtitles)

        val unpacked = runCatching {
            if (!getPacked(response.text).isNullOrEmpty()) getAndUnpack(response.text) else null
        }.getOrNull()
        if (!unpacked.isNullOrBlank()) {
            collectPlayableFromText(unpacked, pageUrl, directLinks, dashLinks, subtitles)
        }

        // Some iQIYI free/trailer pages expose a DASH JSON request. If the URL is already present
        // in the page, we use it as-is. No DRM/login/VIP bypass is attempted.
        dashLinks.take(4).forEach { dashUrl ->
            val dashText = runCatching {
                app.get(
                    dashUrl,
                    headers = defaultHeaders + mapOf("Accept" to "application/json,text/plain,*/*"),
                    referer = pageUrl,
                    timeout = 20L
                ).text
            }.getOrNull().orEmpty()

            collectPlayableFromText(dashText, dashUrl, directLinks, dashLinks, subtitles)
        }

        subtitles.distinct().forEach { (label, url) ->
            subtitleCallback(newSubtitleFile(label.ifBlank { "Subtitle" }, url))
        }

        var found = false
        directLinks
            .map { normalizeUrl(it, pageUrl).cleanEscaped().replace(".txt", ".m3u8") }
            .filter { it.startsWith("http", true) }
            .filterNot { isBlockedMedia(it) }
            .distinct()
            .sortedWith(compareBy<String> { if (it.isHlsLike()) 0 else 1 }.thenBy { qualityRank(it) })
            .forEach { link ->
                emitDirectLink(
                    link = link,
                    referer = pageUrl,
                    callback = callback
                )
                found = true
            }

        return found
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "a[href*='/album/'], " +
                "a[href*='/play/'], " +
                "a[href*='/short/']"
        ).forEach { element ->
            element.toSearchResultOrNull()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResultOrNull(): SearchResponse? {
        val href = attr("href")
            .takeIf { it.isNotBlank() }
            ?.toAbsoluteIqUrl()
            ?.withLocale()
            ?: return null

        if (!href.contains("/album/") && !href.contains("/play/") && !href.contains("/short/")) return null

        val image = selectFirst("img")
        val title = listOf(
            attr("title"),
            attr("aria-label"),
            image?.attr("alt"),
            text(),
            href.substringBefore("?").substringAfterLast("/").replace("-", " ")
        ).firstOrNull { !it.isNullOrBlank() && !it.isUiText() }
            ?.cleanTitle()
            ?: return null

        if (title.length < 2 || title.isUiText()) return null

        val poster = findPoster(this, href)
        val type = inferType(href, title, emptyList(), 0)

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, href, type) {
                posterUrl = poster
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select("a[href*='/play/'], a[href*='/short/']").forEachIndexed { index, element ->
            val href = element.attr("href")
                .takeIf { it.isNotBlank() }
                ?.toAbsoluteIqUrl()
                ?.withLocale()
                ?: return@forEachIndexed

            if (href.substringBefore("?") == baseUrl.substringBefore("?")) return@forEachIndexed

            val image = element.selectFirst("img")
            val rawTitle = listOf(
                element.attr("title"),
                element.attr("aria-label"),
                image?.attr("alt"),
                element.text(),
                "Episode ${index + 1}"
            ).firstOrNull { !it.isNullOrBlank() && !it.isUiText() }
                ?.cleanTitle()
                ?: return@forEachIndexed

            val poster = findPoster(element, href)
            val epNumber = extractEpisodeNumber(rawTitle, href) ?: index + 1

            episodes[href] = newEpisode(href) {
                name = rawTitle
                episode = epNumber
                posterUrl = poster
            }
        }

        if (episodes.isEmpty() && baseUrl.contains("/play/", true)) {
            episodes[baseUrl] = newEpisode(baseUrl) {
                name = "Episode 1"
                episode = 1
            }
        }

        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    private fun inferType(url: String, title: String, tags: List<String>, episodeCount: Int): TvType {
        val lower = (url + " " + title + " " + tags.joinToString(" ")).lowercase()

        return when {
            lower.contains("anime") -> TvType.Anime
            lower.contains("kids") || lower.contains("anak") || lower.contains("cartoon") -> TvType.Cartoon
            lower.contains("movie") || lower.contains("film") || (url.contains("/play/") && episodeCount <= 1) -> TvType.Movie
            lower.contains("drama") || lower.contains("korea") || lower.contains("china") || lower.contains("thai") -> TvType.AsianDrama
            lower.contains("variety") || lower.contains("documentary") -> TvType.Others
            episodeCount > 1 -> TvType.TvSeries
            else -> TvType.AsianDrama
        }
    }

    private fun findPoster(element: Element, pageUrl: String): String? {
        val boxes = listOfNotNull(
            element,
            element.parent(),
            element.parent()?.parent(),
            element.parent()?.parent()?.parent()
        ).distinct()

        for (box in boxes) {
            extractImage(box, pageUrl)?.let { return it }
            box.select("img, source, div, span").forEach { child ->
                extractImage(child, pageUrl)?.let { return it }
            }
        }

        return null
    }

    private fun extractImage(element: Element, pageUrl: String): String? {
        val attrs = listOf(
            "src",
            "data-src",
            "data-original",
            "data-lazy-src",
            "data-image",
            "data-img",
            "poster"
        )

        attrs.forEach { attr ->
            val value = element.attr(attr).trim()
            if (value.isImageCandidate()) return value.normalizeImageUrl(pageUrl)
        }

        listOf("srcset", "data-srcset").forEach { attr ->
            val src = element.attr(attr)
                .split(",")
                .map { it.trim().substringBefore(" ").trim() }
                .firstOrNull { it.isImageCandidate() }
            if (!src.isNullOrBlank()) return src.normalizeImageUrl(pageUrl)
        }

        Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(element.attr("style"))
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf { it.isImageCandidate() }
            ?.let { return it.normalizeImageUrl(pageUrl) }

        return null
    }

    private fun collectPlayableFromDocument(
        document: Document,
        baseUrl: String,
        directLinks: MutableSet<String>,
        dashLinks: MutableSet<String>,
        subtitles: MutableSet<Pair<String, String>>
    ) {
        document.select(
            "meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], " +
                "meta[name=twitter:player], video[src], video source[src], source[src], " +
                "iframe[src], iframe[data-src], [data-src], [data-video], [data-url], [data-file]"
        ).forEach { element ->
            val raw = element.attr("content")
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-file") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("src") }
                .trim()

            addPlayableCandidate(raw, baseUrl, directLinks, dashLinks)
        }

        document.select("track[src], [kind=subtitles][src]").forEach { element ->
            val raw = element.attr("src").trim()
            val label = element.attr("label").ifBlank { element.attr("srclang") }.ifBlank { "Subtitle" }
            if (raw.isNotBlank()) subtitles.add(label to normalizeUrl(raw, baseUrl))
        }
    }

    private fun collectPlayableFromText(
        text: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        dashLinks: MutableSet<String>,
        subtitles: MutableSet<Pair<String, String>>
    ) {
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|cache-video\.iq\.com/dash)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            addPlayableCandidate(match.value, baseUrl, directLinks, dashLinks)
        }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|cache-video\.iq\.com%2Fdash)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val decoded = runCatching { URLDecoder.decode(match.value, "UTF-8") }.getOrDefault(match.value)
            addPlayableCandidate(decoded, baseUrl, directLinks, dashLinks)
        }

        Regex(
            """(?:file|src|url|playUrl|videoUrl|m3u8|mp4Url|manifest|dash)":\s*"((?:\\.|[^"\\])+)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty().cleanEscaped()
            addPlayableCandidate(raw, baseUrl, directLinks, dashLinks)
        }

        Regex(
            """(?:file|src|url|playUrl|videoUrl|m3u8|mp4Url|manifest|dash)\s*[:=]\s*['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val raw = match.groupValues.getOrNull(1).orEmpty().cleanEscaped()
            addPlayableCandidate(raw, baseUrl, directLinks, dashLinks)
        }

        Regex(
            """"(?:lang|label|name)"\s*:\s*"([^"]+)"[^}]*?"(?:url|file|src|path)"\s*:\s*"((?:\\.|[^"])*)""",
            RegexOption.IGNORE_CASE
        ).findAll(clean).forEach { match ->
            val label = match.groupValues.getOrNull(1).orEmpty().cleanEscaped()
            val raw = match.groupValues.getOrNull(2).orEmpty().cleanEscaped()
            if (raw.endsWith(".vtt", true) || raw.endsWith(".srt", true) || raw.contains("subtitle", true)) {
                subtitles.add(label to normalizeUrl(raw, baseUrl))
            }
        }
    }

    private fun addPlayableCandidate(
        raw: String,
        baseUrl: String,
        directLinks: MutableSet<String>,
        dashLinks: MutableSet<String>
    ) {
        val fixed = normalizeUrl(raw.cleanEscaped(), baseUrl)
            .replace(".txt", ".m3u8")
            .trim()

        if (fixed.isBlank() || isBlockedMedia(fixed)) return

        when {
            fixed.contains("cache-video.iq.com/dash", true) -> dashLinks.add(fixed)
            fixed.isHlsLike() || fixed.contains(".mp4", true) || fixed.contains(".webm", true) -> directLinks.add(fixed)
        }
    }

    private suspend fun emitDirectLink(
        link: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (link.isHlsLike()) {
            generateM3u8(
                source = name,
                streamUrl = link,
                referer = referer,
                headers = streamHeaders(referer)
            ).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = link,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(link).takeIf {
                    it != Qualities.Unknown.value
                } ?: qualityFromUrl(link)
                this.headers = streamHeaders(referer)
            }
        )
    }

    private fun streamHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )
    }

    private fun String.isImageCandidate(): Boolean {
        if (isBlank()) return false
        if (startsWith("data:", true)) return false
        if (contains("logo", true) || contains("avatar", true) || contains("icon", true)) return false
        return contains(".jpg", true) ||
            contains(".jpeg", true) ||
            contains(".png", true) ||
            contains(".webp", true) ||
            contains("pic", true) ||
            contains("image", true)
    }

    private fun String.normalizeImageUrl(baseUrl: String = mainUrl): String {
        val value = cleanEscaped()
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault("$mainUrl/$value")
        }
    }

    private fun String.toAbsoluteIqUrl(): String {
        val value = trim().cleanEscaped()
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun String.withLocale(): String {
        return when {
            startsWith("search:") -> this
            contains("lang=", true) -> this
            contains("?") -> "$this&lang=$locale"
            else -> "$this?lang=$locale"
        }
    }

    private fun String.cleanEscaped(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return cleanEscaped()
            .replace(Regex("""\s+[-–]\s+iQIYI\s*\|\s*iQ\.com.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .replace("Tonton online", "", ignoreCase = true)
            .replace("Watch online", "", ignoreCase = true)
            .replace("Sub Indo", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .trim(' ', '-', '|')
    }

    private fun String.isUiText(): Boolean {
        if (isBlank()) return true
        val ui = listOf(
            "favorit", "login", "signup", "bahasa", "history", "histori",
            "download app", "join vip", "vip privileges", "watch later",
            "lebih banyak", "more", "rekomendasi", "popular searches",
            "pengaturan", "apple", "google", "facebook", "twitter", "line",
            "copy link", "share", "scan", "my account", "subtitle translation",
            "cookie", "privacy", "terms of service"
        )
        val lower = lowercase().trim()
        return ui.any { lower == it || lower.contains(it) } || length > 180
    }

    private fun String.urlEncoded(): String = runCatching {
        URLEncoder.encode(this, "UTF-8")
    }.getOrDefault(this)

    private fun normalizeUrl(url: String, baseUrl: String): String {
        val clean = url.cleanEscaped().trim()
        return when {
            clean.isBlank() -> ""
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> {
                val origin = Regex("""^https?://[^/]+""").find(baseUrl)?.value ?: mainUrl
                "$origin$clean"
            }
            else -> runCatching { URI(baseUrl).resolve(clean).toString() }.getOrDefault(clean)
        }
    }

    private fun extractEpisodeNumber(text: String, href: String): Int? {
        return Regex("""(?:episode|eps?|ep|e)\s*\.?\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
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

    private fun String.isHlsLike(): Boolean {
        return contains(".m3u8", true) || contains("application/vnd.apple.mpegurl", true)
    }

    private fun isBlockedMedia(url: String): Boolean {
        val value = url.lowercase()
        return value.contains("doubleclick") ||
            value.contains("googlesyndication") ||
            value.contains("google-analytics") ||
            value.contains("cloudflareinsights") ||
            value.contains("facebook.com") ||
            value.contains("twitter.com") ||
            value.contains("youtube.com") ||
            value.contains("youtu.be") ||
            value.contains(".css") ||
            value.contains(".js") ||
            value.contains("favicon") ||
            value.contains("/ads/") ||
            value.contains("vast") ||
            value.contains("preroll")
    }

    private fun qualityRank(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> 0
            url.contains("1080", true) -> 1
            url.contains("720", true) -> 2
            url.contains("480", true) -> 3
            url.contains("360", true) -> 4
            else -> 9
        }
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

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/?lang=$locale"
    )
}
