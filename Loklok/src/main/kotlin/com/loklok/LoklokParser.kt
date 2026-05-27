package com.loklok

import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.loklok.LoklokUtils.cleanText
import com.loklok.LoklokUtils.normalizeId
import com.loklok.LoklokUtils.proxyPoster

object LoklokParser {
    fun parseHomeLists(api: MainAPI, response: LoklokHomeResponse): List<HomePageList> {
        return response.data?.recommendItems.orEmpty()
            .filterNot { it.homeSectionType == "BLOCK_GROUP" }
            .filterNot { it.homeSectionType == "BANNER" }
            .mapNotNull { section ->
                val header = cleanText(section.homeSectionName).ifBlank { return@mapNotNull null }
                val media = section.media.orEmpty()
                    .mapNotNull { parseMediaItem(api, it) }
                    .distinctBy { it.url }
                    .take(40)
                if (media.isEmpty()) return@mapNotNull null
                HomePageList(header, media)
            }
    }

    fun parseSearchResults(api: MainAPI, response: LoklokSearchResponse): List<SearchResponse> {
        return response.data?.searchResults.orEmpty()
            .mapNotNull { parseMediaItem(api, it) }
            .distinctBy { it.url }
            .take(50)
    }

    fun parseMediaItem(api: MainAPI, item: MediaItem): SearchResponse? {
        val id = normalizeId(item.id) ?: return null
        val title = cleanText(item.title ?: item.name).ifBlank { return null }
        val category = item.category ?: item.domainType ?: 1
        val poster = proxyPoster(item.imageUrl ?: item.coverVerticalUrl ?: item.coverHorizontalUrl)
        val type = typeFrom(category, null, emptyList())
        val payload = UrlData(id, category).toJson()

        return when (type) {
            TvType.Movie -> api.newMovieSearchResponse(title, payload, TvType.Movie) {
                posterUrl = poster
                item.year?.let { year = it }
                score = Score.from10(item.score)
            }
            else -> api.newTvSeriesSearchResponse(title, payload, type) {
                posterUrl = poster
                item.year?.let { year = it }
                score = Score.from10(item.score)
            }
        }
    }

    suspend fun parseLoadResponse(
        api: MainAPI,
        sourceData: UrlData,
        rawUrl: String,
        detail: MediaDetail
    ): LoadResponse? {
        val title = cleanText(detail.name).ifBlank { return null }
        val actors = detail.starList?.mapNotNull { actor ->
            Actor(actor.localName ?: return@mapNotNull null, actor.image)
        }

        val episodePayloads = detail.episodeVo.orEmpty().mapNotNull { episode ->
            val epId = episode.id ?: return@mapNotNull null
            val definitions = episode.definitionList?.map { DefinitionRef(it.code, it.description) }.orEmpty()
            val subtitles = episode.subtitlingList?.map {
                SubtitleRef(it.languageAbbr, it.language, it.subtitlingUrl)
            }.orEmpty()
            val payload = EpisodeData(
                id = sourceData.id,
                category = sourceData.category,
                epId = epId,
                definitionList = definitions,
                subtitlingList = subtitles
            ).toJson()
            payload to episode.seriesNo
        }

        if (episodePayloads.isEmpty()) return null

        val recommendations = detail.likeList.orEmpty()
            .mapNotNull { parseMediaItem(api, it) }
            .distinctBy { it.url }
            .take(20)

        val type = typeFrom(sourceData.category, detail.areaList?.firstOrNull()?.id, detail.tagNameList.orEmpty())
        val poster = proxyPoster(detail.coverVerticalUrl)
        val background = proxyPoster(detail.coverHorizontalUrl) ?: detail.coverHorizontalUrl
        val tags = detail.tagNameList

        return if (type == TvType.Movie) {
            val movieData = episodePayloads.first().first
            api.newMovieLoadResponse(title, rawUrl, TvType.Movie, movieData) {
                posterUrl = poster
                backgroundPosterUrl = background
                year = detail.year
                plot = detail.introduction
                this.tags = tags
                score = Score.from10(detail.score)
                addActors(actors)
                this.recommendations = recommendations
            }
        } else {
            val episodes: List<Episode> = episodePayloads.map { (payload, seriesNo) ->
                api.newEpisode(payload) {
                    this.episode = seriesNo
                }
            }
            api.newTvSeriesLoadResponse(title, rawUrl, type, episodes) {
                posterUrl = poster
                backgroundPosterUrl = background
                year = detail.year
                plot = detail.introduction
                this.tags = tags
                score = Score.from10(detail.score)
                addActors(actors)
                this.recommendations = recommendations
            }
        }
    }

    private fun typeFrom(category: Int?, areaId: Int?, tags: List<String>): TvType {
        val normalizedTags = tags.map { it.lowercase() }
        return when {
            areaId == 44 && normalizedTags.any { it.contains("anime") } -> TvType.Anime
            normalizedTags.any { it.contains("anime") } -> TvType.Anime
            normalizedTags.any { it.contains("korea") || it.contains("drama") } -> TvType.AsianDrama
            category == 0 -> TvType.Movie
            else -> TvType.TvSeries
        }
    }
}
