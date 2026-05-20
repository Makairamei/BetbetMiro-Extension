package com.animexin

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

open class AnimexinProvider : MainAPI() {

    override var mainUrl = "https://animexin.dev"
    override var name = "Animexin"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/page/" to "Ongoing Anime",
        "$mainUrl/rilisan-donghua-terbaru/page/" to "Ongoing Donghua",
        "$mainUrl/movie-terbaru/page/" to "Movie",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/demon/" to "Demon",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/historical/" to "Historical",
        "$mainUrl/genres/martial-arts/" to "Martial Arts",
        "$mainUrl/genres/romance/" to "Romance",
        "$mainUrl/genres/supernatural/" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("div.listupd article, article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkElement = selectFirst(
            ".tt > h2 > a, h2.entry-title > a, h2 > a, h3 > a, a[rel=bookmark], a[href]"
        ) ?: return null

        val rawHref = fixUrlNull(
            linkElement.attr("href").ifBlank {
                selectFirst("a[href]")?.attr("href")
            }
        ) ?: return null

        val href = if (rawHref.contains("/anime/")) rawHref else "$mainUrl/anime/${rawHref.substringAfter("$mainUrl/")}"

        val rawTitle = listOfNotNull(
            selectFirst(".tt > h2")?.text(),
            selectFirst("h2.entry-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            linkElement.attr("title").takeIf { it.isNotBlank() },
            select("a[href]").map { it.text().trim() }.filter { it.isNotBlank() && it.lowercase() !in listOf("next","previous") }.maxByOrNull { it.length }
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim().removeSuffix("-").trim()

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(this.selectFirst("div.limit img, img.wp-post-image, img.attachment-post-thumbnail, img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document
        return document.select("div.listupd article, article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title")?.text()?.replace("Subtitle Indonesia", "")?.trim().orEmpty()
        val poster = document.selectFirst("div.entry-content > img")?.attr("src")
        val type = TvType.Anime
        val episodes = document.select("ul.daftar > li").map {
            val link = fixUrl(it.select("a").attr("href"))
            val name = it.select("a").text()
            val epNum = Regex("Episode\\s?(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) { this.episode = epNum }
        }.reversed()

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val servers = document.select(".mobius option, select option")

        servers.forEach { element ->
            val raw = element.attr("value").takeIf(String::isNotBlank) ?: return@forEach
            val decoded = runCatching { base64Decode(raw) }.getOrNull() ?: return@forEach

            val urls = Regex("""src=["']([^"']+)["']""").findAll(decoded)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .toList()

            urls.forEach { link ->
                if (!link.startsWith("http")) return@forEach
                loadExtractor(link, referer = mainUrl, subtitleCallback = subtitleCallback, callback = callback)
            }
        }

        return true
    }
}