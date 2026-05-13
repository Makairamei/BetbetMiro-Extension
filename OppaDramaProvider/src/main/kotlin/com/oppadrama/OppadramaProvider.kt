package com.oppadrama

import com.lagradost.cloudstream3.*

class OppadramaProvider : MainAPI() {

    override var mainUrl = "http://45.11.57.199"
    override var name = "OppaDrama"
    override var lang = "id"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // 🔥 IMPORTANT: jangan pakai init heavy logic
    init {
        // kosongkan atau log ringan saja
    }

    override val mainPage = mainPageOf(
        "series/" to "Series",
        "movie/" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/${request.data}?page=$page").document

        val items = doc.select("article, a[href]").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = a.attr("title").ifBlank { a.text() }

            if (title.isBlank()) return@mapNotNull null

            newTvSeriesSearchResponse(title, href, TvType.TvSeries)
        }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("article, a[href]").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = a.attr("title").ifBlank { a.text() }

            if (title.isBlank()) return@mapNotNull null

            newTvSeriesSearchResponse(title, href, TvType.TvSeries)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text().orEmpty()
        val poster = doc.selectFirst("img")?.attr("src")

        val episodes = doc.select("a[href*='episode']").mapIndexedNotNull { i, a ->
            val link = fixUrl(a.attr("href"))

            newEpisode(link) {
                this.name = "Episode ${i + 1}"
                this.episode = i + 1
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        doc.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
}