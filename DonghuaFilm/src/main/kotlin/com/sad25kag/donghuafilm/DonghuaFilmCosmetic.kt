package com.sad25kag.donghuafilm

import com.lagradost.cloudstream3.ExtractorLink
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse

/**
 * Cosmetic-only wrapper for DonghuaFilm homepage.
 * Keeps detail, episode, and playback behavior delegated to DonghuaFilm untouched.
 */
class DonghuaFilmCosmetic : MainAPI() {
    private val delegate = DonghuaFilm()

    override var mainUrl = delegate.mainUrl
    override var name = delegate.name
    override val hasMainPage = true
    override val hasQuickSearch = delegate.hasQuickSearch
    override val hasDownloadSupport = delegate.hasDownloadSupport
    override var lang = delegate.lang
    override val supportedTypes = delegate.supportedTypes

    override val mainPage = mainPageOf(
        "anime/?order=update&status=&type=" to "New Donghua",
        "anime/?order=update&status=completed&type=" to "Completed",
        "anime/?order=popular&status=&type=" to "Popular",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/fanstasy/" to "Fantasy",
        "genres/historical/" to "Historical",
        "genres/martial-arts/" to "Martial Arts",
        "genres/romance/" to "Romance",
        "genres/sci-fi/" to "Sci-Fi",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = delegate.getMainPage(page, request)
        val cleaned = response.items.mapNotNull { homeList ->
            val list = homeList.list.filterNot { it.name.equals("Text Mode", ignoreCase = true) }
            if (list.isEmpty()) return@mapNotNull null
            HomePageList(homeList.name, list, homeList.isHorizontalImages)
        }
        return newHomePageResponse(cleaned, hasNext = response.hasNext == true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = delegate.quickSearch(query)

    override suspend fun search(query: String): List<SearchResponse> = delegate.search(query)

    override suspend fun load(url: String): LoadResponse = delegate.load(url)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean = delegate.loadLinks(data, isCasting, subtitleCallback, callback)
}
