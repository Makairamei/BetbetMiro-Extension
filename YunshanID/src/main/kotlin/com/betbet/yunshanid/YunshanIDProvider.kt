package com.betbet.yunshanid

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class YunshanIDProvider : MainAPI() {

    override var mainUrl = "https://yunshanid.site"
    override var name = "YunshanID"
    override var lang = "id"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/donghuas/page/" to "Latest Donghua",
        "$mainUrl/donghua-tamat/page/" to "Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("${request.data}$page/").document

        val items = doc.select("div.bs").mapNotNull {
            val title = it.selectFirst(".tt")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val img = it.selectFirst("img")?.attr("src")

            newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = img
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document

        return doc.select("div.bs").mapNotNull {
            val title = it.selectFirst(".tt")?.text() ?: return@mapNotNull null
            val link = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null

            newAnimeSearchResponse(title, link, TvType.Anime)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text() ?: "YunshanID"
        val poster = doc.selectFirst(".thumb img")?.attr("src")
        val desc = doc.selectFirst(".entry-content p")?.text()

        val episodes = doc.select("div.eplister li").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            Episode(a.attr("href"), it.text())
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = desc
            this.episodes = episodes.reversed()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document

        doc.select("select.mirror option").forEach {
            val raw = it.attr("value")

            val decoded = try {
                String(Base64.decode(raw, Base64.DEFAULT))
            } catch (e: Exception) {
                raw
            }

            if (decoded.startsWith("http")) {
                loadExtractor(decoded, data, subtitleCallback, callback)
            }
        }

        return true
    }
}