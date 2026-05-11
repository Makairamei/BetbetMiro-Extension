package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Auratail : Anichin() {
    override var mainUrl              = "https://auratail.vip"
    override var name                 = "Donghua Auratail"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie,TvType.Anime)

        private val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

override val mainPage = mainPageOf(
        "" to "Home - Update Terbaru",           
        "page/2/" to "-----",  
        "?order=popular" to "Paling Populer"
        // "genre/ongoing/?order=update" to "Update Terbaru",
        // "genre/ongoing/?order=popular" to "Paling Populer",
        // "?s=&post_type=post" to "Semua Series",    // atau sesuaikan jika ada kategori khusus
        // "genre/movie/" to "Movies / Batch"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/page/$page").documentLarge
        val home     = document.select("article.bs, article.bsx, .listupd article")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
    val titleElement = selectFirst("a") ?: return null
    
    val title = titleElement.attr("title").ifBlank { 
        titleElement.selectFirst("h2, h3, .title")?.text()?.trim() ?: titleElement.text().trim()
    }.trim()
    
    if (title.isEmpty()) return null

    val href = fixUrl(titleElement.attr("href"))

    // Perbaikan utama: ambil gambar dengan prioritas lazy loading
    val posterElement = selectFirst("img")
    val posterUrl = when {
        posterElement == null -> null
        else -> {
            val src = posterElement.attr("src").ifBlank { 
                posterElement.attr("data-src").ifBlank { 
                    posterElement.attr("data-lazy-src") 
                } 
            }
            if (src.isNotBlank()) fixUrlNull(src) else null
        }
    }

    return newAnimeSearchResponse(title, href, TvType.Anime) {
        this.posterUrl = posterUrl
    }
}
    
override suspend fun load(url: String): LoadResponse {
    val doc = app.get(url).document

    val name = doc.selectFirst("h1.entry-title, .post-title h1, h1[itemprop=name], h1")
        ?.text()
        ?.trim() ?: "Unknown Title"

    val poster = doc.selectFirst("div.thumb img, img.poster, .wp-post-image, meta[property=og:image]")
        ?.let { elem ->
            elem.attr("src").ifBlank { elem.attr("data-src") }.ifBlank { elem.attr("data-lazy-src") }
                .ifBlank { elem.attr("content") }
        }?.trim()?.let { fixUrlNull(it) }

    // Ambil episodes (untuk series)
    val episodes = doc.select("div.eplister ul li, .episodelist ul li, #episode_related li, .episode-list li")
        .mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val epNumStr = el.selectFirst(".epl-num, .episode-number, span")?.text()?.trim() ?: ""
            val epNum = epNumStr.toIntOrNull() ?: Regex("""\d+""").find(epNumStr)?.value?.toIntOrNull()

            newEpisode(href) {
                this.name = "Episode ${epNum ?: "?"}"
                this.episode = epNum
            }
        }.reversed()  // Urut dari episode 1 ke terbaru

    // Deteksi movie vs series
    val isMovie = url.contains("/movie/", ignoreCase = true) ||
                  name.lowercase().contains("movie") ||
                  name.lowercase().contains("batch") ||
                  episodes.isEmpty()
    val description = doc.selectFirst("div.entry-content, meta[property=og:description]")?.text()?.trim()
    return if (isMovie) {
        newMovieLoadResponse(
            name = name,     
            url = url,
            type = TvType.AnimeMovie,
            dataUrl = url     
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = doc.select("a[rel=tag]").eachText()
                //this.tags = document.select("a[rel=tag]").eachText()
            // Tambahkan jika ada: this.plot = synopsis, this.tags = genres, dll
        }
    } else {
        newAnimeLoadResponse(
            name = name,     
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = poster
            this.plot = description
            this.tags = doc.select("a[rel=tag]").eachText()
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }
}
}
