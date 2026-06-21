package com.sad25kag.xiaomitvplus

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class XiaomiTVPlusProvider : MainAPI() {
    override var mainUrl = XIAOMI_WEB_HOST
    override var name = "XiaomiTVPlus"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)

    override val mainPage = mainPageOf(
        "all" to "All Channels",
        "featured" to "Featured",
        "news" to "News",
        "movies" to "Movies",
        "entertainment" to "Entertainment",
        "reality" to "Reality TV",
        "kids" to "Kids",
        "sports" to "Sports",
        "music" to "Music"
    )

    private val channelCache = ConcurrentHashMap<String, TvChannel>()
    @Volatile private var catalogLoaded = false

    private val apiHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json,text/plain,*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin" to XIAOMI_WEB_HOST,
        "Referer" to "$XIAOMI_WEB_HOST/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureCatalog()

        val channels = filterChannels(request.data)
        val start = ((page.coerceAtLeast(1) - 1) * PAGE_SIZE).coerceAtLeast(0)
        val pageItems = channels.drop(start).take(PAGE_SIZE).map { it.toSearchResponse() }

        return newHomePageResponse(
            HomePageList(request.name, pageItems, isHorizontalImages = true),
            hasNext = channels.size > start + PAGE_SIZE
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim().lowercase(Locale.ROOT)
        if (keyword.length < 2) return emptyList()

        ensureCatalog()
        return channelCache.values
            .filter { channel ->
                channel.title.lowercase(Locale.ROOT).contains(keyword) ||
                    channel.category.orEmpty().lowercase(Locale.ROOT).contains(keyword)
            }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .take(SEARCH_LIMIT)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureCatalog()

        val id = url.substringAfter("/channel/", "")
            .substringBefore("?")
            .trim()
            .ifBlank { url.substringAfterLast("/").trim() }

        val channel = channelCache[id]
            ?: channelCache.values.firstOrNull { it.providerUrl == url }
            ?: throw ErrorLoadingException("Channel not found: $url")

        return newLiveStreamLoadResponse(
            name = channel.title,
            url = channel.providerUrl,
            dataUrl = channel.toLoadData()
        ).apply {
            posterUrl = channel.logo
            plot = buildString {
                if (!channel.category.isNullOrBlank()) append(channel.category).append("\n")
                if (!channel.description.isNullOrBlank()) append(channel.description)
            }.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(DATA_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
        if (parts.isEmpty()) return false

        val channelId = parts.first()
        val explicitUrls = parts.drop(1)
        val candidates = buildList {
            addAll(explicitUrls)
            add(plutoStitcherUrl(channelId, false))
            add(plutoStitcherUrl(channelId, true))
        }.distinct().filter { it.startsWith("http", ignoreCase = true) }

        var emitted = false
        for (candidate in candidates.take(MAX_LINKS_PER_CHANNEL)) {
            val lower = candidate.lowercase(Locale.ROOT)
            val linkType = when {
                lower.contains(".m3u8") -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            val label = when {
                lower.contains(".m3u8") -> "HLS"
                lower.contains(".mpd") -> "DASH"
                else -> "Video"
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name ($label)",
                    url = candidate,
                    type = linkType
                ) {
                    quality = Qualities.Unknown.value
                    referer = PLUTO_BOOT_URL
                    headers = playbackHeaders()
                }
            )
            emitted = true
        }

        return emitted
    }

    private suspend fun ensureCatalog() {
        if (catalogLoaded) return
        synchronized(channelCache) {
            if (catalogLoaded) return
        }

        val channels = runCatching { loadCatalog() }
            .onFailure { logError(it) }
            .getOrDefault(emptyList())

        if (channels.isEmpty()) {
            throw ErrorLoadingException("Xiaomi TV+ catalog not resolved from APK endpoints. Need DNS/HAR if this region uses a different backend.")
        }

        synchronized(channelCache) {
            if (!catalogLoaded) {
                channels.distinctBy { it.id }.forEach { channelCache[it.id] = it }
                catalogLoaded = true
            }
        }
    }

    private suspend fun loadCatalog(): List<TvChannel> {
        val results = mutableListOf<TvChannel>()
        for (url in catalogUrls()) {
            val text = runCatching {
                app.get(url, headers = apiHeaders, referer = "$XIAOMI_WEB_HOST/", timeout = 25L).text
            }.getOrNull() ?: continue

            val root = parseJson(text) ?: continue
            results += findChannelObjects(root).mapNotNull { it.toTvChannel() }
        }
        return results.distinctBy { it.id }.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun filterChannels(category: String): List<TvChannel> {
        val all = channelCache.values.toList().sortedBy { it.title.lowercase(Locale.ROOT) }
        if (category == "all") return all

        return all.filter { channel ->
            val haystack = listOfNotNull(channel.category, channel.title, channel.description)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            when (category) {
                "featured" -> channel.featured || haystack.contains("featured") || haystack.contains("recommended")
                else -> haystack.contains(category)
            }
        }.ifEmpty { all }
    }

    private fun findChannelObjects(root: JsonElement): List<JsonObject> {
        val found = mutableListOf<JsonObject>()

        fun walk(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    if (obj.looksLikeChannel()) found += obj
                    obj.entrySet().forEach { walk(it.value) }
                }
                element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
            }
        }

        walk(root)
        return found
    }

    private fun JsonObject.looksLikeChannel(): Boolean {
        val id = directString(ID_KEYS)
        val title = directString(TITLE_KEYS)
        if (id.isNullOrBlank() || title.isNullOrBlank()) return false
        if (title.equals("channel", ignoreCase = true)) return false

        val keys = keySet().joinToString(" ").lowercase(Locale.ROOT)
        val hasChannelSignal = CHANNEL_SIGNAL_KEYS.any { keys.contains(it) }
        val hasPlayableSignal = firstMediaUrl() != null || recursiveString(PLAYBACK_KEYS) != null
        return hasChannelSignal || hasPlayableSignal
    }

    private fun JsonObject.toTvChannel(): TvChannel? {
        val id = directString(ID_KEYS)?.trim()?.ifBlank { null } ?: return null
        val title = directString(TITLE_KEYS)?.trim()?.ifBlank { null } ?: return null
        val logo = recursiveImageUrl()
        val description = directString(DESCRIPTION_KEYS) ?: recursiveString(DESCRIPTION_KEYS)
        val category = directString(CATEGORY_KEYS) ?: recursiveString(CATEGORY_KEYS)
        val playback = firstMediaUrl() ?: recursiveString(PLAYBACK_KEYS)?.takeIf { it.startsWith("http", true) }
        val featured = recursiveString(listOf("featured", "recommended"))?.equals("true", true) == true

        return TvChannel(
            id = id,
            title = title,
            logo = logo,
            description = description,
            category = category,
            playbackUrl = playback,
            featured = featured
        )
    }

    private fun TvChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(
            name = title,
            url = providerUrl,
            type = TvType.Live
        ).apply {
            posterUrl = logo
        }
    }

    private fun TvChannel.toLoadData(): String {
        return listOfNotNull(id, playbackUrl).joinToString(DATA_SEPARATOR)
    }

    private fun JsonObject.directString(keys: List<String>): String? {
        for (key in keys) {
            val value = get(key).asCleanString()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun JsonObject.recursiveString(keys: List<String>): String? {
        fun walk(element: JsonElement?): String? {
            if (element == null || element.isJsonNull) return null
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                obj.directString(keys)?.let { return it }
                obj.entrySet().forEach { entry -> walk(entry.value)?.let { return it } }
            } else if (element.isJsonArray) {
                element.asJsonArray.forEach { child -> walk(child)?.let { return it } }
            }
            return null
        }
        return walk(this)
    }

    private fun JsonObject.firstMediaUrl(): String? {
        val candidates = mutableListOf<String>()

        fun walk(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element.isJsonPrimitive -> {
                    val value = element.asCleanString().orEmpty()
                    if (value.startsWith("http", true) && MEDIA_URL_HINTS.any { value.contains(it, true) }) {
                        candidates += value
                    }
                }
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { walk(it.value) }
                element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
            }
        }

        walk(this)
        return candidates.firstOrNull()
    }

    private fun JsonObject.recursiveImageUrl(): String? {
        val candidates = mutableListOf<String>()

        fun walk(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element.isJsonPrimitive -> {
                    val value = element.asCleanString().orEmpty()
                    if (value.startsWith("http", true) && IMAGE_URL_HINTS.any { value.contains(it, true) }) {
                        candidates += value
                    }
                }
                element.isJsonObject -> element.asJsonObject.entrySet().forEach { walk(it.value) }
                element.isJsonArray -> element.asJsonArray.forEach { walk(it) }
            }
        }

        walk(this)
        return candidates.firstOrNull()
    }

    private fun JsonElement?.asCleanString(): String? {
        if (this == null || isJsonNull || !isJsonPrimitive) return null
        return runCatching { asJsonPrimitive.asString.trim() }.getOrNull()?.ifBlank { null }
    }

    @Suppress("DEPRECATION")
    private fun parseJson(text: String): JsonElement? {
        return runCatching { JsonParser().parse(text) }.getOrNull()
    }

    private fun catalogUrls(): List<String> {
        val common = mapOf(
            "appName" to "mitvplus",
            "appVersion" to APK_VERSION,
            "deviceMake" to "Xiaomi",
            "deviceModel" to "MiTV",
            "deviceType" to "tv",
            "deviceVersion" to "11",
            "clientID" to clientId,
            "deviceId" to clientId,
            "sid" to sessionId,
            "serverSideAds" to "false",
            "deviceDNT" to "1"
        )

        val web = common + mapOf(
            "appName" to "web",
            "deviceMake" to "Chrome",
            "deviceModel" to "web",
            "deviceType" to "web"
        )

        return listOf(
            "$PLUTO_BOOT_URL?${common.toQueryString()}",
            "$PLUTO_BOOT_URL?${web.toQueryString()}",
            PLUTO_CHANNELS_URL
        )
    }

    private fun plutoStitcherUrl(channelId: String, embed: Boolean): String {
        val path = if (embed) "embed/hls" else "hls"
        val params = mapOf(
            "appName" to "mitvplus",
            "appVersion" to APK_VERSION,
            "deviceMake" to "Xiaomi",
            "deviceModel" to "MiTV",
            "deviceType" to "tv",
            "deviceVersion" to "11",
            "clientID" to clientId,
            "deviceId" to clientId,
            "sid" to sessionId,
            "serverSideAds" to "false",
            "deviceDNT" to "1"
        )
        return "$PLUTO_STITCHER_URL/v1/stitch/$path/channel/${channelId.urlEncode()}/master.m3u8?${params.toQueryString()}"
    }

    private fun playbackHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/vnd.apple.mpegurl,application/x-mpegURL,application/dash+xml,video/*,*/*",
            "Origin" to XIAOMI_WEB_HOST,
            "Referer" to "$XIAOMI_WEB_HOST/"
        )
    }

    private val TvChannel.providerUrl: String
        get() = "$mainUrl/channel/${id.urlEncode()}"

    private fun Map<String, String>.toQueryString(): String {
        return entries.joinToString("&") { (key, value) -> "${key.urlEncode()}=${value.urlEncode()}" }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")

    private data class TvChannel(
        val id: String,
        val title: String,
        val logo: String?,
        val description: String?,
        val category: String?,
        val playbackUrl: String?,
        val featured: Boolean
    )

    companion object {
        private const val APK_VERSION = "3.9.7"
        private const val XIAOMI_WEB_HOST = "https://global.mitvplus.mi.com"
        private const val PLUTO_BOOT_URL = "https://boot.pluto.tv/v4/start"
        private const val PLUTO_CHANNELS_URL = "https://api.pluto.tv/v2/channels"
        private const val PLUTO_STITCHER_URL = "https://service-stitcher.clusters.pluto.tv"
        private const val DATA_SEPARATOR = "\u001F"
        private const val PAGE_SIZE = 48
        private const val SEARCH_LIMIT = 80
        private const val MAX_LINKS_PER_CHANNEL = 3

        private val clientId = UUID.randomUUID().toString()
        private val sessionId = UUID.randomUUID().toString()

        private val ID_KEYS = listOf("id", "_id", "channelId", "channelID", "channel_id", "slug")
        private val TITLE_KEYS = listOf("name", "title", "displayName", "channelName", "channel_name")
        private val DESCRIPTION_KEYS = listOf("description", "summary", "synopsis", "shortDescription", "longDescription")
        private val CATEGORY_KEYS = listOf("category", "genre", "group", "classification", "type")
        private val PLAYBACK_KEYS = listOf("streamUrl", "stream_url", "playbackUrl", "playback_url", "hls", "hlsUrl", "hls_url", "manifest", "manifestUrl", "url")
        private val CHANNEL_SIGNAL_KEYS = listOf("channel", "stream", "hls", "epg", "logo", "image", "genre")
        private val MEDIA_URL_HINTS = listOf(".m3u8", ".mpd", ".mp4", "master.m3u", "playlist")
        private val IMAGE_URL_HINTS = listOf(".png", ".jpg", ".jpeg", ".webp", "logo", "poster", "image")
    }
}
