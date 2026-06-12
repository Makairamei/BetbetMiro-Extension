package com.sad25kag.aktv

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class AKTVProvider : MainAPI() {
    override var mainUrl = "https://raw.githubusercontent.com"
    override var name = "AKTV"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    private val playlistUrl = "https://raw.githubusercontent.com/Andrilight/Nonton/refs/heads/main/Andri"
    private val legacyRedirectUrl = "https://bit.ly/Gratis-99"

    /**
     * HAR evidence: AKTV requests the playlist with `User-Agent: SimpleTV11.6`.
     */
    private val playlistUserAgent = "SimpleTV11.6"

    private val fallbackStreamUserAgent =
        "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0 Mobile Safari/537.36"

    private val categoryPages = listOf(
        "all" to "All Channels",
        "INDONESIA TV" to "Indonesia TV",
        "MOVIES" to "Movies",
        "SPORTS" to "Sports",
        "WORLD TV" to "World TV",
        "NEWS" to "News",
        "RELIGION" to "Religion",
        "LIFESTYLE" to "Lifestyle",
        "ENTERTAINMENT" to "Entertainment",
        "WORLD SPORTS" to "World Sports",
        "MUSIC" to "Music",
        "IT 🇮🇹" to "Italy",
        "GB 🇬🇧" to "United Kingdom",
    )

    override val mainPage = mainPageOf(*categoryPages.toTypedArray())

    private data class StreamCandidate(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val drmProtected: Boolean = false,
    )

    private data class Channel(
        val title: String,
        val logo: String? = null,
        val group: String,
        val streams: List<StreamCandidate>,
    )

    private data class MutableChannel(
        val title: String,
        val logo: String?,
        val group: String,
        val baseHeaders: MutableMap<String, String>,
        var drmForDash: Boolean,
        val streams: MutableList<StreamCandidate> = mutableListOf(),
    )

    private var cachedAtMs: Long = 0L
    private var cachedChannels: List<Channel> = emptyList()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(emptyList())

        val channels = fetchChannels()
        if (channels.isEmpty()) return newHomePageResponse(emptyList())

        val lists = if (request.data == "all") {
            buildAllCategoryLists(channels)
        } else {
            val items = channels
                .filter { it.group.equals(request.data, ignoreCase = true) }
                .distinctBy { it.title.lowercase() to it.streams.firstOrNull()?.url.orEmpty() }
                .take(120)
                .map { it.toSearchResponse() }
            listOf(HomePageList(request.name, items))
        }.filter { it.list.isNotEmpty() }

        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return emptyList()

        return fetchChannels()
            .filter { channel ->
                channel.title.lowercase().contains(q) ||
                    channel.group.lowercase().contains(q) ||
                    channel.streams.any { it.url.lowercase().contains(q) }
            }
            .distinctBy { it.title.lowercase() to it.group.lowercase() }
            .take(80)
            .map { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val channel = decodeChannel(url)
        return newMovieLoadResponse(channel.title, url, TvType.Live, encodeChannel(channel)) {
            this.posterUrl = channel.logo
            this.plot = buildString {
                append("Live channel from AKTV playlist")
                append("\nCategory: ").append(channel.group)
                append("\nPlayable alternatives: ").append(channel.streams.size)
            }
            this.tags = listOf(channel.group)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channel = decodeChannel(data)
        var emitted = 0

        channel.streams
            .asSequence()
            .filterNot { it.drmProtected }
            .filter { it.url.isHttpUrl() && !it.url.isBlockedStreamUrl() }
            .distinctBy { it.url.substringBefore("|") }
            .take(6)
            .forEach { stream ->
                val url = stream.url.substringBefore("|").trim()
                val headers = stream.headers.withDefaultStreamHeaders()
                val referer = headers["Referer"] ?: mainUrl
                val linkName = "$name - ${channel.title}".trim()

                emitted += when {
                    url.contains(".m3u8", ignoreCase = true) -> emitHls(linkName, url, referer, headers, callback)
                    url.contains(".mpd", ignoreCase = true) -> emitDash(linkName, url, referer, headers, callback)
                    else -> emitDirect(linkName, url, referer, headers, callback)
                }
            }

        return emitted > 0
    }

    private fun buildAllCategoryLists(channels: List<Channel>): List<HomePageList> {
        val grouped = channels.groupBy { it.group }
        val knownOrder = categoryPages.map { it.first }.filterNot { it == "all" }
        val orderedGroups = knownOrder + (grouped.keys - knownOrder.toSet()).sorted()

        return orderedGroups.mapNotNull { group ->
            val items = grouped[group]
                ?.distinctBy { it.title.lowercase() to it.streams.firstOrNull()?.url.orEmpty() }
                ?.take(40)
                ?.map { it.toSearchResponse() }
                .orEmpty()
            if (items.isEmpty()) null else HomePageList(group.toDisplayGroupName(), items)
        }
    }

    private suspend fun fetchChannels(): List<Channel> {
        val now = System.currentTimeMillis()
        if (cachedChannels.isNotEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cachedChannels

        val channels = fetchPlaylistFrom(playlistUrl)
            .ifEmpty { fetchPlaylistFrom(legacyRedirectUrl) }
            .distinctBy { it.group.lowercase() to it.title.lowercase() to it.streams.firstOrNull()?.url.orEmpty() }

        cachedAtMs = now
        cachedChannels = channels
        return channels
    }

    private suspend fun fetchPlaylistFrom(url: String): List<Channel> {
        return runCatching {
            val text = app.get(url, headers = playlistHeaders(), referer = mainUrl).text
            if (text.isBadPlaylistResponse()) return@runCatching emptyList()
            parseM3u(text)
        }.getOrDefault(emptyList())
    }

    private fun playlistHeaders() = mapOf(
        "User-Agent" to playlistUserAgent,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    private fun parseM3u(text: String): List<Channel> {
        val rawChannels = mutableListOf<MutableChannel>()
        var sectionGroup: String? = null
        var current: MutableChannel? = null
        var currentHasStream = false
        var pendingHeaders = mutableMapOf<String, String>()
        var pendingDrm = false

        fun directiveTargetHeaders(): MutableMap<String, String> {
            return if (current != null && !currentHasStream) current!!.baseHeaders else pendingHeaders
        }

        fun applyDirective(rawLine: String) {
            val line = rawLine.cleanPlaylistLine()
            val targetHeaders = directiveTargetHeaders()

            line.optionValue("http-user-agent")?.let { targetHeaders["User-Agent"] = it }
            line.optionValue("http-referrer")?.let { targetHeaders["Referer"] = it }
            line.optionValue("http-referer")?.let { targetHeaders["Referer"] = it }
            line.optionValue("http-origin")?.let { targetHeaders["Origin"] = it }
            line.streamHeaderValue("Referer")?.let { targetHeaders["Referer"] = it }
            line.streamHeaderValue("User-Agent")?.let { targetHeaders["User-Agent"] = it }
            line.streamHeaderValue("Origin")?.let { targetHeaders["Origin"] = it }

            if (line.contains("inputstream.adaptive.license_type", ignoreCase = true) ||
                line.contains("inputstream.adaptive.license_key", ignoreCase = true)
            ) {
                if (current != null && !currentHasStream) {
                    current!!.drmForDash = true
                } else {
                    pendingDrm = true
                }
            }
        }

        text.lineSequence()
            .map { it.cleanPlaylistLine() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.contains(">>>") -> {
                        sectionGroup = line.substringAfter(">>>")
                            .replace("*", "")
                            .trim()
                            .ifBlank { null }
                            ?.uppercase()
                    }

                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        val title = line.substringAfterLast(",", "").trim()
                            .ifBlank { line.attrValue("tvg-name") ?: "AKTV Channel" }
                        val group = (line.attrValue("group-title") ?: line.attrValue("group") ?: sectionGroup ?: "AKTV")
                            .normalizeGroupName()
                        val logo = line.attrValue("tvg-logo") ?: line.attrValue("logo")

                        current = MutableChannel(
                            title = title,
                            logo = logo,
                            group = group,
                            baseHeaders = pendingHeaders.toMutableMap(),
                            drmForDash = pendingDrm,
                        )
                        rawChannels.add(current!!)
                        currentHasStream = false
                        pendingHeaders = mutableMapOf()
                        pendingDrm = false
                    }

                    line.startsWith("#") || line.startsWith("//") -> {
                        applyDirective(line)
                    }

                    line.isHttpUrl() -> {
                        val candidate = parseStreamCandidate(line, current, pendingHeaders, pendingDrm)
                        if (candidate != null) {
                            if (current == null) {
                                current = MutableChannel(
                                    title = candidate.url.substringAfterLast('/').substringBefore('?').ifBlank { "AKTV Stream" },
                                    logo = null,
                                    group = sectionGroup?.normalizeGroupName() ?: "AKTV",
                                    baseHeaders = mutableMapOf(),
                                    drmForDash = candidate.drmProtected,
                                )
                                rawChannels.add(current!!)
                            }
                            current!!.streams.add(candidate)
                            currentHasStream = true
                        }
                    }
                }
            }

        return rawChannels.mapNotNull { item ->
            val streams = item.streams
                .filterNot { it.drmProtected }
                .filter { it.url.isHttpUrl() && !it.url.isBlockedStreamUrl() }
                .distinctBy { it.url.substringBefore("|") }
            if (streams.isEmpty()) return@mapNotNull null
            Channel(
                title = item.title.cleanChannelTitle(),
                logo = item.logo?.takeIf { it.isHttpUrl() },
                group = item.group,
                streams = streams,
            )
        }
    }

    private fun parseStreamCandidate(
        rawLine: String,
        current: MutableChannel?,
        pendingHeaders: Map<String, String>,
        pendingDrm: Boolean,
    ): StreamCandidate? {
        val split = rawLine.split("|", limit = 2)
        val url = split.firstOrNull()?.trim()?.trimEnd('*')?.trim() ?: return null
        if (!url.isHttpUrl() || !url.isLikelyPlayableStreamUrl() || url.isBlockedStreamUrl()) return null

        val inheritedHeaders = (current?.baseHeaders ?: pendingHeaders).toMutableMap()
        if (split.size == 2) inheritedHeaders.putAll(split[1].parsePipeHeaders())

        val isDash = url.contains(".mpd", ignoreCase = true) || url.contains("manifest", ignoreCase = true)
        val drm = isDash && ((current?.drmForDash == true) || pendingDrm)
        return StreamCandidate(url = url, headers = inheritedHeaders, drmProtected = drm)
    }

    private fun Channel.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(title, encodeChannel(this), TvType.Live) {
            this.posterUrl = logo
        }
    }

    private fun encodeChannel(channel: Channel): String {
        val root = JSONObject()
        root.put("title", channel.title)
        root.put("logo", channel.logo ?: "")
        root.put("group", channel.group)

        val streams = JSONArray()
        channel.streams.take(8).forEach { stream ->
            val streamJson = JSONObject()
            streamJson.put("url", stream.url)
            streamJson.put("drm", stream.drmProtected)
            val headers = JSONObject()
            stream.headers.forEach { (key, value) -> headers.put(key, value) }
            streamJson.put("headers", headers)
            streams.put(streamJson)
        }
        root.put("streams", streams)

        val encoded = URLEncoder.encode(base64Encode(root.toString().toByteArray()), "UTF-8")
        return "$mainUrl/aktv/play?data=$encoded"
    }

    private fun decodeChannel(data: String): Channel {
        val encoded = data.queryParam("data") ?: data
        val jsonText = runCatching { base64Decode(URLDecoder.decode(encoded, "UTF-8")) }.getOrNull().orEmpty()
        val root = runCatching { JSONObject(jsonText) }.getOrNull()
            ?: return Channel("AKTV", null, "AKTV", emptyList())

        val streams = mutableListOf<StreamCandidate>()
        val streamArray = root.optJSONArray("streams") ?: JSONArray()
        for (i in 0 until streamArray.length()) {
            val item = streamArray.optJSONObject(i) ?: continue
            val headersJson = item.optJSONObject("headers") ?: JSONObject()
            val headers = mutableMapOf<String, String>()
            headersJson.keys().forEach { key ->
                val value = headersJson.optString(key).trim()
                if (value.isNotBlank()) headers[key] = value
            }
            val streamUrl = item.optString("url").trim()
            if (streamUrl.isHttpUrl()) {
                streams.add(
                    StreamCandidate(
                        url = streamUrl,
                        headers = headers,
                        drmProtected = item.optBoolean("drm", false),
                    )
                )
            }
        }

        return Channel(
            title = root.optString("title", "AKTV").ifBlank { "AKTV" },
            logo = root.optString("logo").takeIf { it.isNotBlank() && it.isHttpUrl() },
            group = root.optString("group", "AKTV").ifBlank { "AKTV" },
            streams = streams,
        )
    }

    private fun emitHls(
        linkName: String,
        url: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        var emitted = 0
        runCatching {
            M3u8Helper.generateM3u8(linkName, url, referer = referer, headers = headers).forEach { link ->
                emitted++
                callback.invoke(link)
            }
        }

        if (emitted == 0) {
            callback.invoke(
                newExtractorLink(name, linkName, url, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = referer
                    this.headers = headers
                }
            )
            emitted = 1
        }
        return emitted
    }

    private fun emitDash(
        linkName: String,
        url: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        callback.invoke(
            newExtractorLink(name, linkName, url, ExtractorLinkType.DASH) {
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = headers
            }
        )
        return 1
    }

    private fun emitDirect(
        linkName: String,
        url: String,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ): Int {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = linkName,
                url = url,
                type = if (url.contains(".mp4", ignoreCase = true) || url.contains(".ts", ignoreCase = true)) {
                    ExtractorLinkType.VIDEO
                } else {
                    INFER_TYPE
                },
            ) {
                this.quality = Qualities.Unknown.value
                this.referer = referer
                this.headers = headers
            }
        )
        return 1
    }

    private fun Map<String, String>.withDefaultStreamHeaders(): Map<String, String> {
        val normalized = mutableMapOf<String, String>()
        forEach { (key, value) ->
            val canonical = when (key.lowercase()) {
                "user-agent", "ua" -> "User-Agent"
                "referer", "referrer", "http-referrer", "http-referer" -> "Referer"
                "origin" -> "Origin"
                else -> key
            }
            if (value.isNotBlank()) normalized[canonical] = value
        }
        if (!normalized.containsKey("User-Agent")) normalized["User-Agent"] = fallbackStreamUserAgent
        return normalized
    }

    private fun String.cleanPlaylistLine(): String = this
        .replace("\uFEFF", "")
        .replace("&amp;", "&")
        .trim()
        .trim { it == '*' || it.isWhitespace() }
        .trim()

    private fun String.cleanChannelTitle(): String = this
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "AKTV Channel" }

    private fun String.normalizeGroupName(): String = this
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "AKTV" }
        .uppercase()
        .replace("NASIONAL TV", "INDONESIA TV")

    private fun String.toDisplayGroupName(): String {
        return when (this.uppercase()) {
            "INDONESIA TV" -> "Indonesia TV"
            "WORLD TV" -> "World TV"
            "WORLD SPORTS" -> "World Sports"
            else -> this.split(" ").joinToString(" ") { part ->
                if (part.any { it.isLetter() }) part.lowercase().replaceFirstChar { it.uppercase() } else part
            }
        }
    }

    private fun String.attrValue(attr: String): String? {
        return Regex("""${Regex.escape(attr)}=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.optionValue(option: String): String? {
        val pattern = Regex("""${Regex.escape(option)}\s*=\s*(.+)$""", RegexOption.IGNORE_CASE)
        return pattern.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.streamHeaderValue(headerName: String): String? {
        return Regex("""${Regex.escape(headerName)}\s*=\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.parsePipeHeaders(): Map<String, String> {
        return split("&")
            .mapNotNull { part ->
                val key = part.substringBefore("=", "").trim()
                val value = part.substringAfter("=", "").trim()
                if (key.isBlank() || value.isBlank()) return@mapNotNull null
                val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
                val canonical = when (key.lowercase()) {
                    "referer", "referrer" -> "Referer"
                    "origin" -> "Origin"
                    "user-agent", "ua" -> "User-Agent"
                    else -> key
                }
                canonical to decoded
            }
            .toMap()
    }

    private fun String.queryParam(name: String): String? {
        return substringAfter("?", "")
            .split("&")
            .firstOrNull { it.substringBefore("=") == name }
            ?.substringAfter("=", "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun String.isLikelyPlayableStreamUrl(): Boolean {
        val lower = lowercase()
        if (!isHttpUrl()) return false
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif")) return false
        if (lower.contains("google.com") || lower.contains("doubleclick")) return false
        if (lower.contains("bit.ly/")) return false
        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains(".mp4") ||
            lower.contains(".ts") ||
            lower.contains("manifest") ||
            lower.contains("playlist") ||
            lower.contains("/live/") ||
            lower.contains("stream") ||
            lower.contains("short.gy") ||
            lower.contains("a1xs.vip")
    }

    private fun String.isBlockedStreamUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("/logo/offline.m3u8") ||
            lower.contains("glot.io/snippets/g0aw8b172c/raw") ||
            lower.contains("warning! | there might be a problem")
    }

    private fun String.isBadPlaylistResponse(): Boolean {
        val lower = lowercase()
        return isBlank() ||
            lower.contains("<title>not found</title>") ||
            lower.contains("warning! | there might be a problem with the requested link") ||
            (lower.contains("<html") && !lower.contains("#extinf")) ||
            !lower.contains("#extinf")
    }

    companion object {
        private const val CACHE_TTL_MS = 15 * 60 * 1000L
    }
}
