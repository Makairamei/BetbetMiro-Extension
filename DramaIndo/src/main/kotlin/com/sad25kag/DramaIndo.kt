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
import java.net.URLEncoder
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
        val forcedType = if (request.data == "movie") TvType.Movie else TvType.AsianDrama
        val document = app.get(buildArchiveUrl(request.data, page), referer = mainUrl).document
        val cards = document.parseArchiveCards(forcedType)

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
            .parseArchiveCards(null)
            .toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl).document
        val rawTitle = document.rawPageTitle()
            ?: throw RuntimeException("DramaIndo: title tidak ditemukan")

        if (rawTitle.isGenrePageTitle()) {
            val seriesTitle = rawTitle.substringAfter(":").cleanDisplayTitle()
            val poster = document.archivePoster()
            val episodes = document.parseEpisodeSeeds(seriesTitle.slugKey())
                .distinctBy { it.url }
                .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                .map { it.toEpisode(seriesTitle) }

            return newTvSeriesLoadResponse(seriesTitle, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = document.archivePlot()
            }
        }

        val root = document.detailRoot()
        val detailTitle = root.rawDetailTitle() ?: rawTitle
        val displayTitle = detailTitle.cleanDisplayTitle()
        val categories = root.mainCategories()
        val isMovie = categories.any { it.title.equals("Movie", ignoreCase = true) } || detailTitle.isMoviePostTitle()
        val isEpisode = detailTitle.hasEpisodeNumber() && !isMovie
        val poster = root.posterUrl() ?: document.posterUrl()
        val plot = root.plotText(displayTitle)
        val tags = root.genreList()

        if (isEpisode) {
            val seriesCategory = categories.firstOrNull { !it.title.equals("Movie", ignoreCase = true) }
            val seriesTitle = seriesCategory?.title?.cleanDisplayTitle()
                ?: detailTitle.removeEpisodeSuffix().cleanDisplayTitle()
            val seriesUrl = seriesCategory?.url
            val seriesSlug = (seriesUrl?.lastPathSegment() ?: seriesTitle.slugKey()).ifBlank { seriesTitle.slugKey() }
            val seeds = linkedSetOf<EpisodeSeed>()

            seeds.add(
                EpisodeSeed(
                    title = detailTitle.cleanDisplayTitle(stripEpisodePrefix = false),
                    url = url,
                    poster = poster,
                    episode = detailTitle.episodeNumber(),
                )
            )

            if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                runCatching {
                    app.get(seriesUrl, referer = url).document.parseEpisodeSeeds(seriesSlug)
                }.getOrDefault(emptyList()).forEach { seeds.add(it) }
            }

            val episodes = seeds
                .filter { it.url.isNotBlank() && it.url.isSameSeriesSlug(seriesSlug) }
                .ifEmpty { seeds.toList() }
                .distinctBy { it.url }
                .sortedWith(compareBy<EpisodeSeed> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })
                .map { it.toEpisode(seriesTitle) }

            return newTvSeriesLoadResponse(seriesTitle, seriesUrl ?: url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }

        val playData = root.extractPlayableLinks(url).firstOrNull()?.toData() ?: url
        return newMovieLoadResponse(displayTitle, url, TvType.Movie, playData) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val decoded = data.decodeSourceData()
        val sources = if (decoded != null) {
            listOf(decoded)
        } else if (data.isExternalPlayerUrl()) {
            listOf(SourceLink(url = data, name = data.hostName(), referer = mainUrl, quality = null))
        } else {
            val document = runCatching { app.get(data, referer = mainUrl).document }.getOrNull() ?: return false
            val root = document.detailRoot()
            root.extractPlayableLinks(data)
        }

        var callbackCount = 0
        val seen = linkedSetOf<String>()
        for (source in sources.distinctBy { it.url }) {
            if (!seen.add(source.url)) continue
            val before = callbackCount
            runCatching {
                loadExtractor(source.url, source.referer ?: data, subtitleCallback) { link ->
                    callbackCount++
                    callback(link)
                }
            }

            if (callbackCount == before && source.url.isDramaIndoStreamHost()) {
                DramaIndoStreamResolver.resolve(source.name, source.url, source.referer ?: data) { link ->
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

    private fun Document.parseArchiveCards(forcedType: TvType?): List<SearchResponse> {
        val articleCards = select("article.item, article.item-infinite, article[class~=\\bitem\\b]")
            .mapNotNull { it.toSearchResult(forcedType) }
            .distinctBy { it.url }
        if (articleCards.isNotEmpty()) return articleCards

        return select(".gmr-item-modulepost, .gmr-box-content, article")
            .mapNotNull { it.toSearchResult(forcedType) }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(forcedType: TvType?): SearchResponse? {
        val titleAnchor = selectFirst("h1.entry-title a[href], h2.entry-title a[href], h3.entry-title a[href], .entry-title a[href]")
            ?: selectFirst("a[rel=bookmark][href]")
            ?: return null
        val href = fixUrlNull(titleAnchor.attr("href")) ?: return null
        if (!href.startsWith(mainUrl) || href.isBadInternalUrl()) return null

        val rawTitle = titleAnchor.text().ifBlank { titleAnchor.attr("title") }
        val title = rawTitle.cleanDisplayTitle()
        if (title.isBlank() || title.isGenericNavigationText()) return null

        val poster = selectFirst("img")?.imageUrl()
        val tvType = forcedType ?: detectCardType(title, href)
        return newMovieSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    private fun detectCardType(title: String, url: String): TvType {
        return when {
            url.contains("/movie", ignoreCase = true) -> TvType.Movie
            title.hasEpisodeNumber() || url.contains("episode-", ignoreCase = true) -> TvType.AsianDrama
            else -> TvType.Movie
        }
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        val nextPage = page + 1
        return select("a[href]").any { link ->
            val href = link.attr("href")
            val text = link.text().cleanText()
            href.contains("/page/$nextPage/") || text == nextPage.toString()
        }
    }

    private fun Document.rawPageTitle(): String? {
        return selectFirst(".gmr-movie-data h1.entry-title, h1.entry-title, h1:not(.screen-reader-text)")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
            ?: selectFirst("meta[property='og:title']")
                ?.attr("content")
                ?.cleanText()
                ?.removeSuffix("⋆ Dramaindo")
                ?.cleanText()
                ?.takeIf { it.isNotBlank() }
    }

    private fun Document.detailRoot(): Element {
        return selectFirst("article[id^=post-]") ?: selectFirst("main") ?: this
    }

    private fun Element.rawDetailTitle(): String? {
        return selectFirst(".gmr-movie-data h1.entry-title, h1.entry-title, h1[itemprop=name]")
            ?.text()
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
    }

    private fun Element.mainCategories(): List<CategoryLink> {
        return select(".gmr-movie-on a[rel*=category], a[rel*=category]")
            .mapNotNull { link ->
                val href = link.attr("href").normalizeUrl(mainUrl) ?: return@mapNotNull null
                val title = link.text().cleanDisplayTitle()
                if (title.isBlank() || href.isBadInternalUrl()) null else CategoryLink(title, href)
            }
            .distinctBy { it.url }
    }

    private fun Document.parseEpisodeSeeds(seriesSlug: String): List<EpisodeSeed> {
        return select("article.item, article.item-infinite, article[class~=\\bitem\\b]")
            .mapNotNull { article ->
                val anchor = article.selectFirst("h1.entry-title a[href], h2.entry-title a[href], h3.entry-title a[href], .entry-title a[href]")
                    ?: return@mapNotNull null
                val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                if (!href.startsWith(mainUrl) || href.isBadInternalUrl()) return@mapNotNull null
                if (!href.isSameSeriesSlug(seriesSlug)) return@mapNotNull null
                val rawTitle = anchor.text().ifBlank { anchor.attr("title") }.cleanDisplayTitle(stripEpisodePrefix = false)
                val episode = rawTitle.episodeNumber() ?: href.episodeNumber()
                if (episode == null && !rawTitle.hasEpisodeNumber()) return@mapNotNull null
                EpisodeSeed(
                    title = rawTitle,
                    url = href,
                    poster = article.selectFirst("img")?.imageUrl(),
                    episode = episode,
                )
            }
            .distinctBy { it.url }
    }

    private fun Element.extractPlayableLinks(pageUrl: String): List<SourceLink> {
        val sources = linkedSetOf<SourceLink>()

        select(".entry-content iframe[src], article iframe[src], iframe[src], embed[src]").forEachIndexed { index, iframe ->
            val href = iframe.attr("src").normalizeUrl(pageUrl) ?: return@forEachIndexed
            if (href.isExternalPlayerUrl()) {
                sources.add(SourceLink(href, iframe.attr("title").cleanText().ifBlank { href.hostName() }, pageUrl, null))
            }
        }

        select("option[value]").forEachIndexed { index, option ->
            option.attr("value").toPlayerUrls(pageUrl).forEach { href ->
                sources.add(SourceLink(href, option.text().cleanText().ifBlank { "Server ${index + 1}" }, pageUrl, null))
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
                sources.add(SourceLink(href, element.text().cleanText().ifBlank { "Server ${index + 1}" }, pageUrl, null))
            }
        }

        select("script").forEachIndexed { index, script ->
            val text = script.html().ifBlank { script.data() }.ifBlank { script.toString() }.cleanEscapedHtml()
            iframeRegex.findAll(text).forEach { match ->
                val href = match.groupValues.getOrNull(1)?.normalizeUrl(pageUrl) ?: return@forEach
                if (href.isExternalPlayerUrl()) sources.add(SourceLink(href, "Script ${index + 1}", pageUrl, null))
            }
        }

        select(".gmr-download-wrap a[href], .gmr-download-list a[href], .entry-content a[rel*=nofollow][href]").forEach { anchor ->
            val href = anchor.attr("href").normalizeUrl(pageUrl) ?: return@forEach
            if (!href.isExternalPlayerUrl()) return@forEach
            val label = anchor.text().cleanText().ifBlank { href.hostName() }
            sources.add(SourceLink(href, label, pageUrl, label.qualityFromText() ?: anchor.attr("title").qualityFromText()))
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
        runCatching { URLDecoder.decode(this, "UTF-8") }
            .getOrNull()
            ?.cleanEscapedHtml()
            ?.let { values.add(it) }

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

    private fun Element.genreList(): List<String> {
        val genres = linkedSetOf<String>()
        select(".gmr-moviedata").forEach { row ->
            val label = row.selectFirst("strong")?.text()?.cleanText()?.removeSuffix(":") ?: return@forEach
            if (label.equals("Genre", ignoreCase = true) || label.equals("Genres", ignoreCase = true)) {
                val value = row.text().replace(row.selectFirst("strong")?.text().orEmpty(), "").cleanText().removePrefix(":").cleanText()
                value.split(",").map { it.cleanDisplayTitle() }.filter { it.isNotBlank() }.forEach { genres.add(it) }
            }
        }
        infoValue("Genres")?.split(",")?.map { it.cleanDisplayTitle() }?.filter { it.isNotBlank() }?.forEach { genres.add(it) }
        infoValue("Genre")?.split(",")?.map { it.cleanDisplayTitle() }?.filter { it.isNotBlank() }?.forEach { genres.add(it) }
        return genres.toList()
    }

    private fun Element.infoValue(label: String): String? {
        select(".entry-content li, .entry-content p, .gmr-moviedata, li, p").forEach { element ->
            val text = element.text().cleanText()
            val value = text.extractInfoValue(label)
            if (!value.isNullOrBlank()) return value.cleanText()
        }
        return null
    }

    private fun String.extractInfoValue(label: String): String? {
        val keys = listOf(
            "Native Title", "Also Known As", "Director", "Screenwriter", "Screenwriter & Director",
            "Genres", "Genre", "Title", "Movie", "Type", "Format", "Country", "Episodes", "Aired", "Aired On",
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

    private fun Element.posterUrl(): String? {
        return selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: selectFirst(".entry-content img[src*='wp-content'], img.wp-post-image, article img[src*='wp-content'], img")?.imageUrl()
    }

    private fun Document.posterUrl(): String? {
        return selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }?.let { fixUrlNull(it) }
            ?: selectFirst("img.wp-post-image, img[src*='wp-content'], img")?.imageUrl()
    }

    private fun Document.archivePoster(): String? {
        return selectFirst("article.item img, article.item-infinite img, img.wp-post-image")?.imageUrl()
    }

    private fun Document.archivePlot(): String? = null

    private fun Element.plotText(title: String): String? {
        val entry = (selectFirst(".entry-content.entry-content-single") ?: selectFirst(".entry-content") ?: this).clone()
        entry.select("iframe, script, style, ul.gmr-download-list, .gmr-download-wrap, .content-moviedata, .tags-links-content, .sharedaddy, .fb-comments, .gmr-rating").forEach { it.remove() }

        entry.select("p").forEach { paragraph ->
            val candidate = paragraph.text().toPlotCandidate(title)
            if (candidate.length > 45 && !candidate.isDirtyPlot()) return candidate
        }

        val allText = entry.text()
            .substringAfter("Sinopsis", "")
            .substringBefore("Download", "")
            .toPlotCandidate(title)
        return allText.takeIf { it.length > 45 && !it.isDirtyPlot() }
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
        val number = episode
        val display = title.cleanEpisodeDisplayName(seriesTitle, number)
        return newEpisode(url) {
            this.name = display
            this.episode = number
            this.posterUrl = poster
        }
    }

    private fun SourceLink.toData(): String {
        val encodedUrl = URLEncoder.encode(url, "UTF-8")
        val encodedReferer = URLEncoder.encode(referer ?: mainUrl, "UTF-8")
        val encodedName = URLEncoder.encode(name, "UTF-8")
        return "$DATA_PREFIX$encodedUrl|$encodedReferer|$encodedName"
    }

    private fun String.decodeSourceData(): SourceLink? {
        if (!startsWith(DATA_PREFIX)) return null
        val parts = removePrefix(DATA_PREFIX).split("|")
        if (parts.size < 2) return null
        val url = runCatching { URLDecoder.decode(parts[0], "UTF-8") }.getOrNull() ?: return null
        val referer = runCatching { URLDecoder.decode(parts[1], "UTF-8") }.getOrNull() ?: mainUrl
        val name = runCatching { URLDecoder.decode(parts.getOrElse(2) { url.hostName() }, "UTF-8") }.getOrNull() ?: url.hostName()
        return SourceLink(url, name, referer, null)
    }

    private fun String.cleanText(): String {
        return replace("\u00a0", " ")
            .replace("﻿", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.cleanDisplayTitle(stripEpisodePrefix: Boolean = true): String {
        var value = cleanText()
            .replace(Regex("(?i)^Permalink\\s+to:\\s*"), "")
            .replace(Regex("(?i)^Nonton\\s+"), "")
            .replace(Regex("(?i)^Drama\\s+(?=(?:Movie|Film|Korea|China|Jepang|Thailand|Bloody|[A-Z]))"), "")
            .replace(Regex("(?i)^Movie\\s+(?:Korea|China|Jepang|Japan|Thailand|Taiwan|Hong\\s*Kong|Asia)\\s+"), "")
            .replace(Regex("(?i)^Film\\s+(?:Korea|China|Jepang|Japan|Thailand|Taiwan|Hong\\s*Kong|Asia)\\s+"), "")
            .replace(Regex("(?i)^Movie\\s+"), "")
            .replace(Regex("(?i)^Film\\s+"), "")
            .replace(Regex("(?i)\\s+Sub\\s+Indo(?:nesia)?\\s*$"), "")
            .replace(Regex("(?i)\\s+⋆\\s+Dramaindo.*$"), "")
            .replace(Regex("(?i)^Genre:\\s*"), "")
            .cleanText()
        if (stripEpisodePrefix) value = value.replace(Regex("(?i)^Episode\\s+\\d+\\.\\s*"), "").cleanText()
        return value
    }

    private fun String.toPlotCandidate(title: String): String {
        return cleanText()
            .replace(Regex("(?i)^Sinopsis\\s*[:：-]?\\s*"), "")
            .replace(Regex("(?i)^Download\\s+Streaming\\s+Film\\s+.+?(?:–|-)\\s*"), "")
            .replace(Regex("(?i)^Nonton\\s+Drama\\s+${Regex.escape(title)}.*?Dramasubindo\\s+${Regex.escape(title)}\\s*"), "")
            .cleanText()
    }

    private fun String.cleanEpisodeDisplayName(seriesTitle: String, episode: Int?): String {
        val cleanSeries = seriesTitle.cleanDisplayTitle()
        val clean = cleanDisplayTitle(stripEpisodePrefix = false)
            .replace(Regex("(?i)^${Regex.escape(cleanSeries)}\\s*"), "")
            .cleanDisplayTitle(stripEpisodePrefix = false)
        return when {
            episode != null && clean.equals("Episode $episode", ignoreCase = true) -> "Episode $episode"
            episode != null && clean.isBlank() -> "Episode $episode"
            else -> clean.ifBlank { episode?.let { "Episode $it" } ?: this.cleanDisplayTitle() }
        }
    }

    private fun String.removeEpisodeSuffix(): String {
        return replace(Regex("(?i)\\s+Episode\\s+\\d+(?:\\s*-\\s*\\d+)?(?:\\s*\\[END])?.*$"), "")
            .cleanDisplayTitle()
    }

    private fun String.isGenrePageTitle(): Boolean = startsWith("Genre:", ignoreCase = true)

    private fun String.hasEpisodeNumber(): Boolean {
        return Regex("(?i)\\bEpisode\\s+\\d+").containsMatchIn(this) || Regex("(?i)episode-\\d+").containsMatchIn(this)
    }

    private fun String.episodeNumber(): Int? {
        return Regex("(?i)\\bEpisode\\s+(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)episode-(\\d+)").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun String.isMoviePostTitle(): Boolean {
        return Regex("(?i)^(?:Drama\\s+)?(?:Movie|Film)(?:\\s+(?:Korea|China|Jepang|Japan|Thailand|Taiwan|Hong\\s*Kong|Asia))?\\b").containsMatchIn(this)
    }

    private fun String.isSameSeriesSlug(seriesSlug: String): Boolean {
        if (seriesSlug.isBlank()) return hasEpisodeNumber()
        val lower = lowercase()
        val slug = seriesSlug.lowercase().trim('/')
        return lower.contains("/$slug-episode-") || lower.contains("/$slug/20") || lower.contains("/$slug/")
    }

    private fun String.slugKey(): String {
        return cleanDisplayTitle()
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }

    private fun String.lastPathSegment(): String {
        return runCatching { URI(this).path.trim('/').substringAfterLast('/') }.getOrNull().orEmpty()
    }

    private fun String.isBadInternalUrl(): Boolean {
        val lower = lowercase()
        return lower.contains("/author/") || lower.contains("/wp-") || lower.endsWith("#") || lower.contains("/feed/")
    }

    private fun String.isDirtyPlot(): Boolean {
        val lower = lowercase()
        return lower.isBlank() || lower.startsWith("sharer tweet") || lower.contains("no votes nonton drama") || lower.contains("native title:") || lower.contains("also known as:") || lower.contains("genre:")
    }

    private fun String.isGenericNavigationText(): Boolean {
        val text = cleanText().lowercase()
        return text in setOf(
            "home", "watch", "watch movie", "more movie", "list drama", "movie", "sharer", "tweet",
            "view more", "dramaindo", "drama", "by", "posted on", "genre", "no more posts available",
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
            "facebook.com/plugins", "histats.com", "doubleclick", "googletag", "google-analytics",
            "analytics", "adskeeper", "popads", "popcash", "whatsapp://", "twitter.com/share",
            "t.me/share", "linktr.ee", "staticxx.facebook.com",
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

    private data class CategoryLink(
        val title: String,
        val url: String,
    )

    private data class EpisodeSeed(
        val title: String,
        val url: String,
        val poster: String?,
        val episode: Int?,
    )

    private data class SourceLink(
        val url: String,
        val name: String,
        val referer: String?,
        val quality: Int?,
    )

    private companion object {
        const val DATA_PREFIX = "DramaIndoSource|"
        val streamHosts = listOf("drakorkita.stream", "nuna.upns.pro")
        val iframeRegex = Regex("""(?i)<iframe[^>]+src=[\"']([^\"']+)[\"']""")
    }
}
