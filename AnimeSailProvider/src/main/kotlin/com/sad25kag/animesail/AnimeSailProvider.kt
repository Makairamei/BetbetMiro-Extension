package com.sad25kag.animesail

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class AnimeSailProvider : MainAPI() {
    override var mainUrl = "https://154.26.137.28"
    override var name = "AnimeSail"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val CF_USER_MSG =
            "Terkena perlindungan Cloudflare. Silakan tap ikon Web/Bumi di kanan atas (Open in Browser), " +
            "centang verifikasi manusia, lalu kembali dan reload halaman ini."

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t.trim()) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    /**
     * Single entry point for all HTTP requests.
     * Uses WebViewResolver(useOkhttp = false) — mandatory for Cloudflare sites.
     * User-Agent is the Android WebView UA supplied by CloudStream (USER_AGENT constant).
     * No cookies, no debug headers, no local TurnstileInterceptor.
     */
    private suspend fun request(url: String, ref: String? = null): NiceResponse {
        return app.get(
            url,
            interceptor = WebViewResolver(
                interceptUrl = Regex("""^${Regex.escape(mainUrl)}(?:/.*)?$"""),
                useOkhttp = false
            ),
            referer = ref ?: "$mainUrl/"
        )
    }

    /**
     * Detects Cloudflare challenge pages: 403/503 status or body hints.
     * Covers Turnstile, JS challenge, and "Just a moment..." screens.
     */
    private fun isChallengeResponse(response: NiceResponse): Boolean {
        val status = response.code
        if (status == 403 || status == 503) return true

        val body = response.text
        if (body.isBlank()) return false

        val title = response.document.title()
        val hints = listOf(
            "<title>Just a moment</title>",
            "Just a moment",
            "Checking your Browser",
            "cf-challenge",
            "cf-browser-verification",
            "cf_clearance",
            "_as_turnstile",
            "challenge-platform",
            "challenges.cloudflare.com",
            "turnstile",
            "/cdn-cgi/challenge-platform/",
            "Attention Required",
            "<title>Loading..</title>",
            "Aktifkan JavaScript"
        )

        return title.contains("Just a moment", ignoreCase = true) ||
            title.equals("Loading..", ignoreCase = true) ||
            hints.any { body.contains(it, ignoreCase = true) }
    }

    /**
     * Throws the user-facing interactive fallback error when Cloudflare blocks.
     * This is the ONLY error path for CF challenge — no silent empty returns from
     * load() or loadLinks().
     */
    private fun throwCfError(): Nothing {
        throw ErrorLoadingException(CF_USER_MSG)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/rilisan-anime-terbaru/" to "Ongoing Anime",
        "$mainUrl/rilisan-donghua-terbaru/" to "Ongoing Donghua",
        "$mainUrl/movie-terbaru/" to "Movie Terbaru",

        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/comedy/" to "Comedy",
        "$mainUrl/genres/drama/" to "Drama",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/romance/" to "Romance",
        "$mainUrl/genres/school/" to "School",
        "$mainUrl/genres/slice-of-life/" to "Slice of Life",
        "$mainUrl/genres/shounen/" to "Shounen",
        "$mainUrl/genres/seinen/" to "Seinen",
        "$mainUrl/genres/isekai/" to "Isekai",
        "$mainUrl/genres/supernatural/" to "Supernatural",
        "$mainUrl/genres/magic/" to "Magic",
        "$mainUrl/genres/mystery/" to "Mystery",
        "$mainUrl/genres/sci-fi/" to "Sci-Fi",
        "$mainUrl/genres/mecha/" to "Mecha",
        "$mainUrl/genres/sports/" to "Sports",
        "$mainUrl/genres/historical/" to "Historical",
        "$mainUrl/genres/harem/" to "Harem",
        "$mainUrl/genres/ecchi/" to "Ecchi",
        "$mainUrl/genres/horror/" to "Horror"
    )

    override suspend fun getMainPage(page: Int, pageRequest: MainPageRequest): HomePageResponse {
        val baseUrl = pageRequest.data.trimEnd('/')
        val pageUrl = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
        val response = request(pageUrl)

        // Throw CF error so UI shows the interactive instruction — not a silent empty list.
        if (isChallengeResponse(response)) throwCfError()

        val document = response.document
        val home = document.select(
            "div.listupd article, div.listupd .bs, div.listupd .bsx, article, .bsx"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }

        return newHomePageResponse(
            pageRequest.name,
            home,
            hasNext = home.isNotEmpty()
        )
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/anime/")) {
            uri
        } else {
            var title = uri.substringAfter("$mainUrl/").trim('/')
            title = when {
                (title.contains("-episode")) && !(title.contains("-movie")) -> title.substringBefore("-episode")
                (title.contains("-movie")) -> title.substringBefore("-movie")
                else -> title
            }
            "$mainUrl/anime/$title"
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkElement = selectFirst(
            ".tt > h2 > a, .tt > a, .bsx a[href], h2.entry-title > a, h2 > a, h3 > a, a[rel=bookmark], a[href]"
        ) ?: return null

        val rawHref = fixUrlNull(
            linkElement.attr("href").ifBlank {
                selectFirst("a[href]")?.attr("href")
            }
        ) ?: return null
        val href = getProperAnimeLink(rawHref)

        val rawTitle = listOfNotNull(
            selectFirst(".tt > h2")?.text(),
            selectFirst(".tt")?.text(),
            selectFirst("h2.entry-title")?.text(),
            selectFirst("h2")?.text(),
            selectFirst("h3")?.text(),
            linkElement.attr("title").takeIf { it.isNotBlank() },
            select("a[href]")
                .map { it.text().trim() }
                .filter { it.isNotBlank() && !it.equals("Next", true) && !it.equals("Previous", true) }
                .maxByOrNull { it.length }
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        val title = rawTitle.replace(Regex("(?i)Episode\\s?\\d+"), "")
            .replace(Regex("(?i)Subtitle Indonesia"), "")
            .replace(Regex("(?i)Sub Indo"), "")
            .trim()
            .removeSuffix("-")
            .trim()

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            selectFirst("div.limit img, img.wp-post-image, img.attachment-post-thumbnail, img")?.let { img ->
                img.attr("abs:data-src").ifBlank {
                    img.attr("abs:data-lazy-src").ifBlank {
                        img.attr("abs:src").ifBlank {
                            img.attr("data-src").ifBlank {
                                img.attr("data-lazy-src").ifBlank {
                                    img.attr("src")
                                }
                            }
                        }
                    }
                }
            }
        )

        val epNum = Regex("(?i)Episode\\s?(\\d+)").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val typeText = listOfNotNull(
            selectFirst(".tt > span")?.text(),
            selectFirst(".typez")?.text(),
            text().takeIf { it.contains("\u00b7") }
        ).joinToString(" ")
        val type = if (typeText.contains("Movie", ignoreCase = true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val response = request("$mainUrl/?s=$encoded")
        if (isChallengeResponse(response)) throwCfError()

        return response.document.select(
            "div.listupd article, div.listupd .bs, div.listupd .bsx, article, .bsx"
        ).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = request(url)
        if (isChallengeResponse(response)) throwCfError()

        val document = response.document

        val title = document.selectFirst("h1.entry-title")?.text().orEmpty()
            .replace("Subtitle Indonesia", "").trim()
        val poster = fixUrlNull(
            document.selectFirst("div.entry-content > img, .entry-content img")?.attr("src")
        )
        val type = getType(
            document.select("tbody th:contains(Tipe)").next().text().lowercase()
        )
        val year = document.select("tbody th:contains(Dirilis)").next().text().trim().toIntOrNull()

        val parsedEpisodes = document.select("ul.daftar > li").mapNotNull {
            val link = fixUrlNull(it.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
            val epName = it.selectFirst("a")?.text().orEmpty()
            val episode = Regex("Episode\\s?(\\d+)").find(epName)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(link) {
                this.name = epName.ifBlank { null }
                this.episode = episode
            }
        }.reversed()

        val episodes = if (parsedEpisodes.isEmpty() && type == TvType.AnimeMovie) {
            listOf(newEpisode(url) { this.name = "Movie" })
        } else parsedEpisodes

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(type), year, true)

        return newAnimeLoadResponse(title, url, type) {
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = getStatus(
                document.select("tbody th:contains(Status)").next().text().trim()
            )
            plot = document.selectFirst("div.entry-content > p")?.text()
            tags = document.select("tbody th:contains(Genre)").next().select("a").map { it.text() }
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = request(data)
        if (isChallengeResponse(response)) throwCfError()

        val document = response.document
        val playerPath = "$mainUrl/utils/player/"
        val visitedUrls = linkedSetOf<String>()
        var emitted = false

        document.select(".mobius > .mirror > option, .mobius option, select.mirror option").amap { element ->
            safeApiCall {
                val rawText = element.text().trim()
                val quality = getIndexQuality(rawText)
                val serverName = rawText.split(" ").firstOrNull()?.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                } ?: name

                val candidates = element.extractMirrorCandidates()
                candidates.forEach { candidate ->
                    if (resolveMirrorLink(
                            rawUrl = candidate,
                            referer = data,
                            playerPath = playerPath,
                            serverName = serverName,
                            quality = quality,
                            visitedUrls = visitedUrls,
                            subtitleCallback = subtitleCallback,
                            callback = callback
                        )
                    ) {
                        emitted = true
                    }
                }
            }
        }

        return emitted
    }

    private suspend fun resolveMirrorLink(
        rawUrl: String,
        referer: String,
        playerPath: String,
        serverName: String,
        quality: Int?,
        visitedUrls: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val normalized = normalizeMirrorUrl(rawUrl) ?: return false
        if (normalized.contains("statistic", true)) return false
        if (!visitedUrls.add(normalized)) return false

        return when {
            isDirectMediaUrl(normalized) -> {
                emitDirectMediaLink(normalized, serverName, quality, referer, callback)
                true
            }

            normalized.contains("${playerPath}popup", true) -> {
                val encodedUrl = normalized.substringAfter("url=", "").substringBefore("&")
                val realUrl = runCatching { URLDecoder.decode(encodedUrl, "UTF-8") }.getOrNull()
                if (realUrl.isNullOrBlank()) false else resolveMirrorLink(
                    rawUrl = realUrl,
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains("aghanim.xyz/tools/redirect/", true) -> {
                val id = normalized.substringAfter("id=").substringBefore("&token")
                if (id.isBlank()) false else resolveMirrorLink(
                    rawUrl = "https://rasa-cintaku-semakin-berantai.xyz/v/$id",
                    referer = normalized,
                    playerPath = playerPath,
                    serverName = serverName,
                    quality = quality,
                    visitedUrls = visitedUrls,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            normalized.contains(playerPath, true) ||
                normalized.contains("player-kodir.aghanim.xyz", true) ||
                normalized.contains("uservideo.xyz", true) -> {
                val res = runCatching { request(normalized, ref = referer) }.getOrNull()
                val nestedLinks = linkedSetOf<String>()

                if (res != null) {
                    val text = res.text
                    val playerDoc = res.document
                    val packedHtml = text.substringAfter("= `", "").substringBefore("`;", "")
                    if (packedHtml.isNotBlank()) {
                        nestedLinks.addAll(
                            Jsoup.parse(packedHtml)
                                .select("source[src], video[src], iframe[src], a[href]")
                                .mapNotNull {
                                    it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank)
                                }
                        )
                    }
                    nestedLinks.addAll(
                        playerDoc.select("source[src], video[src], iframe[src], a[href], script[src]")
                            .mapNotNull {
                                it.attr("src").ifBlank { it.attr("href") }.trim().takeIf(String::isNotBlank)
                            }
                    )
                    nestedLinks.addAll(extractCandidatesFromText(text, normalized))
                }

                if (nestedLinks.isEmpty()) {
                    loadFixedExtractor(normalized, serverName, quality, referer, subtitleCallback, callback)
                    true
                } else {
                    var emitted = false
                    nestedLinks.forEach { nested ->
                        if (resolveMirrorLink(
                                nested, normalized, playerPath, serverName, quality,
                                visitedUrls, subtitleCallback, callback
                            )
                        ) emitted = true
                    }
                    emitted
                }
            }

            else -> {
                loadFixedExtractor(normalized, serverName, quality, referer, subtitleCallback, callback)
                true
            }
        }
    }

    private fun Element.extractMirrorCandidates(): List<String> {
        val raw = listOf(
            attr("data-em"),
            attr("value"),
            attr("data-iframe"),
            attr("data-url"),
            attr("data-src")
        ).filter { it.isNotBlank() }

        val results = linkedSetOf<String>()
        raw.forEach { encoded -> results.addAll(decodeMirrorCandidates(encoded)) }
        return results.toList()
    }

    private fun decodeMirrorCandidates(encodedData: String): List<String> {
        if (encodedData.isBlank()) return emptyList()
        val candidates = linkedSetOf<String>()
        val clean = encodedData.trim().replace("\\u0026", "&")

        fun addUrl(raw: String?) { normalizeMirrorUrl(raw)?.let { candidates.add(it) } }

        fun parseBlob(blob: String) {
            if (blob.isBlank()) return
            addUrl(blob)
            Jsoup.parse(blob).select("iframe[src], source[src], video[src], a[href]").forEach { el ->
                addUrl(el.attr("src").ifBlank { el.attr("href") })
            }
            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                .findAll(blob).forEach { addUrl(it.value) }
        }

        parseBlob(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.let(::parseBlob)
        runCatching { base64Decode(clean.replace("\\s".toRegex(), "")) }.getOrNull()?.let(::parseBlob)
        return candidates.toList()
    }

    private fun normalizeMirrorUrl(raw: String?): String? = normalizeUrlFromBase(raw, mainUrl)

    private fun normalizeUrlFromBase(raw: String?, baseUrl: String?): String? {
        val clean = raw?.trim()
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.removePrefix("'")
            ?.removeSuffix("'")
            ?.replace("\\/", "/")
            ?.replace("&amp;", "&")
            ?.replace("\\u0026", "&")
            ?.trim() ?: return null

        if (clean.isBlank() || clean.startsWith("javascript:", true) || clean.startsWith("data:", true)) return null

        fun resolveWithBase(path: String): String? {
            if (baseUrl.isNullOrBlank()) return null
            return runCatching { URI(baseUrl).resolve(path).toString() }.getOrNull()
        }

        return when {
            clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> resolveWithBase(clean) ?: runCatching { fixUrl(clean) }.getOrNull()
            else -> resolveWithBase(clean)
        }
    }

    private fun isDirectMediaUrl(url: String): Boolean =
        Regex("""(?i)\.(m3u8|mp4)(?:$|[?#&])""").containsMatchIn(url)

    private fun extractCandidatesFromText(text: String, baseUrl: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val out = linkedSetOf<String>()
        val patterns = listOf(
            Regex("""https?://[^\s"'<>\\]+""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|video_url|play_url|hls)\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']((?:/|//)[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE),
        )
        patterns.forEach { rgx ->
            rgx.findAll(text).forEach { match ->
                val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
                normalizeUrlFromBase(raw, baseUrl)?.let(out::add)
            }
        }
        return out
    }

    private suspend fun emitDirectMediaLink(
        mediaUrl: String,
        serverName: String,
        quality: Int?,
        refererHint: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val isMp4Upload = mediaUrl.contains("mp4upload.com", ignoreCase = true)
        val directReferer = if (isMp4Upload) "https://www.mp4upload.com/" else (refererHint ?: mainUrl)
        val directHeaders = if (isMp4Upload) {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer,
                "Origin" to "https://www.mp4upload.com"
            )
        } else {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to directReferer
            )
        }

        callback.invoke(
            newExtractorLink(
                source = serverName,
                name = serverName,
                url = mediaUrl,
                type = if (mediaUrl.contains(".m3u8", ignoreCase = true))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                referer = directReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = directHeaders
            }
        )
    }

    private suspend fun loadFixedExtractor(
        url: String,
        serverName: String,
        quality: Int?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = normalizeYourUploadUrl(url)

        if (tryLoadMp4UploadDirect(normalizedUrl, serverName, quality, callback)) return

        loadExtractor(normalizedUrl, referer, subtitleCallback) { link ->
            val finalName = if (serverName.equals(link.name, ignoreCase = true)) link.name else "$serverName - ${link.name}"
            runBlocking {
                callback.invoke(
                    newExtractorLink(
                        source = link.name,
                        name = finalName,
                        url = link.url,
                        type = link.type
                    ) {
                        this.referer = link.referer.takeIf { it.isNotBlank() } ?: referer ?: mainUrl
                        this.quality = if (link.type == ExtractorLinkType.M3U8) link.quality else quality ?: Qualities.Unknown.value
                        this.headers = link.headers
                        this.extractorData = link.extractorData
                    }
                )
            }
        }
    }

    private fun normalizeYourUploadUrl(url: String): String {
        if (!url.contains("yourupload.com", true)) return url
        return if (url.contains("/watch/", true)) url.replace("/watch/", "/embed/", true) else url
    }

    private suspend fun tryLoadMp4UploadDirect(
        url: String,
        serverName: String,
        quality: Int?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex(
            """mp4upload\.com/(?:embed-)?([A-Za-z0-9]+)(?:\.html)?""",
            RegexOption.IGNORE_CASE
        ).find(url)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return false

        val downloadUrl = "https://www.mp4upload.com/dl?op=download2&id=$id"
        val watchReferer = "https://www.mp4upload.com/"
        val redirect = runCatching {
            app.get(
                downloadUrl,
                referer = watchReferer,
                allowRedirects = false,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            )
        }.getOrNull() ?: return false

        val location = redirect.headers["Location"] ?: redirect.headers["location"]
        val finalUrl = when {
            location.isNullOrBlank() -> return false
            location.startsWith("http://", true) || location.startsWith("https://", true) -> location
            location.startsWith("//") -> "https:$location"
            location.startsWith("/") -> "https://www.mp4upload.com$location"
            else -> return false
        }

        callback.invoke(
            newExtractorLink(
                source = "Mp4Upload",
                name = serverName,
                url = finalUrl,
                type = INFER_TYPE
            ) {
                referer = watchReferer
                this.quality = quality ?: Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to watchReferer,
                    "Origin" to "https://www.mp4upload.com"
                )
            }
        )
        return true
    }

    private fun getIndexQuality(str: String): Int {
        return Regex("(\\d{3,4})[pP]").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
