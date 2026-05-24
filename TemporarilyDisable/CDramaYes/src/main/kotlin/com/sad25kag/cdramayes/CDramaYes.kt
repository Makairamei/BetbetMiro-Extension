package com.sad25kag.cdramayes

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class CDramaYes : MainAPI() {
    override var mainUrl = "https://c-dramayes.com"
    override var name = "CDramaYes"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "" to "Beranda",
        "series" to "Series List",
        "az-list" to "AZ List",
        "schedule" to "Schedule",

        "category/the-prisoner-of-beauty" to "The Prisoner of Beauty",
        "category/hello-beautiful-life" to "Hello Beautiful Life",
        "category/the-fragments-of-kylin" to "The Fragments of Kylin",
        "category/ray-of-light-2023" to "Ray of Light 2023",

        "series/begin-again" to "Begin Again",
        "series/abyss-dweller" to "Abyss Dweller",
        "series/brilliant-girls" to "Brilliant Girls",
        "series/three-body" to "Three-Body",
        "series/dragon-day-youre-dead" to "Dragon Day, You're Dead",
        "series/the-longest-promise" to "The Longest Promise"
    )

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = buildPageUrl(request.data, page)
        val document = app.get(url, headers = commonHeaders).document

        if (request.data.isBlank() && page <= 1) {
            val rows = parseHomeRows(document)
            if (rows.isNotEmpty()) return newHomePageResponse(rows)
        }

        val list = parseCards(document)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            list,
            hasNext = document.selectFirst(
                "a[rel=next], " +
                    "a.next, " +
                    ".pagination a:contains(Next), " +
                    ".pagination a:contains(›), " +
                    "a.page-numbers:contains(»), " +
                    "a[href*='/page/${page + 1}/'], " +
                    "a[href*='paged=${page + 1}']"
            ) != null || list.isNotEmpty()
        )
    }

    private fun buildPageUrl(
        path: String,
        page: Int
    ): String {
        val cleanPath = path.trim('/')

        return when {
            cleanPath.isBlank() && page <= 1 -> mainUrl
            cleanPath.isBlank() -> "$mainUrl/page/$page/"
            page <= 1 -> "$mainUrl/$cleanPath/"
            else -> "$mainUrl/$cleanPath/page/$page/"
        }
    }

    private fun parseHomeRows(document: Document): List<HomePageList> {
        val rows = mutableListOf<HomePageList>()

        document.select(
            "section, " +
                ".bixbox, " +
                ".listupd, " +
                ".postbody, " +
                ".serieslist, " +
                ".items"
        ).forEach { section ->
            val title = section.selectFirst(
                "h1, h2, h3, " +
                    ".releases h2, " +
                    ".releases h3, " +
                    ".heading, " +
                    ".title"
            )?.text()
                ?.cleanTitle()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach

            val items = section.select(
                "article, " +
                    ".bs, " +
                    ".bsx, " +
                    ".utao, " +
                    ".listupd a[href], " +
                    "a[href*='/series/'], " +
                    "a[href*='episode']"
            ).mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                rows.add(HomePageList(title, items))
            }
        }

        if (rows.isEmpty()) {
            val fallback = parseCards(document)
            if (fallback.isNotEmpty()) {
                rows.add(HomePageList("Beranda", fallback))
            }
        }

        return rows.distinctBy { it.name }
    }

    private fun parseCards(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(
            "article, " +
                ".bs, " +
                ".bsx, " +
                ".utao, " +
                ".listupd article, " +
                ".listupd .bs, " +
                ".serieslist li, " +
                ".items article, " +
                "a[href*='/series/'], " +
                "a[href*='episode']"
        ).forEach { element ->
            element.toSearchResult()?.let { item ->
                results[item.url] = item
            }
        }

        return results.values.toList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.`is`("a[href]")) {
            this
        } else {
            selectFirst(
                "h2 a[href], " +
                    "h3 a[href], " +
                    ".tt a[href], " +
                    ".entry-title a[href], " +
                    ".bsx a[href], " +
                    "a[href*='/series/'], " +
                    "a[href*='episode'], " +
                    "a[href]"
            ) ?: return null
        }

        val href = fixUrlNull(anchor.attr("href")) ?: return null

        if (!href.startsWith(mainUrl)) return null
        if (isBlockedUrl(href)) return null

        val rawTitle = listOf(
            selectFirst("h2")?.text()?.trim(),
            selectFirst("h3")?.text()?.trim(),
            selectFirst(".tt")?.text()?.trim(),
            selectFirst(".entry-title")?.text()?.trim(),
            selectFirst(".series-title")?.text()?.trim(),
            selectFirst(".ep-title")?.text()?.trim(),
            anchor.attr("title").trim(),
            anchor.attr("aria-label").trim(),
            selectFirst("img[alt]")?.attr("alt")?.trim(),
            anchor.text().trim()
        ).firstOrNull {
            !it.isNullOrBlank() &&
                !it.equals("Home", true) &&
                !it.equals("Series List", true) &&
                !it.equals("AZ List", true) &&
                !it.equals("Schedule", true) &&
                !it.equals("Bookmark", true) &&
                !it.equals("My Account", true) &&
                !it.equals("Next", true)
        } ?: return null

        val title = rawTitle.cleanTitle()
            .takeIf { it.length >= 2 }
            ?: return null

        val poster = fixUrlNull(
            selectFirst("img")?.getImageAttr()
        )

        return newTvSeriesSearchResponse(
            title,
            normalizeToSeriesUrl(href),
            TvType.AsianDrama
        ) {
            posterUrl = poster
        }
    }

    private fun normalizeToSeriesUrl(url: String): String {
        if (url.contains("/series/", true)) return url

        val episodeSlug = url.substringAfter(mainUrl)
            .trim('/')
            .substringBefore("?")

        val seriesSlug = episodeSlug
            .replace(Regex("""-episode-\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""-ep-\d+.*$""", RegexOption.IGNORE_CASE), "")

        return if (seriesSlug.isNotBlank() && seriesSlug != episodeSlug) {
            "$mainUrl/series/$seriesSlug/"
        } else {
            url
        }
    }

    private fun isBlockedUrl(url: String): Boolean {
        val path = url.substringAfter(mainUrl).trim('/').lowercase()

        if (path.isBlank()) return true

        val blocked = listOf(
            "wp-login",
            "my-account",
            "bookmark",
            "privacy",
            "dmca",
            "contact",
            "tag/",
            "author/",
            "page/"
        )

        return blocked.any { path == it || path.startsWith(it) }
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            return newSearchResponseList(emptyList(), hasNext = false)
        }

        val encoded = URLEncoder.encode(keyword, "UTF-8")

        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl/page/${page.coerceAtLeast(1)}/?s=$encoded",
            "$mainUrl/series/?s=$encoded"
        )

        val results = linkedMapOf<String, SearchResponse>()

        urls.forEach { url ->
            val document = runCatching {
                app.get(url, headers = commonHeaders).document
            }.getOrNull() ?: return@forEach

            parseCards(document)
                .filter { it.name.contains(keyword, ignoreCase = true) }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) return@forEach
        }

        return newSearchResponseList(
            results.values.toList(),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val realUrl = normalizeToSeriesUrl(url)
        val document = app.get(realUrl, headers = commonHeaders).document

        val title = document.selectFirst(
            "h1.entry-title, " +
                "h1, " +
                ".entry-title, " +
                ".series-title, " +
                "meta[property=og:title], " +
                "meta[name=title]"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.cleanTitle()
            ?.takeIf { it.isNotBlank() }
            ?: realUrl.substringAfterLast("/").replace("-", " ").cleanTitle()

        val poster = fixUrlNull(
            document.selectFirst(
                "meta[property=og:image], " +
                    "meta[name=twitter:image], " +
                    ".thumb img, " +
                    ".poster img, " +
                    ".series-thumb img, " +
                    "img.wp-post-image, " +
                    "picture img, " +
                    "img"
            )?.let { element ->
                when {
                    element.hasAttr("content") -> element.attr("content")
                    else -> element.getImageAttr()
                }
            }
        )

        val description = document.selectFirst(
            "meta[property=og:description], " +
                "meta[name=description], " +
                ".entry-content p, " +
                ".entry-content, " +
                ".desc, " +
                ".sinopsis, " +
                ".synopsis, " +
                ".series-synops"
        )?.let { element ->
            when {
                element.hasAttr("content") -> element.attr("content")
                else -> element.text()
            }
        }?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/category/'], " +
                ".genres a, " +
                ".genre a, " +
                ".category a"
        ).map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val statusText = document.selectFirst(
            ".status, " +
                "span:contains(Status), " +
                "div:contains(Status)"
        )?.text().orEmpty()

        val episodes = parseEpisodes(document, realUrl, title)

        val recommendations = parseCards(document)
            .filter { it.url != realUrl }
            .distinctBy { it.url }

        return newTvSeriesLoadResponse(
            title,
            realUrl,
            TvType.AsianDrama,
            episodes
        ) {
            posterUrl = poster
            plot = description
            this.tags = tags
            showStatus = when {
                statusText.contains("ongoing", true) -> ShowStatus.Ongoing
                statusText.contains("completed", true) -> ShowStatus.Completed
                statusText.contains("complete", true) -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = recommendations
        }
    }

    private fun parseEpisodes(
        document: Document,
        fallbackUrl: String,
        seriesTitle: String
    ): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()

        document.select(
            "a[href*='episode-'], " +
                "a[href*='ep-'], " +
                "a:contains(Episode), " +
                "a:contains(Eps), " +
                "[class*=episode] a[href], " +
                ".eplister a[href], " +
                ".episodelist a[href], " +
                ".episode-list a[href], " +
                ".bixbox a[href]"
        ).forEachIndexed { index, element ->
            val href = fixUrlNull(element.attr("href")) ?: return@forEachIndexed

            if (!href.startsWith(mainUrl)) return@forEachIndexed

            val isEpisode = href.contains("episode", true) ||
                href.contains("/ep", true) ||
                element.text().contains("episode", true) ||
                element.text().contains("eps", true)

            if (!isEpisode) return@forEachIndexed

            val text = element.text().trim()
            val epNum = extractEpisodeNumber(text, href) ?: index + 1

            episodes[href] = newEpisode(href) {
                name = text.cleanEpisodeTitle().ifBlank { "Episode $epNum" }
                episode = epNum
            }
        }

        return episodes.values
            .sortedBy { it.episode ?: Int.MAX_VALUE }
            .ifEmpty {
                listOf(
                    newEpisode(fallbackUrl) {
                        name = seriesTitle
                        episode = 1
                    }
                )
            }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data, headers = commonHeaders, referer = mainUrl)
        val document = response.document
        val html = response.text.cleanEscaped()

        val directLinks = linkedSetOf<String>()
        val embedLinks = linkedSetOf<String>()

        extractMediaUrls(html).forEach { raw ->
            val fixed = fixUrl(raw)

            when {
                fixed.contains(".m3u8", true) -> directLinks.add(fixed)
                fixed.contains(".mp4", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        document.select(
            "video source[src], " +
                "source[src], " +
                "video[src], " +
                "iframe[src], " +
                "embed[src], " +
                "a[href*='.m3u8'], " +
                "a[href*='.mp4'], " +
                "a[href*='embed'], " +
                "a[href*='player'], " +
                "a[href*='stream'], " +
                "a[href*='watch'], " +
                "[data-url], " +
                "[data-src], " +
                "[data-video], " +
                "[data-file]"
        ).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("href") }
                .ifBlank { element.attr("data-url") }
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video") }
                .ifBlank { element.attr("data-file") }
                .trim()

            if (raw.isBlank()) return@forEach

            val fixed = fixUrl(raw)

            when {
                fixed.contains(".m3u8", true) || fixed.contains(".mp4", true) -> directLinks.add(fixed)
                fixed.startsWith("http", true) -> embedLinks.add(fixed)
            }
        }

        var found = false

        directLinks.forEach { link ->
            if (link.contains(".m3u8", true)) {
                generateM3u8(
                    source = name,
                    streamUrl = link,
                    referer = data
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        referer = data
                        quality = getQualityFromName(link).takeIf {
                            it != Qualities.Unknown.value
                        } ?: Qualities.Unknown.value
                    }
                )
            }

            found = true
        }

        embedLinks.forEach { embed ->
            val success = loadExtractor(
                embed,
                data,
                subtitleCallback,
                callback
            )

            if (success) found = true
        }

        return found
    }

    private fun extractMediaUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()

        Regex("""https?://[^"'\\\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex("""https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map {
                runCatching {
                    java.net.URLDecoder.decode(it.value, "UTF-8")
                }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped() }
            .forEach { urls.add(it) }

        Regex("""(?:file|src|source|url|video|playUrl|videoUrl)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped() }
            .filter {
                it.contains(".m3u8", true) ||
                    it.contains(".mp4", true) ||
                    it.contains("embed", true) ||
                    it.contains("player", true) ||
                    it.contains("stream", true)
            }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun Element.getImageAttr(): String? {
        return when {
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
            hasAttr("src") -> attr("abs:src")
            else -> null
        }
    }

    private fun extractEpisodeNumber(
        text: String,
        href: String
    ): Int? {
        return Regex("""(?:episode|eps?|ep)\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("""(?:episode|eps?|ep)-?(\d+)""", RegexOption.IGNORE_CASE)
                .find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            ?: Regex("""\b(\d+)\b""")
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

    private fun String.cleanTitle(): String {
        return this
            .replace(Regex("""\s+-\s+C-Dramayes.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Sub Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+English Subbed.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Nonton\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^Watch Streaming\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.cleanEpisodeTitle(): String {
        return this
            .replace(Regex("""\s+Sub Indo.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+English Subbed.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}