package com.sad25kag.jagoanhentai

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class JagoanHentai : MainAPI() {
    override var mainUrl = "https://jagoanhentai.fun"
    override var name = "JagoanHentai"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "/" to "Episode Terbaru",
        "/anime/?status=&type=&order=update" to "Series Update",
        "/anime/?status=ongoing&type=&order=update" to "Ongoing",
        "/anime/?status=completed&type=&order=update" to "Completed",
        "/genres/big-oppai/" to "Big Oppai",
        "/genres/blowjob/" to "Blowjob",
        "/genres/milf/" to "MILF",
        "/genres/ahegao/" to "Ahegao",
        "/genres/creampie/" to "Creampie",
        "/genres/uncensored/" to "Uncensored",
        "/genres/femdom/" to "Femdom",
        "/genres/paihame/" to "Paihame",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(buildPageUrl(request.data, page), headers = browserHeaders).document
        val results = parseCards(document)
            .filterNot { it.isBlockedResult() }
            .distinctBy { it.url.normalizeKey() }
        val hasNext = document.selectFirst("a.next[href], a[rel=next], .pagination a[href]:contains(Next), .hpage a[href]:contains(Next), .nav-links a[href]:contains(Next)") != null
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/anime/?s=$encoded",
        )
        return routes.flatMap { url ->
            runCatching { parseCards(app.get(url, headers = browserHeaders).document) }.getOrDefault(emptyList())
        }.filterNot { it.isBlockedResult() }.distinctBy { it.url.normalizeKey() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = browserHeaders).document
        if (document.isBlockedPage()) return null

        val title = cleanTitle(
            document.selectFirst("h1.entry-title, h1[itemprop=name], h1, meta[property=og:title]")?.let {
                if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
            } ?: document.title(),
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            ?: document.selectFirst(".thumb img, .tb img, .bigcontent img, .animefull img, .single-info img, article img")?.imageUrl()

        val plot = document.selectFirst(".synp .entry-content, .desc, .mindes, .entry-content p, meta[name=description]")?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.cleanText()

        val tags = document.select(".genxed a[href*='/genres/'], a[href*='/genres/'], a[rel=tag]")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() && !it.isBlockedTerm() }
            .distinct()

        val recommendations = parseCards(document)
            .filterNot { it.url.normalizeKey() == url.normalizeKey() || it.isBlockedResult() }
            .take(16)

        val episodes = parseEpisodes(document)
            .filterNot { it.data.isBlockedTerm() || (it.name ?: "").isBlockedTerm() }
            .distinctBy { it.data.normalizeKey() }

        return if (episodes.isNotEmpty() && !looksEpisodePage(url, title)) {
            newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes.sortedBy { it.episode ?: Int.MAX_VALUE }) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                this.showStatus = detectStatus(document)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlockedTerm()) return false
        val document = app.get(data, headers = browserHeaders).document
        if (document.isBlockedPage()) return false

        val emitted = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        candidates.addAll(collectStaticPlayers(document, data))
        candidates.addAll(collectMirrorPlayers(document, data))
        candidates.addAll(collectScriptPlayers(document.html(), data))

        for (candidate in candidates.take(40)) {
            val playerUrl = candidate.toAbsoluteUrl(data) ?: continue
            if (!playerUrl.isSupportedPlayerUrl()) continue
            val before = emitted.size
            val countedCallback: (ExtractorLink) -> Unit = { link ->
                emitted.add(link.url.substringBefore("#"))
                callback.invoke(link)
            }
            runCatching { loadExtractor(playerUrl, data, subtitleCallback, countedCallback) }
            if (emitted.size > before) continue

            val playerHtml = runCatching {
                app.get(playerUrl, headers = browserHeaders + mapOf("Referer" to data), referer = data).text
            }.getOrDefault("")
            val nested = collectScriptPlayers(playerHtml, playerUrl)
            for (nestedUrl in nested.take(10)) {
                runCatching { loadExtractor(nestedUrl, playerUrl, subtitleCallback, countedCallback) }
            }
        }

        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val base = when {
            path.startsWith("http", true) -> path.trimEnd('/')
            path == "/" || path.isBlank() -> mainUrl
            else -> mainUrl + path
        }
        return when {
            page <= 1 -> base
            base.contains("?") -> base + "&paged=$page"
            else -> base.trimEnd('/') + "/page/$page/"
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val selectors = listOf(
            ".listupd article",
            ".listupd .bs",
            ".listupd .bsx",
            ".excstf article",
            ".bsx",
            "article:has(a[href*='/anime/'])",
            "article:has(a[href*='episode'])",
            ".ongoingseries li",
            ".serieslist li",
        )
        return selectors.asSequence()
            .flatMap { document.select(it).asSequence() }
            .mapNotNull { it.toCard() }
            .distinctBy { it.url.normalizeKey() }
            .toList()
    }

    private fun Element.toCard(): SearchResponse? {
        val anchor = selectFirst("a.tip[href], .bsx a[href], h2 a[href], h3 a[href], .tt a[href], a[href*='/anime/'], a[href*='episode']") ?: return null
        val href = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!href.contains(mainUrl) || href.contains("/genres/") || href.contains("/studio/") || href.contains("/author/")) return null
        val title = cleanTitle(
            anchor.attr("title").cleanText().takeIf { it.length > 2 }
                ?: anchor.selectFirst("h2, h3, .tt, .title")?.text()?.cleanText()?.takeIf { it.length > 2 }
                ?: selectFirst("h2, h3, .tt, .title, .epl-title")?.text()?.cleanText()?.takeIf { it.length > 2 }
                ?: anchor.text().cleanText().takeIf { it.length > 2 },
        ) ?: return null
        val poster = anchor.selectFirst("img")?.imageUrl()
            ?: selectFirst("img")?.imageUrl()
            ?: styleImage()
        val responseType = if (href.contains("/anime/")) TvType.NSFW else TvType.NSFW
        return newMovieSearchResponse(title, href, responseType) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document): List<Episode> {
        val selectors = listOf(
            ".eplister li a[href]",
            ".episodelist li a[href]",
            ".episodelist a[href*='episode']",
            ".episode-list a[href*='episode']",
            ".epslist a[href*='episode']",
            ".lastend a[href*='episode']",
            ".entry-content a[href*='episode']",
        )
        return selectors.asSequence()
            .flatMap { document.select(it).asSequence() }
            .mapNotNull { anchor ->
                val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
                if (!href.contains(mainUrl) || !href.contains("episode", true)) return@mapNotNull null
                val rawTitle = anchor.selectFirst(".epl-title, .title, .ep-title, h2, h3")?.text()
                    ?: anchor.attr("title").takeIf { it.isNotBlank() }
                    ?: anchor.text()
                val title = cleanTitle(rawTitle)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val episodeNumber = anchor.selectFirst(".epl-num")?.text()?.toIntOrNull()
                    ?: Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: Regex("""episode-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                newEpisode(href) {
                    this.name = title
                    this.episode = episodeNumber
                    this.posterUrl = anchor.selectFirst("img")?.imageUrl()
                }
            }.distinctBy { it.data.normalizeKey() }
            .toList()
    }

    private fun collectStaticPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("#pembed iframe[src], .player-embed iframe[src], #embed_holder iframe[src], iframe[src], embed[src], video[src], video source[src], source[src]")
            .forEach { it.attr("src").toAbsoluteUrl(pageUrl)?.let(links::add) }
        return links.toList()
    }

    private fun collectMirrorPlayers(document: Document, pageUrl: String): List<String> {
        val links = linkedSetOf<String>()
        document.select("select.mirror option[value], option[value]").forEach { option ->
            val decoded = option.attr("value").decodeBase64Safe() ?: option.attr("value").decodeUrlLike()
            links.addAll(collectScriptPlayers(decoded, pageUrl))
        }
        return links.toList()
    }

    private fun collectScriptPlayers(raw: String, baseUrl: String): List<String> {
        val html = raw.decodeUrlLike()
        val links = linkedSetOf<String>()
        Jsoup.parse(html).select("iframe[src], embed[src], source[src], video[src], a[href]").forEach { element ->
            val value = element.attr("src").ifBlank { element.attr("href") }
            value.toAbsoluteUrl(baseUrl)?.let(links::add)
        }
        Regex("""(?i)(?:file|url|src|source|embed|embed_url|player|iframe)\s*[:=]\s*['\"]([^'\"]+)['\"]""")
            .findAll(html)
            .forEach { it.groupValues.getOrNull(1)?.toAbsoluteUrl(baseUrl)?.let(links::add) }
        Regex("""(?i)https?:\\?/\\?/[^'\"<>\s]+""")
            .findAll(html)
            .forEach { it.value.toAbsoluteUrl(baseUrl)?.let(links::add) }
        return links.filter { it.isSupportedPlayerUrl() }.distinct()
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.select(".spe, .info-content, .status, .infodetail").text().lowercase()
        return when {
            "ongoing" in text -> ShowStatus.Ongoing
            "completed" in text || "complete" in text -> ShowStatus.Completed
            else -> null
        }
    }

    private fun looksEpisodePage(url: String, title: String): Boolean =
        url.contains("episode", true) || Regex("""(?i)episode\s*\d+""").containsMatchIn(title)

    private fun cleanTitle(value: String?): String? {
        val text = value?.cleanText()?.takeIf { it.isNotBlank() } ?: return null
        return text
            .substringBefore(" - Jagoan Hentai", text)
            .substringBefore(" | Jagoan Hentai", text)
            .replace(Regex("""(?i)^\s*(TV|OVA|ONA|Movie)\s+Ep\s+\d+\s+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun Element.imageUrl(): String? = attr("data-src").ifBlank { attr("data-lazy-src") }
        .ifBlank { attr("data-original") }
        .ifBlank { attr("src") }
        .toAbsoluteUrl()

    private fun Element.styleImage(): String? = Regex("""url\(['\"]?([^)'"]+)""")
        .find(attr("style"))?.groupValues?.getOrNull(1)?.toAbsoluteUrl()

    private fun Document.isBlockedPage(): Boolean {
        val tagText = select(".genxed a, a[href*='/genres/'], .spe, .info-content").text()
        val titleText = selectFirst("h1, meta[property=og:title]")?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }.orEmpty()
        return (tagText + " " + titleText).isBlockedTerm()
    }

    private fun SearchResponse.isBlockedResult(): Boolean = name.isBlockedTerm() || url.isBlockedTerm()

    private fun String.isBlockedTerm(): Boolean {
        val value = lowercase()
        return listOf("loli", "lolicon", "shota", "shotacon", "underage").any { value.contains(it) }
    }

    private fun String.cleanText(): String = Jsoup.parse(this).text()
        .replace("\\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.decodeUrlLike(): String = trim()
        .replace("\\/", "/")
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&#039;", "'")
        .replace("&amp;", "&")
        .replace("&#038;", "&")
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3F", "?", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)

    private fun String.decodeBase64Safe(): String? = runCatching {
        val normalized = trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        base64Decode(padded)
    }.getOrNull()

    private fun String?.toAbsoluteUrl(base: String = mainUrl): String? {
        val raw = this?.trim()?.trim('"', '\'', ' ')?.decodeUrlLike()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("#") || raw.startsWith("javascript:", true) || raw.startsWith("whatsapp:", true) -> null
            else -> runCatching { URI(base).resolve(raw).toString() }.getOrNull()
        }
    }

    private fun String.isSupportedPlayerUrl(): Boolean {
        val value = lowercase()
        if (!value.startsWith("http")) return false
        if (Regex("""\.(?:jpg|jpeg|png|webp|gif|svg|css|woff|woff2|ttf)(?:\?|$)""").containsMatchIn(value)) return false
        return listOf(
            "playmogo", "dood", "d000d", "doodstream", "streamtape", "filemoon", "streamwish",
            "vidhide", "voe", "mixdrop", "mp4upload", "streamruby", "vidguard", "luluvdo",
            "sbembed", "blogger", "blogspot", "googlevideo", "embed", "player", ".m3u8", ".mp4",
        ).any { value.contains(it) }
    }

    private fun String.normalizeKey(): String = trim().trimEnd('/').lowercase()
}
