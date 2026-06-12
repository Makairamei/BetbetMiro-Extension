package com.sad25kag.mynimeku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.net.URLEncoder

class MynimekuProvider : MainAPI() {
    override var mainUrl = MynimekuSeeds.MAIN_URL
    override var name = "Mynimeku"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(*MynimekuSeeds.MAIN_PAGE.toTypedArray())

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = MynimekuParser.pageUrl(request.data, page)
        val document = app.get(url, headers = MynimekuUtils.pageHeaders()).document
        val cards = MynimekuParser.cards(document).map { it.toSearchResponse() }
        if (cards.isEmpty()) throw ErrorLoadingException("Mynimeku category cards kosong")
        return newHomePageResponse(
            listOf(HomePageList(request.name, cards, isHorizontalImages = true)),
            hasNext = MynimekuParser.hasNextPage(document, page + 1)
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val words = query.lowercase().split(Regex("""\s+""")).filter { it.isNotBlank() }
        val document = runCatching {
            app.get("$mainUrl/?s=$encoded", headers = MynimekuUtils.pageHeaders()).document
        }.getOrNull()

        val results = document?.let { MynimekuParser.cards(it) }.orEmpty()
            .filter { card -> words.isEmpty() || words.all { card.title.lowercase().contains(it) } }
            .distinctBy { it.url }
        if (results.isNotEmpty()) return results.map { it.toSearchResponse() }

        val slug = query.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')
        if (slug.isBlank()) return emptyList()

        return listOf("$mainUrl/series/$slug/", "$mainUrl/anime/$slug/").mapNotNull { directUrl ->
            runCatching {
                val detail = app.get(directUrl, headers = MynimekuUtils.pageHeaders()).document
                val title = MynimekuParser.detailTitle(detail, directUrl)
                if (title.isBlank() || MynimekuUtils.isNoiseText(title)) null else MynimekuCard(
                    title = title,
                    url = directUrl,
                    posterUrl = MynimekuParser.poster(detail, directUrl),
                    tvType = MynimekuParser.detectType(directUrl, MynimekuParser.infoValue(detail, "Type"), title)
                )
            }.getOrNull()
        }.distinctBy { it.url }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = MynimekuUtils.normalizeUrl(url)
        val document = app.get(fixedUrl, headers = MynimekuUtils.pageHeaders()).document
        val title = MynimekuParser.detailTitle(document, fixedUrl).ifBlank {
            throw ErrorLoadingException("Judul Mynimeku tidak ditemukan")
        }
        val poster = MynimekuParser.poster(document, fixedUrl)
        val plot = MynimekuParser.description(document)
        val tags = MynimekuParser.tags(document)
        val year = MynimekuParser.year(document)
        val status = MynimekuParser.status(document)
        val typeText = MynimekuParser.infoValue(document, "Type")
        val type = MynimekuParser.detectType(fixedUrl, typeText, title)
        val episodes = MynimekuParser.episodes(document, fixedUrl)
        val recs = MynimekuParser.recommendations(document).map { it.toSearchResponse() }

        val isSeriesUrl = fixedUrl.contains("/series/", ignoreCase = true)
        if (episodes.isNotEmpty() || (isSeriesUrl && type != TvType.AnimeMovie)) {
            return newAnimeLoadResponse(title, fixedUrl, type) {
                posterUrl = poster
                backgroundPosterUrl = poster
                posterHeaders = MynimekuUtils.imageHeaders(fixedUrl)
                this.plot = plot
                this.tags = tags
                this.year = year
                showStatus = status
                this.recommendations = recs
                if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes)
            }
        }

        val players = MynimekuParser.players(document, fixedUrl)
        val data = MynimekuUtils.encodeBundle(fixedUrl, players).takeIf { players.isNotEmpty() } ?: fixedUrl
        return newMovieLoadResponse(title, fixedUrl, TvType.AnimeMovie, data) {
            posterUrl = poster
            backgroundPosterUrl = poster
            posterHeaders = MynimekuUtils.imageHeaders(fixedUrl)
            this.plot = plot
            this.tags = tags
            this.year = year
            this.recommendations = recs
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return MynimekuExtractor.loadLinks(data, subtitleCallback, callback)
    }

    private fun MynimekuCard.toSearchResponse(): SearchResponse {
        return newAnimeSearchResponse(title, url, tvType) {
            posterUrl = posterUrl
            posterHeaders = MynimekuUtils.imageHeaders(url)
            episode?.let { addSub(it) }
        }
    }
}
