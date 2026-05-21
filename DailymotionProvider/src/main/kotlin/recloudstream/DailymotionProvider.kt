package recloudstream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URLEncoder

class DailymotionProvider : MainAPI() {

    override var mainUrl = "https://www.dailymotion.com"
    override var name = "Dailymotion 📺"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Others)

    private val apiBase = "https://api.dailymotion.com"
    private val videoFields = listOf(
        "id",
        "title",
        "description",
        "thumbnail_360_url",
        "thumbnail_720_url",
        "duration",
        "channel",
        "created_time"
    ).joinToString(",")

    override val mainPage = mainPageOf(
        "sort=recent" to "Terbaru",
        "sort=visited" to "Paling Banyak Ditonton",

        "channel=news&sort=recent" to "Berita",
        "channel=sport&sort=recent" to "Olahraga",
        "channel=music&sort=recent" to "Musik",
        "channel=fun&sort=recent" to "Hiburan & Lucu",
        "channel=tv&sort=recent" to "TV",
        "channel=shortfilms&sort=recent" to "Film Pendek",
        "channel=videogames&sort=recent" to "Gaming",
        "channel=tech&sort=recent" to "Teknologi",
        "channel=travel&sort=recent" to "Travel",
        "channel=animals&sort=recent" to "Hewan",
        "channel=auto&sort=recent" to "Otomotif",
        "channel=creation&sort=recent" to "Kreasi",
        "channel=lifestyle&sort=recent" to "Lifestyle",
        "channel=people&sort=recent" to "People",
        "channel=school&sort=recent" to "Edukasi",
        "channel=kids&sort=recent" to "Kids",
        "channel=webcam&sort=recent" to "Webcam"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val apiUrl = buildVideoListUrl(
            query = request.data,
            page = page,
            limit = 24
        )

        val videos = app.get(apiUrl, headers = apiHeaders)
            .text
            .let { tryParseJson<VideoSearchResponse>(it) }

        val home = videos?.list
            .orEmpty()
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            home,
            hasNext = videos?.hasMore == true || home.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val encodedQuery = query.urlEncoded()
        val apiUrl = buildVideoListUrl(
            query = "search=$encodedQuery&sort=relevance",
            page = 1,
            limit = 30
        )

        return app.get(apiUrl, headers = apiHeaders)
            .text
            .let { tryParseJson<VideoSearchResponse>(it) }
            ?.list
            .orEmpty()
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = url.extractDailymotionId()
            ?: throw ErrorLoadingException("ID video Dailymotion tidak ditemukan")

        val apiUrl = "$apiBase/video/$videoId?fields=id,title,description,thumbnail_720_url,thumbnail_360_url,duration,channel,created_time"
        val detail = app.get(apiUrl, headers = apiHeaders)
            .text
            .let { tryParseJson<VideoDetailResponse>(it) }
            ?: throw ErrorLoadingException("Detail video Dailymotion tidak ditemukan")

        return detail.toLoadResponse()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        return try {
            loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildVideoListUrl(query: String, page: Int, limit: Int): String {
        val safeQuery = query.trim().trimStart('&')
        return "$apiBase/videos?fields=$videoFields&limit=$limit&page=$page&$safeQuery"
    }

    private fun VideoItem.toSearchResponse(): SearchResponse? {
        val videoId = id?.takeIf { it.isNotBlank() } ?: return null
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: "Dailymotion Video"
        val watchUrl = "$mainUrl/video/$videoId"

        return newMovieSearchResponse(cleanTitle, watchUrl, TvType.Others) {
            posterUrl = thumbnail360Url ?: thumbnail720Url
        }
    }

    private suspend fun VideoDetailResponse.toLoadResponse(): LoadResponse {
        val videoId = id?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("ID video Dailymotion kosong")
        val watchUrl = "$mainUrl/video/$videoId"
        val cleanTitle = title?.trim()?.takeIf { it.isNotBlank() } ?: "Dailymotion Video"

        return newMovieLoadResponse(cleanTitle, watchUrl, TvType.Others, watchUrl) {
            posterUrl = thumbnail720Url ?: thumbnail360Url
            plot = description?.trim()?.takeIf { it.isNotBlank() }
            duration = duration?.let { seconds -> seconds / 60 }
            tags = listOfNotNull(channel?.trim()?.takeIf { it.isNotBlank() })
        }
    }

    private fun String.extractDailymotionId(): String? {
        return Regex("""(?:dailymotion\.com/video/|dai\.ly/)([A-Za-z0-9]+)""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?: substringAfter("/video/", "")
                .substringBefore("?")
                .substringBefore("_")
                .takeIf { it.isNotBlank() && !it.contains("/") }
            ?: takeIf { it.matches(Regex("""[A-Za-z0-9]+""")) }
    }

    private fun String.urlEncoded(): String = try {
        URLEncoder.encode(this, "UTF-8")
    } catch (_: Throwable) {
        this
    }

    private val apiHeaders = mapOf(
        "Accept" to "application/json,text/plain,*/*",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    data class VideoSearchResponse(
        @param:JsonProperty("list") val list: List<VideoItem>? = emptyList(),
        @param:JsonProperty("has_more") val hasMore: Boolean? = null,
        @param:JsonProperty("page") val page: Int? = null,
        @param:JsonProperty("limit") val limit: Int? = null,
        @param:JsonProperty("total") val total: Int? = null
    )

    data class VideoItem(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null,
        @param:JsonProperty("thumbnail_720_url") val thumbnail720Url: String? = null,
        @param:JsonProperty("duration") val duration: Int? = null,
        @param:JsonProperty("channel") val channel: String? = null,
        @param:JsonProperty("created_time") val createdTime: Long? = null
    )

    data class VideoDetailResponse(
        @param:JsonProperty("id") val id: String? = null,
        @param:JsonProperty("title") val title: String? = null,
        @param:JsonProperty("description") val description: String? = null,
        @param:JsonProperty("thumbnail_360_url") val thumbnail360Url: String? = null,
        @param:JsonProperty("thumbnail_720_url") val thumbnail720Url: String? = null,
        @param:JsonProperty("duration") val duration: Int? = null,
        @param:JsonProperty("channel") val channel: String? = null,
        @param:JsonProperty("created_time") val createdTime: Long? = null
    )
}
