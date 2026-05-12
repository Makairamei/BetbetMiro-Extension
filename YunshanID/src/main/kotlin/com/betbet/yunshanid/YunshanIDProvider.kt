package com.betbet.yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class YunshanIDProvider : MainAPI() {

    override var mainUrl = "https://yunshanid.site"

    override var name = "YunshanID"

    override val hasMainPage = true

    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/donghuas" to "Donghua"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val json = app.get("$mainUrl/donghuas")
            .parsedSafe<List<YunshanMain>>()
            ?: return newHomePageResponse(
                request.name,
                emptyList()
            )

        val home = json.map {

            newAnimeSearchResponse(
                it.title,
                "$mainUrl/donghua/${it.id}",
                TvType.Anime
            ) {
                posterUrl = it.poster
            }
        }

        return newHomePageResponse(
            request.name,
            home
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val json = app.get("$mainUrl/donghuas")
            .parsedSafe<List<YunshanMain>>()
            ?: return emptyList()

        return json.filter {
            it.title.contains(query, ignoreCase = true)
        }.map {

            newAnimeSearchResponse(
                it.title,
                "$mainUrl/donghua/${it.id}",
                TvType.Anime
            ) {
                posterUrl = it.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {

        return newAnimeLoadResponse(
            "YunshanID",
            url,
            TvType.Anime
        ) {
            posterUrl = null
            plot = "Provider YunshanID"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return true
    }
}

data class YunshanMain(
    val id: Int,
    val title: String,
    val poster: String
)