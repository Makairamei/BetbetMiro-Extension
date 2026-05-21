package com.Dramabox

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

class Dramabox : MainAPI() {
    override var mainUrl = "https://www.dramabox.com/in"
    private val apiUrl = "https://db.hafizhibnusyam.my.id"
    override var name = "DramaBox"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        // Official API sections
        "/api/dramas/indo" to "Drama Dub Indo",
        "/api/dramas/trending" to "Trending",
        "/api/dramas/must-sees" to "Must Sees",
        "/api/dramas/hidden-gems" to "Hidden Gems",

        // Curated search sections, useful because DramaBox API does not expose a genre list endpoint.
        "search:Cinta" to "Romance Indonesia",
        "search:Romance" to "Romance",
        "search:Love" to "Love Story",
        "search:Pernikahan" to "Pernikahan",
        "search:Marriage" to "Marriage",
        "search:Kontrak" to "Marriage Contract",
        "search:Balas Dendam" to "Balas Dendam",
        "search:Revenge" to "Revenge",
        "search:CEO" to "CEO",
        "search:Boss" to "Boss",
        "search:Billionaire" to "Billionaire",
        "search:Mafia" to "Mafia",
        "search:Keluarga" to "Keluarga",
        "search:Family" to "Family",
        "search:Baby" to "Baby",
        "search:Istri" to "Istri",
        "search:Wife" to "Wife",
        "search:Suami" to "Suami",
        "search:Husband" to "Husband",
        "search:Putri" to "Princess",
        "search:Fantasy" to "Fantasy",
        "search:Werewolf" to "Werewolf",
        "search:Sekolah" to "School"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val safePage = if (page < 1) 1 else page
        val response = if (request.data.startsWith("search:")) {
            fetchSearchList(request.data.removePrefix("search:").trim(), safePage)
        } else {
            fetchDramaList(request.data, safePage)
        }

        val items = response?.data
            .orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        return newHomePageResponse(
            HomePageList(request.name, items, false),
            hasNext = response?.meta?.pagination?.hasMore ?: items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val response = fetchSearchList(keyword, page = 1, size = 50)
        return response?.data
            .orEmpty()
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val dramaId = url.substringAfterLast("_").substringBefore("?").trim()
        if (dramaId.isBlank()) throw ErrorLoadingException("ID DramaBox tidak ditemukan")

        val drama = fetchDramaDetail(dramaId) ?: throw ErrorLoadingException("Drama tidak ditemukan")
        val episodeCount = drama.episodeCount ?: inferEpisodeCount(dramaId)
        if (episodeCount <= 0) throw ErrorLoadingException("Episode tidak ditemukan")

        val cleanName = cleanTitle(drama.title ?: "DramaBox")
        val episodes = (1..episodeCount).map { ep ->
            newEpisode(LoadData(bookId = dramaId, episodeNo = ep).toJson()) {
                this.name = "Episode $ep"
                this.episode = ep
                this.posterUrl = drama.coverImage
            }
        }

        return newTvSeriesLoadResponse(cleanName, "$mainUrl/drama/_$dramaId", TvType.AsianDrama, episodes) {
            this.posterUrl = drama.coverImage
            this.plot = drama.introduction
            this.tags = drama.tags?.mapNotNull { it.trim().takeIf { tag -> tag.isNotBlank() } }?.distinct()
            this.recommendations = fetchRelatedRecommendations(drama)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        val dramaId = parsed.bookId?.trim()?.takeIf { it.isNotBlank() } ?: return false
        val episodeNo = parsed.episodeNo ?: return false

        val chapter = fetchChapterForEpisode(dramaId, episodeNo)
        val streams = chapter?.streamUrl.orEmpty()
            .filter { it.url?.isNotBlank() == true }
            .distinctBy { it.url }
            .sortedByDescending { it.quality ?: 0 }

        if (streams.isEmpty()) return false

        streams.forEach { stream ->
            val streamUrl = stream.url ?: return@forEach
            val quality = qualityFromNumber(stream.quality)
            callback.invoke(
                newExtractorLink(
                    name,
                    "DramaBox ${stream.quality?.let { "${it}p" } ?: "Auto"}",
                    streamUrl,
                    if (streamUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to "https://www.dramabox.com",
                        "User-Agent" to USER_AGENT
                    )
                }
            )
        }
        return true
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("\\((Sulih Suara|Dub Indo|Indonesian Sub|Sub Indo)\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    private fun DramaItem.toSearchResult(): SearchResponse? {
        val dramaId = id?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val cleanName = cleanTitle(title ?: "DramaBox").ifBlank { "DramaBox" }

        return newTvSeriesSearchResponse(cleanName, "$mainUrl/drama/_$dramaId", TvType.AsianDrama) {
            this.posterUrl = coverImage
        }
    }

    private suspend fun fetchDramaList(path: String, page: Int, size: Int = 24): DramaListResponse? {
        return try {
            val base = if (path.startsWith("http", true)) path else "$apiUrl$path"
            val url = "$base${if (base.contains("?")) "&" else "?"}page=$page&size=$size"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            tryParseJson<DramaListResponse>(body)
        } catch (e: Exception) {
            logError("Dramabox", "fetchDramaList failed for path=$path page=$page", e)
            null
        }
    }

    private suspend fun fetchSearchList(keyword: String, page: Int, size: Int = 24): DramaListResponse? {
        return try {
            val encoded = URLEncoder.encode(keyword.trim(), "UTF-8")
            val url = "$apiUrl/api/search?keyword=$encoded&page=$page&size=$size"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            tryParseJson<DramaListResponse>(body)
        } catch (e: Exception) {
            logError("Dramabox", "fetchSearchList failed keyword=$keyword page=$page", e)
            null
        }
    }

    private suspend fun fetchDramaDetail(dramaId: String): DramaItem? {
        return try {
            val url = "$apiUrl/api/dramas/$dramaId"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            logDebug("Dramabox", "fetchDramaDetail[$dramaId] raw body: ${body.take(300)}")

            val wrapped = tryParseJson<DramaDetailResponse>(body)?.data
            if (wrapped?.id != null) return wrapped

            val direct = tryParseJson<DramaItem>(body)
            if (direct?.id != null) return direct

            logError("Dramabox", "fetchDramaDetail[$dramaId]: both parse formats returned null. Body: ${body.take(500)}")
            null
        } catch (e: Exception) {
            logError("Dramabox", "fetchDramaDetail failed for id=$dramaId", e)
            null
        }
    }

    private suspend fun fetchChapterForEpisode(dramaId: String, episodeNo: Int): ChapterContent? {
        return try {
            val url = "$apiUrl/api/chapters/video?book_id=$dramaId&episode=$episodeNo"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            val res = tryParseJson<ChapterResponse>(body) ?: return null
            val allChapters = res.data.orEmpty() + res.extras.orEmpty()

            allChapters.firstOrNull { it.chapterIndex?.toIntOrNull() == episodeNo }
                ?: allChapters.firstOrNull { it.streamUrl?.isNotEmpty() == true }
        } catch (e: Exception) {
            logError("Dramabox", "fetchChapterForEpisode failed id=$dramaId ep=$episodeNo", e)
            null
        }
    }

    private suspend fun inferEpisodeCount(dramaId: String): Int {
        return try {
            val url = "$apiUrl/api/chapters/video?book_id=$dramaId&episode=1"
            val body = executeWithRetry {
                rateLimitDelay(moduleName = "Dramabox")
                app.get(url, timeout = AutoUsedConstants.DEFAULT_TIMEOUT).text
            }
            val res = tryParseJson<ChapterResponse>(body) ?: return 0
            (res.data.orEmpty() + res.extras.orEmpty())
                .mapNotNull { it.chapterIndex?.toIntOrNull() }
                .maxOrNull() ?: 0
        } catch (e: Exception) {
            logError("Dramabox", "inferEpisodeCount failed for id=$dramaId", e)
            0
        }
    }

    private suspend fun fetchRelatedRecommendations(drama: DramaItem): List<SearchResponse> {
        val firstTag = drama.tags?.firstOrNull { it.isNotBlank() } ?: return emptyList()
        return fetchSearchList(firstTag, page = 1, size = 12)
            ?.data
            .orEmpty()
            .filterNot { it.id == drama.id }
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
            .take(12)
    }

    private fun qualityFromNumber(value: Int?): Int {
        return when (value) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1080.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            240 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    data class DramaListResponse(
        @JsonProperty("data") val data: List<DramaItem>? = null,
        @JsonProperty("meta") val meta: ResponseMeta? = null
    )

    data class DramaDetailResponse(
        @JsonProperty("data") val data: DramaItem? = null
    )

    data class DramaItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("cover_image") val coverImage: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null
    )

    data class ResponseMeta(
        @JsonProperty("pagination") val pagination: Pagination? = null
    )

    data class Pagination(
        @JsonProperty("has_more") val hasMore: Boolean? = null
    )

    data class ChapterResponse(
        @JsonProperty("data") val data: List<ChapterContent>? = null,
        @JsonProperty("extras") val extras: List<ChapterContent>? = null
    )

    data class ChapterContent(
        @JsonProperty("chapter_index") val chapterIndex: String? = null,
        @JsonProperty("stream_url") val streamUrl: List<StreamItem>? = null
    )

    data class StreamItem(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("url") val url: String? = null
    )

    data class LoadData(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("episodeNo") val episodeNo: Int? = null
    )
}
