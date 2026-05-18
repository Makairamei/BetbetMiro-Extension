package com.gomunime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class Gomunime : MainAPI() {
    override var mainUrl = "https://gomunime.top"
    override var name = "Gomunime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/anime/page/%d/?status=&type=&order=update" to "Terbaru",
        "$mainUrl/anime/page/%d/?status=ongoing&type=&order=update" to "Ongoing",
        "$mainUrl/anime/page/%d/?status=completed&type=&order=update" to "Completed",
        "$mainUrl/anime/page/%d/?status=&type=&order=popular" to "Popular",
        "$mainUrl/genres/action/page/%d/" to "Action",
        "$mainUrl/genres/fantasy/page/%d/" to "Fantasy",
        "$mainUrl/genres/isekai/page/%d/" to "Isekai"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // FIX #1: JURUS ANTI ANTI-404. Buang folder "page/%d/" khusus di halaman pertama!
        val url = if (page == 1) {
            request.data.replace("page/%d/", "").replace("//?", "/?")
        } else {
            request.data.format(page)
        }
        
        val document = app.get(url).document
        
        // Selector diperluas melimpah biar kebal badai update tema website
        val home = document.select("div.listupd div.bs, div.mangaip div.bs, .listupd .bsx, div.animposx, article.bs").mapNotNull { 
            it.toSearchResult() 
        }
        
        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = false
                )
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.tt h2, .tt h2, .tt, h2, title, .entry-title, .limit h2")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        
        // FIX #2: Amankan poster dari cengkraman lazy-load gambar hemat kuota
        val poster = fixUrlNull(
            this.selectFirst("img")?.let { img ->
                when {
                    img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> ""
                }
            }
        ) ?: ""
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.listupd div.bs, div.mangaip div.bs, .listupd .bsx, div.animposx, article.bs").mapNotNull { 
            it.toSearchResult() 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, .entry-title, h1")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: ""
        
        val poster = fixUrlNull(
            document.selectFirst("div.thumb img, .thumb img, .poster img")?.let { img ->
                when {
                    img.hasAttr("data-lazy-src") -> img.attr("data-lazy-src")
                    img.hasAttr("data-src") -> img.attr("data-src")
                    img.hasAttr("src") -> img.attr("src")
                    else -> ""
                }
            }
        ) ?: ""
        
        val type = getType(document.selectFirst(".spe span:contains(Type), .spe span:contains(Jenis), .info-content span:contains(Type)")?.text() ?: "")
        
        val episodes = document.getEpisodes(url)

        return if (type == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.showStatus = getStatus(document.selectFirst(".spe span:contains(Status), .info-content span:contains(Status)")?.text() ?: "")
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCaster: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadGomunimeLinks(data, subtitleCallback, callback)
    }

    private fun Document.getEpisodes(url: String): List<Episode> {
        val slug = runCatching {
            URI(url).path.trim('/').substringAfter("anime/")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()

        return this.select("a[href*=\"$slug-episode-\"], ul.episodios li a, div.eplister ul li a")
            .mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val epNum = Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE)
                    .find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(a.text())?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.episode = epNum
                    this.name = "Episode ${epNum ?: ""}".trim()
                }
            }
            .distinctBy { "${it.name}-${it.episode}" }
            .sortedBy { it.episode ?: Int.MAX_VALUE }
        }

    private fun getType(text: String): TvType = when {
        text.contains("movie", true) -> TvType.AnimeMovie
        text.contains("ova", true) || text.contains("special", true) -> TvType.OVA
        else -> TvType.Anime
    }

    private fun getStatus(text: String): ShowStatus = when {
        text.contains("ongoing", true) -> ShowStatus.Ongoing
        text.contains("completed", true) -> ShowStatus.Completed
        else -> ShowStatus.Completed
    }

    data class ServerOption(
        val name: String,
        val url: String
    )

    private fun fixUrlNull(url: String?): String? {
        return url?.let { fixUrl(it) }
    }
}