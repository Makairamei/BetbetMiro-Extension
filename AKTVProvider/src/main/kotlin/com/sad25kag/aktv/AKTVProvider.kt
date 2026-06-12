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
    // AKTV official website/base URL is not exposed in the protected APK.
    // Use the primary APK-extracted playlist host instead of a fabricated local placeholder.
    override var mainUrl = "https://glot.io"
    override var name = "AKTV"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Live)

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/123.0.0.0 Mobile Safari/537.36"

    private data class PlaylistSource(
        val id: String,
        val title: String,
        val url: String,
    )

    private data class Channel(
        val title: String,
        val streamUrl: String,
        val logo: String? = null,
        val group: String? = null,
        val sourceTitle: String,
        val sourceUrl: String,
    )

    /**
     * URLs extracted from AKTV.apk resources. The app dex is DPT protected, so these public
     * playlist/config endpoints are the usable source evidence without runtime HAR/logcat.
     */
    private val playlistSources = listOf(
        PlaylistSource("aktv_config", "AKTV Config", "https://glot.io/snippets/g0aw8b172c/raw"),
        PlaylistSource("gratis_99", "Gratis-99", "https://bit.ly/Gratis-99"),
        PlaylistSource("tv_kitkat", "TV KitKat", "https://bit.ly/TVKITKAT"),
        PlaylistSource("kl_free", "KL Free TV", "https://bit.ly/klfreeTlVI"),
    )

    override val mainPage = mainPageOf(
        playlistSources[0].id to playlistSources[0].title,
        playlistSources[1].id to playlistSources[1].title,
        playlistSources[2].id to playlistSources[2].title,
        playlistSources[3].id to playlistSources[3].title,
    )

    private fun requestHeaders() = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "*/*",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page != 1) return newHomePageResponse(emptyList())

        val source = playlistSources.firstOrNull { it.id == request.data } ?: playlistSources.first()
        val channels = fetchChannels(source)
        if (channels.isEmpty()) return newHomePageResponse(emptyList())

        val lists = channels
            .groupBy { it.group?.takeIf { group -> group.isNotBlank() } ?: source.title }
            .map { (group, items) ->
                HomePageList(
                    group,
                    items.distinctBy { it.streamUrl }.take(100).map { it.toSearchResponse() }
                )
            }
            .filter { it.list.isNotEmpty() }

        return newHomePageResponse(lists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return emptyList()

        return playlistSources
            .flatMap { fetchChannels(it) }
            .distinctBy { it.streamUrl }
            .filter { channel ->
                channel.title.lowercase().contains(normalizedQuery) ||
                    channel.group?.lowercase()?.contains(normalizedQuery) == true ||
                    channel.sourceTitle.lowercase().contains(normalizedQuery)
            }
            .take(60)
            .map { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val channel = decodeChannel(url)
        val displayTitle = channel.title.ifBlank { channel.sourceTitle }

        return newMovieLoadResponse(displayTitle, url, TvType.Live, encodeChannel(channel)) {
            this.posterUrl = channel.logo
            this.plot = buildString {
                append("Live channel from ").append(channel.sourceTitle)
                channel.group?.takeIf { it.isNotBlank() }?.let { append(" / ").append(it) }
            }
            this.tags = listOfNotNull(channel.group, channel.sourceTitle).filter { it.isNotBlank() }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val channel = decodeChannel(data)
        val streamUrl = channel.streamUrl.trim()
        if (!streamUrl.startsWith("http://", ignoreCase = true) &&
            !streamUrl.startsWith("https://", ignoreCase = true)
        ) return false

        val referer = channel.sourceUrl
        val headers = requestHeaders() + mapOf("Referer" to referer)
        val linkName = "${name} - ${channel.title}".trim()

        return when {
            streamUrl.contains(".m3u8", ignoreCase = true) -> {
                var emitted = 0
                M3u8Helper.generateM3u8(linkName, streamUrl, referer = referer, headers = headers).forEach { link ->
                    emitted++
                    callback.invoke(link)
                }
                emitted > 0
            }

            streamUrl.contains(".mpd", ignoreCase = true) -> {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = streamUrl,
                        type = ExtractorLinkType.DASH,
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = referer
                        this.headers = headers
                    }
                )
                true
            }

            else -> {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = streamUrl,
                        type = if (streamUrl.contains(".mp4", ignoreCase = true) || streamUrl.contains(".ts", ignoreCase = true)) {
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
                true
            }
        }
    }

    private suspend fun fetchChannels(source: PlaylistSource): List<Channel> {
        return runCatching {
            val text = app.get(source.url, headers = requestHeaders()).text
            parsePlaylist(text, source)
        }.getOrDefault(emptyList())
    }

    private fun parsePlaylist(text: String, source: PlaylistSource): List<Channel> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()

        return when {
            trimmed.startsWith("#EXTM3U", ignoreCase = true) || trimmed.contains("#EXTINF", ignoreCase = true) ->
                parseM3u(trimmed, source)

            trimmed.startsWith("{") || trimmed.startsWith("[") ->
                parseJson(trimmed, source)

            else ->
                parseLooseUrls(trimmed, source)
        }.distinctBy { it.streamUrl }
    }

    private fun parseM3u(text: String, source: PlaylistSource): List<Channel> {
        val channels = mutableListOf<Channel>()
        var lastTitle: String? = null
        var lastLogo: String? = null
        var lastGroup: String? = null

        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        lastTitle = line.substringAfterLast(",", "").trim().ifBlank { null }
                        lastLogo = line.attrValue("tvg-logo") ?: line.attrValue("logo")
                        lastGroup = line.attrValue("group-title") ?: line.attrValue("group")
                    }

                    line.startsWith("#") -> Unit

                    line.isHttpUrl() -> {
                        val title = lastTitle ?: line.substringAfterLast('/').substringBefore('?').ifBlank { source.title }
                        channels.add(
                            Channel(
                                title = title,
                                streamUrl = line,
                                logo = lastLogo,
                                group = lastGroup,
                                sourceTitle = source.title,
                                sourceUrl = source.url,
                            )
                        )
                        lastTitle = null
                        lastLogo = null
                        lastGroup = null
                    }
                }
            }

        return channels
    }

    private fun parseJson(text: String, source: PlaylistSource): List<Channel> {
        val channels = mutableListOf<Channel>()

        fun JSONObject.firstString(vararg keys: String): String? {
            for (key in keys) {
                val value = optString(key, "").trim()
                if (value.isNotBlank() && value != "null") return value
            }
            return null
        }

        fun visit(value: Any?, inheritedGroup: String? = null) {
            when (value) {
                is JSONObject -> {
                    val group = value.firstString("group", "group-title", "category", "kategori", "country") ?: inheritedGroup
                    val stream = value.firstString("url", "stream", "stream_url", "link", "file", "source", "playUrl")
                    if (stream?.isHttpUrl() == true) {
                        val title = value.firstString("name", "title", "channel", "chName", "label")
                            ?: stream.substringAfterLast('/').substringBefore('?').ifBlank { source.title }
                        val logo = value.firstString("logo", "tvg-logo", "image", "icon", "poster", "thumbnail")
                        channels.add(
                            Channel(
                                title = title,
                                streamUrl = stream,
                                logo = logo,
                                group = group,
                                sourceTitle = source.title,
                                sourceUrl = source.url,
                            )
                        )
                    }
                    value.keys().forEach { key -> visit(value.opt(key), group) }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) visit(value.opt(i), inheritedGroup)
                }

                is String -> {
                    if (value.isHttpUrl() && value.isPlayableUrl()) {
                        channels.add(
                            Channel(
                                title = value.substringAfterLast('/').substringBefore('?').ifBlank { source.title },
                                streamUrl = value,
                                group = inheritedGroup,
                                sourceTitle = source.title,
                                sourceUrl = source.url,
                            )
                        )
                    }
                }
            }
        }

        runCatching {
            if (text.trim().startsWith("[")) visit(JSONArray(text)) else visit(JSONObject(text))
        }

        return channels
    }

    private fun parseLooseUrls(text: String, source: PlaylistSource): List<Channel> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isHttpUrl() && it.isPlayableUrl() }
            .map { stream ->
                Channel(
                    title = stream.substringAfterLast('/').substringBefore('?').ifBlank { source.title },
                    streamUrl = stream,
                    sourceTitle = source.title,
                    sourceUrl = source.url,
                )
            }
            .toList()
    }

    private fun Channel.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(title, encodeChannel(this), TvType.Live) {
            this.posterUrl = logo
        }
    }

    private fun encodeChannel(channel: Channel): String {
        fun enc(value: String?) = URLEncoder.encode(value.orEmpty(), "UTF-8")
        return buildString {
            append(mainUrl).append("/play")
            append("?title=").append(enc(channel.title))
            append("&stream=").append(enc(channel.streamUrl))
            append("&logo=").append(enc(channel.logo))
            append("&group=").append(enc(channel.group))
            append("&sourceTitle=").append(enc(channel.sourceTitle))
            append("&sourceUrl=").append(enc(channel.sourceUrl))
        }
    }

    private fun decodeChannel(data: String): Channel {
        fun getParam(name: String): String? = runCatching {
            data.substringAfter("?", "")
                .split("&")
                .firstOrNull { it.substringBefore("=") == name }
                ?.substringAfter("=", "")
                ?.let { URLDecoder.decode(it, "UTF-8") }
        }.getOrNull()

        return Channel(
            title = getParam("title").orEmpty(),
            streamUrl = getParam("stream").orEmpty(),
            logo = getParam("logo")?.takeIf { it.isNotBlank() },
            group = getParam("group")?.takeIf { it.isNotBlank() },
            sourceTitle = getParam("sourceTitle")?.takeIf { it.isNotBlank() } ?: name,
            sourceUrl = getParam("sourceUrl")?.takeIf { it.isNotBlank() } ?: mainUrl,
        )
    }

    private fun String.attrValue(attr: String): String? {
        return Regex("""$attr=[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.isHttpUrl(): Boolean =
        startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

    private fun String.isPlayableUrl(): Boolean {
        val lower = lowercase()
        if (!isHttpUrl()) return false
        if (lower.contains("google.com") || lower.contains("doubleclick")) return false
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".mpd") ||
            lower.contains(".ts") ||
            lower.contains("/live") ||
            lower.contains("stream")
    }
}
