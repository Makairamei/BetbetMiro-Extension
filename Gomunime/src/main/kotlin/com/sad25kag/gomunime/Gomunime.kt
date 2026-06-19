package com.sad25kag.gomunime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Gomunime : MainAPI() {

    // ─────────────────────────────────────────────────────────────────────────
    // Provider metadata
    // ─────────────────────────────────────────────────────────────────────────

    override var mainUrl   = "https://gomunime.top"
    override var name      = "Gomunime"
    override var lang      = "id"
    override val hasMainPage        = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // ─────────────────────────────────────────────────────────────────────────
    // Main page sections  (pagination uses ?page=N)
    // ─────────────────────────────────────────────────────────────────────────

    override val mainPage = mainPageOf(
        "$mainUrl/status/ongoing"               to "Sedang Tayang",
        "$mainUrl/status/completed"             to "Tamat",
        "$mainUrl/type/movie"                   to "Movie",
        "$mainUrl/koleksi/anime-skor-mal-tertinggi" to "Top Rated MAL",
    )

    // ─────────────────────────────────────────────────────────────────────────
    // URL / slug helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true for episode pages: URL ends with -episode-N (optionally season) */
    private fun isEpisodeUrl(url: String): Boolean {
        val slug = url.trimEnd('/').substringAfterLast("/")
        return slug.contains(Regex("""-episode-\d+$""", RegexOption.IGNORE_CASE))
    }

    /** Determine if the current detail page represents a movie */
    private fun isMovieDetail(metaDesc: String, slug: String, numEps: Int): Boolean {
        // e.g. "Nonton Anime ... (Movie) ... status completed, 1 episode"
        if (metaDesc.contains(Regex("""\bMovie\b""", RegexOption.IGNORE_CASE))) return true
        if (slug.contains("-movie", ignoreCase = true)) return true
        if (numEps <= 1 && slug.contains("movie", ignoreCase = true)) return true
        return false
    }

    /** Try to parse episode number from text like "Episode 12", "ep 5", etc. */
    private fun parseEpNum(text: String): Int? =
        Regex("""(?:episode|ep|e)[^0-9]*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""(\d+)""").find(text.trimEnd('/').substringAfterLast("/"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()

    // ─────────────────────────────────────────────────────────────────────────
    // Card parsing (shared between getMainPage and search)
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseCard(el: Element): AnimeSearchResponse? {
        val href = el.absUrl("href").trim()
        if (href.isBlank() || href == mainUrl || href == "$mainUrl/") return null
        if (href.contains("/genre/")  || href.contains("/status/") ||
            href.contains("/type/")   || href.contains("/koleksi/") ||
            href.contains("/search")  || href.contains("/build/")   ||
            href.contains("/download")) return null

        // Title: img alt is the most reliable source on this site
        val img   = el.selectFirst("img")
        val title = img?.attr("alt")?.trim()
            ?: el.selectFirst("h3, h2, p[class*=title], [class*=text-white]")?.text()?.trim()
            ?: return null
        if (title.isBlank()) return null

        // Poster: img src
        val poster = img?.attr("src").orEmpty().let { s ->
            when {
                s.startsWith("http") -> s
                s.startsWith("//")   -> "https:$s"
                s.startsWith("/")    -> "$mainUrl$s"
                else                 -> null
            }
        }

        val tvType = if (href.contains("-movie", ignoreCase = true) ||
                        href.contains("/ova",   ignoreCase = true))
            TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = poster
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // getMainPage
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val doc = app.get(url).document
        val items = doc.select("a.card-netflix")
            .mapNotNull { parseCard(it) }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Search  (/search?q=...)
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val q   = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/search?q=$q").document
        return doc.select("a.card-netflix")
            .mapNotNull { parseCard(it) }
            .distinctBy { it.url }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // load  (anime/movie detail page)
    //
    // Evidence:
    //   - Detail URL : https://gomunime.top/{slug}      (no /anime/ prefix!)
    //   - Episode URL: https://gomunime.top/{slug}-episode-{N}
    //   - JSON-LD    : <script type="application/ld+json">{"@type":"TVSeries",...}
    //   - Episode list: #episode-list a[href]
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val doc      = app.get(cleanUrl).document

        // ── JSON-LD (most reliable source of metadata) ──────────────────────
        val jsonRaw = doc.select("script[type='application/ld+json']")
            .mapNotNull { it.data().takeIf { d -> d.contains("\"TVSeries\"") } }
            .firstOrNull() ?: ""

        fun jldField(key: String): String? =
            Regex(""""$key"\s*:\s*"([^"]+)"""").find(jsonRaw)?.groupValues?.getOrNull(1)

        val title = jldField("name")
            ?: doc.selectFirst("h1, h2.title")?.text()?.trim()
            ?: "Unknown"

        val poster = jldField("image")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        val description = Regex(""""description"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(jsonRaw)?.groupValues?.getOrNull(1)
            ?.replace("\\n", "\n")?.replace("\\\"", "\"")?.trim()
            ?: doc.selectFirst(".prose-invert, .plot, .description")?.text()?.trim()

        val genres: List<String> = Regex(""""genre"\s*:\s*\[([^\]]+)]""")
            .find(jsonRaw)?.groupValues?.getOrNull(1)
            ?.split(",")
            ?.map { it.trim().trim('"') }
            ?.filter { it.isNotBlank() && !it.equals("anime", ignoreCase = true) }
            ?: doc.select("a[href*=/genre/]").map { it.text().trim() }.filter { it.isNotBlank() }

        val year   = jldField("datePublished")?.take(4)?.toIntOrNull()
        val numEps = Regex(""""numberOfEpisodes"\s*:\s*(\d+)""")
            .find(jsonRaw)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

        // ── Movie detection ──────────────────────────────────────────────────
        val metaDesc = doc.selectFirst("meta[name='description']")?.attr("content").orEmpty()
        val slug     = cleanUrl.trimEnd('/').substringAfterLast("/")
        val isMovie  = isMovieDetail(metaDesc, slug, numEps)

        // ── Episode list ─────────────────────────────────────────────────────
        // Evidence: #episode-list a[href] → "https://gomunime.top/{slug}-episode-N"
        val episodes = doc.select("#episode-list a[href]")
            .mapNotNull { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith(mainUrl) || !isEpisodeUrl(href)) return@mapNotNull null
                val epText = a.text().trim().ifBlank {
                    href.trimEnd('/').substringAfterLast("/")
                }
                val epNum = parseEpNum(epText) ?: parseEpNum(href)
                newEpisode(href) {
                    name    = epNum?.let { "Episode $it" } ?: epText
                    episode = epNum
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        // ── Return ───────────────────────────────────────────────────────────
        if (isMovie) {
            // For movies: use the first episode link (watch page) as data, not detail page
            val movieData = episodes.firstOrNull()?.data ?: cleanUrl
            return newMovieLoadResponse(title, cleanUrl, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.plot      = description
                this.tags      = genres
                this.year      = year
            }
        }

        val tracker = APIHolder.getTracker(
            listOf(title),
            TrackerType.getTypes(TvType.Anime),
            null,
            true
        )
        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            engName             = title
            posterUrl           = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            addEpisodes(DubStatus.Subbed, episodes)
            plot    = description
            tags    = genres
            this.year = year
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // loadLinks  (episode/movie watch page)
    //
    // Evidence from episode page:
    //   <div class="aspect-video bg-black relative">
    //     <div x-show="active === 0" ...>
    //       <iframe src="https://anime-indo.lol/btube3.php?url=...">  ← B-TUBE
    //     <div x-show="active === 1" ...>
    //       <iframe src="https://xtwap.top/cepat.php?url=...">        ← CEPAT
    //     <div x-show="active === 2" ...>
    //       <iframe src="https://gdplayer.to/x/?...">                 ← GDPLAYER
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = app.get(data).document

        // Collect all player iframes — specifically from Alpine.js panels inside .aspect-video
        val iframeSrcs = doc
            .select("div.aspect-video div[x-show] iframe[src], " +
                    ".aspect-video [x-show] iframe[src], "       +
                    "div[x-show^='active'] iframe[src]")
            .map { it.attr("src") }
            .filter { it.isNotBlank() }
            .distinct()

        var found = false

        for (rawSrc in iframeSrcs) {
            val src = when {
                rawSrc.startsWith("//")   -> "https:$rawSrc"
                rawSrc.startsWith("http") -> rawSrc
                rawSrc.startsWith("/")    -> "$mainUrl$rawSrc"
                else                      -> continue
            }
            val ok = when {
                src.contains("btube3.php")      ||
                src.contains("b-tube")          ||
                src.contains("btube", ignoreCase = true) -> handleBtube(src, data, callback)

                src.contains("cepat.php")       ||
                src.contains("xtwap.top")       -> handleCepat(src, data, callback)

                src.contains("gdplayer.to")     -> handleGdPlayer(src, data, callback)

                else -> {
                    loadExtractor(src, data, subtitleCallback, callback)
                    true
                }
            }
            if (ok) found = true
        }

        return found
    }

    // ─────────────────────────────────────────────────────────────────────────
    // B-TUBE  (anime-indo.lol/btube3.php?url=...)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun handleBtube(
        src: String, referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc  = app.get(src,
            referer = mainUrl,
            headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
        ).document
        val html = doc.html()

        // Priority 1: JWPlayer "file":"url"
        val jwFile = Regex(""""file"\s*:\s*"(https?://[^"]+)"""").find(html)
            ?.groupValues?.getOrNull(1)
        if (!jwFile.isNullOrBlank()) {
            val isHls = jwFile.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name, name = "B-TUBE",
                    url    = jwFile,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // Priority 2: HTML5 <source src="...">
        val srcAttr = doc.selectFirst("source[src]")?.attr("src").orEmpty()
        if (srcAttr.isNotBlank()) {
            val videoUrl = when {
                srcAttr.startsWith("//")   -> "https:$srcAttr"
                srcAttr.startsWith("http") -> srcAttr
                else                       -> return false
            }
            callback(
                newExtractorLink(
                    source = name, name = "B-TUBE",
                    url    = videoUrl,
                    type   = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CEPAT  (xtwap.top/cepat.php?url=...)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun handleCepat(
        src: String, referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc  = app.get(src,
            referer = mainUrl,
            headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
        ).document
        val html = doc.html()

        // JWPlayer "file":"url" — usually delivers HLS
        val jwFile = Regex(""""file"\s*:\s*"(https?://[^"]+)"""").find(html)
            ?.groupValues?.getOrNull(1)
        if (!jwFile.isNullOrBlank()) {
            val isHls = jwFile.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name, name = "CEPAT",
                    url    = jwFile,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = if (isHls) Qualities.Unknown.value else Qualities.P720.value
                }
            )
            return true
        }

        // Fallback: <source src="...">
        val srcAttr = doc.selectFirst("source[src]")?.attr("src").orEmpty()
        if (srcAttr.isNotBlank()) {
            val videoUrl = if (srcAttr.startsWith("//")) "https:$srcAttr" else srcAttr
            val isHls    = videoUrl.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name, name = "CEPAT",
                    url    = videoUrl,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GDPLAYER  (gdplayer.to/x/?{base64-params})
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun handleGdPlayer(
        src: String, referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc  = app.get(src,
            referer = mainUrl,
            headers = mapOf("Referer" to mainUrl, "Origin" to mainUrl)
        ).document
        val html = doc.html()

        // HLS m3u8
        val hlsUrl = Regex(""""(https?://[^"]+\.m3u8[^"]*)"""").find(html)
            ?.groupValues?.getOrNull(1)
        if (!hlsUrl.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name, name = "GDPLAYER",
                    url    = hlsUrl,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // JWPlayer "file":"url"
        val jwFile = Regex(""""file"\s*:\s*"(https?://[^"]+)"""").find(html)
            ?.groupValues?.getOrNull(1)
        if (!jwFile.isNullOrBlank()) {
            val isHls = jwFile.contains(".m3u8")
            callback(
                newExtractorLink(
                    source = name, name = "GDPLAYER",
                    url    = jwFile,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // MP4 direct
        val mp4Url = Regex(""""(https?://[^"]+\.mp4[^"]*)"""").find(html)
            ?.groupValues?.getOrNull(1)
        if (!mp4Url.isNullOrBlank()) {
            callback(
                newExtractorLink(
                    source = name, name = "GDPLAYER",
                    url    = mp4Url,
                    type   = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = src
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // Fallback: let CloudStream's own extractors try it
        loadExtractor(src, mainUrl, { }, callback)
        return true
    }
}
