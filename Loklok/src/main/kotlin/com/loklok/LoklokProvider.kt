package com.loklok

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.loklok.LoklokUtils.apiGet
import com.loklok.LoklokUtils.apiPost
import com.loklok.LoklokUtils.parseUrlData

class LoklokProvider : MainAPI() {
    override var name = "Loklok"
    override var mainUrl = LoklokSeeds.H5_SITE
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val instantLinkLoading = true
    override val hasQuickSearch = true
    override val usesWebView = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(*LoklokSeeds.mainPageRows())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val safePage = page.coerceAtLeast(1)
        val rows = runCatching {
            when {
                request.data.startsWith(LoklokSeeds.Token.HOME) -> loadHomeRows(request.data, safePage)
                request.data.startsWith(LoklokSeeds.Token.SEARCH) -> loadSearchRow(request.name, request.data, safePage)
                else -> loadHomeRows("${LoklokSeeds.Token.HOME}0", safePage)
            }
        }.onFailure {
            Log.e(name, "homepage row failed for ${request.name}: ${it.message}")
        }.getOrNull().orEmpty()

        val safeRows = rows.ifEmpty { fallbackRows(request.name) }
        return newHomePageResponse(safeRows)
    }

    private suspend fun loadHomeRows(data: String, page: Int): List<HomePageList> {
        val baseIndex = data.removePrefix(LoklokSeeds.Token.HOME).toIntOrNull() ?: 0
        val candidates = listOf(
            (baseIndex + page - 1).coerceAtLeast(0),
            baseIndex.coerceAtLeast(0),
            0
        ).distinct()

        for (apiPage in candidates) {
            val rows = runCatching {
                val json = apiGet("${LoklokSeeds.ApiPath.HOME}?page=$apiPage")
                LoklokParser.parseHomeLists(this, parseJson(json))
            }.onFailure {
                Log.e(name, "home page=$apiPage failed: ${it.message}")
            }.getOrNull().orEmpty()
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    private suspend fun loadSearchRow(title: String, data: String, page: Int): List<HomePageList> {
        val query = data.removePrefix(LoklokSeeds.Token.SEARCH)
        val results = performSearch(query, page).orEmpty().take(40)
        return if (results.isEmpty()) emptyList() else listOf(HomePageList(title, results))
    }

    private fun fallbackRows(title: String): List<HomePageList> {
        val results = LoklokParser.parseFallbackResults(this, null)
        return if (results.isEmpty()) emptyList() else listOf(HomePageList(title.ifBlank { "Loklok Backup" }, results))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = performSearch(query, 1)

    override suspend fun search(query: String): List<SearchResponse>? = performSearch(query, 1)

    private suspend fun performSearch(query: String, page: Int = 1): List<SearchResponse>? {
        val bodyJson = mapOf(
            "searchKeyWord" to query,
            "size" to "50",
            "page" to page.coerceAtLeast(1).toString(),
            "sort" to "",
            "searchType" to "",
        ).toJson()

        val paths = listOf(LoklokSeeds.ApiPath.SEARCH_V2, LoklokSeeds.ApiPath.SEARCH_V1).distinct()
        for (path in paths) {
            val results = runCatching {
                val json = apiPost(path, bodyJson, useV2 = false)
                LoklokParser.parseSearchResults(this, parseJson(json))
            }.onFailure {
                Log.e(name, "search failed on $path: ${it.message}")
            }.getOrNull().orEmpty()
            if (results.isNotEmpty()) return results
        }

        val fallback = LoklokParser.parseFallbackResults(this, query)
        return fallback.ifEmpty { null }
    }

    override suspend fun load(url: String): LoadResponse? {
        val data = parseUrlData(url) ?: throw ErrorLoadingException("Unsupported Loklok load payload")
        val id = data.id?.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("Missing Loklok content id")
        val detail = runCatching {
            val json = apiGet("${LoklokSeeds.ApiPath.DETAIL}?id=$id&category=${data.category ?: 1}")
            parseJson<DetailResponse>(json)?.data
        }.onFailure {
            Log.e(name, "load failed: ${it.message}")
        }.getOrNull() ?: throw ErrorLoadingException("Failed to load Loklok details")

        return LoklokParser.parseLoadResponse(this, data, url, detail)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return LoklokExtractor.loadLinks(name, data, subtitleCallback, callback)
    }
}
