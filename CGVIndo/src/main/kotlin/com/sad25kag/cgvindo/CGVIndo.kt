package com.sad25kag.cgvindo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class CGVIndo : MainAPI() {
    override var mainUrl = "http://167.71.211.231"
    override var name = "CGVIndo"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/lates" to "Latest",
        "/genre/action/" to "Action",
        "/genre/drama/" to "Drama",
        "/genre/drama-asia/" to "Drama Asia",
        "/genre/drama-serial-barat/" to "Drama Serial Barat",
        "/genre/drama-korea/" to "Drama Korea"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        val document = runCatching { app.get(url, headers = headers, referer = mainUrl).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val items = parseCards(document)
        return newHomePageResponse(request.name, items, hasNext = hasNextPage(document, page))
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val results = linkedMapOf<String, SearchResponse>()
        listOf("$mainUrl/?s=$encoded", "$mainUrl/search/$encoded/", "$mainUrl/page/1/?s=$encoded").forEach { url ->
            val document = runCatching { app.get(url, headers = headers, referer = mainUrl).document }.getOrNull() ?: return@forEach
            parseCards(document)
                .filter { it.name.contains(keyword, true) || it.url.contains(keyword.slug(), true) }
                .forEach { results[it.url.normalKey()] = it }
            if (results.isNotEmpty()) return results.values.take(60)
        }
        return results.values.take(60)
    }

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = fixUrl(url, mainUrl) ?: return null
        val response = runCatching { app.get(pageUrl, headers = headers, referer = mainUrl) }.getOrNull() ?: return null
        val document = response.document
        val text = cleanText(document.text())
        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1.post-title, h1.title, h1, meta[property=og:title], title")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        ).ifBlank { titleFromUrl(pageUrl) }
        if (title.isBlank()) return null

        val poster = findPoster(document, pageUrl)
        val plot = cleanDescription(
            document.selectFirst("meta[property=og:description], meta[name=description], .entry-content p, .post-content p, .content p, .sinopsis, .synopsis, .description, .desc, [itemprop=description]")
                ?.let { if (it.tagName().equals("meta", true)) it.attr("content") else it.text() }
        )
        val genres = document.select("a[href*='/genre/']")
            .map { cleanText(it.text()).substringBefore("(").trim() }
            .filter { it.length in 2..40 && !it.equals("Home", true) && !it.equals("Nonton", true) && !it.equals("Download", true) }
            .distinct()
            .take(20)
        val actors = document.select("a[href*='/cast/'], a[href*='/actor/'], a[href*='/stars/']")
            .map { cleanText(it.text()) }
            .filter { it.length in 2..60 }
            .distinct()
            .take(24)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val rating = document.selectFirst("[itemprop=ratingValue], .rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        val duration = Regex("""(?i)(\d{1,3})\s*(?:min|menit|m)\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episodes = parseEpisodes(document, pageUrl)
        val recommendations = parseRecommendations(document, pageUrl)
        val type = inferType(pageUrl, title, text, episodes.size)

        return if (type == TvType.TvSeries && episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.tags = genres
                this.duration = duration ?: 0
                this.recommendations = recommendations
                this.plot = plot
                addActors(actors)
                rating?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, pageUrl, type, pageUrl) {
                posterUrl = poster
                this.year = year
                this.tags = genres
                this.duration = duration ?: 0
                this.recommendations = recommendations
                this.plot = plot
                addActors(actors)
                rating?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = fixUrl(data, mainUrl) ?: return false
        val startResponse = runCatching { app.get(startUrl, headers = headers, referer = mainUrl) }.getOrNull() ?: return false
        val startDocument = startResponse.document
        val startHtml = startResponse.text.ifBlank { startDocument.html() }.normalizeEscapes()
        val queue = ArrayDeque<Pair<String, String>>()
        val seen = linkedSetOf<String>()
        var found = false

        collectSubtitles(startDocument, startUrl, subtitleCallback)
        collectMuviproPlayers(startDocument, startUrl).forEach { queue.add(it to startUrl) }
        collectPlayerContainerLinks(startDocument, startUrl).forEach { queue.add(it to startUrl) }
        if (queue.isEmpty()) collectKnownEmbeds(startDocument, startHtml, startUrl).forEach { queue.add(it to startUrl) }

        var guard = 0
        while (queue.isNotEmpty() && guard < 60) {
            guard++
            val (raw, referer) = queue.removeFirst()
            val url = normalizeCandidate(raw, referer) ?: continue
            if (!seen.add(url.normalKey()) || url.isTrailerUrl() || url.isNoiseUrl()) continue

            if (url.startsWith(mainUrl, true)) {
                val response = runCatching { app.get(url, headers = headers + mapOf("Referer" to referer), referer = referer) }.getOrNull() ?: continue
                val document = response.document
                val html = response.text.ifBlank { document.html() }.normalizeEscapes()
                collectSubtitles(document, url, subtitleCallback)
                collectPlayerLinks(document, html, url).forEach { queue.add(it to url) }
                collectMuviproPlayers(document, url).forEach { queue.add(it to url) }
            } else {
                runCatching {
                    loadExtractor(url, referer, subtitleCallback, callback)
                    found = true
                }
                if (url.shouldFetchEmbedPage()) {
                    val response = runCatching { app.get(url, headers = headers + mapOf("Referer" to referer), referer = referer) }.getOrNull()
                    val document = response?.document
                    val html = response?.text?.ifBlank { document?.html().orEmpty() }?.normalizeEscapes().orEmpty()
                    if (document != null) collectPlayerLinks(document, html, url).forEach { queue.add(it to url) }
                }
            }
        }
        return found
    }

    private suspend fun collectMuviproPlayers(document: Document, pageUrl: String): List<String> {
        val pageHtml = document.html().normalizeEscapes()
        val postId = document.selectFirst("[data-post-id], [data-post_id], [data-post]")?.let {
            it.attr("data-post-id").ifBlank { it.attr("data-post_id").ifBlank { it.attr("data-post") } }
        }?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("article[id^=post-]")?.id()?.substringAfter("post-")
            ?: Regex("""(?i)(?:postid-|post-)(\d{2,})""").find(pageHtml)?.groupValues?.getOrNull(1)
            ?: Regex("""(?i)(?:post[_-]?id|postId|post)['"]?\s*[:=]\s*['"]?(\d{2,})""").find(pageHtml)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val tabs = document.select("a[href^='#p'], button[href^='#p'], [data-tab^='p'], [data-target^='#p']")
            .mapNotNull { element ->
                element.attr("href").ifBlank { element.attr("data-tab") }.ifBlank { element.attr("data-target") }
                    .trim().removePrefix("#").takeIf { it.matches(Regex("""p\d+""")) }
            }
            .ifEmpty { (1..6).map { "p$it" } }
            .distinct()

        val links = linkedSetOf<String>()
        tabs.forEach { tab ->
            val body = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "muvipro_player_content", "tab" to tab, "post_id" to postId),
                    headers = headers + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Accept" to "text/html, */*; q=0.01",
                        "Referer" to pageUrl
                    ),
                    referer = pageUrl
                ).text
            }.getOrDefault("")
            collectPlayerLinks(Jsoup.parse(body.normalizeEscapes(), pageUrl), body, pageUrl).forEach { links.add(it) }
        }
        return links.toList()
    }

    private fun collectPlayerContainerLinks(document: Document, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(PLAYER_CONTAINER_SELECTOR).forEach { container ->
            val html = container.outerHtml().normalizeEscapes()
            collectPlayerLinks(Jsoup.parse(html, baseUrl), html, baseUrl).forEach { links.add(it) }
        }
        return links.toList()
    }

    private fun collectPlayerLinks(document: Document, html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select(SERVER_VALUE_SELECTOR).forEach { element ->
            SERVER_VALUE_ATTRS.forEach { attr ->
                element.attr(attr).takeIf { it.isNotBlank() }?.let { value -> decodeServerValue(value, baseUrl).forEach { links.add(it) } }
            }
        }
        document.select(PLAYER_LINK_SELECTOR).forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("data-litespeed-src") }.ifBlank { element.attr("href") }
            normalizeCandidate(raw, baseUrl)?.let { links.add(it) }
        }
        Regex("""(?i)(?:file|source|src|url|link|hls|m3u8|embed_url|iframe_url|player_url)\s*[:=]\s*['"]([^'"]+)['"]""").findAll(html.normalizeEscapes())
            .mapNotNull { normalizeCandidate(it.groupValues[1], baseUrl) }
            .forEach { links.add(it) }
        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""").findAll(html.normalizeEscapes())
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded -> decodeServerValue(decoded, baseUrl).forEach { links.add(it) } }
        collectKnownEmbeds(document, html, baseUrl).forEach { links.add(it) }
        return links.filterNot { it.isTrailerUrl() || it.isNoiseUrl() }.toList()
    }

    private fun collectKnownEmbeds(document: Document, html: String, baseUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("iframe[src], iframe[data-src], embed[src], video[src], source[src]").forEach { element ->
            normalizeCandidate(element.attr("src").ifBlank { element.attr("data-src") }, baseUrl)?.let { links.add(it) }
        }
        Regex("""(?i)(?:https?:)?//[^'"<>\s]+""").findAll(html.normalizeEscapes())
            .mapNotNull { normalizeCandidate(it.value, baseUrl) }
            .filter { it.looksLikePlayerUrl() }
            .forEach { links.add(it) }
        return links.filterNot { it.isTrailerUrl() || it.isNoiseUrl() }.toList()
    }

    private fun decodeServerValue(raw: String, baseUrl: String): List<String> {
        val values = linkedSetOf<String>()
        fun add(value: String?) {
            val v = value?.trim().orEmpty().normalizeEscapes()
            if (v.isNotBlank()) values.add(v)
        }
        add(raw)
        add(runCatching { URLDecoder.decode(raw.normalizeEscapes(), "UTF-8") }.getOrNull())
        add(decodeBase64(raw))
        val links = linkedSetOf<String>()
        values.forEach { value ->
            normalizeCandidate(value, baseUrl)?.let { links.add(it) }
            Regex("""(?i)(?:https?:)?//[^'"<>\s]+""").findAll(value)
                .mapNotNull { normalizeCandidate(it.value, baseUrl) }
                .forEach { links.add(it) }
            if (value.contains("<iframe", true) || value.contains("<video", true) || value.contains("<source", true)) {
                collectPlayerLinks(Jsoup.parse(value, baseUrl), value, baseUrl).forEach { links.add(it) }
            }
        }
        return links.filterNot { it.isTrailerUrl() || it.isNoiseUrl() }.toList()
    }

    private fun normalizeCandidate(raw: String?, baseUrl: String): String? {
        val value = raw?.trim().orEmpty().normalizeEscapes()
        if (value.isBlank() || value.startsWith("#") || value.startsWith("javascript:", true)) return null
        if (value.contains("<iframe", true) || value.contains("<video", true) || value.contains("<source", true)) {
            return Jsoup.parse(value, baseUrl).selectFirst("iframe[src], video[src], source[src]")
                ?.let { normalizeCandidate(it.attr("src"), baseUrl) }
        }
        return fixUrl(value, baseUrl)
    }

    private fun decodeBase64(value: String): String? = runCatching {
        val clean = value.trim().substringBefore("?").substringBefore("&")
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private fun fixUrl(raw: String?, baseUrl: String = mainUrl): String? {
        val value = raw?.trim().orEmpty().normalizeEscapes()
        if (value.isBlank() || value.startsWith("#") || value.startsWith("javascript:", true)) return null
        return when {
            value.startsWith("//") -> "http:$value"
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("/") -> mainUrl.trimEnd('/') + value
            else -> baseUrl.substringBeforeLast('/', baseUrl).trimEnd('/') + "/" + value
        }
    }

    private fun pageUrl(path: String, page: Int): String {
        val fixed = fixUrl(path, mainUrl) ?: mainUrl
        return if (page <= 1) fixed else fixed.trimEnd('/') + "/page/$page/"
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        document.select(CARD_SELECTOR).forEach { it.toSearchResult()?.let { item -> results[item.url.normalKey()] = item } }
        return results.values.take(80)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName().equals("a", true) && hasAttr("href")) this else selectFirst("h1 a[href], h2 a[href], h3 a[href], .entry-title a[href], .title a[href], .name a[href], a[href][title], a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"), mainUrl) ?: return null
        if (!isContentUrl(href)) return null
        val container = anchor.closest("article.item-infinite, article.item, article.has-post-thumbnail, article, .post, .movie, .film, .item, .card, .result, .thumbnail, li, div") ?: this
        val image = container.selectFirst(".content-thumbnail img[data-src], .content-thumbnail img[src], img[data-src], img[data-original], img[data-lazy-src], img[src], img[srcset]") ?: anchor.selectFirst("img")
        val title = listOf(
            container.selectFirst("h1.entry-title, h2.entry-title, h3.entry-title, h1, h2, h3, .post-title, .title, .name")?.text(),
            anchor.attr("title").substringAfter("Permalink ke:").trim(),
            image?.attr("alt"),
            anchor.text(),
            titleFromUrl(href)
        ).firstOrNull { isUsefulTitle(it) }?.let { cleanTitle(it) } ?: return null
        val poster = image?.imageUrl(mainUrl) ?: container.styleImage(mainUrl)
        val text = cleanText(container.text())
        val type = inferType(href, title, text, 0)
        val year = Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(text)?.value?.toIntOrNull()
        val score = container.selectFirst(".rating, .score, .imdb, .vote")?.text()?.replace(",", ".")
            ?.let { Regex("""\d+(?:\.\d+)?""").find(it)?.value?.toDoubleOrNull() }
        return if (type == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieSearchResponse(title, href, type) {
                posterUrl = poster
                this.year = year
                score?.let { this.score = Score.from10(it) }
            }
        }
    }

    private fun parseEpisodes(document: Document, baseUrl: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        document.select(".episode-list a[href], .episodes a[href], .episodios a[href], .season a[href], .seasons a[href], [class*=episode] a[href], [id*=episode] a[href]")
            .forEachIndexed { index, element ->
                val href = fixUrl(element.attr("href"), baseUrl) ?: return@forEachIndexed
                val label = cleanText(element.text()).ifBlank { titleFromUrl(href) }
                val ep = Regex("""(?i)(?:episode|eps|ep)\s*[-:.]?\s*(\d{1,4})""").find("$label $href")?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)
                episodes[href] = newEpisode(href) {
                    name = label.ifBlank { "Episode $ep" }
                    episode = ep
                }
            }
        return episodes.values.sortedBy { it.episode ?: 9999 }
    }

    private fun parseRecommendations(document: Document, currentUrl: String): List<SearchResponse> =
        document.select(".related, .recommend, .rekomendasi, .similar, section")
            .flatMap { section -> section.select(CARD_SELECTOR).mapNotNull { it.toSearchResult() } }
            .distinctBy { it.url.normalKey() }
            .filterNot { it.url.normalKey() == currentUrl.normalKey() }
            .take(16)

    private fun collectSubtitles(document: Document, baseUrl: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.srt], a[href$=.vtt]").forEach { element ->
            val url = fixUrl(element.attr("src").ifBlank { element.attr("href") }, baseUrl) ?: return@forEach
            val label = cleanText(element.attr("label").ifBlank { element.attr("srclang").ifBlank { element.text().ifBlank { "Subtitle" } } })
            subtitleCallback(SubtitleFile(label, url))
        }
    }

    private fun findPoster(document: Document, baseUrl: String): String? =
        document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")?.let { fixUrl(it, baseUrl) }
            ?: document.selectFirst(".poster img, .thumb img, .thumbnail img, .entry-content img, article img, img")?.imageUrl(baseUrl)
            ?: document.body()?.styleImage(baseUrl)

    private fun hasNextPage(document: Document, page: Int): Boolean =
        document.select("a.next, .next a, a[rel=next], .pagination a, .nav-links a").any { it.attr("href").contains("/page/${page + 1}") || cleanText(it.text()).contains("next", true) }

    private fun inferType(url: String, title: String, text: String, episodeCount: Int): TvType {
        val haystack = "$url $title $text".lowercase(Locale.ROOT)
        return when {
            episodeCount > 0 -> TvType.TvSeries
            listOf("/series", "/tv", "drama", "episode", "season", "eps").any { haystack.contains(it) } -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun String.looksLikePlayerUrl(): Boolean = contains("/embed", true) || contains("/e/", true) || contains("/video", true) || contains(".m3u8", true) || contains(".mp4", true)

    private fun String.shouldFetchEmbedPage(): Boolean = contains("byseqekaho", true) || contains("q8y5z", true) || contains("/embed", true) || contains("/e/", true)

    private fun String.isTrailerUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("youtube.com") || lower.contains("youtu.be") || lower.contains("youtube-nocookie.com")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return BAD_MEDIA_PARTS.any { lower.contains(it) } || lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".gif") || lower.endsWith(".webp")
    }

    private fun isContentUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith(mainUrl.lowercase(Locale.ROOT))) return false
        if (BAD_URL_PARTS.any { lower.contains(it) }) return false
        return !lower.endsWith(".jpg") && !lower.endsWith(".png") && !lower.endsWith(".webp") && !lower.endsWith(".gif")
    }

    private fun isUsefulTitle(value: String?): Boolean {
        val title = cleanText(value)
        if (title.length < 2) return false
        return title.lowercase(Locale.ROOT) !in setOf("home", "beranda", "nonton", "download", "play", "trailer", "more", "selengkapnya")
    }

    private fun Element.imageUrl(baseUrl: String): String? {
        val raw = attr("data-src").ifBlank { attr("data-original").ifBlank { attr("data-lazy-src").ifBlank { attr("src") } } }
            .ifBlank { attr("srcset").substringBefore(" ") }
        return fixUrl(raw, baseUrl)
    }

    private fun Element.styleImage(baseUrl: String): String? = Regex("""url\(['"]?([^'")]+)['"]?\)""").find(attr("style"))?.groupValues?.getOrNull(1)?.let { fixUrl(it, baseUrl) }

    private fun titleFromUrl(url: String): String = url.substringBeforeLast('/').substringAfterLast('/').replace('-', ' ').replace('_', ' ').trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> if (c.isLowerCase()) c.titlecase(Locale.ROOT) else c.toString() } }

    private fun cleanTitle(value: String?): String = cleanText(value).replace(Regex("""(?i)\s*[-|]\s*(CGVIndo|Nonton|Streaming).*$"""), "").replace(Regex("""(?i)^nonton\s+"""), "").trim()

    private fun cleanDescription(value: String?): String? = cleanText(value).takeIf { it.length >= 12 }

    private fun cleanText(value: String?): String = value.orEmpty().replace('\u00a0', ' ').replace(Regex("""\s+"""), " ").trim()

    private fun String.slug(): String = lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), "-").trim('-')

    private fun String.normalKey(): String = trim().trimEnd('/').lowercase(Locale.ROOT)

    private fun String.normalizeEscapes(): String = replace("\\/", "/").replace("&amp;", "&").replace("&#038;", "&").replace("&quot;", "\"").replace("&#039;", "'")

    companion object {
        private const val CARD_SELECTOR = "article.item-infinite, article.item, article.has-post-thumbnail, .gmr-box-content, .post, .movie, .film, .item, .card, .result, .thumbnail, .ml-item, .grid-item"
        private const val PLAYER_CONTAINER_SELECTOR = "#player, #player2, #pembed, #video, .player, .muvipro-player, .movieplay, .gmr-player, .video-content, .embed-responsive, .server, .server-list, .mirror, .mobius, .tab-content, [id^=p], [class*=player], [class*=server], [class*=mirror]"
        private const val SERVER_VALUE_SELECTOR = "select option[value], .mobius option[value], .mirror option[value], .server option[value], .server-list option[value], [data-video], [data-embed], [data-url], [data-link], [data-src], [data-player], [data-iframe], [value]"
        private const val PLAYER_LINK_SELECTOR = "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], video[src], video source[src], source[src], a[href*='embed'], a[href*='player'], a[href*='/e/'], a[href*='/v/'], a[href*='/video'], a[href*='.m3u8'], a[href*='.mp4'], a[href*='/hls/'], a[href*='/stream/']"
        private val SERVER_VALUE_ATTRS = listOf("value", "data-video", "data-embed", "data-url", "data-link", "data-src", "data-player", "data-iframe", "src", "href")
        private val BAD_URL_PARTS = listOf("/wp-content/", "/category/", "/genre/", "/tag/", "/author/", "/page/", "/quality/", "/country/", "/director/", "/cast/", "/actor/", "#", "javascript:", "mailto:")
        private val BAD_MEDIA_PARTS = listOf("doubleclick", "googlesyndication", "google-analytics", "/ads", "banner")
    }
}
