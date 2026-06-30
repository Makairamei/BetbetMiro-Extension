package com.sad25kag.alqanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*

class Alqanime : MainAPI() {
    override var mainUrl = "https://alqanime.net"
    override var name = "Alqanime."
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val commonHeaders = providerHeaders(mainUrl)

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Rilisan Terbaru",
        "$mainUrl/advanced-search/page/%d/?status=completed&order=update" to "Selesai Tayang",
        "$mainUrl/advanced-search/page/%d/?type[]=movie&order=update" to "Film Layar Lebar",
        "$mainUrl/advanced-search/page/%d/?type[]=tv&order=update" to "TV",
        "$mainUrl/advanced-search/page/%d/?type[]=ova&order=update" to "OVA",
        "$mainUrl/advanced-search/page/%d/?type[]=ona&order=update" to "ONA",
        "$mainUrl/advanced-search/page/%d/?type[]=special&order=update" to "Special",
        "$mainUrl/advanced-search/page/%d/?type[]=bd&order=update" to "BD",
        "$mainUrl/advanced-search/page/%d/?type[]=donghua&order=update" to "Donghua",
        "$mainUrl/advanced-search/page/%d/?type[]=live-action&order=update" to "Live Action",
        "$mainUrl/tag/action/page/%d/" to "Action",
        "$mainUrl/tag/adventure/page/%d/" to "Adventure",
        "$mainUrl/tag/comedy/page/%d/" to "Comedy",
        "$mainUrl/tag/drama/page/%d/" to "Drama",
        "$mainUrl/tag/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/tag/isekai/page/%d/" to "Isekai",
        "$mainUrl/tag/romance/page/%d/" to "Romance",
        "$mainUrl/tag/school/page/%d/" to "School",
        "$mainUrl/tag/shounen/page/%d/" to "Shounen",
        "$mainUrl/tag/slice-of-life/page/%d/" to "Slice of Life",
        "$mainUrl/tag/supernatural/page/%d/" to "Supernatural",
        "$mainUrl/tag/mystery/page/%d/" to "Mystery",
        "$mainUrl/tag/horror/page/%d/" to "Horror",
        "$mainUrl/tag/sci-fi/page/%d/" to "Sci-Fi",
        "$mainUrl/tag/seinen/page/%d/" to "Seinen",
        "$mainUrl/tag/magic/page/%d/" to "Magic",
        "$mainUrl/tag/martial-arts/page/%d/" to "Martial Arts",
        "$mainUrl/tag/donghua/page/%d/" to "Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val list = loadMainPageEntries(request.data, page, commonHeaders)
        return newHomePageResponse(request, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return parseSearchPage("$mainUrl/?s=$query", commonHeaders)
    }

    override suspend fun load(url: String): LoadResponse? {
        return parseLoadPage(url, commonHeaders)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return resolveLoadLinks(
            sourceName = name,
            data = data,
            mainUrl = mainUrl,
            headers = commonHeaders,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }
}
