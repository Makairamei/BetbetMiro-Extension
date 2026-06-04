package com.sad25kag.saikonime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

class SaikoNime : MainAPI() {
    override var mainUrl = "https://saikonime.ink"
    override var name = "SaikoNime"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.7,en;q=0.5",
        "Referer" to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "browse?sort=updated&status=Ongoing" to "Baru Diupdate",
        "browse?sort=popular" to "Trending Anime",
        "browse?status=Completed" to "Selesai Tayang",
        "browse?type=Movie" to "Top Movies",
        "genre/action" to "Action",
        "genre/adventure" to "Adventure",
        "genre/aksi" to "Aksi",
        "genre/donghua" to "Donghua",
        "genre/fantasi" to "Fantasi",
        "genre/komedi" to "Komedi",
        "genre/romansa" to "Romansa",
        "genre/shounen" to "Shounen",
        "genre/isekai" to "Isekai",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = browserHeaders).document
        val results = parseSaikoCards(document).distinctBy { it.url.normalizedKey() }
        val hasNext = document.selectFirst("link[rel=next], a[rel=next], .pagination a.next[href], a.next[href]") != null || results.isNotEmpty()
        return newHomePageResponse(request.name, results, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val routes = listOf(
            "$mainUrl/browse?keyword=$encoded",
            "$mainUrl/search?keyword=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?s=$encoded",
        )

        return routes.flatMap { url ->
            runCatching {
                val document = app.get(url, headers = browserHeaders).document
                parseSaikoCards(document)
            }.getOrDefault(emptyList())
        }.distinctBy { it.url.normalizedKey() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = url.toAbsoluteUrl() ?: url
        val document = app.get(fixedUrl, headers = browserHeaders).document
        val title = cleanTitle(
            document.selectFirst("h1, .title h1, .anime-title, .post-title")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.title()
        ) ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.toAbsoluteUrl()
            ?: document.selectFirst(".poster img, .cover img, .thumb img, img[alt*='${title.take(12)}']")?.imageUrl()
            ?: document.selectFirst("img")?.imageUrl()

        val plot = document.select(".synopsis, .description, .desc, .entry-content, article p")
            .map { it.text().cleanText() }
            .filter { value -> value.isNotBlank() && !value.contains("WATCH NOW", true) && !value.contains("INFO", true) }
            .distinct()
            .take(3)
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.cleanText()

        val tags = document.select("a[href*='/genre/']")
            .map { it.text().cleanText() }
            .filter { it.isNotBlank() }
            .distinct()

        val year = document.text().let { text ->
            Regex("""(?:Released|Tahun|Year)\s*:?\s*(\d{4})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""\b(20\d{2}|19\d{2})\b""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        val episodes = parseEpisodes(document).distinctBy { it.data.normalizedKey() }
        val recommendations = parseSaikoCards(document)
            .filterNot { it.url.normalizedKey() == fixedUrl.normalizedKey() }
            .take(16)

        val tvType = if (fixedUrl.contains("/episode/", true) && episodes.isEmpty()) TvType.AnimeMovie else TvType.Anime
        return if (episodes.isNotEmpty() && !fixedUrl.contains("/episode/", true)) {
            newTvSeriesLoadResponse(title, fixedUrl, TvType.Anime, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
                this.showStatus = detectStatus(document)
            }
        } else {
            val dataUrl = if (fixedUrl.contains("/episode/", true)) fixedUrl else episodes.firstOrNull()?.data ?: fixedUrl
            newMovieLoadResponse(title, fixedUrl, tvType, dataUrl) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
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
        val pageUrl = data.toAbsoluteUrl() ?: data
        val document = app.get(pageUrl, headers = browserHeaders).document
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(rawUrl: String?, label: String? = null, referer: String = pageUrl): Boolean {
            val videoUrl = rawUrl?.decodeEmbedText()?.toAbsoluteUrl(referer)?.takeIf { it.isDirectMediaLike() } ?: return false
            if (!emitted.add(videoUrl.substringBefore("#"))) return true
            val sourceName = listOfNotNull(name, label?.cleanText()?.takeIf { it.isNotBlank() }).joinToString(" - ")
            val type = if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(sourceName, sourceName, videoUrl, type) {
                    this.referer = referer
                    this.quality = getQualityFromName(label ?: videoUrl)
                    this.headers = browserHeaders + mapOf(
                        "Referer" to referer,
                        "Origin" to originOf(referer),
                    )
                },
            )
            return true
        }

        val candidates = collectPlayerCandidates(document, pageUrl)
        for (candidate in candidates.take(40)) {
            val playerUrl = candidate.decodeEmbedText().toAbsoluteUrl(pageUrl) ?: continue
            if (emitDirect(playerUrl, hostLabel(playerUrl), pageUrl)) continue

            val countedCallback: (ExtractorLink) -> Unit = { link ->
                if (emitted.add(link.url.substringBefore("#"))) callback.invoke(link)
            }

            runCatching { loadExtractor(playerUrl, pageUrl, subtitleCallback, countedCallback) }
            if (emitted.isNotEmpty()) continue

            val playerHtml = runCatching {
                app.get(playerUrl, headers = browserHeaders + mapOf("Referer" to pageUrl), referer = pageUrl).text
            }.getOrNull() ?: continue
            val unpacked = runCatching { getAndUnpack(playerHtml) }.getOrNull().orEmpty()
            val nested = collectUrlsFromText(playerHtml + "\n" + unpacked, playerUrl)
            for (nestedUrl in nested.take(20)) {
                if (emitDirect(nestedUrl, hostLabel(playerUrl), playerUrl)) continue
                val fixedNested = nestedUrl.toAbsoluteUrl(playerUrl) ?: continue
                runCatching { loadExtractor(fixedNested, playerUrl, subtitleCallback, countedCallback) }
            }
        }
        return emitted.isNotEmpty()
    }

    private fun buildPageUrl(path: String, page: Int): String {
        val cleanPath = path.trim().trimStart('/')
        val base = if (cleanPath.startsWith("http", true)) cleanPath else "$mainUrl/$cleanPath"
        return when {
            page <= 1 -> base
            base.contains("?") -> "$base&page=$page"
            else -> "$base?page=$page"
        }
    }

    private fun parseSaikoCards(document: Document): List<SearchResponse> {
        val roots = mutableListOf<Element>()
        val selectors = listOf(
            "article", ".anime-card", ".movie-card", ".grid > div", ".list > div", ".swiper-slide", ".card", ".item",
            "a[href*='/anime/']"
        )
        selectors.forEach { selector -> document.select(selector).forEach { roots.add(it) } }

        return roots.asSequence()
            .mapNotNull { it.toSaikoCard() }
            .distinctBy { it.url.normalizedKey() }
            .toList()
    }

    private fun Element.toSaikoCard(): SearchResponse? {
        val anchor = when {
            tagName().equals("a", true) -> this
            else -> selectFirst("a[href*='/anime/']:not([href*='/episode/']), a[href*='/anime/'][href*='/episode/'], h2 a[href], h3 a[href], h4 a[href], a[href]")
        } ?: return null
        val rawHref = anchor.attr("href").toAbsoluteUrl() ?: return null
        if (!rawHref.contains("/anime/", true) || rawHref.contains("#")) return null
        val href = rawHref.toInfoUrl()
        if (!href.contains(mainUrl, true)) return null

        val rawTitle = anchor.attr("title").cleanText().takeIf { it.length > 2 }
            ?: anchor.selectFirst("img[alt]")?.attr("alt")?.cleanText()?.takeIf { it.length > 2 }
            ?: selectFirst("h1, h2, h3, h4, .title, .name, .tt")?.text()?.cleanText()?.takeIf { it.length > 2 }
            ?: anchor.text().cleanText().takeIf { it.length > 2 }
            ?: return null
        val title = cleanTitle(rawTitle) ?: rawTitle
        if (title.equals("WATCH NOW", true) || title.equals("INFO", true)) return null

        val poster = selectFirst("img")?.imageUrl() ?: anchor.selectFirst("img")?.imageUrl()
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to "$mainUrl/")
        }
    }

    private fun parseEpisodes(document: Document) = document.select("a[href*='/episode/']")
        .mapNotNull { anchor ->
            val href = anchor.attr("href").toAbsoluteUrl() ?: return@mapNotNull null
            val rawTitle = anchor.attr("title").cleanText().takeIf { it.isNotBlank() }
                ?: anchor.text().cleanText().takeIf { it.isNotBlank() }
                ?: href.substringAfterLast("/episode/").trim('/').let { "Episode $it" }
            val epNumber = rawTitle.toEpisodeNumber() ?: href.toEpisodeNumber()
            newEpisode(href) {
                this.name = cleanTitle(rawTitle) ?: rawTitle
                this.episode = epNumber
                this.posterUrl = anchor.selectFirst("img")?.imageUrl()
            }
        }

    private fun collectPlayerCandidates(document: Document, referer: String): LinkedHashSet<String> {
        val candidates = linkedSetOf<String>()
        document.select("iframe[src], embed[src], video[src], source[src]").forEach { element ->
            element.attr("src").takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        }
        document.select("select option[value], .server option[value], .mirror option[value], button[value], [data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-stream]").forEach { element ->
            listOf("value", "data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-stream")
                .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .forEach { raw ->
                    candidates.add(raw)
                    decodePossibleBase64(raw)?.let { decoded -> collectUrlsFromText(decoded, referer).forEach { candidates.add(it) } }
                    runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull()?.let { decoded -> collectUrlsFromText(decoded, referer).forEach { candidates.add(it) } }
                }
        }
        collectUrlsFromText(document.html(), referer).forEach { candidates.add(it) }
        return candidates
    }

    private fun collectUrlsFromText(text: String, base: String): List<String> {
        val normalized = text.decodeEmbedText()
        val urls = linkedSetOf<String>()
        Regex("""<(?:iframe|embed|source|video)[^>]+(?:src|data-src)=['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""(?:src|file|url|source|embed|iframe|data-url|data-src|data-file|data-player|data-stream)\s*[:=]\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .forEach { urls.add(it.groupValues[1]) }
        Regex("""atob\(['\"]([^'\"]+)['\"]\)""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .mapNotNull { decodePossibleBase64(it.groupValues[1]) }
            .forEach { decoded -> collectUrlsFromText(decoded, base).forEach { urls.add(it) } }
        Regex("""https?://[^'\"<>()\s]+""", RegexOption.IGNORE_CASE)
            .findAll(normalized)
            .map { it.value.trimEnd(',', ';') }
            .forEach { urls.add(it) }
        return urls.mapNotNull { it.toAbsoluteUrl(base) }.filter { it.isPotentialPlayer() }
    }

    private fun decodePossibleBase64(value: String): String? {
        val clean = value.trim().trim('"', '\'')
        if (clean.length < 8 || clean.contains("<")) return null
        return runCatching { base64Decode(clean) }.getOrNull()
            ?: runCatching {
                val fixed = clean.replace('-', '+').replace('_', '/').let { raw -> raw + "=".repeat((4 - raw.length % 4) % 4) }
                String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
            }.getOrNull()
    }

    private fun detectStatus(document: Document): ShowStatus? {
        val text = document.text().lowercase(Locale.ROOT)
        return when {
            text.contains("completed") || text.contains("selesai") -> ShowStatus.Completed
            text.contains("ongoing") || text.contains("tayang") -> ShowStatus.Ongoing
            else -> null
        }
    }

    private fun Element.imageUrl(): String? {
        val raw = attr("data-src").takeIf { it.isNotBlank() }
            ?: attr("data-lazy-src").takeIf { it.isNotBlank() }
            ?: attr("data-original").takeIf { it.isNotBlank() }
            ?: attr("src").takeIf { it.isNotBlank() }
        return raw?.toAbsoluteUrl()
    }

    private fun cleanTitle(raw: String?): String? {
        return raw?.htmlUnescape()?.cleanText()
            ?.replace(Regex("""(?i)\s*[-–|]\s*Saikonime.*$"""), "")
            ?.replace(Regex("""(?i)\s*Subtitle\s+Indonesia.*$"""), "")
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.toInfoUrl(): String = replace(Regex("""/episode/[^/?#]+.*$""", RegexOption.IGNORE_CASE), "").trimEnd('/')

    private fun String.cleanText(): String = htmlUnescape()
        .replace("\u00a0", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun String.htmlUnescape(): String = Jsoup.parse(this).text()

    private fun String.decodeEmbedText(): String = htmlUnescape()
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u003A", ":")
        .replace("\\u003D", "=")
        .replace("\\u0026", "&")
        .replace("&quot;", "\"")
        .replace("&#038;", "&")
        .replace("&amp;", "&")
        .trim()

    private fun String.toAbsoluteUrl(base: String = mainUrl): String? {
        val clean = trim().trim('"', '\'', ' ', '\n', '\r', '\t')
        if (clean.isBlank() || clean.startsWith("javascript", true) || clean.startsWith("#")) return null
        return runCatching { URI(base).resolve(clean).toString() }.getOrNull()
    }

    private fun String.normalizedKey(): String = substringBefore("#").substringBefore("?").trimEnd('/').lowercase(Locale.ROOT)

    private fun String.toEpisodeNumber(): Int? = Regex("""(?i)(?:episode|eps?|ep)\s*(\d+)""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""/episode/(\d+)""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
        ?: Regex("""\b(\d{1,4})\b""").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun String.isDirectMediaLike(): Boolean {
        val value = lowercase(Locale.ROOT)
        return value.contains(".m3u8") || value.contains(".mp4") || value.contains(".webm") || value.contains(".mkv") || value.contains("videoplayback")
    }

    private fun String.isPotentialPlayer(): Boolean {
        val value = lowercase(Locale.ROOT)
        if (isDirectMediaLike()) return true
        return listOf(
            "iframe", "embed", "player", "stream", "vidhide", "filemoon", "streamwish", "streamruby", "dood", "mp4upload", "blogger", "googlevideo", "ok.ru", "dailymotion", "rumble", "mega.nz", "krakenfiles", "acefile"
        ).any { value.contains(it) }
    }

    private fun originOf(url: String): String = runCatching {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}"
    }.getOrDefault(mainUrl)

    private fun hostLabel(url: String): String = runCatching { URI(url).host ?: url }.getOrDefault(url)
}
