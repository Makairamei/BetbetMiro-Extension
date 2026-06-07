package com.sad25kag.Animexin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder

class Animexin : MainAPI() {
    override var mainUrl              = "https://animexin.dev"
    override var name                 = "Animexin"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?status=ongoing&order=update" to "Recently Updated",
        "anime/?status=ongoing&order=popular" to "Popular",
        "anime/?" to "Donghua",
        "anime/?status=&type=movie&page=" to "Movies",
        "genres/action/" to "Action",
        "genres/adventure/" to "Adventure",
        "genres/demon/" to "Demon",
        "genres/fantasy/" to "Fantasy",
        "genres/historical/" to "Historical",
        "genres/martial-arts/" to "Martial Arts",
        "genres/romance/" to "Romance",
        "genres/supernatural/" to "Supernatural"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val link = if (request.data.contains("genres")) {
            "$mainUrl/${request.data}page/$page"
        } else {
            "$mainUrl/${request.data}&page=$page"
        }

        val document = app.get(link).documentLarge
        val home = document.select("div.listupd > article").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = document.select("a.next, li.next a").isNotEmpty()
        )
    }


    private fun inferTvType(title: String, href: String, cardText: String = ""): TvType {
        val haystack = "$title $href $cardText".lowercase()
        return if (
            haystack.contains("/anime/?type=movie") ||
            haystack.contains(" movie") ||
            haystack.contains("movie ")
        ) {
            TvType.Movie
        } else {
            TvType.Anime
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val cardText = text()
        if (cardText.contains("Upcoming", true)) return null

        val anchor = selectFirst("div.bsx > a, a[href]") ?: return null
        val title = anchor.attr("title").ifBlank { anchor.text() }.trim()
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        if (title.isBlank() || !isContentUrl(href)) return null

        val posterUrl = fixUrlNull(selectFirst("div.bsx > a img, img")?.getsrcAttribute().orEmpty())

        return newAnimeSearchResponse(title, href, inferTvType(title, href, cardText)) {
            this.posterUrl = posterUrl
        }
    }

    private fun isContentUrl(url: String): Boolean {
        val clean = url.substringBefore("?").trimEnd('/')
        if (!clean.startsWith(mainUrl)) return false
        val path = clean.removePrefix(mainUrl).trim('/')
        if (path.isBlank()) return false
        if (
            path.startsWith("genres/") ||
            path.startsWith("anime/") ||
            path.startsWith("tag/") ||
            path.startsWith("page/") ||
            path.startsWith("az-list") ||
            path.startsWith("release-date") ||
            path.startsWith("bookmark")
        ) return false

        return path.contains("-episode-", true) || !path.contains("/")
    }

    private fun sameContentUrl(first: String, second: String): Boolean {
        return first.substringBefore("?").trimEnd('/') == second.substringBefore("?").trimEnd('/')
    }

    private fun extractEpisodeList(document: org.jsoup.nodes.Document, poster: String): List<Episode> {
        val episodeRegex = Regex("(\\d+)")
        return document.select(
            "div.eplister > ul > li, .eplister li, .episodelist li, ul li"
        ).mapNotNull { info ->
            val anchor = info.selectFirst("a[href]") ?: return@mapNotNull null
            val href = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
            if (!href.contains("-episode-", true)) return@mapNotNull null

            val posterEpisode = info.selectFirst("a img, img")?.attr("src").orEmpty()
            val epText = info.selectFirst("div.epl-num, .epl-num, a span")?.text()?.ifBlank { anchor.text() } ?: anchor.text()
            val epnum = episodeRegex.find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()

            newEpisode(href) {
                this.episode = epnum
                this.name = epnum?.let { "Episode $it" } ?: epText
                this.posterUrl = posterEpisode.ifBlank { poster }
            }
        }.distinctBy { it.data }.sortedBy { it.episode ?: Int.MAX_VALUE }
    }

    private fun extractMoviePlayData(document: org.jsoup.nodes.Document, detailUrl: String): String {
        val detailSlug = detailUrl.substringBefore("?").trimEnd('/').substringAfterLast('/')
        val movieAnchors = document.select(
            "div.eplister > ul > li a[href], .eplister li a[href], .episodelist li a[href], .epcheck a[href], .bixbox.bxcl a[href]"
        )

        for (anchor in movieAnchors) {
            val href = fixUrlNull(anchor.attr("href")) ?: continue
            if (sameContentUrl(href, detailUrl) || !isContentUrl(href)) continue

            val haystack = "${anchor.text()} $href".lowercase()
            val looksLikeMovieWatchPage = haystack.contains("movie") ||
                haystack.contains("episode") ||
                haystack.contains("subtitle") ||
                haystack.contains("-sub") ||
                haystack.contains(detailSlug)

            if (looksLikeMovieWatchPage) return href
        }

        return detailUrl
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = linkedSetOf<SearchResponse>()
        val queryTokens = query.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 }

        fun SearchResponse.matchesQuery(): Boolean {
            if (queryTokens.isEmpty()) return name.contains(query, true)
            return queryTokens.all { token -> name.contains(token, true) }
        }

        val searchUrls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/page/$page/?s=$encodedQuery"
        ).distinct()

        searchUrls.forEach { url ->
            runCatching {
                app.get(url).documentLarge
                    .select("div.listupd > article")
                    .mapNotNull { it.toSearchResult() }
                    .filter { it.matchesQuery() }
                    .forEach { results.add(it) }
            }
        }

        if (results.isEmpty()) {
            val fallbackPages = if (page <= 1) 1..3 else page..page
            fallbackPages.forEach { fallbackPage ->
                runCatching {
                    app.get("$mainUrl/anime/?status=&order=latest&page=$fallbackPage").documentLarge
                        .select("div.listupd > article")
                        .mapNotNull { it.toSearchResult() }
                        .filter { it.matchesQuery() }
                        .forEach { results.add(it) }
                }
            }
        }

        return results.toList().toNewSearchResponseList()
    }

    @Suppress("SuspiciousIndentation")
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).documentLarge
        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val poster = document.select("div.thumb img").attr("src").ifEmpty {
            document.selectFirst("meta[property=og:image]")?.attr("content")?.trim().orEmpty()
        }
        val description = document.selectFirst("div.entry-content")?.text()?.trim()
        val type = document.selectFirst(".spe")?.text().orEmpty()
        val tvtag = if (type.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        return if (tvtag == TvType.TvSeries) {
            val episodes = extractEpisodeList(document, poster)

            if (episodes.isEmpty()) {
                throw ErrorLoadingException("No episodes found")
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val movieData = extractMoviePlayData(document, url)
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
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
        val pageUrl = data.takeIf { it.startsWith("http", true) } ?: fixUrl(data)
        val visitedPages = linkedSetOf<String>()
        val candidates = linkedSetOf<String>()
        var found = false

        val safeCallback: (ExtractorLink) -> Unit = { link ->
            found = true
            callback(link)
        }

        fun isKnownPlayer(url: String): Boolean {
            val lower = url.lowercase()
            return listOf(
                "dailymotion.com",
                "dai.ly",
                "odysee.com",
                "mega.nz",
                "rumble.com",
                "dood",
                "ok.ru",
                "streamwish",
                "wishfast",
                "filelions",
                "vidhide",
                "vidhidepro",
                "vidguard",
                "streamtape",
                "mixdrop",
                "mp4upload",
                "yourupload",
                "uqload",
                "krakenfiles",
                "abyss",
                "filemoon",
                "lulustream",
                "voe.sx",
                ".m3u8",
                ".mp4",
                ".webm",
                ".mkv"
            ).any { lower.contains(it) }
        }

        fun shouldFollow(url: String): Boolean {
            val lower = url.lowercase()
            return lower.startsWith(mainUrl.lowercase()) && (
                lower.contains("player") ||
                    lower.contains("embed") ||
                    lower.contains("ajax") ||
                    lower.contains("wp-admin") ||
                    lower.contains("wp-content") ||
                    lower.contains("stream")
            )
        }

        fun normalizeUrl(raw: String?): String? {
            var url = raw?.trim()
                ?.trim('"', '\'', '`')
                ?.replace("&amp;", "&")
                ?.replace("\\/", "/")
                ?.replace("\\u0026", "&")
                ?.replace("%3A", ":")
                ?.replace("%2F", "/")
                ?.substringBefore("\\\"")
                ?.substringBefore("\"")
                ?.substringBefore("'")
                ?.takeIf { it.isNotBlank() }
                ?: return null

            if (url.startsWith("//")) url = "https:$url"
            if (url.startsWith("/")) url = "$mainUrl$url"
            if (!url.startsWith("http", true)) return null
            return url
        }

        lateinit var addCandidate: (String?) -> Unit

        fun addFromText(text: String?) {
            val body = text ?: return
            listOf(
                Regex("""https?://[^"'\\< >\n\r\t]+"""),
                Regex("""https?:\\/\\/[^"'\\< >\n\r\t]+"""),
                Regex("""(?<!:)//[A-Za-z0-9][^"'\\< >\n\r\t]+"""),
                Regex("""(?i)(?:src|data-src|data-video|data-url|data-file|data-link|data-embed|file|url)["']?\s*[:=]\s*["']([^"']+)["']""")
            ).forEach { regex ->
                regex.findAll(body).forEach { match ->
                    val value = match.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() } ?: match.value
                    addCandidate(value)
                }
            }
        }

        fun addFromDocument(doc: org.jsoup.nodes.Document) {
            doc.select("iframe[src], iframe[data-src], embed[src], video[src], source[src], a[href]").forEach { element ->
                addCandidate(element.attr("src"))
                addCandidate(element.attr("data-src"))
                addCandidate(element.attr("href"))
            }

            doc.select("[data-src], [data-video], [data-url], [data-file], [data-link], [data-embed]").forEach { element ->
                addCandidate(element.attr("data-src"))
                addCandidate(element.attr("data-video"))
                addCandidate(element.attr("data-url"))
                addCandidate(element.attr("data-file"))
                addCandidate(element.attr("data-link"))
                addCandidate(element.attr("data-embed"))
            }
        }

        addCandidate = candidate@{ raw: String? ->
            val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return@candidate

            if (value.contains("<iframe", true) || value.contains("<video", true) || value.contains("<source", true)) {
                val parsed = Jsoup.parse(value)
                addFromDocument(parsed)
                addFromText(value)
            }

            if (value.length >= 8 && value.matches(Regex("^[A-Za-z0-9+/=_%.-]+$"))) {
                runCatching { base64Decode(value) }.getOrNull()
                    ?.takeIf { decoded ->
                        decoded != value && (
                            decoded.contains("http", true) ||
                                decoded.contains("iframe", true) ||
                                decoded.contains("video", true) ||
                                decoded.contains("source", true)
                            )
                    }
                    ?.let { decoded -> addCandidate(decoded) }
            }

            normalizeUrl(value)?.let { url ->
                if (isKnownPlayer(url) || shouldFollow(url)) candidates.add(url)
            }
        }

        suspend fun scanPage(url: String, referer: String) {
            val normalized = normalizeUrl(url) ?: return
            if (!visitedPages.add(normalized)) return

            val responseText = runCatching {
                app.get(normalized, referer = referer).text
            }.getOrNull() ?: return

            val parsed = Jsoup.parse(responseText, normalized)
            addFromDocument(parsed)
            addFromText(responseText)

            parsed.select(".mobius option, #mobius option, select.mirror option, select option, option").forEach { option ->
                addCandidate(option.attr("value"))
                addCandidate(option.attr("data-src"))
                addCandidate(option.attr("data-url"))
                addCandidate(option.attr("data-file"))
            }
        }

        scanPage(pageUrl, mainUrl)

        candidates.toList().filter { shouldFollow(it) }.forEach { internalPlayer ->
            scanPage(internalPlayer, pageUrl)
        }

        candidates
            .filter { isKnownPlayer(it) }
            .distinct()
            .forEach { link ->
                runCatching {
                    loadExtractor(
                        link,
                        referer = pageUrl,
                        subtitleCallback = subtitleCallback,
                        callback = safeCallback
                    )
                }
            }

        return found
    }

    private fun Element.getsrcAttribute(): String {
        val src = attr("src")
        val dataSrc = attr("data-src")
        return src.takeIf { it.startsWith("http") } ?: dataSrc.takeIf { it.startsWith("http") } ?: ""
    }
}
