package com.sad25kag

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class DramaIndo : MainAPI() {
    override var mainUrl = "https://dramaindo.my"
    override var name = "DramaIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "" to "Drama Terbaru",
        "movie" to "Movie Korea",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildArchiveUrl(request.data, page), referer = mainUrl).document
        val cards = document.parseCards()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = cards,
                isHorizontalImages = false,
            ),
            hasNext = document.hasNextPage(page),
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim().replace(Regex("\\s+"), "+")
        val url = if (page <= 1) "$mainUrl/?s=$cleanQuery" else "$mainUrl/page/$page/?s=$cleanQuery"

        return app.get(url, referer = mainUrl)
            .document
            .parseCards()
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val pageTitle = document.pageTitle()
            ?: throw RuntimeException("DramaIndo: title tidak ditemukan")
        val poster = document.posterUrl()
        val plot = document.plotText()
        val genres = document.genreList()
        val type = document.infoValue("Type")?.cleanTitle()
        val isMovie = type.equals("Movie", ignoreCase = true) || (pageTitle.isMovieTitle() && !pageTitle.hasEpisodeNumber())
        val isEpisodePost = pageTitle.hasEpisodeNumber() && !isMovie
        val seriesTitle = when {
            isEpisodePost -> document.infoValue("Title")?.cleanSeriesTitle() ?: pageTitle.removeEpisodeSuffix()
            pageTitle.startsWith("Genre:", ignoreCase = true) -> pageTitle.substringAfter(":").cleanTitle()
            else -> pageTitle.cleanTitle()
        }
        val seriesUrl = if (isEpisodePost) document.findSeriesUrl(url, seriesTitle) else null

        if (isEpisodePost && seriesTitle.isNotBlank()) {
            val seeds = linkedSetOf<EpisodeSeed>()
            seeds.add(EpisodeSeed(pageTitle, url, poster, pageTitle.episodeNumber()))

            if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                runCatching {
                    app.get(seriesUrl, referer = url).document.parseEpisodeSeeds(seriesTitle)
                }.getOrDefault(emptyList()).forEach { seeds.add(it) }
            }

            document.parseEpisodeSeeds(seriesTitle).forEach { seeds.add(it) }

            val episodes = seeds
                .filter { it.url.isNotBlank() && it.title.belongsToSeries(seriesTitle) }
                .distinctBy { it.url }
                .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                .map { it.toEpisode(seriesTitle) }

            return newTvSeriesLoadResponse(
                seriesTitle.cleanTitle(),
                seriesUrl ?: url,
                TvType.AsianDrama,
                episodes,
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        val listingTitle = pageTitle.removePrefix("Genre:").cleanTitle()
        val listingEpisodes = document.parseEpisodeSeeds(listingTitle)
        if (listingEpisodes.isNotEmpty() && !isMovie) {
            return newTvSeriesLoadResponse(
                listingTitle,
                url,
                TvType.AsianDrama,
                listingEpisodes
                    .distinctBy { it.url }
                    .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                    .map { it.toEpisode(listingTitle) },
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = genres
            }
        }

        val movieData = document.extractPlayableLinks(url).firstOrNull()?.url ?: url
        return newMovieLoadResponse(pageTitle.cleanTitle(), url, TvType.Movie, movieData) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = genres
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val links = if (data.isExternalPlayerUrl()) {
            listOf(SourceLink(data, data.hostName(), null))
        } else {
            runCatching { app.get(data, referer = mainUrl).document.extractPlayableLinks(data) }.getOrDefault(emptyList())
        }

        var callbackCount = 0
        for (source in links) {
            val before = callbackCount
            runCatching {
                loadExtractor(source.url, data, subtitleCallback) { link ->
                    callbackCount++
                    callback(link)
                }
            }

            if (callbackCount == before && source.url.isDramaIndoStreamHost()) {
                DramaIndoStreamResolver.resolve(source.name, source.url, data) { link ->
                    callbackCount++
                    callback(link)
                }
            }
        }

        return callbackCount > 0
    }

    private fun buildArchiveUrl(path: String, page: Int): String {
        return if (path.isBlank()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            val cleanPath = path.trim('/').trim()
            if (page <= 1) "$mainUrl/$cleanPath/" else "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun Document.pageTitle(): String? {
        return selectFirst("h1.entry-title, h1[itemprop=name], h1")
            ?.text()
            ?.cleanTitle()
            ?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
            ?: selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.cleanTitle()
                ?.removeSuffix("⋆ Dramaindo")
                ?.cleanTitle()
    }

    private fun Document.parseCards(): List<SearchResponse> {
        val cards = select("article.item, article.item-infinite, article[class*=item]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        if (cards.isNotEmpty()) return cards

        return select("article, div[class*=post], div[class*=movie], div[class*=item]")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst(".entry-title a[href], h1 a[href], h2 a[href], h3 a[href]")
            ?: select("a[href]").firstOrNull { link ->
                val text = link.text().cleanTitle().ifBlank { link.attr("title").cleanTitle() }
                text.isNotBlank() && !text.isGenericNavigationText()
            }
            ?: return null

        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || href.isBadInternalUrl()) return null

        val title = anchor.text().ifBlank { anchor.attr("title") }.cleanTitle()
        if (title.isBlank() || title.isGenericNavigationText()) return null

        val poster = selectFirst("img")?.imageUrl()
        val tvType = if (title.isMovieTitle()) TvType.Movie else TvType.AsianDrama

        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    private fun Document.parseEpisodeSeeds(seriesTitle: String): List<EpisodeSeed> {
        val cards = select("article.item, article.item-infinite, article[class*=item]")
            .mapNotNull { it.toEpisodeSeed(seriesTitle) }
            .distinctBy { it.url }
        if (cards.isNotEmpty()) return cards

        return select(".entry-title a[href], h1 a[href], h2 a[href], h3 a[href]")
            .mapNotNull { anchor ->
                val title = anchor.text().ifBlank { anchor.attr("title") }.cleanTitle()
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!href.startsWith(mainUrl) || href.isBadInternalUrl() || !title.hasEpisodeNumber()) return@mapNotNull null
                if (!title.belongsToSeries(seriesTitle)) return@mapNotNull null
                EpisodeSeed(title, href, anchor.closest("article")?.selectFirst("img")?.imageUrl(), title.episodeNumber())
            }
            .distinctBy { it.url }
    }

    private fun Element.toEpisodeSeed(seriesTitle: String): EpisodeSeed? {
        val anchor = selectFirst(".entry-title a[href], h1 a[href], h2 a[href], h3 a[href]")
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val title = anchor.text().ifBlank { anchor.attr("title") }.cleanTitle()
        if (!href.startsWith(mainUrl) || href.isBadInternalUrl() || !title.hasEpisodeNumber()) return null
        if (!title.belongsToSeries(seriesTitle)) return null

        return EpisodeSeed(
            title = title,
            url = href,
            poster = selectFirst("img")?.imageUrl(),
            episode = title.episodeNumber(),
        )
    }

    private fun Document.extractPlayableLinks(pageUrl: String): List<SourceLink> {
        val sources = linkedSetOf<SourceLink>()

        // Source-first resolver: read whatever player/server value the website exposes,
        // decode it if necessary, parse iframe/player URL, then pass it to loadExtractor().
        select("option[value]").forEachIndexed { index, option ->
            option.attr("value").toPlayerUrls(pageUrl).forEach { href ->
                sources.add(SourceLink(href, option.text().cleanText().ifBlank { "Server ${index + 1}" }, null))
            }
        }

        select("[data-src], [data-url], [data-embed], [data-iframe], [data-link], [data-video], [data-file]").forEachIndexed { index, element ->
            val value = element.attr("data-src")
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-embed") }
                .ifBlank { element.attr("data-iframe") }
                .ifBlank { element.attr("data-link") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
            value.toPlayerUrls(pageUrl).forEach { href ->
                sources.add(SourceLink(href, element.text().cleanText().ifBlank { "Server ${index + 1}" }, null))
            }
        }

        select(".entry-content iframe[src], article iframe[src], iframe[src], embed[src]").forEachIndexed { index, iframe ->
            val href = iframe.attr("src").normalizeUrl(pageUrl) ?: return@forEachIndexed
            if (href.isExternalPlayerUrl()) {
                sources.add(SourceLink(href, iframe.attr("title").cleanText().ifBlank { href.hostName() }, null))
            }
        }

        select("script").forEachIndexed { index, script ->
            val text = script.html().ifBlank { script.data() }.ifBlank { script.toString() }.cleanEscapedHtml()
            iframeRegex.findAll(text).forEach { match ->
                val href = match.groupValues.getOrNull(1)?.normalizeUrl(pageUrl) ?: return@forEach
                if (href.isExternalPlayerUrl()) sources.add(SourceLink(href, "Script ${index + 1}", null))
            }
        }

        // Download links are fallback only. Player iframes above stay first.
        select(".gmr-download-list a[href], .entry-content a[rel*=nofollow][href]").forEach { anchor ->
            val href = anchor.attr("href").normalizeUrl(pageUrl) ?: return@forEach
            if (!href.isExternalPlayerUrl()) return@forEach
            val parentText = anchor.parent()?.text()?.cleanText().orEmpty()
            sources.add(
                SourceLink(
                    url = href,
                    name = anchor.text().cleanText().ifBlank { href.hostName() },
                    quality = parentText.qualityFromText() ?: anchor.text().qualityFromText(),
                )
            )
        }

        return sources
            .filterNot { it.url.isAdOrSocialUrl() }
            .distinctBy { it.url }
    }

    private fun String.toPlayerUrls(pageUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        decodedServerValues().forEach { decoded ->
            val clean = decoded.cleanEscapedHtml()
            val parsed = Jsoup.parse(clean)
            parsed.select("iframe[src], embed[src]").forEach { iframe ->
                iframe.attr("src").normalizeUrl(pageUrl)?.let { if (it.isExternalPlayerUrl()) urls.add(it) }
            }
            iframeRegex.findAll(clean).forEach { match ->
                match.groupValues.getOrNull(1)?.normalizeUrl(pageUrl)?.let { if (it.isExternalPlayerUrl()) urls.add(it) }
            }
            clean.normalizeUrl(pageUrl)?.let { if (it.isExternalPlayerUrl()) urls.add(it) }
        }
        return urls.toList()
    }

    private fun String.decodedServerValues(): List<String> {
        val values = linkedSetOf(cleanEscapedHtml())
        runCatching {
            URLDecoder.decode(this, "UTF-8")
        }.getOrNull()?.cleanEscapedHtml()?.let { values.add(it) }

        val raw = trim().replace(Regex("\\s+"), "")
        if (raw.length >= 8) {
            runCatching {
                val padded = raw.padEnd(raw.length + ((4 - raw.length % 4) % 4), '=')
                String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
            }.getOrNull()
                ?.takeIf { it.contains("iframe", ignoreCase = true) || it.contains("http", ignoreCase = true) }
                ?.cleanEscapedHtml()
                ?.let { values.add(it) }
        }
        return values.toList()
    }

    private fun Document.infoValue(label: String): String? {
        select(".entry-content li, .entry-content p, li, p").forEach { element ->
            val text = element.text().cleanText()
            val value = text.extractInfoValue(label)
            if (!value.isNullOrBlank()) return value.cleanTitle()
        }
        return null
    }

    private fun String.extractInfoValue(label: String): String? {
        val keys = listOf(
            "Native Title", "Also Known As", "Director", "Screenwriter", "Screenwriter & Director",
            "Genres", "Title", "Movie", "Type", "Format", "Country", "Episodes", "Aired", "Aired On",
            "Original Network", "Duration", "Release Date", "Content Rating", "Kualitas", "Tahun", "Negara",
            "Rilis", "Bahasa", "Direksi", "Pemain",
        ).filterNot { it.equals(label, ignoreCase = true) }
        val nextKey = keys.joinToString("|") { Regex.escape(it) }
        val regex = Regex("(?i)(?:^|\\s)${Regex.escape(label)}\\s*:\\s*(.+?)(?=\\s+(?:$nextKey)\\s*:|$)")
        return regex.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Document.genreList(): List<String> {
        return infoValue("Genres")
            ?.split(",")
            ?.map { it.cleanTitle() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }

    private fun Document.posterUrl(): String? {
        return selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: selectFirst(".entry-content img[src*='wp-content'], article img[src*='wp-content'], .post img[src*='wp-content'], img")?.imageUrl()
    }

    private fun Document.plotText(): String? {
        select(".entry-content p").forEach { paragraph ->
            val text = paragraph.text().cleanText()
            if (text.contains("Sinopsis", ignoreCase = true)) {
                val sinopsis = text
                    .replace(Regex("(?i)^\\s*Sinopsis\\s*[:：-]?\\s*"), "")
                    .cleanText()
                if (sinopsis.length > 40 && !sinopsis.isDirtyPlot()) return sinopsis
            }
        }

        val clone = selectFirst(".entry-content.entry-content-single, .entry-content")?.clone() ?: return null
        clone.select("iframe, script, style, ul, table, h1, h2, center, .sharedaddy, .fb-comments, .gmr-rating, .gmr-download-wrap").forEach { it.remove() }
        val text = clone.text()
            .substringAfter("Sinopsis", "")
            .substringBefore("Details")
            .substringBefore("Download")
            .cleanText()
        return text.takeIf { it.length > 40 && !it.isDirtyPlot() }
    }

    private fun Document.findSeriesUrl(currentUrl: String, seriesTitle: String): String? {
        val cleanSeries = seriesTitle.cleanTitle()
        return select("a[rel=category], a[rel=tag], .entry-content a[href]")
            .mapNotNull { link ->
                val href = link.attr("href").normalizeUrl(currentUrl) ?: return@mapNotNull null
                val text = link.text().cleanTitle()
                if (!href.startsWith(mainUrl) || href == currentUrl || href.isEpisodePermalink() || href.isBadInternalUrl()) return@mapNotNull null
                if (text.equals(cleanSeries, ignoreCase = true)) href else null
            }
            .firstOrNull()
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        val nextPage = page + 1
        return select("a[href]").any { link ->
            val href = link.attr("href")
            val text = link.text().cleanText()
            href.contains("/page/$nextPage/") || text == nextPage.toString()
        }
    }

    private fun Element.imageUrl(): String? {
        val image = attr("data-src").ifBlank { attr("data-lazy-src") }
            .ifBlank { attr("data-original") }
            .ifBlank { attr("src") }
            .ifBlank { attr("data-srcset").substringBefore(" ") }
            .ifBlank { attr("srcset").substringBefore(" ") }
        return fixUrlNull(image)
    }

    private fun EpisodeSeed.toEpisode(seriesTitle: String): Episode {
        val displayName = title.cleanEpisodeDisplayName(seriesTitle, episode)
        return newEpisode(url) {
            this.name = displayName
            this.episode = episode
            this.posterUrl = poster
        }
    }

    private fun String.cleanText(): String {
        return replace("\u00a0", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return cleanText()
            .replace(Regex("(?i)^Permalink\\s+to:\\s*"), "")
            .replace(Regex("(?i)^Drama\\s+(?=.+\\b(?:Episode|Movie|Film)\\b)"), "")
            .replace(Regex("(?i)^Watch\\s+"), "")
            .replace(Regex("(?i)\\s+Sub\\s+Indo\\s*$"), "")
            .replace(Regex("(?i)\\s+⋆\\s+Dramaindo.*$"), "")
            .cleanText()
    }

    private fun String.cleanSeriesTitle(): String {
        return substringBefore("/")
            .cleanTitle()
    }

    private fun String.cleanEpisodeDisplayName(seriesTitle: String, episode: Int?): String {
        val cleanSeries = seriesTitle.cleanTitle()
        val clean = cleanTitle()
            .replace(Regex("(?i)^${Regex.escape(cleanSeries)}\\s*"), "")
            .cleanTitle()
        return when {
            episode != null && clean.equals("Episode $episode", ignoreCase = true) -> "Episode $episode"
            episode != null && clean.isBlank() -> "Episode $episode"
            else -> clean.ifBlank { episode?.let { "Episode $it" } ?: this.cleanTitle() }
        }
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(Regex("(?i)\\s+Episode\\s+\\d+(?:\\s*-\\s*\\d+)?(?:\\s*\\[END])?.*$"), "")
            .cleanTitle()
    }

    private fun String.belongsToSeries(seriesTitle: String): Boolean {
        val cleanSeries = seriesTitle.cleanTitle().lowercase()
        val base = removeEpisodeSuffix().cleanTitle().lowercase()
        return cleanSeries.isNotBlank() && base == cleanSeries
    }

    private fun String.hasEpisodeNumber(): Boolean {
        return Regex("(?i)\\bEpisode\\s+\\d+").containsMatchIn(this)
    }

    private fun String.episodeNumber(): Int? {
        return Regex("(?i)\\bEpisode\\s+(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.isMovieTitle(): Boolean {
        return Regex("(?i)\\b(Movie|Film Korea|Film)\\b").containsMatchIn(this)
    }

    private fun String.isEpisodePermalink(): Boolean {
        return Regex("/20\\d{2}/").containsMatchIn(this) || Regex("(?i)episode-?\\d+").containsMatchIn(this)
    }

    private fun String.isBadInternalUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("/author/") || lower.contains("/wp-") || lower.endsWith("#")
    }

    private fun String.isDirtyPlot(): Boolean {
        val lower = lowercase()
        return lower.startsWith("sharer tweet") || lower.contains("no votes nonton drama")
    }

    private fun String.isGenericNavigationText(): Boolean {
        val text = cleanText().lowercase()
        return text in setOf(
            "home",
            "watch",
            "watch movie",
            "more movie",
            "list drama",
            "movie",
            "sharer",
            "tweet",
            "view more",
            "dramaindo",
            "drama",
            "by",
            "posted on",
            "genre",
            "no more posts available",
            "proudly powered by wordpress",
        )
    }

    private fun String.normalizeUrl(base: String): String? {
        val raw = cleanEscapedHtml().trim()
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript:", ignoreCase = true)) return null
        return runCatching {
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http://") || raw.startsWith("https://") -> raw
                else -> URI(base).resolve(raw).toString()
            }
        }.getOrNull()
    }

    private fun String.cleanEscapedHtml(): String {
        return replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#039;", "'")
            .replace("&#x27;", "'")
            .replace("&apos;", "'")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003a", ":")
            .replace("\\u002f", "/")
            .replace("\\\"", "\"")
    }

    private fun String.isExternalPlayerUrl(): Boolean {
        val lower = lowercase()
        if (!lower.startsWith("http")) return false
        if (lower.startsWith(mainUrl.lowercase())) return false
        if (lower.isAdOrSocialUrl()) return false
        return true
    }

    private fun String.isAdOrSocialUrl(): Boolean {
        val lower = lowercase()
        return listOf(
            "facebook.com/plugins",
            "histats.com",
            "doubleclick",
            "googletag",
            "google-analytics",
            "analytics",
            "adskeeper",
            "popads",
            "popcash",
            "whatsapp://",
            "twitter.com/share",
        ).any { lower.contains(it) }
    }

    private fun String.isDramaIndoStreamHost(): Boolean {
        val lower = lowercase()
        return streamHosts.any { lower.contains(it) }
    }

    private fun String.qualityFromText(): Int? {
        return Regex("(?i)(2160|1080|720|540|480|360)p").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.hostName(): String {
        return substringAfter("://")
            .substringBefore("/")
            .removePrefix("www.")
    }

    private data class EpisodeSeed(
        val title: String,
        val url: String,
        val poster: String?,
        val episode: Int?,
    )

    private data class SourceLink(
        val url: String,
        val name: String,
        val quality: Int?,
    )

    private companion object {
        val streamHosts = listOf(
            "drakorkita.stream",
            "nuna.upns.pro",
        )

        val iframeRegex = Regex("""(?i)<iframe[^>]+src=[\"']([^\"']+)[\"']""")
    }
}
