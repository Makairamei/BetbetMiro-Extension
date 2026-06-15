package com.sad25kag.livetvcentral

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
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class LiveTVCentralProvider : MainAPI() {
    override var mainUrl = "https://livetvcentral.com"
    override var name = "LiveTVCentral"
    override var lang = "en"
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

    private val countryCache = ConcurrentHashMap<String, List<LiveChannel>>()

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,id-ID;q=0.8,id;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val country = countryBySlug(request.data) ?: return newHomePageResponse(
            HomePageList(request.name, emptyList(), isHorizontalImages = true),
            hasNext = false
        )

        val channels = runCatching { channelsFor(country, forceRefresh = page > 1) }
            .onFailure { logError(it) }
            .getOrDefault(emptyList())

        val start = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtLeast(0)
        val pageItems = channels
            .drop(start)
            .take(PAGE_SIZE)
            .map { it.toSearchResponse() }

        return newHomePageResponse(
            HomePageList(request.name, pageItems, isHorizontalImages = true),
            hasNext = channels.size > start + PAGE_SIZE
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim().lowercase(Locale.ROOT)
        if (keyword.length < 2) return emptyList()

        val matches = mutableListOf<LiveChannel>()
        for (country in countries) {
            val channels = runCatching { channelsFor(country) }
                .onFailure { logError(it) }
                .getOrDefault(emptyList())

            matches += channels.filter { channel ->
                channel.title.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.country.name.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.detailUrl.lowercase(Locale.ROOT).contains(keyword)
            }
            if (matches.size >= SEARCH_LIMIT * 2) break
        }

        return matches
            .distinctBy { it.stableId }
            .take(SEARCH_LIMIT)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val countrySlug = url.substringAfter("/cloudstream/channel/", "")
            .substringBefore("/")
            .ifBlank { throw ErrorLoadingException("LiveTVCentral country slug missing.") }

        val stableId = url.substringAfter("/cloudstream/channel/$countrySlug/", "")
            .substringBefore("?")
            .substringBefore("#")
            .decodeUrl()
            .ifBlank { throw ErrorLoadingException("LiveTVCentral channel id missing.") }

        val country = countryBySlug(countrySlug)
            ?: throw ErrorLoadingException("Unsupported LiveTVCentral country: $countrySlug")

        val channel = channelsFor(country).firstOrNull { it.stableId == stableId }
            ?: throw ErrorLoadingException("LiveTVCentral channel not found on active country page.")

        val detail = runCatching { loadDetail(channel) }
            .onFailure { logError(it) }
            .getOrDefault(channel)

        return newLiveStreamLoadResponse(
            name = detail.displayName,
            url = detail.cloudstreamUrl,
            dataUrl = detail.toJson()
        ).apply {
            posterUrl = detail.posterUrl
            plot = buildString {
                append(detail.country.name)
                append("\n")
                append("LiveTVCentral detail: ").append(detail.detailUrl)
                if (detail.description.isNotBlank()) {
                    append("\n\n").append(detail.description)
                }
                append("\n\nPlayback resolver follows the evidence-backed LiveTVCentral detail/player flow and only emits discovered m3u8/mp4 media URLs. No private token, account sharing, DRM bypass, proxy, or restreaming is used.")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channel = LiveChannel.fromJson(data) ?: return false
        val candidates = resolvePlayback(channel)
            .distinctBy { it.url.substringBefore("#") }
            .take(MAX_LINKS_PER_CHANNEL)

        candidates.forEach { media ->
            val type = if (media.url.substringBefore("?").lowercase(Locale.ROOT).endsWith(".m3u8")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "${channel.title} - Live",
                    url = media.url,
                    type = type
                ) {
                    quality = media.quality
                    referer = media.referer.ifBlank { channel.detailUrl }
                    headers = playbackHeaders(media.referer.ifBlank { channel.detailUrl })
                }
            )
        }

        return candidates.isNotEmpty()
    }

    private suspend fun channelsFor(country: Country, forceRefresh: Boolean = false): List<LiveChannel> {
        if (!forceRefresh) countryCache[country.slug]?.let { return it }

        val document = app.get(
            country.url(mainUrl),
            headers = siteHeaders,
            referer = "$mainUrl/",
            timeout = 25L
        ).document

        val channels = parseCountryPage(document, country)
            .distinctBy { it.stableId }
            .sortedBy { it.title.lowercase(Locale.ROOT) }

        countryCache[country.slug] = channels
        return channels
    }

    private fun parseCountryPage(document: Document, country: Country): List<LiveChannel> {
        val result = linkedMapOf<String, LiveChannel>()

        document.select(CARD_SELECTORS).forEach { element ->
            val link = when {
                element.tagName().equals("a", ignoreCase = true) -> element
                else -> element.selectFirst("a[href]")
            } ?: return@forEach

            val href = link.attr("href").trim()
            val detailUrl = href.toAbsoluteUrl(mainUrl) ?: return@forEach
            if (!detailUrl.isLikelyChannelUrl(country)) return@forEach

            val image = link.selectFirst("img") ?: element.selectFirst("img")
            val title = cleanTitle(
                image?.attr("alt").orEmpty()
                    .ifBlank { link.attr("title") }
                    .ifBlank { link.ownText() }
                    .ifBlank { element.text() }
                    .ifBlank { detailUrl.slugTitle() }
            )
            if (title.isBlank() || title.isCountryBoilerplate(country)) return@forEach

            val poster = image?.let { img ->
                listOf("data-src", "data-original", "data-lazy-src", "src")
                    .firstNotNullOfOrNull { attr -> img.attr(attr).toAbsoluteUrl(mainUrl) }
            }

            val channel = LiveChannel(
                title = title,
                country = country,
                detailUrl = detailUrl,
                posterUrl = poster,
                description = ""
            )
            result[channel.stableId] = channel
        }

        return result.values.toList()
    }

    private suspend fun loadDetail(channel: LiveChannel): LiveChannel {
        val document = app.get(
            channel.detailUrl,
            headers = siteHeaders,
            referer = channel.country.url(mainUrl),
            timeout = 25L
        ).document

        val title = cleanTitle(
            document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
                .ifBlank { document.selectFirst("h1")?.text().orEmpty() }
                .ifBlank { document.title() }
                .ifBlank { channel.title }
        )

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl(mainUrl)
            ?: document.selectFirst("img[alt*=\"${channel.title.cssEscape()}\"], .logo img, .channel img, article img, main img")?.attr("src")?.toAbsoluteUrl(mainUrl)
            ?: channel.posterUrl

        val description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            .ifBlank { document.selectFirst(".description, .entry-content, article, main")?.text().orEmpty() }
            .cleanSpaces()
            .take(DESCRIPTION_LIMIT)

        return channel.copy(
            title = title.ifBlank { channel.title },
            posterUrl = poster,
            description = description
        )
    }

    private suspend fun resolvePlayback(channel: LiveChannel): List<MediaCandidate> {
        val queue = ArrayDeque<Pair<String, String>>()
        val visited = mutableSetOf<String>()
        val media = linkedMapOf<String, MediaCandidate>()

        queue += channel.detailUrl to channel.country.url(mainUrl)

        while (queue.isNotEmpty() && visited.size < MAX_PAGES_TO_TRACE) {
            val (url, referer) = queue.removeFirst()
            val normalized = url.substringBefore("#")
            if (!visited.add(normalized)) continue

            if (url.isDirectMediaUrl()) {
                media[url] = MediaCandidate(url = url, referer = referer, quality = url.qualityFromUrl())
                continue
            }

            val responseText = runCatching {
                app.get(
                    url,
                    headers = siteHeaders,
                    referer = referer,
                    timeout = 25L
                ).text
            }.getOrNull() ?: continue

            extractMediaUrls(responseText, url).forEach { mediaUrl ->
                media[mediaUrl] = MediaCandidate(
                    url = mediaUrl,
                    referer = url,
                    quality = mediaUrl.qualityFromUrl()
                )
            }

            val document = runCatching { org.jsoup.Jsoup.parse(responseText, url) }.getOrNull() ?: continue
            document.select("video[src], source[src]").forEach { element ->
                element.attr("src").toAbsoluteUrl(url)?.takeIf { it.isDirectMediaUrl() }?.let { mediaUrl ->
                    media[mediaUrl] = MediaCandidate(mediaUrl, url, mediaUrl.qualityFromUrl())
                }
            }

            document.select("iframe[src], embed[src]").forEach { element ->
                element.attr("src").toAbsoluteUrl(url)?.let { nextUrl ->
                    if (nextUrl.isDirectMediaUrl()) {
                        media[nextUrl] = MediaCandidate(nextUrl, url, nextUrl.qualityFromUrl())
                    } else if (nextUrl.isTraceablePlayerUrl()) {
                        queue += nextUrl to url
                    }
                }
            }

            document.select("a[href]").forEach { element ->
                element.attr("href").toAbsoluteUrl(url)?.let { nextUrl ->
                    when {
                        nextUrl.isDirectMediaUrl() -> media[nextUrl] = MediaCandidate(nextUrl, url, nextUrl.qualityFromUrl())
                        nextUrl.isTraceablePlayerUrl() -> queue += nextUrl to url
                    }
                }
            }
        }

        return media.values.toList()
    }

    private fun extractMediaUrls(text: String, baseUrl: String): List<String> {
        val direct = DIRECT_MEDIA_REGEX.findAll(text)
            .map { it.value.unescapeJsUrl() }
            .mapNotNull { it.toAbsoluteUrl(baseUrl) }
            .filter { it.isDirectMediaUrl() }

        val fileValues = FILE_VALUE_REGEX.findAll(text)
            .map { it.groupValues[2].unescapeJsUrl() }
            .mapNotNull { it.toAbsoluteUrl(baseUrl) }
            .filter { it.isDirectMediaUrl() }

        return (direct + fileValues).distinct().toList()
    }

    private fun LiveChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = displayName,
            url = cloudstreamUrl,
            type = TvType.Live
        ).apply {
            posterUrl = this@toSearchResponse.posterUrl
            lang = country.slug
        }
    }

    private fun playbackHeaders(referer: String): Map<String, String> {
        val origin = referer.originOrNull()
        return buildMap {
            put("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,video/mp4,*/*")
            put("User-Agent", USER_AGENT)
            if (referer.isNotBlank()) put("Referer", referer)
            if (!origin.isNullOrBlank()) put("Origin", origin)
        }
    }

    private fun countryBySlug(slug: String): Country? {
        return countries.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
    }

    data class Country(
        val slug: String,
        val name: String
    ) {
        fun url(mainUrl: String): String = "$mainUrl/country/$slug"
    }

    private data class LiveChannel(
        val title: String,
        val country: Country,
        val detailUrl: String,
        val posterUrl: String?,
        val description: String
    ) {
        val stableId: String by lazy {
            listOf(country.slug, detailUrl.substringBefore("#").substringBefore("?"), title)
                .joinToString("|")
        }

        val displayName: String by lazy { "$title • ${country.name}" }

        val cloudstreamUrl: String by lazy {
            "https://livetvcentral.com/cloudstream/channel/${country.slug}/${stableId.encodeUrl()}"
        }

        fun toJson(): String {
            return JSONObject().apply {
                put("title", title)
                put("countrySlug", country.slug)
                put("countryName", country.name)
                put("detailUrl", detailUrl)
                put("posterUrl", posterUrl.orEmpty())
                put("description", description)
            }.toString()
        }

        companion object {
            fun fromJson(text: String): LiveChannel? {
                return runCatching {
                    val json = JSONObject(text)
                    val country = Country(
                        slug = json.optString("countrySlug"),
                        name = json.optString("countryName")
                    )
                    LiveChannel(
                        title = json.optString("title"),
                        country = country,
                        detailUrl = json.optString("detailUrl"),
                        posterUrl = json.optString("posterUrl").ifBlank { null },
                        description = json.optString("description")
                    )
                }.getOrNull()
            }
        }
    }

    private data class MediaCandidate(
        val url: String,
        val referer: String,
        val quality: Int
    )

    companion object {
        private const val PAGE_SIZE = 48
        private const val SEARCH_LIMIT = 80
        private const val MAX_LINKS_PER_CHANNEL = 12
        private const val MAX_PAGES_TO_TRACE = 8
        private const val DESCRIPTION_LIMIT = 800

        private const val CARD_SELECTORS =
            ".channel, .tv-channel, .station, .thumbnail, .item, .card, article, li, a[href]"

        private val countries = listOf(
            Country("indonesia", "Indonesia"),
            Country("malaysia", "Malaysia"),
            Country("singapore", "Singapore"),
            Country("philippines", "Philippines"),
            Country("thailand", "Thailand"),
            Country("vietnam", "Vietnam"),
            Country("japan", "Japan"),
            Country("south-korea", "South Korea"),
            Country("india", "India"),
            Country("united-states", "United States")
        )

        val EXCLUDED_PATH_PARTS = listOf(
            "/country/", "/about", "/privacy", "/dmca", "/sitemap", "/add-tv-channel",
            "facebook.com", "twitter.com", "instagram.com", "youtube.com", "pinterest.", "mailto:"
        )

        val PLAYER_HINTS = listOf(
            "player", "watch", "live", "stream", "embed", "iframe", "video", "tv"
        )

        private val DIRECT_MEDIA_REGEX = Regex(
            """https?:\\?/\\?/[^\s"'<>]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""",
            RegexOption.IGNORE_CASE
        )

        private val FILE_VALUE_REGEX = Regex(
            """(?i)(file|source|src|url)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']"""
        )
    }
}

private fun String.toAbsoluteUrl(baseUrl: String): String? {
    val value = trim().trim('"', '\'', ' ')
    if (value.isBlank() || value.startsWith("javascript:", true) || value.startsWith("#")) return null
    return runCatching {
        when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            else -> URI(baseUrl).resolve(value).toString()
        }
    }.getOrNull()
}

private fun String.isLikelyChannelUrl(country: LiveTVCentralProvider.Country): Boolean {
    val lower = lowercase(Locale.ROOT)
    if (lower == "https://livetvcentral.com" || lower == "https://livetvcentral.com/") return false
    if (LiveTVCentralProvider.EXCLUDED_PATH_PARTS.any { lower.contains(it) }) return false
    if (lower.endsWith("/country/${country.slug}")) return false
    return lower.startsWith("https://livetvcentral.com/") || !lower.contains("livetvcentral.com")
}

private fun String.isDirectMediaUrl(): Boolean {
    val lower = substringBefore("#").substringBefore("?").lowercase(Locale.ROOT)
    return lower.endsWith(".m3u8") || lower.endsWith(".mp4")
}

private fun String.isTraceablePlayerUrl(): Boolean {
    val lower = lowercase(Locale.ROOT)
    if (isDirectMediaUrl()) return true
    if (lower.startsWith("mailto:") || lower.startsWith("tel:")) return false
    return LiveTVCentralProvider.PLAYER_HINTS.any { lower.contains(it) }
}

private fun String.qualityFromUrl(): Int {
    val lower = lowercase(Locale.ROOT)
    return when {
        lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
        lower.contains("1440") -> Qualities.P1440.value
        lower.contains("1080") -> Qualities.P1080.value
        lower.contains("720") -> Qualities.P720.value
        lower.contains("576") -> Qualities.P480.value
        lower.contains("480") -> Qualities.P480.value
        lower.contains("360") -> Qualities.P360.value
        lower.contains("240") -> Qualities.P240.value
        else -> Qualities.Unknown.value
    }
}

private fun cleanTitle(text: String): String {
    return text
        .replace("Live TV Central", "", ignoreCase = true)
        .replace("live Tv", "", ignoreCase = true)
        .replace("TV Channels", "", ignoreCase = true)
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-', '|', '–', '—')
}

private fun String.isCountryBoilerplate(country: LiveTVCentralProvider.Country): Boolean {
    val lower = lowercase(Locale.ROOT).cleanSpaces()
    val countryName = country.name.lowercase(Locale.ROOT)
    return lower == countryName ||
        lower == "watch $countryName live tv channels" ||
        lower.contains("all $countryName televisions") ||
        lower.contains("online tv channels in $countryName") ||
        lower.contains("popular countries") ||
        lower.contains("about live tv central")
}

private fun String.slugTitle(): String {
    return substringBefore("?")
        .substringBefore("#")
        .trimEnd('/')
        .substringAfterLast('/')
        .replace('-', ' ')
        .replace('_', ' ')
        .cleanSpaces()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

private fun String.unescapeJsUrl(): String {
    return replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("&amp;", "&")
}

private fun String.cleanSpaces(): String {
    return replace(Regex("""\s+"""), " ").trim()
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

private fun String.cssEscape(): String {
    return replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
}
