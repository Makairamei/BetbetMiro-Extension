package com.sad25kag.betbettv

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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class BetbetTVProvider : MainAPI() {
    override var mainUrl = FIREBASE_PRIMARY
    override var name = "BetbetTV"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        Category.TV_INDONESIA.slug to Category.TV_INDONESIA.title,
        Category.SPORT.slug to Category.SPORT.title,
        Category.TV_OTHER.slug to Category.TV_OTHER.title,
        Category.TV_MOVIES.slug to Category.TV_MOVIES.title,
        Category.KIDS.slug to Category.KIDS.title
    )

    private val allChannelCache = ConcurrentHashMap<String, List<LiveChannel>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = Category.bySlug(request.data) ?: Category.TV_OTHER
        val channels = runCatching { channelsFor(category) }
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

        return runCatching { loadAllChannels() }
            .onFailure { logError(it) }
            .getOrDefault(emptyList())
            .filter { channel ->
                channel.name.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.category.title.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.rawCategory.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.sourcePath.lowercase(Locale.ROOT).contains(keyword)
            }
            .distinctBy { it.stableId }
            .take(SEARCH_LIMIT)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val categorySlug = url.substringAfter("/channel/", "")
            .substringBefore("/")
            .ifBlank { throw ErrorLoadingException("Kategori channel tidak ditemukan.") }
        val channelId = url.substringAfter("/channel/$categorySlug/", "")
            .substringBefore("?")
            .substringBefore("#")
            .decodeUrl()
            .ifBlank { throw ErrorLoadingException("ID channel tidak ditemukan.") }

        val category = Category.bySlug(categorySlug) ?: Category.TV_OTHER
        val channel = channelsFor(category).firstOrNull { it.stableId == channelId }
            ?: throw ErrorLoadingException("Channel BetbetTV tidak ditemukan dari data Firebase runtime.")

        return newLiveStreamLoadResponse(
            name = channel.displayName,
            url = channel.detailUrl,
            dataUrl = channel.toJson()
        ).apply {
            posterUrl = channel.posterUrl
            plot = buildString {
                append(channel.category.title)
                if (channel.rawCategory.isNotBlank()) append(" • Source category: ").append(channel.rawCategory)
                append("\n")
                append("Source path: ").append(channel.sourcePath.ifBlank { "Firebase runtime scan" })
                append("\n")
                append("Stream type: ").append(channel.streamTypeLabel)
                append("\n\n")
                append("Evidence: provider follows Sumaleng TV APK Firebase Realtime Database configuration discovered from the attached APK. No app account, cookie, DRM bypass, proxy, or restreaming is used.")
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
        val streamUrl = channel.streamUrl.takeIf { it.isSupportedStreamUrl() && !it.hasBlockedPlaybackFlag() }
            ?: return false
        val headers = channel.playbackHeaders()
        val linkType = when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "${channel.name} - ${channel.category.title}",
                url = streamUrl,
                type = linkType
            ) {
                quality = channel.quality()
                referer = headers["Referer"] ?: channel.referer.ifBlank { mainUrl }
                this.headers = headers
            }
        )

        return true
    }

    private suspend fun channelsFor(category: Category): List<LiveChannel> {
        return loadAllChannels()
            .filter { it.category == category }
            .distinctBy { it.stableId }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private suspend fun loadAllChannels(): List<LiveChannel> {
        allChannelCache[CACHE_KEY]?.let { return it }

        val parsed = mutableListOf<LiveChannel>()
        for (endpoint in databaseEndpoints()) {
            val endpointChannels = runCatching {
                val text = app.get(
                    endpoint,
                    headers = mapOf(
                        "Accept" to "application/json,text/plain,*/*",
                        "User-Agent" to USER_AGENT
                    ),
                    timeout = 30L
                ).text.trim()

                when {
                    text.isBlank() || text == "null" -> emptyList()
                    text.startsWith("#EXTM3U", ignoreCase = true) -> parseM3u(text, endpoint, Category.TV_OTHER)
                    else -> parseJsonTree(JSONTokener(text).nextValue(), endpoint)
                }
            }.onFailure { logError(it) }.getOrDefault(emptyList())

            parsed += endpointChannels
            if (parsed.size >= MIN_RUNTIME_CHANNELS) break
        }

        val result = parsed
            .mapNotNull { it.normalized() }
            .filter { it.streamUrl.isSupportedStreamUrl() }
            .filterNot { it.streamUrl.hasBlockedPlaybackFlag() }
            .distinctBy { it.stableId }
            .sortedWith(compareBy({ it.category.order }, { it.name.lowercase(Locale.ROOT) }))

        allChannelCache[CACHE_KEY] = result
        return result
    }

    private fun parseJsonTree(value: Any?, sourcePath: String, inheritedCategory: String = ""): List<LiveChannel> {
        val result = mutableListOf<LiveChannel>()
        when (value) {
            is JSONObject -> {
                objectToChannel(value, sourcePath, inheritedCategory)?.let { result += it }
                val nextCategory = value.firstNonBlank(CATEGORY_FIELDS).ifBlank { inheritedCategory }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = value.opt(key)
                    val childCategory = when {
                        key.looksLikeCategoryKey() -> key
                        nextCategory.isNotBlank() -> nextCategory
                        else -> inheritedCategory
                    }
                    result += parseJsonTree(child, "$sourcePath/$key", childCategory)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    result += parseJsonTree(value.opt(index), "$sourcePath[$index]", inheritedCategory)
                }
            }

            is String -> {
                result += parseTextBlock(value, sourcePath, Category.classify(inheritedCategory))
            }
        }
        return result
    }

    private fun objectToChannel(json: JSONObject, sourcePath: String, inheritedCategory: String): LiveChannel? {
        val stream = json.firstStreamUrl() ?: return null
        val name = json.firstNonBlank(NAME_FIELDS)
            .ifBlank { sourcePath.substringAfterLast('/').substringBefore('.').cleanNameCandidate() }
            .ifBlank { stream.hostLabel() }
        val rawCategory = json.firstNonBlank(CATEGORY_FIELDS).ifBlank { inheritedCategory }
        val category = Category.classify("$rawCategory $name $sourcePath")
        val logo = json.firstSafeUrl(LOGO_FIELDS)
        val referer = json.firstSafeUrl(REFERER_FIELDS).orEmpty()
        val origin = json.firstSafeUrl(ORIGIN_FIELDS).orEmpty()
        val userAgent = json.firstNonBlank(USER_AGENT_FIELDS)
        val qualityLabel = json.firstNonBlank(QUALITY_FIELDS)

        return LiveChannel(
            name = name.cleanChannelName(),
            rawCategory = rawCategory.cleanText(),
            category = category,
            logo = logo.orEmpty(),
            streamUrl = stream,
            referer = referer,
            origin = origin,
            userAgent = userAgent,
            qualityLabel = qualityLabel,
            sourcePath = sourcePath
        )
    }

    private fun parseTextBlock(text: String, sourcePath: String, category: Category): List<LiveChannel> {
        if (text.startsWith("#EXTM3U", ignoreCase = true)) return parseM3u(text, sourcePath, category)
        return collectUrlsFromText(text)
            .filter { it.isSupportedStreamUrl() }
            .mapIndexed { index, stream ->
                LiveChannel(
                    name = sourcePath.substringAfterLast('/').substringBefore('.').cleanNameCandidate()
                        .ifBlank { "BetbetTV ${index + 1}" },
                    rawCategory = category.title,
                    category = category,
                    logo = "",
                    streamUrl = stream,
                    referer = "",
                    origin = "",
                    userAgent = "",
                    qualityLabel = "",
                    sourcePath = sourcePath
                )
            }
    }

    private fun parseM3u(text: String, sourcePath: String, fallbackCategory: Category): List<LiveChannel> {
        val result = mutableListOf<LiveChannel>()
        var info = ""
        var name = ""
        var attrs: Map<String, String> = emptyMap()
        var pendingReferer = ""
        var pendingUserAgent = ""

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    attrs = parseAttributes(line)
                    name = line.substringAfterLast(',', missingDelimiterValue = "").trim()
                    pendingReferer = ""
                    pendingUserAgent = ""
                }

                line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                    pendingReferer = line.substringAfter("=", "").trim()
                }

                line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                    pendingUserAgent = line.substringAfter("=", "").trim()
                }

                line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true) -> {
                    val channelName = name.ifBlank { attrs["tvg-name"].orEmpty() }
                        .ifBlank { attrs["tvg-id"].orEmpty() }
                        .ifBlank { line.hostLabel() }
                    val rawCategory = attrs["group-title"].orEmpty()
                    val category = Category.classify("$rawCategory $channelName $sourcePath")
                        .takeIf { it != Category.TV_OTHER } ?: fallbackCategory

                    result += LiveChannel(
                        name = channelName.cleanChannelName(),
                        rawCategory = rawCategory,
                        category = category,
                        logo = attrs["tvg-logo"].orEmpty().toSafeUrl().orEmpty(),
                        streamUrl = line,
                        referer = pendingReferer,
                        origin = "",
                        userAgent = pendingUserAgent,
                        qualityLabel = info,
                        sourcePath = sourcePath
                    )

                    info = ""
                    name = ""
                    attrs = emptyMap()
                    pendingReferer = ""
                    pendingUserAgent = ""
                }
            }
        }
        return result
    }

    private fun databaseEndpoints(): List<String> {
        val bases = listOf(
            FIREBASE_PRIMARY,
            "https://sumaleng-tv-default-rtdb.asia-southeast1.firebasedatabase.app",
            "https://sumaleng-tv-default-rtdb.firebasedatabase.app",
            "https://sumaleng-tv.firebaseio.com"
        ).map { it.normalizeFirebaseBase() }.distinct()

        val paths = listOf(
            "",
            "channels",
            "channel",
            "tv",
            "live",
            "livetv",
            "data",
            "items",
            "list",
            "categories"
        )

        return bases.flatMap { base ->
            paths.map { path ->
                if (path.isBlank()) "$base/.json" else "$base/$path.json"
            }
        }.distinct()
    }

    private fun LiveChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = displayName,
            url = detailUrl,
            type = TvType.Live
        ).apply {
            posterUrl = this@toSearchResponse.posterUrl
            lang = null
        }
    }

    private data class LiveChannel(
        val name: String,
        val rawCategory: String,
        val category: Category,
        val logo: String,
        val streamUrl: String,
        val referer: String,
        val origin: String,
        val userAgent: String,
        val qualityLabel: String,
        val sourcePath: String
    ) {
        val stableId: String by lazy {
            stableChannelId(name, category.slug, streamUrl)
        }

        val displayName: String by lazy {
            "${category.prefix} $name"
        }

        val detailUrl: String by lazy {
            "$FIREBASE_PRIMARY/channel/${category.slug}/${stableId.encodeUrl()}"
        }

        val posterUrl: String? by lazy {
            logo.toSafeUrl()
        }

        val streamTypeLabel: String by lazy {
            when {
                streamUrl.contains(".m3u8", ignoreCase = true) -> "HLS"
                streamUrl.contains(".mp4", ignoreCase = true) -> "MP4"
                else -> "Direct video URL"
            }
        }

        fun normalized(): LiveChannel? {
            val cleanName = name.cleanChannelName().ifBlank { return null }
            val cleanStream = streamUrl.trim().takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
                ?: return null
            return copy(
                name = cleanName,
                logo = logo.toSafeUrl().orEmpty(),
                streamUrl = cleanStream,
                referer = referer.toSafeUrl().orEmpty(),
                origin = origin.toSafeUrl().orEmpty(),
                rawCategory = rawCategory.cleanText(),
                sourcePath = sourcePath.cleanText()
            )
        }

        fun playbackHeaders(): Map<String, String> {
            val resolvedReferer = referer.toSafeUrl().orEmpty()
            val resolvedOrigin = origin.toSafeUrl().orEmpty().ifBlank { resolvedReferer.originOrNull().orEmpty() }
            val headers = linkedMapOf<String, String>()
            headers["Accept"] = "application/vnd.apple.mpegurl,application/x-mpegURL,video/*,*/*"
            headers["User-Agent"] = userAgent.ifBlank { USER_AGENT }
            if (resolvedReferer.isNotBlank()) headers["Referer"] = resolvedReferer
            if (resolvedOrigin.isNotBlank()) headers["Origin"] = resolvedOrigin
            return headers
        }

        fun quality(): Int {
            val text = "$name $qualityLabel $streamUrl".lowercase(Locale.ROOT)
            return when {
                text.contains("2160") || text.contains("4k") -> Qualities.P2160.value
                text.contains("1440") -> Qualities.P1440.value
                text.contains("1080") || text.contains("fhd") -> Qualities.P1080.value
                text.contains("720") || text.contains("hd") -> Qualities.P720.value
                text.contains("576") || text.contains("480") -> Qualities.P480.value
                text.contains("360") -> Qualities.P360.value
                text.contains("240") -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }
        }

        fun toJson(): String {
            return JSONObject().apply {
                put("name", name)
                put("rawCategory", rawCategory)
                put("category", category.slug)
                put("logo", logo)
                put("streamUrl", streamUrl)
                put("referer", referer)
                put("origin", origin)
                put("userAgent", userAgent)
                put("qualityLabel", qualityLabel)
                put("sourcePath", sourcePath)
            }.toString()
        }

        companion object {
            fun fromJson(data: String): LiveChannel? {
                return runCatching {
                    val json = JSONObject(data)
                    LiveChannel(
                        name = json.optString("name"),
                        rawCategory = json.optString("rawCategory"),
                        category = Category.bySlug(json.optString("category")) ?: Category.TV_OTHER,
                        logo = json.optString("logo"),
                        streamUrl = json.optString("streamUrl"),
                        referer = json.optString("referer"),
                        origin = json.optString("origin"),
                        userAgent = json.optString("userAgent"),
                        qualityLabel = json.optString("qualityLabel"),
                        sourcePath = json.optString("sourcePath")
                    )
                }.getOrNull()
            }
        }
    }

    private enum class Category(
        val slug: String,
        val title: String,
        val prefix: String,
        val order: Int,
        private val keywords: List<String>
    ) {
        TV_INDONESIA(
            "tv-indonesia",
            "TV Indonesia",
            "🇮🇩",
            0,
            listOf("tv indonesia", "indonesia", "indonesian", "nasional", "lokal", "daerah", "id", "rcti", "sctv", "indosiar", "trans", "tvri", "metro", "kompas", "antv", "mnctv", "gtv", "net tv", "inews")
        ),
        SPORT(
            "sport",
            "Sport",
            "🏟️",
            1,
            listOf("sport", "sports", "bola", "sepak", "football", "soccer", "liga", "motogp", "formula", "f1", "badminton", "ufc", "fight", "bein", "espn", "skor", "arena", "racing")
        ),
        TV_OTHER(
            "tv-other",
            "TV Other",
            "🌐",
            2,
            listOf("other", "others", "international", "news", "music", "lifestyle", "religion", "knowledge", "documentary", "asia", "malaysia", "singapore", "thailand", "korea", "japan", "china")
        ),
        TV_MOVIES(
            "tv-movies",
            "TV Movies",
            "🎬",
            3,
            listOf("movie", "movies", "film", "cinema", "cinemax", "hbo", "thriller", "action movie", "box office", "bioskop")
        ),
        KIDS(
            "kids",
            "Kids",
            "🧒",
            4,
            listOf("kids", "kid", "anak", "cartoon", "kartun", "disney", "nick", "nickelodeon", "boomerang", "cartoon network", "cbeebies", "baby")
        );

        companion object {
            fun bySlug(slug: String): Category? = values().firstOrNull { it.slug == slug }

            fun classify(text: String): Category {
                val haystack = text.lowercase(Locale.ROOT)
                for (category in values()) {
                    if (category == TV_OTHER) continue
                    for (keyword in category.keywords) {
                        if (haystack.contains(keyword.lowercase(Locale.ROOT))) return category
                    }
                }
                return TV_OTHER
            }
        }
    }

    companion object {
        private const val FIREBASE_PRIMARY = "https://sumaleng-tv-default-rtdb.firebaseio.com"
        private const val CACHE_KEY = "all"
        private const val PAGE_SIZE = 80
        private const val SEARCH_LIMIT = 120
        private const val MIN_RUNTIME_CHANNELS = 5

        private val NAME_FIELDS = listOf(
            "name", "title", "channel", "channelName", "channel_name", "nama", "nama_channel", "tv", "label", "judul"
        )
        private val CATEGORY_FIELDS = listOf(
            "category", "kategori", "group", "groupTitle", "group-title", "type", "jenis", "folder", "genre"
        )
        private val LOGO_FIELDS = listOf(
            "logo", "icon", "image", "img", "poster", "thumbnail", "thumb", "tvgLogo", "tvg-logo", "gambar"
        )
        private val STREAM_FIELDS = listOf(
            "url", "link", "stream", "streamUrl", "stream_url", "video", "videoUrl", "video_url", "source", "src", "file", "m3u8", "hls", "playUrl", "play_url", "tvUrl", "tv_url", "channelUrl", "channel_url"
        )
        private val REFERER_FIELDS = listOf("referer", "referrer", "http-referrer", "http_referrer")
        private val ORIGIN_FIELDS = listOf("origin")
        private val USER_AGENT_FIELDS = listOf("userAgent", "user-agent", "user_agent", "ua")
        private val QUALITY_FIELDS = listOf("quality", "res", "resolution", "label")
        private val ATTR_REGEX = Regex("""([a-zA-Z0-9_-]+)=\"([^\"]*)\"""")
        private val URL_REGEX = Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
        private val BLOCKED_PLAYBACK_FLAGS = listOf(
            "widevine",
            "license_type",
            "licenseurl",
            "license_url",
            "clearkey",
            "drm"
        )
        private val CREDENTIAL_PATTERN = Regex("""([?&](username|password)=|/get\.php\?)""", RegexOption.IGNORE_CASE)
    }
}

private fun JSONObject.firstNonBlank(keys: List<String>): String {
    keys.forEach { key ->
        val value = optStringCompat(key)
        if (value.isNotBlank()) return value
    }
    return ""
}

private fun JSONObject.firstSafeUrl(keys: List<String>): String? {
    keys.forEach { key ->
        val value = optStringCompat(key).toSafeUrl()
        if (!value.isNullOrBlank()) return value
    }
    return null
}

private fun JSONObject.firstStreamUrl(): String? {
    for (key in STREAM_FIELD_NAMES) {
        val url = optStringCompat(key).extractFirstUrl()
        if (url != null && url.isSupportedStreamUrl()) return url
    }

    val iterator = keys()
    while (iterator.hasNext()) {
        val key = iterator.next()
        val value = opt(key)
        if (value is String) {
            val url = value.extractFirstUrl()
            if (url != null && url.isSupportedStreamUrl()) return url
        } else if (value is JSONArray) {
            for (index in 0 until value.length()) {
                val url = value.optString(index).extractFirstUrl()
                if (url != null && url.isSupportedStreamUrl()) return url
            }
        }
    }
    return null
}

private val STREAM_FIELD_NAMES = listOf(
    "url", "link", "stream", "streamUrl", "stream_url", "video", "videoUrl", "video_url", "source", "src", "file", "m3u8", "hls", "playUrl", "play_url", "tvUrl", "tv_url", "channelUrl", "channel_url"
)

private fun JSONObject.optStringCompat(key: String): String {
    if (has(key)) return optString(key).trim()
    val lower = key.lowercase(Locale.ROOT)
    val keys = keys()
    while (keys.hasNext()) {
        val current = keys.next()
        if (current.lowercase(Locale.ROOT) == lower) return optString(current).trim()
    }
    return ""
}

private fun parseAttributes(line: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    val matches = Regex("""([a-zA-Z0-9_-]+)=\"([^\"]*)\"""").findAll(line)
    for (match in matches) {
        result[match.groupValues[1].lowercase(Locale.ROOT)] = match.groupValues[2].trim()
    }
    return result
}


private fun collectUrlsFromText(text: String): List<String> {
    val result = linkedSetOf<String>()
    val matches = Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE).findAll(text.decodeText())
    for (match in matches) {
        result += match.value.trimEnd(',', ';', ')', ']', '}')
    }
    return result.toList()
}


private fun String.extractFirstUrl(): String? {
    val value = decodeText().trim()
    if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value.trimEnd(',', ';')
    return collectUrlsFromText(value).firstOrNull()
}

private fun String.isSupportedStreamUrl(): Boolean {
    val lower = lowercase(Locale.ROOT).substringBefore("#")
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
    return lower.contains(".m3u8") ||
        lower.contains(".mp4") ||
        lower.contains(".webm") ||
        lower.contains(".mkv") ||
        lower.contains("/hls/") ||
        lower.contains("/live/") ||
        lower.contains("playlist.m3u8") ||
        lower.contains("manifest") && !lower.contains(".mpd")
}

private fun String.hasBlockedPlaybackFlag(): Boolean {
    val lower = lowercase(Locale.ROOT)
    if (Regex("""([?&](username|password)=|/get\.php\?)""", RegexOption.IGNORE_CASE).containsMatchIn(this)) return true
    return listOf("widevine", "license_type", "licenseurl", "license_url", "clearkey", "drm").any { lower.contains(it) }
}

private fun String.toSafeUrl(): String? {
    val value = trim().decodeText()
    if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
    return value
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

private fun String.normalizeFirebaseBase(): String {
    var value = trim()
    while (value.startsWith("https://https://", true)) value = value.removePrefix("https://")
    return value.trimEnd('/')
}

private fun String.looksLikeCategoryKey(): Boolean {
    val lower = lowercase(Locale.ROOT)
    return listOf("category", "kategori", "sport", "sports", "kids", "movie", "movies", "indonesia", "other", "others", "tv").any { lower.contains(it) }
}

private fun String.cleanChannelName(): String {
    return cleanText()
        .replace(Regex("""\s*\[[^]]*]\s*"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.cleanNameCandidate(): String {
    return decodeUrl()
        .replace(Regex("""[_\-]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.cleanText(): String {
    return replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("\\/", "/")
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun String.decodeText(): String {
    var value = cleanText()
    repeat(2) {
        value = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
    }
    return value.trim()
}

private fun String.encodeUrl(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

private fun String.decodeUrl(): String {
    return runCatching { URLDecoder.decode(this, Charsets.UTF_8.name()) }.getOrDefault(this)
}

private fun String.hostLabel(): String {
    return runCatching { URI(this).host.orEmpty() }
        .getOrDefault("")
        .removePrefix("www.")
        .substringBefore('.')
        .cleanNameCandidate()
        .ifBlank { "BetbetTV" }
}

private fun stableChannelId(name: String, categorySlug: String, streamUrl: String): String {
    val host = runCatching { URI(streamUrl).host.orEmpty() }.getOrDefault("").removePrefix("www.")
    val base = "$name-$categorySlug-$host"
    return base.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "-")
        .trim('-')
        .ifBlank { streamUrl.hashCode().toString() }
}
