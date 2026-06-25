package com.movieku

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class MovieKuProvider : MainAPI() {
    override var mainUrl = "https://movieku.rest"
    override var name = "MovieKu"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Terbaru",
        "$mainUrl/genre/action/page/%d/" to "Action",
        "$mainUrl/genre/adventure/page/%d/" to "Adventure",
        "$mainUrl/genre/animation/page/%d/" to "Animation",
        "$mainUrl/genre/comedy/page/%d/" to "Comedy",
        "$mainUrl/genre/crime/page/%d/" to "Crime",
        "$mainUrl/genre/drama/page/%d/" to "Drama",
        "$mainUrl/genre/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genre/horror/page/%d/" to "Horror",
        "$mainUrl/genre/romance/page/%d/" to "Romance",
        "$mainUrl/genre/thriller/page/%d/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = app.get(url, headers = siteHeaders, referer = mainUrl).document
        val items = parseListing(document)
        return newHomePageResponse(listOf(HomePageList(request.name, items, isHorizontalImages = true)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/1/?s=$encoded",
            "$mainUrl/search/$encoded/"
        )
        val out = linkedMapOf<String, SearchResponse>()
        for (url in urls) {
            val parsed = runCatching {
                parseListing(app.get(url, headers = siteHeaders, referer = mainUrl).document)
            }.getOrDefault(emptyList())
            parsed.forEach { out[it.url] = it }
            if (out.isNotEmpty()) break
        }
        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = siteHeaders, referer = mainUrl).document
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1.post-title, h1, .entry-title, .post-title, .title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).ifBlank { return null }

        val poster = posterFrom(document, document.selectFirst(".poster img, .thumb img, .post-thumbnail img, .wp-post-image, img[src*=/uploads/], img[data-src*=/uploads/]"))
            ?: absoluteUrl(document.selectFirst("meta[property=og:image]")?.attr("content"))
                ?.takeIf { isValidPoster(it) }

        val plot = cleanText(
            document.selectFirst(".sinopsis, .synopsis, .description, .desc, .entry-content p, .post-content p")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )

        val tags = document.select("a[href*=/genre/], a[href*=/country/], a[href*=/year/], a[href*=/quality/]")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..32 }
            .distinct()
            .take(16)

        val episodes = parseEpisodes(document, url)
        val recommendations = parseListing(document).filterNot { it.url == url }.take(12)
        val type = if (episodes.isNotEmpty() || looksSeries(url, title)) TvType.TvSeries else TvType.Movie

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.plot = plot
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
        val emitted = linkedSetOf<String>()
        return resolvePage(data, data, 0, emitted, subtitleCallback, callback)
    }

    private suspend fun resolvePage(
        pageUrl: String,
        referer: String,
        depth: Int,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (depth > 3) return false
        val document = runCatching { app.get(pageUrl, headers = siteHeaders, referer = referer).document }.getOrNull() ?: return false
        collectSubtitles(pageUrl, document, subtitleCallback)

        var found = false
        val direct = extractDirectMedia(pageUrl, document)
        for (url in direct.take(30)) {
            found = emitDirect(url, pageUrl, emitted, callback) || found
        }

        val embeds = extractEmbeds(pageUrl, document).filterNot { it in direct }
        for (embed in embeds.take(24)) {
            found = runExtractor(embed, pageUrl, emitted, subtitleCallback, callback) || found
            if (!found && canRecurse(embed, pageUrl)) {
                found = resolvePage(embed, pageUrl, depth + 1, emitted, subtitleCallback, callback) || found
            }
        }

        if (!found) found = runExtractor(pageUrl, referer, emitted, subtitleCallback, callback)
        return found
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val out = linkedMapOf<String, SearchResponse>()
        val selectors = listOf(
            "article", ".ml-item", ".movie", ".movie-item", ".result-item", ".film", ".post", ".item:has(img)",
            ".owl-item:has(img)", ".swiper-slide:has(img)", "a[href]:has(img)"
        ).joinToString(",")
        document.select(selectors).mapNotNull { parseCard(it) }.forEach { out[it.url] = it }
        if (out.isEmpty()) {
            document.select("a[href]:has(img)").mapNotNull { parseCard(it) }.forEach { out[it.url] = it }
        }
        return out.values.take(72)
    }

    private fun parseCard(element: Element): SearchResponse? {
        val link = if (element.tagName().equals("a", true) && element.hasAttr("href")) element else
            element.selectFirst("a[href]:has(img), h1 a[href], h2 a[href], h3 a[href], .title a[href], .entry-title a[href], a[title][href], a[href]") ?: return null
        val href = absoluteUrl(link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null
        val image = link.selectFirst(imageSelector) ?: element.selectFirst(imageSelector)
        val title = cleanTitle(
            element.selectFirst("h1, h2, h3, .title, .entry-title, .post-title")?.text()
                ?: link.attr("title").ifBlank { link.attr("aria-label") }.ifBlank { link.text() }.ifBlank { image?.attr("alt").orEmpty() }
                .ifBlank { href.substringAfterLast('/').replace('-', ' ') }
        ).ifBlank { return null }
        if (title.length < 2 || badTitle(title)) return null
        val poster = posterFrom(element, image)
        return if (looksSeries(href, title)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    private fun parseEpisodes(document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val selectors = ".eps a[href], .episode a[href], .episodes a[href], .episodelist a[href], .season a[href], a[href*=episode], a[href*=season]"
        return document.select(selectors).mapNotNull { anchor ->
            val href = absoluteUrl(anchor.attr("href")) ?: return@mapNotNull null
            if (!isVideoUrl(href) || href.substringBefore("#") == fallbackUrl.substringBefore("#")) return@mapNotNull null
            if (!seen.add(href.substringBefore("#"))) return@mapNotNull null
            newEpisode(href) {
                name = cleanTitle(anchor.text()).ifBlank { cleanTitle(anchor.attr("title")).ifBlank { "Episode" } }
                episode = Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""").find(name.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
    }

    private fun extractDirectMedia(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("source[src], video[src], a[href], iframe[src], [data-src], [data-url], [data-link], [data-file], [data-video], [data-stream]").forEach { el ->
            listOf("src", "href", "data-src", "data-url", "data-link", "data-file", "data-video", "data-stream").forEach { attr ->
                normalizeUrl(pageUrl, el.attr(attr))?.takeIf { looksMedia(it) }?.let { out.add(it) }
            }
        }
        val html = normalized(document.html())
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksMedia(it) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksMedia(it) }.forEach { out.add(it) }
        encodedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.value) }.filter { looksMedia(it) }.forEach { out.add(it) }
        base64UrlRegex.findAll(html).mapNotNull { decodeBase64(it.groupValues[1]) }.mapNotNull { normalizeUrl(pageUrl, it) }.filter { looksMedia(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private fun extractEmbeds(pageUrl: String, document: Document): List<String> {
        val out = linkedSetOf<String>()
        document.select("iframe[src], embed[src], a[href], [data-src], [data-url], [data-link], [data-embed], [data-iframe]").forEach { el ->
            listOf("src", "href", "data-src", "data-url", "data-link", "data-embed", "data-iframe").forEach { attr ->
                normalizeUrl(pageUrl, el.attr(attr))?.takeIf { looksEmbed(it) }?.let { out.add(it) }
            }
        }
        val html = normalized(document.html())
        keyValueRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksEmbed(it) }.forEach { out.add(it) }
        quotedUrlRegex.findAll(html).mapNotNull { normalizeUrl(pageUrl, it.groupValues[1]) }.filter { looksEmbed(it) }.forEach { out.add(it) }
        return out.distinct()
    }

    private suspend fun emitDirect(url: String, referer: String, emitted: MutableSet<String>, callback: (ExtractorLink) -> Unit): Boolean {
        return when {
            url.contains(".m3u8", true) -> {
                val links = runCatching { generateM3u8(name, url, referer, headers = videoHeaders(referer)) }.getOrDefault(emptyList())
                links.forEach { if (emitted.add(it.url)) callback(it) }
                links.isNotEmpty()
            }
            url.contains(".mp4", true) -> {
                if (emitted.add(url)) {
                    callback(newExtractorLink(name, "$name MP4", url) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = videoHeaders(referer)
                    })
                    true
                } else false
            }
            else -> false
        }
    }

    private suspend fun runExtractor(
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        runCatching {
            loadExtractor(url, referer, subtitleCallback) { link ->
                if (emitted.add(link.url)) {
                    found = true
                    callback(link)
                }
            }
        }
        return found
    }

    private fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { el ->
            val url = absoluteUrl(el.attr("src").ifBlank { el.attr("href") }, pageUrl) ?: return@forEach
            val label = cleanText(el.attr("label").ifBlank { el.text().ifBlank { "Subtitle" } })
            runCatching { subtitleCallback(newSubtitleFile(label, url)) }
        }
    }

    private fun pageUrl(data: String, page: Int): String {
        val raw = if (data.startsWith("http")) data else mainUrl.trimEnd('/') + "/" + data.trimStart('/')
        if (raw.contains("%d")) {
            if (page <= 1) return raw.replace("/page/%d/", "/").replace("page/%d/", "").replace("%d", "").trimEnd('/') + "/"
            return raw.replace("%d", page.toString())
        }
        return if (page <= 1) raw else raw.trimEnd('/') + "/page/$page/"
    }

    private fun posterFrom(container: Element, image: Element?): String? {
        val candidates = mutableListOf<String?>()
        image?.let {
            candidates += it.attr("data-src")
            candidates += it.attr("data-lazy-src")
            candidates += it.attr("data-original")
            candidates += it.attr("src")
            candidates += it.attr("srcset").split(',').firstOrNull()?.substringBefore(' ')
        }
        candidates += container.selectFirst("noscript img[src]")?.attr("src")
        val style = container.attr("style").ifBlank { container.selectFirst("[style*=background]")?.attr("style").orEmpty() }
        candidates += Regex("""url\((['\"]?)(.*?)\1\)""").find(style)?.groupValues?.getOrNull(2)
        return candidates.asSequence().mapNotNull { absoluteUrl(it) }.firstOrNull { isValidPoster(it) }
    }

    private fun absoluteUrl(value: String?, base: String = mainUrl): String? {
        val raw = value.orEmpty().trim().removePrefix("url(").removeSuffix(")").trim('"', '\'', ' ', ',', ';')
        if (raw.isBlank()) return null
        val low = raw.lowercase()
        if (low == "#" || low == "about:blank" || low == "null" || low == "undefined") return null
        if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("blob:") || low.startsWith("intent:")) return null
        if (raw.startsWith("//")) return "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val origin = originOf(base) ?: mainUrl
        if (raw.startsWith("/")) return origin.trimEnd('/') + raw
        return origin.trimEnd('/') + "/" + raw.trimStart('/')
    }

    private fun normalizeUrl(base: String, value: String?): String? {
        val decoded = decodeMaybe(value.orEmpty())
        return absoluteUrl(decoded, base)
    }

    private fun cleanText(value: String?): String = value.orEmpty()
        .replace("\u00a0", " ")
        .replace("&amp;", "&")
        .replace("&#8211;", "-")
        .replace("&#8217;", "'")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanTitle(value: String?): String = cleanText(value)
        .replace(Regex("(?i)^permalink\\s+to:\\s*"), "")
        .replace(Regex("(?i)^nonton\\s+"), "")
        .replace(Regex("(?i)\\s+-\\s+movieku$"), "")
        .replace(Regex("(?i)\\s+subtitle\\s+indonesia$"), " Sub")
        .trim()

    private fun badTitle(title: String): Boolean {
        val low = title.lowercase()
        return low in setOf("home", "movie", "movies", "trailer", "download", "watch", "watch movie") || low.startsWith("download via")
    }

    private fun isVideoUrl(url: String): Boolean {
        if (!isSameHost(url)) return false
        val path = runCatching { URI(url).path.orEmpty().trim('/') }.getOrDefault("")
        if (path.isBlank()) return false
        val parts = path.split('/').filter { it.isNotBlank() }
        if (parts.size != 1) return false
        val slug = parts.first().lowercase()
        if (slug in catalogSegments) return false
        if (slug.contains("wp-") || slug.endsWith(".jpg") || slug.endsWith(".png") || slug.endsWith(".webp")) return false
        return true
    }

    private fun isSameHost(url: String): Boolean {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "movieku.rest" || host.endsWith(".movieku.rest")
    }

    private fun isValidPoster(url: String): Boolean {
        val low = url.lowercase()
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault(low)
        val fileName = path.substringAfterLast('/')
        return low.startsWith("http") && !low.startsWith("data:") && !low.contains("/logo") &&
            !low.contains("favicon") && !low.contains("placeholder") && !low.contains("no-image") &&
            !low.endsWith(".svg") && !path.contains("/wp-content/themes/") && !path.contains("/wp-content/plugins/") &&
            !fileName.contains("logo") && !fileName.contains("favicon")
    }

    private fun looksSeries(url: String, title: String): Boolean {
        val low = "$url $title".lowercase()
        return low.contains("series") || low.contains("season") || low.contains("episode") || low.contains("drama korea")
    }

    private fun looksMedia(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains(".mp4") || low.contains("/hls/") || low.contains("playlist") || low.contains("master.m3u8")
    }

    private fun looksEmbed(url: String): Boolean {
        val low = url.lowercase()
        if (looksMedia(url)) return true
        if (isSameHost(url)) return !low.contains("/wp-content/") && !low.contains("/wp-admin/")
        return low.contains("embed") || low.contains("stream") || low.contains("player") || low.contains("drive") ||
            low.contains("dood") || low.contains("filemoon") || low.contains("streamtape") || low.contains("vid") ||
            low.contains("short") || low.contains("uqload") || low.contains("mixdrop") || low.contains("voe")
    }

    private fun canRecurse(url: String, referer: String): Boolean {
        val origin = originOf(url) ?: return false
        val refOrigin = originOf(referer)
        return origin == refOrigin || origin.contains("movieku") || looksEmbed(url)
    }

    private fun videoHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Referer" to referer,
        "Origin" to (originOf(referer) ?: mainUrl)
    )

    private fun originOf(url: String?): String? = runCatching {
        val uri = URI(url.orEmpty())
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()

    private fun decodeMaybe(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003a", ":")
        .replace("\\u002f", "/")

    private fun decodeBase64(value: String): String? = runCatching {
        val bytes = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        String(bytes)
    }.getOrNull()

    private fun normalized(value: String): String = value.replace("\\/", "/").replace("&quot;", "\"").replace("&amp;", "&")

    private val siteHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    companion object {
        private const val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36"
        private const val imageSelector = "img[src], img[data-src], img[data-lazy-src], img[data-original], img[srcset]"
        private val catalogSegments = setOf(
            "", "page", "dmca", "faq", "contact", "kontak", "privacy-policy", "disclaimer", "genre", "year", "country", "quality", "tag",
            "cast", "director", "author", "movie", "movies", "series", "tv-series", "drama", "action", "adventure", "animation", "comedy",
            "crime", "fantasy", "horror", "romance", "thriller", "wp-admin", "wp-content", "wp-includes"
        )
        private val keyValueRegex = Regex("""(?i)(?:file|src|url|source|hls|video|stream|playlist|embed|iframe|link)\s*[:=]\s*['\"]([^'\"]+)['\"]""")
        private val quotedUrlRegex = Regex("""(?i)['\"]((?:https?:)?//[^'\"<>\s]+|/[^'\"<>\s]+)['\"]""")
        private val encodedUrlRegex = Regex("""https?%3A%2F%2F[^'\"<>\s]+""", RegexOption.IGNORE_CASE)
        private val base64UrlRegex = Regex("""['\"]([A-Za-z0-9+/=]{28,})['\"]""")
    }
}
