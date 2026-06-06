package it.dogior.nsfw

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.random.Random

class Archivebate : MainAPI() {
    override var mainUrl = URL
    override var name = "Archivebate"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val hasQuickSearch = true
    override var sequentialMainPage = true

    override val mainPage = mainPageOf(
        buildPostsApi() to "Latest Videos",
        buildPostsApi(search = "chaturbate") to "Chaturbate",
        buildPostsApi(search = "camsoda") to "Camsoda",
        buildPostsApi(search = "stripchat") to "Stripchat",
        buildPostsApi(search = "cam4") to "Cam4",
    )

    companion object {
        const val URL = "https://archivebate.com"
        private const val POSTS_API = "$URL/wp-json/wp/v2/posts"
        private const val POSTS_PER_PAGE = 30
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        private val durationMap = mutableMapOf<String, String>()
        private val infoMap = mutableMapOf<String, String>()
        private val profilePosterCache = mutableMapOf<String, String?>()
    }

    private fun requestHeaders(referer: String = mainUrl): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7",
    )

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun cleanHtml(html: String?): String {
        if (html.isNullOrBlank()) return ""
        return Jsoup.parse(html).text().trim()
    }

    private fun strToMin(duration: String?): Int? {
        if (duration.isNullOrBlank()) return null
        val parts = duration.trim().split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] + parts[1] / 60
            3 -> parts[0] * 60 + parts[1] + parts[2] / 60
            else -> null
        }
    }

    private fun buildPostsApi(
        page: Int = 1,
        perPage: Int = POSTS_PER_PAGE,
        search: String? = null,
    ): String {
        val query = mutableListOf(
            "per_page=$perPage",
            "page=$page",
            "_embed=1",
        )
        if (!search.isNullOrBlank()) query += "search=${encode(search)}"
        return "$POSTS_API?${query.joinToString("&")}"
    }

    private fun rewritePostsPage(url: String, page: Int): String {
        val parts = url.split("?", limit = 2)
        val base = parts.firstOrNull().orEmpty().ifBlank { POSTS_API }
        val query = parts.getOrNull(1).orEmpty()
        val params = linkedMapOf<String, String>()

        query.split("&")
            .filter { it.isNotBlank() }
            .forEach { pair ->
                val key = pair.substringBefore("=").trim()
                val value = pair.substringAfter("=", "").trim()
                if (key.isNotBlank()) params[key] = value
            }

        params["page"] = page.toString()

        val newQuery = params.entries
            .filter { it.key.isNotBlank() }
            .joinToString("&") { "${it.key}=${it.value}" }

        return if (newQuery.isNotBlank()) "$base?$newQuery" else base
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val apiUrl = rewritePostsPage(request.data, page)
        val body = runCatching {
            app.get(apiUrl, headers = requestHeaders()).text
        }.getOrElse { error ->
            Log.e("Archivebate", "Posts API failed for ${request.name}: ${error.message.orEmpty()}")
            ""
        }
        val items = parsePostArray(body)

        val responses = if (items.isNotEmpty()) {
            items
        } else {
            runCatching {
                parseHtmlListing(app.get(frontendPageUrl(page), headers = requestHeaders()).document)
            }.getOrElse { error ->
                Log.e("Archivebate", "HTML fallback failed for ${request.name}: ${error.message.orEmpty()}")
                emptyList()
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, responses, true),
            responses.size >= POSTS_PER_PAGE,
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val body = runCatching {
            app.get(buildPostsApi(search = query), headers = requestHeaders()).text
        }.getOrElse { "" }

        val apiItems = parsePostArray(body)
        if (apiItems.isNotEmpty()) return apiItems

        return runCatching {
            val doc = app.get("$mainUrl/search/${encode(query)}/", headers = requestHeaders()).document
            parseHtmlListing(doc)
        }.getOrNull() ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/profile/")) return getModelProfile(url)

        val data = getVideoData(url)
        return newMovieLoadResponse(data.title, url, TvType.NSFW, data.playData.ifBlank { url }) {
            this.plot = data.info.orEmpty().ifBlank { infoMap[url].orEmpty() }
            this.posterUrl = data.poster
            this.duration = strToMin(durationMap[url] ?: data.duration)
            if (!data.profileName.isNullOrBlank() && !data.profileUrl.isNullOrBlank()) {
                this.recommendations = listOf(
                    newMovieSearchResponse(data.profileName, data.profileUrl, TvType.NSFW) {
                        this.posterUrl = data.profilePoster
                    }
                )
            }
        }
    }

    private suspend fun getModelProfile(url: String): LoadResponse? {
        val model = url.substringAfterLast("/").replace("-", " ").trim().ifBlank { "Profile" }
        val items = search(model).take(20).map {
            newEpisode(it.url) {
                this.name = it.name
                this.posterUrl = it.posterUrl
                this.runTime = strToMin(durationMap[it.url])
            }
        }
        val modelPoster = getModelPoster(url)
        return newTvSeriesLoadResponse(model.replaceFirstChar { it.titlecase() }, url, TvType.NSFW, items) {
            this.posterUrl = modelPoster
            this.plot = "Latest videos"
        }
    }

    private suspend fun getModelPoster(url: String): String? {
        profilePosterCache[url]?.let { return it }
        if (profilePosterCache.containsKey(url)) return null

        val poster = runCatching {
            val photosPage = app.get("$url/photos", headers = requestHeaders(url)).document
            val photoUrl = photosPage.select("img.default_thumbnail, img[src]").mapNotNull { image ->
                image.absUrl("src").ifBlank { image.attr("src") }.ifBlank { null }
            }
            if (photoUrl.isEmpty()) null else photoUrl[Random.nextInt(0, photoUrl.size)]
        }.getOrNull()

        profilePosterCache[url] = poster
        return poster
    }

    private fun parsePostArray(body: String): List<SearchResponse> {
        val posts = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until posts.length()).mapNotNull { index ->
            val post = posts.optJSONObject(index) ?: return@mapNotNull null
            val link = post.optString("link").ifBlank { return@mapNotNull null }
            val title = cleanHtml(post.optJSONObject("title")?.optString("rendered")).ifBlank { "Unknown" }
            val poster = selectPoster(post)
            val info = cleanHtml(post.optJSONObject("excerpt")?.optString("rendered"))
                .ifBlank { cleanHtml(post.optJSONObject("content")?.optString("rendered")) }
            val duration = extractDuration(post)
            if (duration.isNotBlank()) durationMap[link] = duration
            if (info.isNotBlank()) infoMap[link] = info
            newMovieSearchResponse(title, link, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    private fun selectPoster(post: JSONObject): String? {
        post.optString("jetpack_featured_media_url").takeIf { it.isNotBlank() }?.let { return it }
        post.optJSONObject("better_featured_image")?.optString("source_url")?.takeIf { it.isNotBlank() }?.let { return it }
        val media = post.optJSONObject("_embedded")?.optJSONArray("wp:featuredmedia")?.optJSONObject(0)
        media?.optString("source_url")?.takeIf { it.isNotBlank() }?.let { return it }
        val html = post.optJSONObject("content")?.optString("rendered") ?: ""
        return Jsoup.parse(html).selectFirst("img[src], img[data-src]")?.let { image ->
            image.attr("data-src").ifBlank { image.attr("src") }
        }?.ifBlank { null }
    }

    private fun extractDuration(post: JSONObject): String {
        post.optJSONObject("acf")?.optString("duration")?.takeIf { it.isNotBlank() }?.let { return it }
        val rendered = listOfNotNull(
            post.optJSONObject("content")?.optString("rendered"),
            post.optJSONObject("excerpt")?.optString("rendered"),
        ).joinToString("\n")
        return Regex("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?)\\b").find(rendered)?.groupValues?.getOrNull(1) ?: ""
    }

    private fun frontendPageUrl(page: Int): String = if (page > 1) "$mainUrl/page/$page/" else "$mainUrl/"

    private fun parseHtmlListing(doc: Document): List<SearchResponse> {
        val candidates = doc.select("article, section.video_item, .post, .video_item")
        return candidates.mapNotNull { item -> parseHtmlCard(item) }
    }

    private fun parseHtmlCard(item: Element): SearchResponse? {
        val linkElement = item.selectFirst("h2.entry-title a, h3.entry-title a, a[rel=bookmark], a[href]") ?: return null
        val link = linkElement.absUrl("href").ifBlank { linkElement.attr("href") }
        if (link.isBlank() || link == mainUrl) return null
        val title = linkElement.text().ifBlank {
            item.selectFirst(".title, .entry-title, div.info.d-flex > div")?.text()
        }?.ifBlank { "Unknown" } ?: "Unknown"
        val poster = item.selectFirst("img[src], img[data-src], video[poster]")?.let { media ->
            media.absUrl("data-src").ifBlank { media.absUrl("src") }.ifBlank { media.attr("poster") }
        }
        val duration = item.selectFirst(".duration, .video-duration, div.duration.text-white > span")?.text().orEmpty()
        if (duration.isNotBlank()) durationMap[link] = duration
        return newMovieSearchResponse(title, link, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private suspend fun getVideoData(url: String): VideoInfo {
        val doc = app.get(url, headers = requestHeaders()).document
        val title = doc.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")
            ?.substringBefore(" - ")
            ?.ifBlank { null }
            ?: doc.selectFirst("h1, .entry-title, title")?.text()?.substringBefore(" - ")?.ifBlank { null }
            ?: "Archivebate Video"
        val info = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst(".entry-content, .entry-summary, .info")?.text().orEmpty()
        val poster = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
            ?.ifBlank { null }
            ?: doc.selectFirst("div.player")?.attr("style")?.substringAfter("url(")?.substringBefore(")")?.trim('"', '\'', ' ')
            ?: doc.selectFirst("video[poster], img[src]")?.let { media -> media.absUrl("poster").ifBlank { media.absUrl("src") } }
        val playData = extractPlayableCandidates(doc, url).firstOrNull().orEmpty()
        val profile = doc.selectFirst("a[href*='/profile/']")
        val profileUrl = profile?.absUrl("href")?.ifBlank { null }
        val profilePoster = if (poster.isNullOrBlank() && !profileUrl.isNullOrBlank()) {
            getModelPoster(profileUrl)
        } else {
            null
        }
        return VideoInfo(
            title = title,
            playData = playData,
            info = info,
            poster = poster,
            duration = Regex("\\b(\\d{1,2}:\\d{2}(?::\\d{2})?)\\b").find(doc.text())?.groupValues?.getOrNull(1),
            profileName = profile?.text()?.ifBlank { null },
            profileUrl = profileUrl,
            profilePoster = profilePoster,
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val candidates = if (data.startsWith(mainUrl)) {
            val doc = app.get(data, headers = requestHeaders(data)).document
            extractPlayableCandidates(doc, data)
        } else {
            listOf(data)
        }

        var emitted = false
        for (candidate in candidates.map { normalizeUrl(it) }.distinct()) {
            if (candidate.isBlank()) continue

            val referer = determineRefererForHost(candidate, data)
            emitted = loadExtractor(candidate, referer = referer, subtitleCallback, callback) || emitted
        }

        if (!emitted) Log.e("Archivebate", "No extractor callback emitted for: $data")
        return emitted
    }

    private fun determineRefererForHost(candidate: String, detailPage: String): String {
        val normalized = normalizeUrl(candidate).lowercase()
        val archivebateHost = mainUrl.removePrefix("https://").removePrefix("http://")
        val safeDetailPage = detailPage.takeIf { it.startsWith("http") } ?: mainUrl

        return when {
            normalized.contains(archivebateHost) -> safeDetailPage
            safeDetailPage.startsWith(mainUrl) -> safeDetailPage
            else -> mainUrl
        }
    }

    private fun extractPlayableCandidates(doc: Document, referer: String): List<String> {
        val candidates = mutableListOf<String>()

        candidates += extractDirectMedia(doc)
        candidates += extractPlayerIframes(doc)
        candidates += extractScriptMedia(doc)

        if (candidates.isEmpty()) {
            doc.selectFirst("iframe[src]")
                ?.absUrl("src")
                ?.ifBlank { doc.selectFirst("iframe[src]")?.attr("src").orEmpty() }
                ?.let { normalizeUrl(it) }
                ?.takeIf { it.isNotBlank() }
                ?.let { candidates += it }
        }

        return candidates
            .map { normalizeUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf(referer) }
    }

    private fun extractDirectMedia(doc: Document): List<String> {
        return doc.select("video[src], source[src]").mapNotNull { media ->
            val src = media.absUrl("src").ifBlank { media.attr("src") }
            normalizeUrl(src).takeIf { isDirectMediaUrl(it) }
        }
    }

    private fun extractPlayerIframes(doc: Document): List<String> {
        return doc.select("iframe[src]").mapNotNull { iframe ->
            val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
            normalizeUrl(src).takeIf { isPlayableIframe(it) }
        }
    }

    private fun extractScriptMedia(doc: Document): List<String> {
        val candidates = mutableListOf<String>()

        doc.select("script[src], script").forEach { script: Element ->
            val html = script.outerHtml()

            candidates += Regex("""https?:\\?/\\?/[^\"'\\s<>]+?\.(?:m3u8|mp4)(?:[^\"'\\s<>]*)""")
                .findAll(html)
                .map { normalizeUrl(it.value) }
                .toList()

            candidates += Regex("""(?:file|src|url)\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4)[^"']*)["']""")
                .findAll(html)
                .map { match -> normalizeUrl(match.groupValues.getOrNull(1).orEmpty()) }
                .filter { isDirectMediaUrl(it) }
                .toList()
        }

        return candidates.distinct()
    }

    private fun normalizeUrl(url: String): String {
        val cleaned = url
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim('"', '\'', ' ', '\n', '\t')

        return when {
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "$mainUrl$cleaned"
            else -> cleaned
        }
    }

    private fun isDirectMediaUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val normalized = normalizeUrl(url).lowercase()
        return Regex("""\.(?:m3u8|mp4)(?:[?#].*)?$""").containsMatchIn(normalized)
    }

    private fun isPlayableIframe(url: String): Boolean {
        val normalized = normalizeUrl(url).lowercase()
        return listOf(
            "mixdrop", "streamtape", "dood", "voe.sx", "filemoon",
            "mp4upload", "streamlare", "vidhide", "player", "embed",
        ).any { normalized.contains(it) }
    }

    data class VideoInfo(
        val title: String,
        val playData: String,
        val info: String?,
        val poster: String?,
        val duration: String?,
        val profileName: String?,
        val profileUrl: String?,
        val profilePoster: String?,
    )
}
