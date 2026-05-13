package com.layarkaca

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI

class LayarKacaProvider : MainAPI() {

    override var mainUrl = "https://tv9.lk21official.cc"
    private var seriesUrl = "https://tv3.nontondrama.my"
    private var searchurl = "https://gudangvape.com"

    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Most Popular Movies",
        "$mainUrl/rating/page/" to "IMDb Rated",
        "$mainUrl/most-commented/page/" to "Most Commented",
        "$seriesUrl/latest-series/page/" to "Latest Series",
        "$mainUrl/latest/page/" to "Latest Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data + page).document

        val items = doc.select("article, li.slider article").mapNotNull {
            val title = it.selectFirst("h3, h3.poster-title")?.text() ?: return@mapNotNull null
            val href = fixUrl(it.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val poster = it.selectFirst("img")?.attr("src")

            val isSeries = it.selectFirst("span.episode") != null

            if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$searchurl/search.php?s=$query").text
        val results = mutableListOf<SearchResponse>()

        try {
            if (!res.trim().startsWith("{")) return results

            val json = JSONObject(res)
            val arr = json.getJSONArray("data")

            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)

                val title = item.getString("title")
                val slug = item.getString("slug")
                val type = item.getString("type")

                val poster = "https://poster.lk21.party/wp-content/uploads/" +
                        item.optString("poster")

                when (type) {
                    "series" -> results.add(
                        newTvSeriesSearchResponse(title, "$seriesUrl/$slug", TvType.TvSeries) {
                            this.posterUrl = poster
                        }
                    )

                    "movie" -> results.add(
                        newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                            this.posterUrl = poster
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("LayarKaca", "Search error: ${e.message}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1, .movie-info h1")?.text() ?: ""
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val description = doc.selectFirst(".synopsis, .meta-info")?.text()

        val tvType = if (doc.select("#season-data").isNotEmpty())
            TvType.TvSeries else TvType.Movie

        return if (tvType == TvType.TvSeries) {

            val episodes = mutableListOf<Episode>()

            val json = doc.selectFirst("script#season-data")?.data()
            if (json != null) {
                val root = JSONObject(json)

                root.keys().forEach { season ->
                    val arr = root.getJSONArray(season)

                    for (i in 0 until arr.length()) {
                        val ep = arr.getJSONObject(i)

                        val link = fixUrl(ep.getString("slug"))
                        val epNum = ep.optInt("episode_no")

                        episodes.add(
                            newEpisode(link) {
                                this.name = "Episode $epNum"
                                this.episode = epNum
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }

        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
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

        val links = doc.select("iframe, a")
            .mapNotNull { it.attr("src").ifEmpty { it.attr("href") } }
            .mapNotNull { fixUrlNull(it) }

        links.amap { url ->
            try {
                loadExtractor(
                    url,
                    getSafeBaseUrl(url),
                    subtitleCallback,
                    callback
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return true
    }

    private fun getSafeBaseUrl(url: String?): String {
        return try {
            val u = URI(url)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            mainUrl
        }
    }
}