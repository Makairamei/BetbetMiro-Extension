package com.duniafilm21

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import java.net.URI

class DuniaFilm21Provider : MainAPI() {
    override var mainUrl = DuniaFilm21Seed.MAIN_URL
    override var name = DuniaFilm21Seed.SITE_NAME
    override var lang = DuniaFilm21Seed.LANGUAGE
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 100L

    override val mainPage = mainPageOf(*DuniaFilm21Seed.mainPages.map { it.path to it.name }.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val candidates = DuniaFilm21Seed.mirrors.map { mirror -> DuniaFilm21Utils.pageUrl(request.data, page, mirror) }
        val pair = firstDocument(candidates) ?: return newHomePageResponse(request.name, emptyList())
        val cards = if (request.data == "/" && page <= 1) {
            DuniaFilm21Parser.parseHomeCards(this, pair.second)
        } else {
            DuniaFilm21Parser.parseCards(this, pair.second).take(36)
        }
        return newHomePageResponse(listOf(HomePageList(request.name, cards, cards.size >= 12)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val encoded = DuniaFilm21Utils.encode(query)
        val searchUrls = DuniaFilm21Seed.mirrors.flatMap { mirror ->
            listOf(
                "${mirror.trimEnd('/')}/?s=$encoded",
                "${mirror.trimEnd('/')}/search/$encoded/"
            )
        }
        for (url in searchUrls) {
            val doc = safeDocument(url) ?: continue
            val result = DuniaFilm21Parser.parseCards(this, doc, query)
            if (result.isNotEmpty()) return result.distinctBy { it.url }.take(60)
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val clean = DuniaFilm21Utils.absoluteUrl(mainUrl, url) ?: url
        val candidates = loadCandidates(clean)
        for (candidate in candidates) {
            val doc = safeDocument(candidate) ?: continue
            return DuniaFilm21Parser.parseLoad(this, candidate, doc)
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return DuniaFilm21Extractor.extract(data, subtitleCallback, callback)
    }

    private suspend fun firstDocument(urls: List<String>): Pair<String, Document>? {
        for (url in urls.distinct()) {
            val doc = safeDocument(url) ?: continue
            return url to doc
        }
        return null
    }

    private suspend fun safeDocument(url: String): Document? = runCatching {
        app.get(url, referer = mainUrl, headers = DuniaFilm21Utils.browserHeaders, timeout = 20L).document
    }.getOrNull()

    private fun loadCandidates(url: String): List<String> {
        val output = linkedSetOf(url)
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri != null) {
            val path = uri.rawPath.orEmpty().ifBlank { "/" }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            DuniaFilm21Seed.mirrors.forEach { mirror -> output += mirror.trimEnd('/') + path + query }
        }
        return output.toList()
    }
}
