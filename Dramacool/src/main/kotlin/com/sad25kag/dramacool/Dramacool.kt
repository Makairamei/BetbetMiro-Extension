package com.sad25kag.dramacool

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Dramacool : MainAPI() {
    override var mainUrl = "https://asianctv.in"
    override var name = "Dramacool"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val usesWebView = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
    )

    override val mainPage = mainPageOf(
        "home#left-tab-1" to "Recently Drama",
        "home#left-tab-2" to "Recently Movie",
        "home#left-tab-3" to "Recently KShow",
        "most-popular-drama/page/%d/" to "Popular Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val safePage = if (page < 1) 1 else page
        val isHomeTab = request.data.startsWith("home#")
        val url = if (isHomeTab) "$mainUrl/" else buildMainPageUrl(request.data, safePage)

        val document = app.get(
            url,
            headers = defaultHeaders,
            referer = "$mainUrl/"
        ).document

        val container = if (isHomeTab) {
            val tabClass = request.data.substringAfter("#")
            document.selectFirst(".content-left .tab-content.$tabClass")
        } else {
            document.selectFirst(".content-left .tab-content.left-tab-1")
                ?: document.selectFirst(".content-left")
        }

        val forceMovie = request.name.contains("Movie", true) || request.data.contains("movie", true)
        val home = container
            ?.select("ul.switch-block.list-episode-item > li, ul.list-episode-item > li, ul.list-episode-item-2 > li")
            ?.mapNotNull { it.toSearchResult(forceMovie) }
            ?.distinctBy { it.url }
            .orEmpty()

        val hasNext = !isHomeTab && document.select(
            ".content-left .pagination a:contains(Next), .content-left .pagination a.next, " +
                ".content-left .pagination a.nextpostslink, .content-left a[href*='/page/${safePage + 1}/']"
        ).isNotEmpty()

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    private fun buildMainPageUrl(template: String, page: Int): String {
        val path = if (page <= 1) {
            template
                .replace("page/%d/", "")
                .replace("page/%d", "")
                .replace("?page=%d", "")
        } else {
            template.format(page)
        }.trimStart('/')

        return if (path.isBlank()) "$mainUrl/" else "$mainUrl/$path"
    }

    private fun Element.toSearchResult(forceMovie: Boolean = false): SearchResponse? {
        val detail = selectFirst("a[href*=/series/], a[href*=/drama-detail/]")
        val episode = selectFirst("a[href*=episode-], a[href*=/video-watch/]")
        val anchor = detail ?: episode ?: if (tagName().equals("a", true) && hasAttr("href")) this else selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!isSupportedContentUrl(href) || isIgnoredUrl(href)) return null

        val title = anchor.attr("title").takeIf { it.isNotBlank() }
            ?: selectFirst("h3.title, h2.title, .title, h3, h2")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: anchor.text().trim()

        if (title.isBlank() || title.equals("View more", true) || title.equals("Show all episodes", true)) return null

        val poster = getPosterUrl()
        val cleanTitle = title.cleanCardTitle()
        val year = selectFirst("span.year")?.text()?.toIntOrNull()
            ?: Regex("""\((\d{4})\)""").find(cleanTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val type = when {
            forceMovie -> TvType.Movie
            href.contains("movie", true) -> TvType.Movie
            else -> TvType.AsianDrama
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.AsianDrama) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        if (encodedQuery.isBlank()) return emptyList()

        val searchUrls = listOf(
            "$mainUrl/?s=$encodedQuery",
            "$mainUrl/search?keyword=$encodedQuery",
            "$mainUrl/search?type=movies&keyword=$encodedQuery"
        )

        searchUrls.forEach { searchUrl ->
            val htmlResults = runCatching {
                app.get(searchUrl, headers = defaultHeaders, referer = mainUrl).document
                    .select(
                        ".content-left .tab-content.left-tab-1 ul.switch-block.list-episode-item > li, " +
                            ".content-left .tab-content.left-tab-1 ul.list-episode-item > li, " +
                            ".content-left .tab-content.left-tab-1 ul.list-episode-item-2 > li, " +
                            ".content-left .search-results li"
                    )
                    .mapNotNull { it.toSearchResult() }
                    .distinctBy { it.url }
            }.getOrNull().orEmpty()

            if (htmlResults.isNotEmpty()) return htmlResults
        }

        val apiText = runCatching {
            app.get(
                "$mainUrl/api?a=search&keyword=$encodedQuery&type=drama",
                headers = defaultHeaders,
                referer = mainUrl
            ).text
        }.getOrNull() ?: return emptyList()

        val apiResults = runCatching {
            AppUtils.parseJson<List<SearchItem>>(apiText)
        }.getOrNull() ?: return emptyList()

        return apiResults.mapNotNull { item ->
            val title = item.name ?: item.value ?: return@mapNotNull null
            val url = fixUrlNull(item.url ?: return@mapNotNull null) ?: return@mapNotNull null
            val type = getTypeFromUrl(url)

            if (type == TvType.Movie) {
                newMovieSearchResponse(title.cleanCardTitle(), url, TvType.Movie) {
                    this.posterUrl = fixUrlNull(item.cover)
                    this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            } else {
                newTvSeriesSearchResponse(title.cleanCardTitle(), url, TvType.AsianDrama) {
                    this.posterUrl = fixUrlNull(item.cover)
                    this.year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val detailUrl = if (isDetailUrl(url)) url else getDetailUrl(url) ?: episodeUrlToSeriesUrl(url) ?: url

        val document = app.get(
            detailUrl,
            headers = defaultHeaders,
            referer = mainUrl
        ).document

        val episodeSeriesTitle = if (isEpisodeUrl(url)) {
            document.selectFirst(".content-left .category a[href*=/series/]")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        } else null

        val title = episodeSeriesTitle
            ?: document.selectFirst(".content-left div.info h1, .content-left h1.entry-title, .content-left h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("div.details div.img img, div.img img, .thumb img, .poster img, meta[property=og:image]")
                ?.let { element ->
                    if (element.tagName() == "meta") element.attr("content") else element.attr("data-src").takeIf { it.isNotBlank() }
                        ?: element.attr("data-original").takeIf { it.isNotBlank() }
                        ?: element.attr("src")
                }
        )

        val description = document.select("div.info p, .entry-content p, .description, .desc")
            .firstOrNull { element ->
                val text = element.text().trim()
                text.isNotBlank() && !text.contains(":") && !text.contains("Comments", true)
            }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val country = getInfoValues(document, "Country")
        val genres = getInfoValues(document, "Genre")
        val statusText = getInfoValues(document, "Status").firstOrNull()
        val tags = (country + genres).filter { it.isNotBlank() }.distinct()

        val status = getStatus(statusText)
        val year = Regex("""\((\d{4})\)""").find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val plot = buildPlotWithInfo(document, description)

        val episodes = document.select(".content-left ul.list-episode-item-2.all-episode > li, .content-left ul.all-episode > li")
            .mapNotNull { it.toEpisode(title) }
            .ifEmpty {
                document.select(".content-left .plugins2 select option[value*=episode-], .content-left .plugins2 select option[value*=/video-watch/]")
                    .mapNotNull { it.toEpisodeFromOption(title) }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })

        val recommendations = document.select(
            "div.content-right a[href*=/series/], ul.switch-block a[href*=/series/], " +
                "div.content-right a[href*=/drama-detail/], ul.switch-block a[href*=/drama-detail/], " +
                ".related a[href*=/series/], .popular a[href*=/series/]"
        )
            .mapNotNull { it.parent()?.toSearchResult() ?: it.toSearchResult() }
            .distinctBy { it.url }

        val isMovie = title.contains("movie", true) ||
            detailUrl.contains("movie", true) ||
            tags.any { it.contains("Movie", true) } ||
            episodes.size <= 1 && document.select("div.info p:contains(Type:), .type").text().contains("Movie", true)

        return if (isMovie) {
            newMovieLoadResponse(title.cleanCardTitle(), detailUrl, TvType.Movie, episodes.firstOrNull()?.data ?: detailUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newTvSeriesLoadResponse(title.cleanCardTitle(), detailUrl, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.showStatus = status
                this.recommendations = recommendations
            }
        }
    }

    private fun Element.toEpisode(showTitle: String? = null): Episode? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!isSupportedContentUrl(href) || !isEpisodeUrl(href)) return null

        val name = selectFirst("h3.title, h2.title, .title, h3, h2")?.text()?.trim()
            ?: attr("title").takeIf { it.isNotBlank() }
            ?: text().trim()

        if (!showTitle.isNullOrBlank() && !name.belongsToSeries(showTitle)) return null

        val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(name)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\bEP\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        val dateText = selectFirst("span.time, .time, .date")?.text()
            ?: parent()?.selectFirst("span.time, .time, .date")?.text()

        return newEpisode(href) {
            this.name = name.cleanTitle()
            this.episode = epNum
            addDate(dateText)
        }
    }

    private fun Element.toEpisodeFromOption(showTitle: String? = null): Episode? {
        val href = fixUrlNull(attr("value")) ?: return null
        if (!isSupportedContentUrl(href) || !isEpisodeUrl(href)) return null

        val name = text().trim().takeIf { it.isNotBlank() }
            ?: attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val fullName = if (!showTitle.isNullOrBlank() && name.startsWith("Episode", true)) {
            "$showTitle $name"
        } else {
            name
        }

        val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(fullName)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return newEpisode(href) {
            this.name = fullName.cleanTitle()
            this.episode = epNum
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(
            data,
            headers = defaultHeaders,
            referer = mainUrl
        ).document

        val html = document.html()
        val links = document.select(
            ".content-left .anime_muti_link li[data-video], .content-left .watch_video iframe[src], " +
                ".content-left #block-tab-video iframe[src], iframe[src*='vidbasic.live/stream/'], " +
                "source[src*='.m3u8'], source[src*='.mp4'], video[src*='.m3u8'], video[src*='.mp4'], " +
                "a[href*='vidbasic.live/stream/']"
        )
            .mapNotNull { element ->
                val raw = element.attr("data-video").takeIf { it.isNotBlank() }
                    ?: element.attr("data-link").takeIf { it.isNotBlank() }
                    ?: element.attr("data-src").takeIf { it.isNotBlank() }
                    ?: element.attr("data-url").takeIf { it.isNotBlank() }
                    ?: element.attr("src").takeIf { it.isNotBlank() }
                    ?: element.attr("href").takeIf { it.isNotBlank() }
                raw?.trim()?.let { fixUrlNull(it) }
            }
            .plus(extractUrlCandidates(html))
            .filterNot { isIgnoredUrl(it) || it.contains("javascript:", true) || it == data }
            .distinct()

        var delivered = false

        links.forEach { link ->
            runCatching {
                if (deliverVideoLink(link, data, subtitleCallback, callback)) delivered = true
            }
        }

        if (!delivered) {
            getWebViewVideoLinks(data).forEach { link ->
                runCatching {
                    if (deliverVideoLink(link, data, subtitleCallback, callback)) delivered = true
                }
            }
        }

        return delivered
    }

    private suspend fun deliverVideoLink(
        link: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resolved = resolveExtractorUrl(link)
        return when {
            resolved.contains("vidbasic.live/stream/", true) -> deliverVidBasicLink(resolved, subtitleCallback, callback)
            resolved.contains(".m3u8", true) -> {
                val generated = M3u8Helper.generateM3u8(
                    name,
                    resolved,
                    referer,
                    headers = mapOf(
                        "Referer" to referer,
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT
                    )
                )
                generated.forEach(callback)
                generated.isNotEmpty()
            }
            resolved.contains(".mp4", true) -> {
                callback.invoke(
                    newExtractorLink(name, name, resolved, ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            }
            else -> loadExtractor(resolved, referer, subtitleCallback, callback)
        }
    }

    private suspend fun deliverVidBasicLink(
        streamUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = extractVidBasicId(streamUrl) ?: return false
        val apiUrl = "https://vidbasic.live/stream/getSources?id=$id&id=$id"
        val responseText = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to USER_AGENT
                ),
                referer = streamUrl
            ).text
        }.getOrNull() ?: return false

        val response = runCatching {
            AppUtils.parseJson<VidBasicResponse>(responseText)
        }.getOrNull() ?: return false

        response.tracks.orEmpty().forEach { track ->
            val file = track.file?.replace("\\/", "/")?.takeIf { it.isNotBlank() } ?: return@forEach
            subtitleCallback.invoke(
                SubtitleFile(
                    track.label?.takeIf { it.isNotBlank() } ?: track.kind?.takeIf { it.isNotBlank() } ?: "Subtitle",
                    file
                )
            )
        }

        val direct = response.sources?.file
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() }
            ?: return false

        val generated = M3u8Helper.generateM3u8(
            "VidBasic",
            direct,
            streamUrl,
            headers = mapOf(
                "Referer" to "https://vidbasic.live/",
                "Origin" to "https://vidbasic.live",
                "User-Agent" to USER_AGENT
            )
        )
        generated.forEach(callback)
        return generated.isNotEmpty()
    }

    private fun extractVidBasicId(url: String): String? {
        return Regex("""/stream/(?:s-\d+/)?(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: Regex("""[?&]id=(\d+)""", RegexOption.IGNORE_CASE)
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
    }

    private suspend fun getWebViewVideoLinks(url: String): List<String> {
        val found = linkedSetOf<String>()
        val script = """
            (function() {
                var out = [];
                function absolute(value) {
                    if (!value) return null;
                    value = String(value).trim();
                    if (!value) return null;
                    try {
                        if (value.indexOf('http') === 0) return value;
                        if (value.indexOf('//') === 0) return location.protocol + value;
                        if (value.charAt(0) === '/') return location.origin + value;
                        if (/(m3u8|mp4|vidbasic\.live\/stream\/)/i.test(value)) {
                            return new URL(value, location.href).href;
                        }
                    } catch(e) {}
                    return value;
                }
                function add(value) {
                    value = absolute(value);
                    if (!value) return;
                    if (/https?:\/\//i.test(value) && /(m3u8|mp4|vidbasic\.live\/stream\/)/i.test(value)) {
                        out.push(value);
                    }
                }
                try {
                    var servers = document.querySelectorAll('li, a, button, div');
                    for (var s = 0; s < servers.length; s++) {
                        var txt = (servers[s].textContent || '').toLowerCase();
                        if (txt.indexOf('standard server') > -1 || txt.indexOf('choose this server') > -1) {
                            try { servers[s].click(); } catch(e) {}
                        }
                    }
                } catch(e) {}
                try {
                    document.querySelectorAll('iframe[src], video[src], source[src], a[href], [data-video], [data-src], [data-link], [data-url], [data-embed]').forEach(function(el) {
                        add(el.getAttribute('data-video'));
                        add(el.getAttribute('data-src'));
                        add(el.getAttribute('data-link'));
                        add(el.getAttribute('data-url'));
                        add(el.getAttribute('data-embed'));
                        add(el.getAttribute('src'));
                        add(el.getAttribute('href'));
                    });
                } catch(e) {}
                try {
                    if (window.jwplayer) {
                        var player = window.jwplayer();
                        add(JSON.stringify(player.getPlaylist && player.getPlaylist()));
                        add(JSON.stringify(player.getPlaylistItem && player.getPlaylistItem()));
                        add(JSON.stringify(player.getConfig && player.getConfig()));
                    }
                } catch(e) {}
                try {
                    var html = document.documentElement.outerHTML;
                    var re = /https?:[^"'<>\s]+(?:m3u8|mp4|vidbasic\.live\/stream\/)[^"'<>\s]*/ig;
                    var match;
                    while ((match = re.exec(html)) !== null) add(match[0]);
                } catch(e) {}
                return out.join('\n');
            })()
        """.trimIndent()

        val resolver = WebViewResolver(
            interceptUrl = Regex("""https?://[^"'<>\s]+(?:m3u8|mp4|vidbasic\.live/stream/)[^"'<>\s]*""", RegexOption.IGNORE_CASE),
            additionalUrls = listOf(Regex(""".*""")),
            useOkhttp = false,
            script = script,
            scriptCallback = { result ->
                extractUrlCandidates(result ?: "").forEach { found.add(it) }
            },
            timeout = 30_000L
        )

        runCatching {
            resolver.resolveUsingWebView(
                url = url,
                referer = mainUrl,
                requestCallBack = { found.isNotEmpty() }
            )
        }

        return found.toList()
    }

    private fun extractUrlCandidates(text: String): List<String> {
        return Regex("""https?://[^"'<>\\\s]+(?:\.m3u8|\.mp4|vidbasic\.live/stream/)[^"'<>\\\s]*""", RegexOption.IGNORE_CASE)
            .findAll(text.replace("\\/", "/"))
            .map { it.value.trim().trim('"', '\'', ',', ';') }
            .filter { it.startsWith("http", true) && !isIgnoredUrl(it) }
            .distinct()
            .toList()
    }

    private suspend fun resolveExtractorUrl(url: String): String {
        return url
    }

    private suspend fun getDetailUrl(url: String): String? {
        return app.get(
            url,
            headers = defaultHeaders,
            referer = mainUrl
        ).document
            .selectFirst(
                ".content-left .category a[href*=/series/], .content-left .category a[href*=/drama-detail/]"
            )
            ?.attr("href")
            ?.let { fixUrl(it) }
    }

    private fun episodeUrlToSeriesUrl(url: String): String? {
        if (!isEpisodeUrl(url)) return null
        val path = Regex("""https?://[^/]+/([^?#]+)/?""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?: url.trim('/').substringAfterLast('/')

        val seriesSlug = path
            .replace(Regex("""-episode-\d+.*$""", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotBlank() }
            ?: return null

        return "$mainUrl/series/$seriesSlug/"
    }

    private fun getInfoValues(document: Element, label: String): List<String> {
        val nodes = document.select("div.info p, .info p, .entry-content p, .post-content p, .meta, .details p")
        nodes.forEach { node ->
            val text = node.text().trim()
            if (text.contains("$label:", true)) {
                val links = node.select("a")
                    .map { it.text().trim() }
                    .filter { it.isNotBlank() && !it.equals(label, true) }
                    .distinct()
                if (links.isNotEmpty()) return links

                return text.substringAfter(":", "")
                    .split(",", "|", "/")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals(label, true) }
                    .distinct()
            }
        }
        return emptyList()
    }

    private fun buildPlotWithInfo(document: Element, description: String?): String? {
        val infoLines = listOf(
            "Other name" to getInfoValues(document, "Other name"),
            "Original Network" to getInfoValues(document, "Original Network"),
            "Director" to getInfoValues(document, "Director"),
            "Country" to getInfoValues(document, "Country"),
            "Status" to getInfoValues(document, "Status"),
            "Genre" to getInfoValues(document, "Genre")
        ).mapNotNull { (label, values) ->
            values.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "$label: $it" }
        }

        return listOfNotNull(
            description?.takeIf { it.isNotBlank() },
            infoLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
        ).joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun Element.getPosterUrl(): String? {
        val candidates = mutableListOf<Element>()
        var current: Element? = this
        repeat(5) {
            current?.let { element ->
                candidates.add(element)
                current = element.parent()
            }
        }

        candidates.forEach { candidate ->
            val img = candidate.selectFirst("img[data-original], img[data-src], img[data-lazy-src], img[data-wpfc-original-src], img[data-cfsrc], img[srcset], img[data-srcset], img[src]")
            val raw = img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-wpfc-original-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-cfsrc")?.takeIf { it.isNotBlank() }
                ?: img?.attr("srcset")?.takeIf { it.isNotBlank() }?.srcSetToUrl()
                ?: img?.attr("data-srcset")?.takeIf { it.isNotBlank() }?.srcSetToUrl()
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
                ?: candidate.attr("style").backgroundImageToUrl()

            fixUrlNull(raw ?: "")?.takeIf { it.isValidPosterUrl() }?.let { return it }
        }

        return null
    }

    private fun String.cleanTitle(): String {
        return replace(Regex("""\s+"""), " ")
            .replace("SUB ", "", ignoreCase = true)
            .replace("DUB ", "", ignoreCase = true)
            .trim()
    }

    private fun String.cleanCardTitle(): String {
        return cleanTitle()
            .replace(Regex("""^\d+\s+"""), "")
            .replace(Regex("""\s+Episode\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+EP\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\d+\s*(?:seconds?|minutes?|hours?|days?|weeks?|months?)\s+ago.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+about\s+\d+\s+\w+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+delayed.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun String.belongsToSeries(seriesTitle: String): Boolean {
        val episodeTitle = cleanTitle().lowercase()
        val normalizedSeries = seriesTitle.cleanTitle()
            .replace(Regex("""\s+\(\d{4}\)"""), "")
            .lowercase()
            .trim()
        if (normalizedSeries.isBlank()) return true
        if (episodeTitle.contains(normalizedSeries)) return true

        val words = normalizedSeries.split(Regex("""\s+""")).filter { it.length > 2 }
        return words.take(2).isNotEmpty() && words.take(2).all { episodeTitle.contains(it) }
    }

    private fun String.srcSetToUrl(): String? {
        return split(",")
            .map { it.trim().substringBefore(" ").trim() }
            .firstOrNull { it.isNotBlank() }
    }

    private fun String.backgroundImageToUrl(): String? {
        return Regex("""url\((['\"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(2)
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.isValidPosterUrl(): Boolean {
        val value = trim()
        return value.isNotBlank() &&
            !value.startsWith("data:", true) &&
            !value.contains("blank", true) &&
            !value.contains("placeholder", true) &&
            !value.contains("/logo", true) &&
            !value.contains("favicon", true)
    }

    private fun isDetailUrl(url: String): Boolean {
        return url.contains("/series/", true) || url.contains("/drama-detail/", true)
    }

    private fun isEpisodeUrl(url: String): Boolean {
        return url.contains("episode-", true) || url.contains("/video-watch/", true)
    }

    private fun isSupportedContentUrl(url: String): Boolean {
        return isDetailUrl(url) || isEpisodeUrl(url)
    }

    private fun isIgnoredUrl(url: String): Boolean {
        return url.contains("/wp-content/", true) ||
            url.contains("/wp-admin/", true) ||
            url.contains("/wp-login", true) ||
            url.contains("/login", true) ||
            url.contains("/about", true) ||
            url.contains("/drama-list", true) ||
            url.contains("/popular-star", true) ||
            url.contains("/genres", true) ||
            url.contains("/genre/", true) ||
            url.contains("/country/", true) ||
            url.contains("/tag/", true) ||
            url.contains("/cdn-cgi/", true) ||
            url.contains("discord", true) ||
            url.contains("telegram", true) ||
            url.startsWith("mailto:", true) ||
            url.startsWith("javascript:", true) ||
            url == mainUrl || url == "$mainUrl/"
    }

    private fun getTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/country/") && url.contains("movie", true) -> TvType.Movie
            url.contains("movie", true) -> TvType.Movie
            else -> TvType.AsianDrama
        }
    }

    private fun getStatus(status: String?): ShowStatus? {
        return when {
            status?.contains("ongoing", true) == true -> ShowStatus.Ongoing
            status?.contains("completed", true) == true -> ShowStatus.Completed
            else -> null
        }
    }

    private val defaultHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    data class SearchItem(
        @JsonProperty("value") val value: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("cover") val cover: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("status") val status: String?,
    )

    data class VidBasicResponse(
        @JsonProperty("sources") val sources: VidBasicSources?,
        @JsonProperty("tracks") val tracks: List<VidBasicTrack>?,
    )

    data class VidBasicSources(
        @JsonProperty("file") val file: String?,
    )

    data class VidBasicTrack(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?,
    )
}
