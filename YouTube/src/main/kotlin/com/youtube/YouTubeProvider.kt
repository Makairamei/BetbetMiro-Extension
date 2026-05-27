package com.youtube

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
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.youtube.YouTubeUtils.channelVideosUrl
import com.youtube.YouTubeUtils.encode
import com.youtube.YouTubeUtils.headers
import com.youtube.YouTubeUtils.rssUrl
import com.youtube.YouTubeUtils.searchUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class YouTubeProvider : MainAPI() {
    override var mainUrl = YouTubeSeeds.MAIN_URL
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        *YouTubeSeeds.mainPage.map { it.data to it.name }.toTypedArray()
    )

    private suspend fun safeGet(url: String, referer: String = mainUrl): com.lagradost.nicehttp.NiceResponse? {
        return runCatching {
            app.get(
                url,
                referer = referer,
                headers = headers,
                timeout = 30L
            )
        }.getOrNull()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val category = YouTubeSeeds.mainPage.firstOrNull { it.name == request.name || it.data == request.data }
        val results = when (category?.mode) {
            YouTubeCategoryMode.Channel -> getChannelResults(category)
            YouTubeCategoryMode.Search -> getSearchResults(category.data)
            YouTubeCategoryMode.Trending -> getHtmlResults(category.data)
            null -> getHtmlResults(request.data)
        }
        return newHomePageResponse(listOf(HomePageList(request.name, results, category?.paged ?: false)))
    }

    private suspend fun getChannelResults(category: YouTubeCategory): List<SearchResponse> {
        if (category.channelId != null) {
            val rssDocument = safeGet(rssUrl(category.channelId))?.text?.let { xml ->
                Jsoup.parse(xml, "", Parser.xmlParser())
            }
            val rssResults = rssDocument?.let { YouTubeParser.parseRss(this, it) }.orEmpty()
            if (rssResults.isNotEmpty()) return rssResults
        }

        val videosUrl = channelVideosUrl(category.data)
        val htmlResults = getHtmlResults(videosUrl)
        if (htmlResults.isNotEmpty()) return htmlResults

        val channelDocument = safeGet(category.data)?.document
        val discoveredId = channelDocument
            ?.selectFirst("meta[itemprop=channelId], meta[property=og:url]")
            ?.attr("content")
            ?.let { Regex("""UC[A-Za-z0-9_-]{20,}""").find(it)?.value }
        if (discoveredId != null) {
            val rssDocument = safeGet(rssUrl(discoveredId))?.text?.let { xml -> Jsoup.parse(xml, "", Parser.xmlParser()) }
            return rssDocument?.let { YouTubeParser.parseRss(this, it) }.orEmpty()
        }
        return emptyList()
    }

    private suspend fun getSearchResults(query: String): List<SearchResponse> {
        return getHtmlResults(searchUrl(query))
    }

    private suspend fun getHtmlResults(url: String): List<SearchResponse> {
        val response = safeGet(url) ?: return emptyList()
        return YouTubeParser.parseHtml(this, response.text, url)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getSearchResults(query).ifEmpty {
            getHtmlResults("$mainUrl/results?search_query=${encode(query)}")
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = safeGet(url) ?: return null
        val meta = YouTubeParser.parseMeta(response.document, response.text, url) ?: return null
        val recommendations = YouTubeParser.parseRecommendations(this, response.text)
        return newMovieLoadResponse(meta.title, meta.url, TvType.Movie, meta.url) {
            posterUrl = meta.poster
            backgroundPosterUrl = meta.poster
            plot = meta.description
            tags = listOfNotNull(meta.channel, meta.published).plus(meta.tags).distinct()
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return YouTubeExtractor.loadLinks(data, subtitleCallback, callback)
    }
}
