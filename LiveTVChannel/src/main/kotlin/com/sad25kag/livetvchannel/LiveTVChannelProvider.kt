package com.sad25kag.livetvchannel

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
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LiveTVChannelProvider : MainAPI() {
    override var mainUrl = "https://live-tv-channels.org"
    override var name = "LiveTVChannel"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "indonesia" to "Indonesia",
        "malaysia" to "Malaysia",
        "singapore" to "Singapore",
        "philippines" to "Philippines",
        "thailand" to "Thailand",
        "vietnam" to "Vietnam",
        "japan" to "Japan",
        "south-korea" to "South Korea",
        "india" to "India",
        "united-states" to "United States"
    )

    private val pageCache = ConcurrentHashMap<String, List<LiveTvChannel>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = countryBySlug(request.data) ?: throw ErrorLoadingException("Negara tidak didukung: ${request.data}")
        val safePage = page.coerceAtLeast(1)
        val channels = runCatching { channelsFor(country, safePage) }
            .onFailure { logError(it) }
            .getOrDefault(emptyList())

        val document = runCatching { countryDocument(country, safePage) }.getOrNull()
        val hasNext = document?.hasNextCountryPage(country, safePage) == true

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = channels.map { it.toSearchResponse() },
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim().lowercase(Locale.ROOT)
        if (keyword.length < 2) return emptyList()

        val result = mutableListOf<LiveTvChannel>()
        for (country in countries) {
            for (page in 1..SEARCH_PAGES_PER_COUNTRY) {
                val channels = runCatching { channelsFor(country, page) }.getOrDefault(emptyList())
                result += channels.filter { channel ->
                    channel.title.lowercase(Locale.ROOT).contains(keyword) ||
                        channel.country.name.lowercase(Locale.ROOT).contains(keyword) ||
                        channel.slug.lowercase(Locale.ROOT).contains(keyword)
                }
                if (result.size >= SEARCH_LIMIT) break
            }
            if (result.size >= SEARCH_LIMIT) break
        }

        return result
            .distinctBy { it.url }
            .take(SEARCH_LIMIT)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val channel = LiveTvChannel.fromJson(url) ?: url.toChannelFromUrl()
        val document = app.get(
            channel.url,
            headers = siteHeaders(channel.url)
        ).document

        val title = document.selectFirst("h1")?.text()?.cleanText()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.cleanText()
            ?: channel.title

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            ?: document.selectFirst("main img[src], article img[src], img[src]")?.attr("src")?.toAbsoluteUrl()
            ?: channel.posterUrl

        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()
            ?: document.select("main p, article p, .description, .entry-content p").firstOrNull()
                ?.text()
                ?.cleanText()
            ?: "Live TV channel from ${channel.country.name}."

        return newLiveStreamLoadResponse(
            name = title,
            url = channel.url,
            dataUrl = channel.copy(title = title, posterUrl = poster, description = plot).toJson()
        ).apply {
            posterUrl = poster
            this.plot = buildString {
                append(plot)
                append("\n\n")
                append("Country: ").append(channel.country.name)
                append("\nSource: live-tv-channels.org")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = LiveTvChannel.fromJson(data) ?: data.toChannelFromUrl()
        val emitted = resolvePlayableLinks(
            pageUrl = channel.url,
            referer = mainUrl,
            depth = 0,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return emitted > 0
    }

    private suspend fun channelsFor(country: Country, page: Int): List<LiveTvChannel> {
        val key = "${country.slug}:$page"
        pageCache[key]?.let { return it }

        val document = countryDocument(country, page)
        val channels = document.select("a[href*=/watch/]")
            .mapNotNull { it.toLiveChannel(country) }
            .filter { it.slug.startsWith("${country.code}-", ignoreCase = true) }
            .distinctBy { it.url }

        pageCache[key] = channels
        return channels
    }

    private suspend fun countryDocument(country: Country, page: Int): Document {
        val url = if (page <= 1) {
            "$mainUrl/country/${country.slug}"
        } else {
            "$mainUrl/country/${country.slug}/$page"
        }
        return app.get(url, headers = siteHeaders(url)).document
    }

    private fun Element.toLiveChannel(country: Country): LiveTvChannel? {
        val href = attr("href").ifBlank {
            selectFirst("a[href*=/watch/]")?.attr("href").orEmpty()
        }
        if (href.isBlank()) return null

        val absoluteUrl = href.toAbsoluteUrl(mainUrl)
        val slug = absoluteUrl.substringAfter("/watch/", "")
            .substringBefore("?")
            .substringBefore("#")
            .trim()
        if (slug.isBlank()) return null

        val title = selectFirst("h1, h2, h3, h4, .title, .card-title, [itemprop=name]")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("img[alt]")?.attr("alt")?.cleanText()?.takeIf { it.isNotBlank() }
            ?: attr("title").cleanText().takeIf { it.isNotBlank() }
            ?: slug.titleFromSlug(country.code)

        val poster = selectFirst("img[src], img[data-src], img[data-lazy-src]")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
        }?.toAbsoluteUrl(mainUrl)

        val description = selectFirst("p, .description, .excerpt")?.text()?.cleanText()
            ?: attr("aria-label").cleanText().takeIf { it.isNotBlank() }

        return LiveTvChannel(
            title = title,
            url = absoluteUrl,
            slug = slug,
            country = country,
            posterUrl = poster,
            description = description
        )
    }

    private fun Document.hasNextCountryPage(country: Country, page: Int): Boolean {
        val nextPage = page + 1
        return select("a[href]").any { anchor ->
            val href = anchor.attr("href")
            val text = anchor.text().lowercase(Locale.ROOT)
            href.contains("/country/${country.slug}/$nextPage") ||
                text == "next page" ||
                text == "next"
        }
    }

    private suspend fun resolvePlayableLinks(
        pageUrl: String,
        referer: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        if (depth > MAX_RESOLVE_DEPTH) return 0

        val document = app.get(
            pageUrl,
            referer = referer,
            headers = siteHeaders(pageUrl, referer)
        ).document

        val html = document.html().decodeEscapedSlashes()
        var emitted = 0
        val seen = linkedSetOf<String>()

        for (element in document.select("video[src], source[src]")) {
            val mediaUrl = element.attr("src").toAbsoluteUrl(pageUrl)
            if (mediaUrl.isLikelyPlayable() && seen.add(mediaUrl)) {
                emitted += emitDirect(mediaUrl, pageUrl, callback)
            }
        }

        for (match in MEDIA_URL_REGEX.findAll(html)) {
            val mediaUrl = match.value.decodeEscapedSlashes().toAbsoluteUrl(pageUrl)
            if (mediaUrl.isLikelyPlayable() && seen.add(mediaUrl)) {
                emitted += emitDirect(mediaUrl, pageUrl, callback)
            }
        }

        for (match in ATOb_REGEX.findAll(html)) {
            val decoded = runCatching {
                java.util.Base64.getDecoder().decode(match.groupValues[1]).decodeToString()
            }.getOrNull()?.decodeEscapedSlashes().orEmpty()
            for (mediaMatch in MEDIA_URL_REGEX.findAll(decoded)) {
                val mediaUrl = mediaMatch.value.decodeEscapedSlashes().toAbsoluteUrl(pageUrl)
                if (mediaUrl.isLikelyPlayable() && seen.add(mediaUrl)) {
                    emitted += emitDirect(mediaUrl, pageUrl, callback)
                }
            }
        }

        for (frame in document.select("iframe[src], embed[src]")) {
            val frameUrl = frame.attr("src").toAbsoluteUrl(pageUrl)
            if (frameUrl.isBlank() || frameUrl.startsWith("javascript:", ignoreCase = true)) continue

            if (sameHost(frameUrl, mainUrl)) {
                emitted += resolvePlayableLinks(frameUrl, pageUrl, depth + 1, subtitleCallback, callback)
            } else {
                loadExtractor(frameUrl, pageUrl, subtitleCallback) { link ->
                    emitted += 1
                    callback.invoke(link)
                }
            }
        }

        return emitted
    }

    private suspend fun emitDirect(
        mediaUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val cleanUrl = mediaUrl.decodeEscapedSlashes()
        val type = if (cleanUrl.substringBefore("?").endsWith(".m3u8", true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = if (type == ExtractorLinkType.M3U8) "$name HLS" else "$name Direct",
                url = cleanUrl,
                type = type
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = siteHeaders(cleanUrl, referer)
            }
        )
        return 1
    }

    private fun LiveTvChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = title,
            url = toJson(),
            type = TvType.Live
        ).apply {
            posterUrl = this@toSearchResponse.posterUrl
            lang = country.code
        }
    }

    private fun String.toChannelFromUrl(): LiveTvChannel {
        val absolute = toAbsoluteUrl(LIVE_TV_BASE_URL)
        val slug = absolute.substringAfter("/watch/", "")
            .substringBefore("?")
            .substringBefore("#")
            .ifBlank { absolute.substringAfterLast("/") }
        val country = countries.firstOrNull { slug.startsWith("${it.code}-", true) }
            ?: Country("xx", "Unknown", "unknown")
        return LiveTvChannel(
            title = slug.titleFromSlug(country.code),
            url = absolute,
            slug = slug,
            country = country,
            posterUrl = null,
            description = null
        )
    }

    private fun countryBySlug(slug: String): Country? {
        return countries.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
    }

    private data class Country(
        val code: String,
        val name: String,
        val slug: String
    )

    private data class LiveTvChannel(
        val title: String,
        val url: String,
        val slug: String,
        val country: Country,
        val posterUrl: String?,
        val description: String?
    ) {
        fun toJson(): String {
            return JSONObject().apply {
                put("title", title)
                put("url", url)
                put("slug", slug)
                put("countryCode", country.code)
                put("countryName", country.name)
                put("countrySlug", country.slug)
                put("posterUrl", posterUrl ?: "")
                put("description", description ?: "")
            }.toString()
        }

        companion object {
            fun fromJson(text: String): LiveTvChannel? {
                return runCatching {
                    val json = JSONObject(text)
                    val country = Country(
                        code = json.optString("countryCode"),
                        name = json.optString("countryName"),
                        slug = json.optString("countrySlug")
                    )
                    LiveTvChannel(
                        title = json.optString("title"),
                        url = json.optString("url"),
                        slug = json.optString("slug"),
                        country = country,
                        posterUrl = json.optString("posterUrl").takeIf { it.isNotBlank() },
                        description = json.optString("description").takeIf { it.isNotBlank() }
                    )
                }.getOrNull()?.takeIf { it.url.startsWith("http", true) }
            }
        }
    }

    companion object {
        private const val MAX_RESOLVE_DEPTH = 2
        private const val SEARCH_LIMIT = 80
        private const val SEARCH_PAGES_PER_COUNTRY = 2

        private val countries = listOf(
            Country("id", "Indonesia", "indonesia"),
            Country("my", "Malaysia", "malaysia"),
            Country("sg", "Singapore", "singapore"),
            Country("ph", "Philippines", "philippines"),
            Country("th", "Thailand", "thailand"),
            Country("vn", "Vietnam", "vietnam"),
            Country("jp", "Japan", "japan"),
            Country("kr", "South Korea", "south-korea"),
            Country("in", "India", "india"),
            Country("us", "United States", "united-states")
        )

        private val MEDIA_URL_REGEX = Regex(
            """https?:\\?/\\?/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        private val ATOb_REGEX = Regex(
            """atob\(["']([^"']+)["']\)""",
            RegexOption.IGNORE_CASE
        )
    }
}

private fun siteHeaders(url: String, referer: String? = null): Map<String, String> {
    val origin = (referer ?: url).originOrNull()
    return buildMap {
        put("User-Agent", USER_AGENT)
        put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        put("Accept-Language", "en-US,en;q=0.9,id;q=0.8")
        if (!referer.isNullOrBlank()) put("Referer", referer)
        if (!origin.isNullOrBlank()) put("Origin", origin)
    }
}

private fun sameHost(first: String, second: String): Boolean {
    val firstHost = runCatching { URI(first).host?.removePrefix("www.") }.getOrNull()
    val secondHost = runCatching { URI(second).host?.removePrefix("www.") }.getOrNull()
    return !firstHost.isNullOrBlank() && firstHost.equals(secondHost, ignoreCase = true)
}

private const val LIVE_TV_BASE_URL = "https://live-tv-channels.org"

private fun String.toAbsoluteUrl(baseUrl: String = LIVE_TV_BASE_URL): String {
    val clean = trim().decodeEscapedSlashes().replace("&amp;", "&")
    if (clean.isBlank()) return ""
    if (clean.startsWith("//")) return "https:$clean"
    if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) return clean

    val base = runCatching { URI(baseUrl) }.getOrNull()
    val origin = if (base?.scheme != null && base.host != null) {
        val port = if (base.port > 0) ":${base.port}" else ""
        "${base.scheme}://${base.host}$port"
    } else {
        LIVE_TV_BASE_URL
    }

    return if (clean.startsWith("/")) {
        origin.trimEnd('/') + clean
    } else {
        val basePath = baseUrl.substringBeforeLast('/', LIVE_TV_BASE_URL).trimEnd('/')
        "$basePath/$clean"
    }
}

private fun String.isLikelyPlayable(): Boolean {
    val lower = substringBefore("?").lowercase(Locale.ROOT)
    return lower.endsWith(".m3u8") || lower.endsWith(".mp4")
}

private fun String.cleanText(): String {
    return trim()
        .replace(Regex("""\s+"""), " ")
        .removeSuffix(" - Live TV Channels")
        .removeSuffix(" | Live TV Channels")
        .trim()
}

private fun String.decodeEscapedSlashes(): String {
    return replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
}

private fun String.titleFromSlug(countryCode: String): String {
    val withoutCountry = removePrefix("$countryCode-")
    return withoutCountry
        .split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part ->
            part.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
        .ifBlank { this }
}

private fun String.encodeUrl(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

private fun String.decodeUrl(): String {
    return URLDecoder.decode(this, "UTF-8")
}

private fun String.originOrNull(): String? {
    return runCatching {
        val uri = URI(this)
        val scheme = uri.scheme ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        "$scheme://$host$port"
    }.getOrNull()
}
