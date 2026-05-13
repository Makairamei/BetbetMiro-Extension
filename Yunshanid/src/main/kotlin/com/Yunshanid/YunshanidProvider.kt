package com.Yunshanid

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class YunshanidProvider : MainAPI() {
    override var mainUrl = "https://yunshanid.site"
    override var name = "Yunshanid"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override suspend fun getMainPage(page: Int, request: HomePageRequest): HomePageResponse? {
        val document = app.get(mainUrl).document
        val homePageLists = mutableListOf<HomePageList>()

        // Mengambil baris konten berdasarkan block di web
        document.select(".block").forEach { section ->
            val title = section.selectFirst(".title-resizer h2, .title-block h3")?.text() ?: "Terbaru"
            val items = section.select("article, .bs").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(title, items))
            }
        }

        return HomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article, .bs").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".tt, h2")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".poster img, .thumb img")?.attr("src")
        val plot = document.selectFirst(".entry-content p, .synopsis p")?.text()
        
        val episodes = document.select(".eplister li, .list-episode li").mapNotNull {
            val name = it.select(".epl-num, .ep-num").text() ?: "Episode"
            val epHref = it.select("a").attr("href") ?: return@mapNotNull null
            Episode(epHref, name)
        }

        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Cek iframe utama
        document.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotEmpty()) loadExtractor(src, data, subtitleCallback, callback)
        }

        // Cek link di tombol player/server
        document.select(".mirror-option option, .nav-tabs li a").forEach {
            val embedUrl = it.attr("value").ifEmpty { it.attr("data-embed") }
            if (embedUrl.startsWith("http")) loadExtractor(embedUrl, data, subtitleCallback, callback)
        }

        return true
    }
}
