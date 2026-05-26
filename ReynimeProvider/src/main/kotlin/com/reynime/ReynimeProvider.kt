package com.reynime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ReynimeProvider : MainAPI() {
    override var mainUrl = "https://reynime.my.id"
    override var name = "Reynime"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Terbaru",
        "$mainUrl/ongoing/" to "Ongoing",
        "$mainUrl/completed/" to "Completed",
        "$mainUrl/movies/" to "Movies"
    )

    init {
        ReynimeSeeds.bootstrap()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val response = runCatching { app.get(url) }.getOrNull()
        val parsed = response?.document?.let { ReynimeParser.parseSearchItems(it, mainUrl) }.orEmpty()

        val items = if (parsed.isNotEmpty()) {
            parsed
        } else {
            ReynimeSeeds.all().map { seed ->
                newAnimeSearchResponse(seed.title, seed.url, TvType.Anime) {
                    this.posterUrl = seed.poster
                }
            }
        }

        return newHomePageResponse(HomePageList(request.name, items), hasNext = parsed.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.urlEncode()}"
        val response = runCatching { app.get(url) }.getOrNull()
        val parsed = response?.document?.let { ReynimeParser.parseSearchItems(it, mainUrl) }.orEmpty()
        if (parsed.isNotEmpty()) return parsed

        return ReynimeSeeds.all().filter { it.title.contains(query, true) }.map { seed ->
            newAnimeSearchResponse(seed.title, seed.url, TvType.Anime) {
                this.posterUrl = seed.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        return ReynimeParser.parseLoad(response.document, url)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return ReynimeExtractor.loadLinks(
            data = data,
            mainUrl = mainUrl,
            headers = mapOf("Referer" to mainUrl),
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}
