package com.astronime

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AstronimeProvider : MainAPI() {
    override var mainUrl = "https://astronime.id"
    override var name = "Astronime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    override val mainPage = mainPageOf(
        "/" to "Latest Episode",
        "/" to "Some Movie Choices",
        "/" to "New Complete Anime",
        "/" to "Most Viewed",
        "/" to "Currently Airing"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(request.name, emptyList(), request.horizontalImages),
                hasNext = false
            )
        }

        val document = app.get(fixUrl(request.data), referer = mainUrl, timeout = 30).document
        val cards = document.parseHomeSection(request.name)

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = cards,
                isHorizontalImages = request.horizontalImages
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery", referer = mainUrl, timeout = 30).document
        return document.select(
            "article, div[class*=item], div[class*=post], div[class*=anime], div[class*=bs], li"
        ).mapNotNull { it.toSearchResult() }
            .ifEmpty { document.select("a[href]").mapNotNull { it.toSearchResult() } }
            .distinctBy { it.url }
            .take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, referer = mainUrl, timeout = 30).document
        document.setBaseUri(url)

        if (url.isEpisodeUrl()) {
            return document.toEpisodeLoadResponse(url)
        }

        val title = document.cleanTitle()
        val poster = document.posterUrl()
        val plot = document.plotText()
        val tags = document.tags()
        val year = document.fullText().extractYear()
        val tvType = document.detectTvType(title)
        val episodes = document.episodes()

        if (episodes.isNotEmpty() && tvType != TvType.AnimeMovie) {
            return newAnimeLoadResponse(title, url, TvType.Anime) {
                posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                showStatus = document.detectStatus()
                addEpisodes(DubStatus.Subbed, episodes)
            }
        }

        return newMovieLoadResponse(title, url, tvType, url) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl, timeout = 30).document
        document.setBaseUri(data)

        val sourceUrls = linkedSetOf<String>()

        // HAR-backed Astronime player flow:
        // episode page -> /wp-admin/admin-ajax.php action=player_ajax -> iframe host
        document.extractAjaxPlayerSources(data).forEach(sourceUrls::add)

        // Fallback for direct iframe/source/link patterns already present in HTML.
        document.extractPlayerSources(data).forEach(sourceUrls::add)

        var emittedOrDelegated = false
        sourceUrls.forEach { source ->
            if (source.resolvePlayerSource(data, subtitleCallback, callback)) {
                emittedOrDelegated = true
            }
        }

        return emittedOrDelegated
    }

    private suspend fun Document.extractAjaxPlayerSources(refererUrl: String): List<String> {
        val options = select(".east_player_option[data-post][data-nume], [id^=player-option][data-post][data-nume]")
            .mapNotNull { element ->
                val post = element.attr("data-post").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val nume = element.attr("data-nume").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val type = element.attr("data-type").takeIf { it.isNotBlank() } ?: "urliframe"
                val label = element.text().cleanText()
                PlayerOption(post, nume, type, label)
            }
            .distinctBy { "${it.post}:${it.nume}:${it.type}" }
            .sortedBy {
                when {
                    it.label.contains("turbo", ignoreCase = true) -> 0
                    it.label.contains("hydrax", ignoreCase = true) -> 1
                    it.label.contains("apollo", ignoreCase = true) -> 2
                    it.label.contains("abyss", ignoreCase = true) -> 3
                    else -> 4
                }
            }

        if (options.isEmpty()) return emptyList()

        return options.flatMap { option ->
            runCatching {
                val ajaxHtml = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "player_ajax",
                        "post" to option.post,
                        "nume" to option.nume,
                        "type" to option.type
                    ),
                    referer = refererUrl,
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "X-Requested-With" to "XMLHttpRequest"
                    ),
                    timeout = 30
                ).text

                Jsoup.parse(ajaxHtml, mainUrl).extractPlayerSources(refererUrl)
            }.getOrDefault(emptyList())
        }.distinct()
    }

    private suspend fun String.resolvePlayerSource(
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sourceUrl = this
        return when {
            sourceUrl.contains(".m3u8", ignoreCase = true) -> {
                callback.directLink(sourceUrl, ExtractorLinkType.M3U8)
                true
            }
            sourceUrl.contains(".mp4", ignoreCase = true) || sourceUrl.contains("/stream-vid/", ignoreCase = true) -> {
                callback.directLink(sourceUrl, ExtractorLinkType.VIDEO)
                true
            }
            sourceUrl.contains("turbovidhls.com", ignoreCase = true) ||
                sourceUrl.contains("turboviplay.com", ignoreCase = true) -> {
                resolveTurbovidSource(sourceUrl, refererUrl, callback)
            }
            else -> {
                loadExtractor(sourceUrl, refererUrl, subtitleCallback, callback)
                true
            }
        }
    }

    private suspend fun resolveTurbovidSource(
        sourceUrl: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerDoc = app.get(sourceUrl, referer = refererUrl, timeout = 30).document
        playerDoc.setBaseUri(sourceUrl)

        val mediaUrls = playerDoc.extractPlayerSources(sourceUrl)
            .filter {
                it.contains(".m3u8", ignoreCase = true) ||
                    it.contains(".mp4", ignoreCase = true)
            }

        mediaUrls.forEach { mediaUrl ->
            callback.directLink(
                mediaUrl,
                if (mediaUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            )
        }

        return mediaUrls.isNotEmpty()
    }

    private fun ((ExtractorLink) -> Unit).directLink(
        mediaUrl: String,
        linkType: ExtractorLinkType
    ) {
        invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = mediaUrl,
                type = linkType
            ).apply {
                quality = mediaUrl.extractQuality()
            }
        )
    }

    private fun String.extractQuality(): Int {
        val lower = lowercase()
        return when {
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            lower.contains("360") -> 360
            else -> Qualities.Unknown.value
        }
    }

    private fun Document.parseHomeSection(sectionName: String): List<SearchResponse> {
        val sectionRoot = findSectionRoot(sectionName)
        val candidates = when {
            sectionRoot != null -> sectionRoot.select("article, div[class*=item], div[class*=post], div[class*=anime], div[class*=bs], li, a[href]")
            sectionName.contains("Episode", ignoreCase = true) -> select("a[href*=episode], article, div[class*=episode]")
            sectionName.contains("Movie", ignoreCase = true) -> select("a[href*=movie], article, div[class*=movie]")
            sectionName.contains("Complete", ignoreCase = true) -> select("a[href*=/anime/], article, div[class*=complete]")
            else -> select("a[href*=/anime/], a[href*=episode], article, div[class*=item], div[class*=post]")
        }

        val results = candidates.mapNotNull { it.toSearchResult(sectionName) }
            .distinctBy { it.url }
            .filter { it.name.isNotBlank() }

        return if (results.isNotEmpty()) results.take(30) else select("a[href]")
            .mapNotNull { it.toSearchResult(sectionName) }
            .distinctBy { it.url }
            .take(30)
    }

    private fun Document.findSectionRoot(sectionName: String): Element? {
        val heading = select("h1, h2, h3, h4, h5, .section-title, .widget-title, .home-title, .title")
            .firstOrNull { it.ownText().equals(sectionName, ignoreCase = true) || it.text().equals(sectionName, ignoreCase = true) }
            ?: select("*:matchesOwn((?i)^${Regex.escape(sectionName)}$)").firstOrNull()

        var parent = heading?.parent()
        repeat(4) {
            if (parent != null && parent!!.select("a[href]").size >= 2) return parent
            parent = parent?.parent()
        }
        return heading?.parent()
    }

    private fun Element.toSearchResult(sectionName: String? = null): SearchResponse? {
        val anchor = if (tagName().equals("a", true)) this else selectFirst("a[href]") ?: return null
        val href = anchor.absUrl("href").ifBlank { fixUrlNull(anchor.attr("href")) } ?: return null
        if (!href.startsWith(mainUrl) || href.isIgnoredUrl()) return null

        val title = titleFrom(anchor, this)
        if (title.isBlank() || title.length < 3 || title.isNoiseTitle()) return null

        val poster = imageFrom(this) ?: imageFrom(anchor)
        val episode = title.extractEpisodeNumber() ?: href.extractEpisodeNumber()
        val type = when {
            title.contains("movie", ignoreCase = true) || sectionName?.contains("Movie", ignoreCase = true) == true -> TvType.AnimeMovie
            title.contains("ova", ignoreCase = true) || title.contains("special", ignoreCase = true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            addSub(episode)
        }
    }

    private fun Document.toEpisodeLoadResponse(url: String): LoadResponse {
        val title = cleanTitle()
        val poster = posterUrl()
        val plot = plotText()
        return newMovieLoadResponse(title, url, TvType.Anime, url) {
            posterUrl = poster
            this.plot = plot
        }
    }

    private fun Document.episodes(): List<com.lagradost.cloudstream3.Episode> {
        val episodeItems = select("a[href]").mapNotNull { anchor ->
            val href = anchor.absUrl("href").ifBlank { fixUrlNull(anchor.attr("href")) } ?: return@mapNotNull null
            if (!href.startsWith(mainUrl) || !href.isEpisodeUrl()) return@mapNotNull null

            val episodeTitle = titleFrom(anchor, anchor.parent() ?: anchor)
            val episodeNumber = episodeTitle.extractEpisodeNumber() ?: href.extractEpisodeNumber()
            val displayName = if (episodeTitle.isNotBlank() && !episodeTitle.isNoiseTitle()) episodeTitle else "Episode ${episodeNumber ?: ""}".trim()

            EpisodeHolder(
                episode = episodeNumber,
                data = newEpisode(href) {
                    name = displayName
                    episode = episodeNumber
                }
            )
        }.distinctBy { it.data.data }

        return episodeItems.sortedWith(
            compareBy<EpisodeHolder> { it.episode ?: Int.MAX_VALUE }.thenBy { it.data.name ?: "" }
        ).map { it.data }
    }

    private fun Document.extractPlayerSources(refererUrl: String): List<String> {
        val urls = mutableListOf<String>()

        select("iframe[src], iframe[data-src], iframe[data-lazy-src], iframe[data-original-src]").forEach { iframe ->
            listOf("src", "data-src", "data-lazy-src", "data-original-src").forEach { attr ->
                iframe.attr(attr).normalizeSourceUrl(refererUrl)?.let { urls.add(it) }
            }
        }

        select("video[src], source[src], a[href]").forEach { element ->
            element.attr("src").normalizeSourceUrl(refererUrl)?.let { urls.add(it) }
            element.attr("href").normalizeSourceUrl(refererUrl)?.let { url ->
                if (url.contains(".m3u8", true) || url.contains(".mp4", true)) urls.add(url)
            }
        }

        select("input[value], div[data-src], div[data-link], div[data-embed], div[data-hash], div[data-url], button[data-src], button[data-link], button[data-embed]").forEach { element ->
            listOf("value", "data-src", "data-link", "data-embed", "data-hash", "data-url").forEach { attr ->
                element.attr(attr).normalizeSourceUrl(refererUrl)?.let { urls.add(it) }
            }
        }

        val scriptText = select("script").joinToString("\n") { it.data() }
        val unescapedScript = Jsoup.parse(scriptText).text()
            .replace("\\/", "/")
            .replace("\\u0026", "&")

        Regex("""https?:\\?/\\?/[^'\"\\s<>]+(?:m3u8|mp4|embed[^'\"\\s<>]*)""")
            .findAll(unescapedScript)
            .map { it.value.replace("\\/", "/") }
            .forEach { it.normalizeSourceUrl(refererUrl)?.let(urls::add) }

        Regex("""(?:file|src|url|source)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(unescapedScript)
            .map { it.groupValues[1] }
            .forEach { it.normalizeSourceUrl(refererUrl)?.let(urls::add) }

        return urls.distinct()
            .filter { !it.contains("youtube.com", true) && !it.contains("youtu.be", true) }
            .filter { it.startsWith("http") }
    }

    private fun String.normalizeSourceUrl(refererUrl: String): String? {
        val raw = trim()
            .replace("&amp;", "&")
            .replace("\\/", "/")
        if (raw.isBlank() || raw == "#" || raw.startsWith("javascript", true)) return null

        val fixed = when {
            raw.startsWith("http", true) -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("www.") -> "https://$raw"
            raw.startsWith("/") -> fixUrl(raw)
            raw.contains(".m3u8", true) || raw.contains(".mp4", true) || raw.contains("embed", true) -> fixUrl(raw)
            else -> return null
        }

        if (fixed == refererUrl || fixed == mainUrl || fixed.startsWith("data:")) return null
        return fixed
    }

    private fun Document.cleanTitle(): String {
        return selectFirst("h1.entry-title, h1[itemprop=name], h1, meta[property=og:title]")
            ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
            ?.cleanText()
            ?.replace("- Astronime", "", ignoreCase = true)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown Title"
    }

    private fun Document.posterUrl(): String? {
        val candidates = listOfNotNull(
            selectFirst("meta[property=og:image]")?.attr("content"),
            selectFirst("img.wp-post-image")?.attr("src"),
            selectFirst(".thumb img, .poster img, .image img, .entry-content img")?.let { imageFrom(it) }
        )
        return candidates.firstNotNullOfOrNull { fixUrlNull(it) }
    }

    private fun Document.plotText(): String? {
        val direct = selectFirst(".sinopsis, .synopsis, .entry-content p, [itemprop=description], .desc, .description")?.text()?.cleanText()
        if (!direct.isNullOrBlank()) return direct

        return select("p").map { it.text().cleanText() }
            .filter { it.length > 40 }
            .firstOrNull()
    }

    private fun Document.tags(): List<String> {
        return select("a[href*=/genre/]").map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun Document.detectStatus(): ShowStatus? {
        val text = fullText()
        return when {
            text.contains("Ongoing", true) || text.contains("Airing", true) -> ShowStatus.Ongoing
            text.contains("Completed", true) || text.contains("Complete", true) || text.contains("Finished", true) -> ShowStatus.Completed
            else -> null
        }
    }

    private fun Document.detectTvType(title: String): TvType {
        val text = "$title ${fullText()}"
        return when {
            text.contains("Movie", true) -> TvType.AnimeMovie
            text.contains("OVA", true) || text.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }
    }

    private fun Document.fullText(): String = body()?.text().orEmpty()

    private fun titleFrom(anchor: Element, container: Element): String {
        val candidates = listOf(
            anchor.attr("title"),
            anchor.selectFirst("h1, h2, h3, h4, h5, .title, .judul, .entry-title, .post-title, .name")?.text(),
            container.selectFirst("h1, h2, h3, h4, h5, .title, .judul, .entry-title, .post-title, .name")?.text(),
            anchor.text(),
            container.text()
        )

        return candidates.firstOrNull { !it.isNullOrBlank() }
            ?.cleanText()
            ?.deriveTitleFromLongText()
            ?: ""
    }

    private fun imageFrom(element: Element): String? {
        val image = if (element.tagName().equals("img", true)) element else element.selectFirst("img") ?: return null
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-cfsrc", "src")
        attrs.forEach { attr ->
            image.attr(attr).takeIf { it.isNotBlank() }?.let { return fixUrlNull(it) }
        }
        return image.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ")?.let { fixUrlNull(it) }
    }

    private fun String.cleanText(): String {
        return Jsoup.parse(this).text()
            .replace(Regex("\\s+"), " ")
            .replace("Nonton Anime", "", ignoreCase = true)
            .replace("Subtitle Indonesia", "", ignoreCase = true)
            .trim(' ', '-', '|', ':')
    }

    private fun String.deriveTitleFromLongText(): String {
        val cleaned = cleanText()
        val episodeMatch = Regex("""([\p{L}\p{N} .:'!?&+\-]+Episode\s*\d+(?:\s*End)?)""", RegexOption.IGNORE_CASE)
            .find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.cleanText()
        if (!episodeMatch.isNullOrBlank()) return episodeMatch

        val parts = cleaned.split("  ", "\n", "|").map { it.cleanText() }.filter { it.isNotBlank() }
        return parts.firstOrNull { it.length in 3..120 } ?: cleaned.take(120).trim()
    }

    private fun String.isNoiseTitle(): Boolean {
        val value = cleanText().lowercase()
        return value in noiseTitles || value.matches(Regex("^[0-9]+$")) || value.length < 3
    }

    private fun String.isIgnoredUrl(): Boolean {
        val lower = lowercase()
        return ignoredPathParts.any { lower.contains(it) } || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp")
    }

    private fun String.isEpisodeUrl(): Boolean {
        val lower = lowercase()
        return startsWith(mainUrl) && lower.contains("episode") && !lower.contains("/genre/") && !lower.contains("/page/")
    }

    private fun String.extractEpisodeNumber(): Int? {
        return Regex("""(?:episode|eps?|ep)\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(this.cleanText())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun String.extractYear(): Int? {
        return Regex("""\b(19\d{2}|20\d{2})\b""")
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private data class PlayerOption(
        val post: String,
        val nume: String,
        val type: String,
        val label: String
    )

    private data class EpisodeHolder(
        val episode: Int?,
        val data: com.lagradost.cloudstream3.Episode
    )

    private val noiseTitles = setOf(
        "home",
        "anime list",
        "jadwal rilis",
        "link rusak",
        "faq",
        "genre",
        "season",
        "studio",
        "search",
        "next",
        "previous",
        "download batch",
        "batch",
        "trailer"
    )

    private val ignoredPathParts = listOf(
        "/genre/",
        "/season/",
        "/studio/",
        "/tag/",
        "/author/",
        "/wp-content/",
        "/privacy",
        "/dmca",
        "/faq",
        "/link-rusak",
        "#respond",
        "facebook.com",
        "twitter.com",
        "telegram",
        "whatsapp",
        "youtube.com",
        "youtu.be"
    )
}
