// Gojodesu.kt
package com.gojodesu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Gojodesu : MainAPI() {
    override var mainUrl = "https://gojodesu.com"
    override var name = "Gojodesu🤖"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/%d/" to "Ongoing Anime",
        "$mainUrl/complete-anime/page/%d/" to "Complete Anime",
        "$mainUrl/anime-movie/page/%d/" to "Anime Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data.format(page)).document
        val home = doc.select("article").mapNotNull { article ->
            val a = article.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = article.selectFirst("h2")?.text() ?: article.attr("title")
            val poster = article.selectFirst("img")?.getImageAttr()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(listOf(HomePageList(request.name, home)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article").mapNotNull { article ->
            val a = article.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href")
            val title = article.selectFirst("h2")?.text() ?: article.attr("title")
            val poster = article.selectFirst("img")?.getImageAttr()
            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst("img.wp-post-image")?.getImageAttr()
        val description = doc.selectFirst("div.entry-content")?.text()
        
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        
        // Menggunakan loop standar (aman dari issue deprecated)
        val mirrorPages = doc.select("select.mirror option[value]:not([disabled])")
            .map { it.attr("value").trim() }
            .filter { it.isNotBlank() && !it.contains("Select Video Server", true) }

        for (page in mirrorPages) {
            val pageUrl = httpsify(page)
            val mDoc = app.get(pageUrl).document
            val mEmbed = mDoc.selectFirst("iframe")?.attr("src")?.let { httpsify(it) } ?: continue
            loadExtractor(mEmbed, pageUrl, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            else -> attr("abs:src")
        }
    }
}