package com.sad25kag.otakupoi

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class OtakuPoi : MainAPI() {
    override var mainUrl = "https://otakupoi.org"
    override var name = "OtakuPoi"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val siteHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val portalPaths = listOf(
        "" to "Portal Web",
        "kusonime" to "Kusonime",
        "oploverz" to "Oploverz",
        "maxnime" to "Maxnime",
        "meownime" to "Meownime",
        "neonime" to "Neonime",
        "otakudesu" to "OtakuDesu",
        "anitoki" to "Anitoki"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Anime Terbaru",
        "$mainUrl/kusonime/" to "Kusonime",
        "$mainUrl/oploverz/ongoing/" to "Oploverz Ongoing",
        "$mainUrl/oploverz/movie/" to "Oploverz Movie",
        "$mainUrl/maxnime/" to "Maxnime",
        "$mainUrl/meownime/" to "Meownime",
        "$mainUrl/neonime/" to "Neonime",
        "$mainUrl/otakudesu/" to "OtakuDesu"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data.toPageUrl(page), headers = siteHeaders, referer = "$mainUrl/").document
        val items = document.parseSearchItems()
            .filterNot { it.name.isNavigationTitle() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, hasNext = document.hasNextPage(page))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val results = linkedMapOf<String, SearchResponse>()

        val urls = mutableListOf<String>()
        for ((path, _) in portalPaths) {
            val base = when {
                path.isBlank() -> "$mainUrl/"
                else -> "$mainUrl/$path/"
            }
            urls += listOf(
                "${base}?s=$encoded",
                "${base}?search=$encoded",
                "${base}search/$encoded/",
                "${base}page/1/?s=$encoded"
            )
        }
        urls += "$mainUrl/"

        for (url in urls.distinct()) {
            val document = runCatching { app.get(url, headers = siteHeaders, referer = "$mainUrl/").document }.getOrNull() ?: continue
            val parsed = document.parseSearchItems().filter { item ->
                item.name.contains(cleanQuery, ignoreCase = true) || !url.equals("$mainUrl/", true)
            }
            for (item in parsed) results[item.url] = item
            if (results.isNotEmpty() && !url.equals("$mainUrl/", true)) break
        }
        return results.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val fixedUrl = normalizePageUrl(url) ?: return null
        val document = app.get(fixedUrl, headers = siteHeaders, referer = "$mainUrl/").document
        val title = document.bestTitle()?.cleanTitle()?.ifBlank { null } ?: fixedUrl.slugTitle()
        val poster = document.bestPoster()
        val plot = document.bestPlot()
        val text = document.text()
        val tags = document.select("a[href*='/genre/'], a[href*='genre='], a[href*='/tag/'], a[href*='tag='], .genre a, .genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && it.length <= 40 && !it.isNavigationTitle() }
            .distinct()
            .take(20)
        val year = text.parseYear()
        val recommendations = document.parseSearchItems().filter { it.url != fixedUrl }.distinctBy { it.url }.take(24)
        val episodes = document.parseEpisodes(fixedUrl, poster, title)
        val type = when {
            title.contains("Movie", true) || fixedUrl.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeLoadResponse(title, fixedUrl, type) {
            posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizePageUrl(data) ?: return false
        val queue = ArrayDeque<ServerCandidate>()
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()
        var hasLinks = false

        suspend fun enqueueFromText(text: String?, referer: String) {
            text?.extractCandidatesFromText(referer)?.forEach { queue.add(it) }
        }

        suspend fun enqueueFromUrl(url: String, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            val response = runCatching { app.get(fixed, headers = playerHeaders(fixed), referer = referer) }.getOrNull() ?: return
            response.document.extractCandidates(fixed).forEach { queue.add(it) }
            enqueueFromText(response.text, fixed)
        }

        suspend fun emitDirect(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return
            val qualityLabel = label?.cleanLabel().orEmpty().ifBlank { fixed.qualityLabelFromUrl() }
            val quality = qualityLabel.takeIf { it.isNotBlank() }?.let { getQualityFromName(it) }
                ?: fixed.parseQuality()
                ?: Qualities.Unknown.value

            if (fixed.contains(".m3u8", true)) {
                val links = M3u8Helper.generateM3u8(name, fixed, referer, headers = siteHeaders)
                for (link in links) {
                    callback(link)
                    hasLinks = true
                }
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = listOf(name, qualityLabel).filter { it.isNotBlank() }.joinToString(" "),
                        url = fixed,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer
                        this.quality = quality
                        this.headers = siteHeaders + mapOf("Range" to "bytes=0-")
                    }
                )
                hasLinks = true
            }
        }

        suspend fun emitExtractor(url: String, label: String?, referer: String) {
            val fixed = normalizeMediaUrl(url) ?: return
            if (fixed.isDirectMedia()) {
                emitDirect(fixed, label, referer)
                return
            }
            val key = fixed.normalizedMediaKey()
            if (!emitted.add(key)) return
            loadExtractor(fixed, referer, subtitleCallback) { link ->
                callback(link)
                hasLinks = true
            }
        }

        enqueueFromUrl(pageUrl, "$mainUrl/")
        var guard = 0
        while (queue.isNotEmpty() && guard < 150) {
            guard++
            val candidate = queue.removeFirst()
            val fixed = normalizeMediaUrl(candidate.url) ?: continue
            if (!visited.add(fixed.normalizedMediaKey())) continue
            val referer = candidate.referer ?: pageUrl

            if (fixed.isDirectMedia()) {
                emitDirect(fixed, candidate.label, referer)
                continue
            }

            if (!fixed.startsWith(mainUrl, true)) {
                emitExtractor(fixed, candidate.label, referer)
                if (fixed.shouldInlineResolve()) enqueueFromUrl(fixed, referer)
                continue
            }

            enqueueFromUrl(fixed, referer)
        }
        return hasLinks
    }

    private fun Document.parseSearchItems(): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val anchors = select("a[href]")
        for (anchor in anchors) {
            val response = anchor.toSearchResponse() ?: continue
            results[response.url] = response
        }
        return results.values.toList()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = attr("href").toAbsoluteUrl() ?: return null
        if (!href.startsWith(mainUrl, true)) return null
        if (!href.isAnimePageUrl()) return null

        val card = bestCard()
        val title = listOf(
            card.selectFirst("h1, h2, h3, h4, h5, h6, .title, .judul, .anime-title, .entry-title")?.text(),
            attr("title"),
            card.selectFirst("img[alt]")?.attr("alt"),
            text(),
            card.text()
        ).firstCleanTitle() ?: return null
        if (title.isNavigationTitle()) return null

        val poster = card.bestImage()?.toAbsoluteUrl()
        val episode = card.text().parseEpisodeNumber() ?: href.parseEpisodeNumber()
        val type = when {
            title.contains("Movie", true) || href.contains("movie", true) -> TvType.AnimeMovie
            title.contains("OVA", true) || title.contains("Special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, href, type) {
            posterUrl = poster
            episode?.let { addSub(it) }
        }
    }

    private fun Document.parseEpisodes(pageUrl: String, poster: String?, fallbackTitle: String): List<Episode> {
        val episodes = linkedMapOf<String, Episode>()
        val anchors = select("a[href]")
        for (anchor in anchors) {
            val href = anchor.attr("href").toAbsoluteUrl() ?: continue
            if (!href.startsWith(mainUrl, true)) continue
            if (!href.isLikelyEpisodeUrl() && !anchor.text().contains("Episode", true)) continue
            val text = anchor.text().trim().ifBlank { anchor.attr("title") }.ifBlank { href.slugTitle() }
            if (text.isNavigationTitle()) continue
            val ep = text.parseEpisodeNumber() ?: href.parseEpisodeNumber()
            episodes[href] = newEpisode(href) {
                this.name = text.parseEpisodeName() ?: ep?.let { "Episode $it" } ?: text.cleanTitle()
                this.episode = ep
                this.posterUrl = anchor.bestCard().bestImage()?.toAbsoluteUrl() ?: poster
            }
        }
        if (episodes.isEmpty()) {
            episodes[pageUrl] = newEpisode(pageUrl) {
                this.name = fallbackTitle.parseEpisodeName() ?: fallbackTitle
                this.episode = fallbackTitle.parseEpisodeNumber() ?: pageUrl.parseEpisodeNumber()
                this.posterUrl = poster
            }
        }
        return episodes.values.sortedWith(compareBy({ it.episode ?: Int.MAX_VALUE }, { it.name ?: "" }))
    }

    private fun Document.extractCandidates(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()
        fun add(raw: String?, label: String? = null) {
            val fixed = normalizeMediaUrl(raw) ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        for (iframe in select("iframe[src], embed[src]")) add(iframe.attr("src"), iframe.attr("title").ifBlank { iframe.attr("name") })
        for (source in select("video[src], source[src]")) add(source.attr("src"), source.attr("label").ifBlank { source.attr("res") })
        for (element in select("[data-src], [data-url], [data-link], [data-iframe], [data-embed], [data-player], [data-video], [data-file], [data-href]")) {
            val label = element.text().ifBlank { element.attr("title") }.ifBlank { element.attr("aria-label") }
            val attrs = listOf("data-src", "data-url", "data-link", "data-iframe", "data-embed", "data-player", "data-video", "data-file", "data-href")
            for (attr in attrs) add(element.attr(attr), label)
        }
        for (option in select("option[value]")) {
            add(option.attr("value"), option.text())
            decodePossibleBase64(option.attr("value"))?.extractCandidatesFromText(referer)?.forEach { add(it.url, option.text()) }
        }
        html().extractCandidatesFromText(referer).forEach { add(it.url, it.label) }
        return results.values.toList()
    }

    private fun String.extractCandidatesFromText(referer: String): List<ServerCandidate> {
        val results = linkedMapOf<String, ServerCandidate>()
        fun add(raw: String?, label: String? = null) {
            val fixed = normalizeMediaUrl(raw) ?: return
            if (fixed.isBlank() || fixed == mainUrl || fixed == referer) return
            if (fixed.startsWith("javascript:", true) || fixed.startsWith("data:", true)) return
            if (!fixed.isPotentialPlayerUrl()) return
            results[fixed] = ServerCandidate(fixed, label, referer)
        }

        val variants = linkedSetOf(this, basicHtmlDecode(), unescapeJs(), basicHtmlDecode().unescapeJs())
        runCatching { getAndUnpack(this) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { unpacked ->
            variants.add(unpacked)
            variants.add(unpacked.basicHtmlDecode().unescapeJs())
        }

        for (source in variants) {
            val cleaned = source.basicHtmlDecode().unescapeJs()
            Regex("""(?:src|url|link|file|iframe|embed|player|video|contentUrl|embedUrl|download)
                \s*["']?\s*[:=]\s*["']([^"']+)["']""".trimIndent().replace("\n", ""), RegexOption.IGNORE_CASE)
                .findAll(cleaned).forEach { add(it.groupValues.getOrNull(1), it.value.qualityLabelFromUrl()) }
            Regex("""https?://[^"'<>&\s\\]+""", RegexOption.IGNORE_CASE)
                .findAll(cleaned).forEach { add(it.value, it.value.qualityLabelFromUrl()) }
            Regex("""(?:atob\(|base64_decode\(|["'])([A-Za-z0-9+/=_-]{32,})(?:["']|\))""")
                .findAll(cleaned).forEach { match ->
                    val decoded = decodePossibleBase64(match.groupValues[1]) ?: return@forEach
                    decoded.extractCandidatesFromText(referer).forEach { add(it.url, it.label) }
                }
        }
        return results.values.toList()
    }

    private fun String.toPageUrl(page: Int): String {
        if (page <= 1) return this
        val trimmed = trimEnd('/')
        return "$trimmed/page/$page/"
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst("a[rel=next], a.next, .next a, .pagination a[href*='/page/${page + 1}/'], a[href*='paged=${page + 1}']") != null
    }

    private fun Element.bestCard(): Element {
        if (selectFirst("img") != null && attr("href").isNotBlank()) return this
        if (selectFirst("img") != null && selectFirst("a[href]") != null) return this
        return parents().firstOrNull { parent ->
            parent.selectFirst("img") != null && parent.selectFirst("a[href]") != null && parent.text().length in 2..1200
        } ?: this
    }

    private fun Element.bestImage(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
            ?: image?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() }
    }

    private fun Document.bestTitle(): String? {
        return selectFirst("h1, .entry-title, .title h1, .anime-title, .judul")?.text()?.trim()
            ?: selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")?.trim()
            ?: selectFirst("title")?.text()?.substringBefore("|")?.substringBefore(" - ")?.trim()
    }

    private fun Document.bestPoster(): String? {
        return listOfNotNull(
            selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content"),
            selectFirst(".poster img, .cover img, .thumb img, .thumbnail img, article img, main img")?.bestImage()
        ).firstOrNull { it.isNotBlank() }?.toAbsoluteUrl()
    }

    private fun Document.bestPlot(): String? {
        return selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst(".sinopsis, .synopsis, .description, .desc, .entry-content p, article p")?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun String.toAbsoluteUrl(): String? {
        val raw = trim().trim('"', '\'', ' ')
        if (raw.isBlank() || raw == "#") return null
        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("/") -> mainUrl.trimEnd('/') + raw
            raw.startsWith("?") -> "$mainUrl/$raw"
            raw.startsWith("./") -> mainUrl.trimEnd('/') + "/" + raw.removePrefix("./")
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> null
        }
    }

    private fun normalizePageUrl(raw: String?): String? = raw?.basicHtmlDecode()?.unescapeJs()?.replace("\\/", "/")?.toAbsoluteUrl()
    private fun normalizeMediaUrl(raw: String?): String? = normalizePageUrl(raw?.trim()?.trim('"', '\'', ' ', ',', ')', ']', '}'))

    private fun String.isAnimePageUrl(): Boolean {
        val lower = lowercase()
        if (lower.contains("/wp-content/") || lower.contains("/tag/") || lower.contains("/genre/") || lower.contains("/page/")) return false
        if (lower.contains("dcma") || lower.contains("dmca") || lower.contains("faq") || lower.contains("disclaimer")) return false
        if (lower.contains("bookmark") || lower.contains("login") || lower.contains("register") || lower.contains("surprise")) return false
        return lower.startsWith(mainUrl.lowercase()) && (
            lower.contains("/kusonime/") || lower.contains("/oploverz/") || lower.contains("/maxnime/") ||
                lower.contains("/meownime/") || lower.contains("/neonime/") || lower.contains("/otakudesu/") ||
                lower.contains("/anitoki/") || lower.contains("/anoboy/")
            ) && !listOf("/kusonime/", "/oploverz/", "/oploverz/ongoing/", "/oploverz/movie/", "/maxnime/", "/meownime/", "/neonime/", "/otakudesu/", "/anitoki/", "/anoboy/").any { lower == mainUrl.lowercase() + it }
    }

    private fun String.isLikelyEpisodeUrl(): Boolean {
        val lower = lowercase()
        return isAnimePageUrl() || lower.contains("episode") || lower.contains("nonton") || lower.contains("watch") || lower.contains("stream")
    }

    private fun String.isPotentialPlayerUrl(): Boolean {
        val lower = lowercase()
        return lower.isDirectMedia() ||
            lower.contains("/embed") || lower.contains("iframe") || lower.contains("player") ||
            lower.contains("stream") || lower.contains("vidhide") || lower.contains("filedon") ||
            lower.contains("mega") || lower.contains("dood") || lower.contains("filemoon") ||
            lower.contains("mp4upload") || lower.contains("voe") || lower.contains("mixdrop") ||
            lower.contains("streamtape") || lower.contains("pixeldrain") || lower.contains("gdrive") ||
            lower.contains("drive.google") || lower.contains("googlevideo.com/videoplayback") ||
            lower.contains("sbembed") || lower.contains("sendvid") || lower.contains("blogger.com/video") ||
            lower.startsWith(mainUrl.lowercase())
    }

    private fun String.isDirectMedia(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".mkv") ||
            lower.contains("googlevideo.com/videoplayback") || lower.contains("mime=video") || lower.contains("cloudflarestorage.com")
    }

    private fun String.shouldInlineResolve(): Boolean {
        val lower = lowercase()
        return lower.contains("vidhide") || lower.contains("filedon") || lower.contains("dood") || lower.contains("filemoon") ||
            lower.contains("stream") || lower.contains("player") || lower.contains("embed") || lower.startsWith(mainUrl.lowercase())
    }

    private fun String.cleanTitle(): String {
        return htmlDecode()
            .replace(Regex("(?i)\\s*sub\\s*indo.*$"), "")
            .replace(Regex("(?i)\\s*subtitle\\s*indonesia.*$"), "")
            .replace(Regex("(?i)\\s*download\\s*anime\\s*"), "")
            .replace(Regex("(?i)\\s*nonton\\s*"), "")
            .replace(Regex("(?i)\\s*episode\\s*\\d+.*$"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':', '»')
    }

    private fun List<String?>.firstCleanTitle(): String? {
        for (item in this) {
            val clean = item?.cleanTitle()?.takeIf { it.length >= 2 } ?: continue
            return clean
        }
        return null
    }

    private fun String.isNavigationTitle(): Boolean {
        val normalized = lowercase().trim().removeSuffix(" >").removeSuffix(">")
        return normalized in setOf(
            "home", "anime terbaru", "anime ongoing", "anime on-going", "anime completed", "anime complete",
            "anime movie", "movie", "ongoing", "complete", "anime list", "jadwal rilis", "bookmark",
            "surprise me!", "portal web", "selanjutnya", "previous", "next", "faqs", "dcma", "disclaimer",
            "kusonime", "oploverz", "maxnime", "meownime", "neonime", "otakudesu", "anitoki", "anoboy"
        )
    }

    private fun String.slugTitle(): String = substringBefore("?").trimEnd('/').substringAfterLast('/').replace('-', ' ').cleanTitle().ifBlank { name }
    private fun String.parseYear(): Int? = Regex("""\b(?:19|20)\d{2}\b""").find(this)?.value?.toIntOrNull()
    private fun String.parseEpisodeNumber(): Int? {
        val match = Regex("""(?i)(?:episode|eps|ep)\s*[-:]?\s*(\d{1,4})""").find(this)
            ?: Regex("""\b(\d{1,4})\s*(?:END|Final)?\b""", RegexOption.IGNORE_CASE).find(this)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
    private fun String.parseEpisodeName(): String? = parseEpisodeNumber()?.let { "Episode $it" }
    private fun String.normalizedMediaKey(): String = substringBefore("&Expires=").substringBefore("?Expires=").substringBefore("&X-Amz-Signature=")
    private fun String.parseQuality(): Int? = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    private fun String.qualityLabelFromUrl(): String = parseQuality()?.let { "${it}p" }.orEmpty()
    private fun String.cleanLabel(): String = htmlDecode().replace(Regex("\\s+"), " ").trim()
    private fun String.htmlDecode(): String = Jsoup.parse(this).text()
    private fun String.basicHtmlDecode(): String {
        return replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#x22;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun String.unescapeJs(): String {
        var output = this
        repeat(2) {
            output = Regex("""\\u([0-9a-fA-F]{4})""").replace(output) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        }
        return output.replace("\\/", "/").replace("\\\"", "\"").replace("\\'", "'")
    }

    private fun decodePossibleBase64(value: String?): String? {
        val raw = value?.trim()?.trim('"', '\'', ' ') ?: return null
        if (raw.length < 16) return null
        return runCatching {
            val normalized = raw.replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT))
        }.getOrNull()?.takeIf { it.contains("http", true) || it.contains("<iframe", true) }
    }

    private fun playerHeaders(url: String): Map<String, String> {
        return siteHeaders + mapOf("Referer" to if (url.startsWith(mainUrl, true)) "$mainUrl/" else mainUrl)
    }

    private data class ServerCandidate(
        val url: String,
        val label: String?,
        val referer: String?
    )
}
