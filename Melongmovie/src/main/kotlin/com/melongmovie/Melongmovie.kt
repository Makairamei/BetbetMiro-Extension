package com.melongmovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class Melongmovie : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "http://139.59.189.160"
    private var directUrl: String? = null
    override var name = "Melongmovie🪁"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/latest-movies/page/%d/" to "Movie Terbaru",
        "$mainUrl/advanced-search/page/%d/?order=latest&country%5B%5D=china&type%5B%5D=post" to "China",
        "$mainUrl/advanced-search/page/%d/?order=latest&country%5B%5D=korea&type%5B%5D=post" to "Korea"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.format(page)).document
        val home = doc.select("article, div.item").mapNotNull { article ->
            val a = article.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = article.selectFirst("h2")?.text() ?: a.attr("title")
            val poster = article.selectFirst("img")?.getImageAttr()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, home)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article, div.item").mapNotNull { article ->
            val a = article.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = article.selectFirst("h2")?.text() ?: a.attr("title")
            val poster = article.selectFirst("img")?.getImageAttr()
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst("img")?.getImageAttr()
        val description = doc.selectFirst("div.entry-content")?.text()
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var found = false

        doc.select("div.tab-content iframe, div.gmr-embed-responsive iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty()) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}