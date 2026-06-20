@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.sad25kag.ayononton

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * AyoNonton Provider v2 — ayononton.live
 *
 * Platform : WordPress + MuviPro/idmuvi theme (Terbit21 clone)
 * x-powered-by: TERBIT21.COM
 *
 * ── ROOT CAUSE FIX (v2) ──────────────────────────────────────────
 * v1 called play-ads.php which only plays a PLACEHOLDER video:
 *   file:"https://github.com/terbit21org/terbit21/raw/main/loading.mp4"
 *   (confirmed via packed-JS decode of JWPlayer setup in play-ads.php)
 *
 * thank_you() in play-ads.php redirects to the REAL player:
 *   DRIVE/P2P  → https://t21.press/p2p.php?movie={slug}
 *   HYDRAX     → https://t21.press/g-hydrax.php?movie={slug}
 *   GDFRAME    → https://t21.press/gdframe.php?movie={slug}
 *
 * v2 fix: skip play-ads.php entirely, call real player endpoints.
 * g-hydrax.php returns iframe src="https://playhydrax.com/?v={id}"
 * → extract that URL → loadExtractor (Hydrax).
 *
 * ── PLAYER FLOW (confirmed) ──────────────────────────────────────
 * 1. POST  https://t21.press/data.php  body: movie={slug}
 *    → HTML: <a href="...play-ads.php?...&iframe={type}" class="{SERVER}">
 * 2. Map class → real endpoint:
 *    DRIVE/P2P  → t21.press/p2p.php?movie={slug}      (WebViewResolver → m3u8)
 *    HYDRAX     → t21.press/g-hydrax.php?movie={slug} (iframe src = playhydrax.com/?v=ID)
 *    GDFRAME    → t21.press/gdframe.php?movie={slug}  (WebViewResolver → m3u8)
 * 3. Download: POST t21.press/verifying.php?movie={slug}
 *    → HTML table: <a href="https://t21.press/download-540p/{slug}">
 *
 * ── CONFIRMED EVIDENCE ───────────────────────────────────────────
 * g-hydrax.php response:  src="https://playhydrax.com/?v=s2-8z_OID"
 * p2p.php response:       complex P2P player (21 KB), signal.* CDN
 * verifying.php response: download table with 540P + OpenX links
 *
 * ── URL PATTERNS ─────────────────────────────────────────────────
 * Search:    /?s={query}  pages: /page/N/?s={query}
 * Listing:   /latest/, /populer/, /trending-minggu-ini/, /genre/{slug}/
 * Pagination: /page/N/
 * Cards:      <article class="item has-post-thumbnail" itemtype="schema.org/Movie">
 * Poster:    i2.wp.com/terbit21.com/wp-content/uploads/... OR ayononton.live/wp-content/uploads/...
 * Metadata:  <div class="gmr-moviedata"> → Genre, Tahun, Durasi, Negara, Rating
 */
class AyoNonton : MainAPI() {

    override var mainUrl = "https://ayononton.live"
    override var name = "AyoNonton"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val T21_API = "https://t21.press"

    // ──────────────────────────────────────────────────────────────
    // Main page sections
    // ──────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/latest/"               to "Terbaru",
        "$mainUrl/populer/"              to "Populer",
        "$mainUrl/trending-minggu-ini/"  to "Trending Minggu Ini",
        "$mainUrl/rating/"               to "Rating Tertinggi",
        "$mainUrl/genre/action/"         to "Aksi",
        "$mainUrl/genre/drama/"          to "Drama",
        "$mainUrl/genre/comedy/"         to "Komedi",
        "$mainUrl/genre/horror/"         to "Horor",
        "$mainUrl/genre/thriller/"       to "Thriller",
        "$mainUrl/genre/romance/"        to "Romansa",
        "$mainUrl/genre/animation/"      to "Animasi",
        "$mainUrl/genre/sci-fi/"         to "Sci-Fi",
        "$mainUrl/genre/biography/"      to "Biografi",
        "$mainUrl/genre/crime/"          to "Kriminal",
        "$mainUrl/genre/fantasy/"        to "Fantasi",
    )

    // ──────────────────────────────────────────────────────────────
    // Parse listing page
    // ──────────────────────────────────────────────────────────────
    private fun parseListingPage(doc: Document): List<SearchResponse> {
        // Confirmed card selector from crawl:
        //   <article class="item-infinite col-md-2 item has-post-thumbnail" itemtype="schema.org/Movie">
        return doc.select("article.item").mapNotNull { art ->
            val anchor = art.selectFirst("a[itemprop=url]") ?: return@mapNotNull null
            val href   = anchor.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null

            // Strip "Nonton Film: " prefix (confirmed in crawl)
            val rawTitle = anchor.attr("title")
                .ifBlank { anchor.ownText() }
            val title = rawTitle
                .removePrefix("Nonton Film: ")
                .removePrefix("Nonton Series: ")
                .removePrefix("Nonton ")
                .trim()
                .ifBlank { href.trimEnd('/').substringAfterLast('/') }

            // Poster: prefer largest srcset → fallback img src → og-equivalent in listing
            val poster = bestPoster(art)

            val tvType = detectType(title, art.attr("itemtype"))

            if (tvType == TvType.TvSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
            }
        }
    }

    private fun bestPoster(el: org.jsoup.nodes.Element): String? {
        // Try <source srcset> — pick largest (last) entry
        el.selectFirst("source[srcset]")?.attr("srcset")
            ?.split(",")
            ?.lastOrNull()
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.firstOrNull { it.startsWith("http") }
            ?.let { return it }

        // Try <img> itemprop or data-src
        el.selectFirst("img[itemprop=image], img[data-src], img[src]")?.let { img ->
            val src = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            if (src.startsWith("http")) return src
        }
        return null
    }

    private fun detectType(title: String, itemtype: String): TvType {
        if (itemtype.contains("TVSeries", ignoreCase = true)) return TvType.TvSeries
        if (title.contains(Regex("""(?i)\b(season|series|s\d+e\d+|episode)\b"""))) return TvType.TvSeries
        return TvType.Movie
    }

    // ──────────────────────────────────────────────────────────────
    // Main page
    // ──────────────────────────────────────────────────────────────
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data
                  else request.data.trimEnd('/') + "/page/$page/"

        val doc  = app.get(url, headers = mapOf("User-Agent" to ua)).document
        val items = parseListingPage(doc)
        val hasMore = doc.selectFirst("a.next.page-numbers, .pagination .next, a[rel=next]") != null

        return newHomePageResponse(
            list    = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasMore,
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Search
    // ──────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/?s=${query.encodeUri()}",
            headers = mapOf("User-Agent" to ua),
        ).document
        return parseListingPage(doc)
    }

    // ──────────────────────────────────────────────────────────────
    // Load detail page
    // ──────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc  = app.get(url, headers = mapOf("User-Agent" to ua)).document
        val slug = url.trimEnd('/').substringAfterLast('/')

        // Title: <h1 itemprop="name">Title (Year)</h1>
        val rawTitle = doc.selectFirst("h1[itemprop=name], h1.entry-title, h1")
            ?.text()?.trim() ?: slug

        // Poster: og:image → confirmed as ayononton.live/wp-content/uploads/...
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("img[itemprop=image]")?.absUrl("src")

        // Description
        val description = doc.selectFirst(
            "div.entry-content.clearfix, div.entry-content, div#synopsis, div.gmr-sinopsis"
        )?.text()?.trim()

        // Metadata from <div class="gmr-moviedata">
        val genres   = mutableListOf<String>()
        var year     : Int?    = null
        var duration : String? = null
        var rating   : String? = null

        doc.select("div.gmr-moviedata, div.gmr-movie-data").forEach { div ->
            val label = div.selectFirst("strong")?.text()?.lowercase()?.trim() ?: ""
            val value = div.text().removePrefix(div.selectFirst("strong")?.text() ?: "").trim()
            when {
                label.contains("genre")  -> div.select("a[href*=/genre/]").forEach { genres.add(it.text()) }
                label.contains("tahun")  -> year = value.take(4).toIntOrNull()
                label.contains("durasi") -> duration = value
                label.contains("rating") ->
                    rating = div.selectFirst("span[itemprop=ratingValue], span, .imdb-rating")
                        ?.text()?.trim()?.ifBlank { null }
            }
        }

        // Year fallback: from title "(YYYY)"
        if (year == null) {
            year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        }

        // Clean title — remove trailing "(YYYY)"
        val title = rawTitle.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim().ifBlank { rawTitle }

        // Type
        val isSeries = doc.select("div.gmr-moviedata a[href*=/genre/]")
            .any { it.text().contains(Regex("(?i)series|episode|season")) }
            || title.contains(Regex("""(?i)\b(season|series|s\d+e?\d*)\b"""))

        val dataToken = "$mainUrl/||$slug"

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(
                Episode(
                    data        = dataToken,
                    name        = title,
                    season      = 1,
                    episode     = 1,
                    posterUrl   = poster,
                    description = description,
                )
            )) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = genres.distinct()
                this.rating    = rating?.toRatingInt()
                this.duration  = duration?.filter { it.isDigit() }?.toIntOrNull()
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, dataToken) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = genres.distinct()
                this.rating    = rating?.toRatingInt()
                this.duration  = duration?.filter { it.isDigit() }?.toIntOrNull()
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Load streaming links  (v2: skip play-ads.php → real endpoints)
    //
    // Root cause fixed:
    //   play-ads.php → JWPlayer file = loading.mp4 (FAKE placeholder)
    //   thank_you()  → real player URL
    //
    // Real player map (confirmed from JS source analysis):
    //   DRIVE / P2P → t21.press/p2p.php?movie={slug}      → WebViewResolver m3u8
    //   HYDRAX      → t21.press/g-hydrax.php?movie={slug} → iframe playhydrax.com/?v={id}
    //   GDFRAME     → t21.press/gdframe.php?movie={slug}  → WebViewResolver m3u8
    //
    // Download:
    //   POST t21.press/verifying.php?movie={slug}
    //   → HTML table: <a href="t21.press/download-{res}/{slug}"> + OpenX links
    // ──────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data     : String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback : (ExtractorLink) -> Unit,
    ): Boolean {
        val slug = if (data.contains("||")) data.substringAfterLast("||")
                   else data.trimEnd('/').substringAfterLast('/')

        var loaded = false

        // ── Step 1: Get server list from data.php ─────────────
        val apiHtml = try {
            app.post(
                "$T21_API/data.php",
                headers = mapOf(
                    "User-Agent"   to ua,
                    "Referer"      to "$mainUrl/$slug/",
                    "Origin"       to mainUrl,
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                data = mapOf("movie" to slug),
            ).text
        } catch (_: Exception) { null }

        if (apiHtml.isNullOrBlank() || apiHtml.trim() == "none") {
            // Try download links as fallback
            loaded = loadDownloadLinks(slug, callback) || loaded
            return loaded
        }

        // ── Step 2: Parse server links ─────────────────────────
        // <a href="...play-ads.php?movie=...&iframe=..." class="DRIVE">UTAMA</a>
        // <a href="...play-ads.php?movie=...&iframe=g-hydrax" class="HYDRAX">HYDRAX</a>
        val serverDoc = Jsoup.parse(apiHtml)
        val servers   = serverDoc.select("a[href][class]").filter { a ->
            val rel = a.attr("rel")
            !rel.contains("download", ignoreCase = true) &&
            !a.attr("href").contains("#download", ignoreCase = true)
        }

        val seen = mutableSetOf<String>() // deduplicate by real endpoint

        for (server in servers) {
            val cssClass   = server.attr("class").uppercase().trim()
            val serverName = server.text().trim().ifBlank { cssClass }
            val iframeParam = server.absUrl("href")
                .substringAfter("iframe=", "")
                .substringBefore("&")

            // Map to real endpoint (skip play-ads.php)
            val realUrl = when {
                cssClass.contains("HYDRAX")  -> "$T21_API/g-hydrax.php?movie=$slug"
                cssClass.contains("GDFRAME") -> "$T21_API/gdframe.php?movie=$slug"
                else                          -> "$T21_API/p2p.php?movie=$slug" // DRIVE / P2P
            }

            if (!seen.add(realUrl)) continue

            when {
                cssClass.contains("HYDRAX") ->
                    if (loadHydrax(realUrl, slug, serverName, subtitleCallback, callback)) loaded = true
                else ->
                    if (loadP2POrGdFrame(realUrl, slug, serverName, subtitleCallback, callback)) loaded = true
            }
        }

        // ── Step 3: Download links ─────────────────────────────
        if (loadDownloadLinks(slug, callback)) loaded = true

        return loaded
    }

    // ── HYDRAX: fetch g-hydrax.php → extract playhydrax.com URL ──
    private suspend fun loadHydrax(
        realUrl  : String,
        slug     : String,
        name     : String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback : (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val pageHtml = app.get(
                realUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer"    to "$mainUrl/$slug/",
                )
            ).text

            // Confirmed pattern: src="https://playhydrax.com/?v=s2-8z_OID"
            val hydraxUrl = Regex("""src=["'](https?://(?:www\.)?playhydrax\.com/[^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(pageHtml)?.groupValues?.get(1)
                ?: Regex("""["'](https?://(?:www\.)?playhydrax\.com/\?v=[^"'\s]+)["']""", RegexOption.IGNORE_CASE)
                    .find(pageHtml)?.groupValues?.get(1)

            if (hydraxUrl != null) {
                loadExtractor(hydraxUrl, realUrl, subtitleCallback, callback)
            } else {
                // Generic fallback: try any iframe src from the page
                val iframeSrc = Regex("""<iframe[^>]+src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(pageHtml)?.groupValues?.get(1)
                if (iframeSrc != null) loadExtractor(iframeSrc, realUrl, subtitleCallback, callback)
                else false
            }
        } catch (_: Exception) { false }
    }

    // ── P2P / GDFRAME: WebViewResolver captures m3u8 from JS player ─
    private suspend fun loadP2POrGdFrame(
        realUrl  : String,
        slug     : String,
        name     : String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback : (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            // Try simple HTTP first — some films may expose m3u8 in static HTML
            val pageHtml = app.get(
                realUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer"    to "$mainUrl/$slug/",
                )
            ).text

            // Regex scrape for video URL
            val videoUrl = extractVideoUrl(pageHtml)

            if (videoUrl != null) {
                val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                callback.invoke(
                    newExtractorLink(
                        source  = this.name,
                        name    = "${this.name} [$name]",
                        url     = videoUrl,
                        referer = realUrl,
                        quality = Qualities.Unknown.value,
                        type    = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    )
                )
                true
            } else {
                // Fallback: WebViewResolver captures any M3U8 network request
                val resolver = WebViewResolver(
                    Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE),
                    userAgent  = ua,
                    useOkhttp  = true,
                )
                val capturedUrl = app.get(
                    realUrl,
                    headers      = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/$slug/"),
                    interceptor  = resolver,
                    timeout      = 60L,
                ).url

                if (capturedUrl.isNotBlank() && capturedUrl.contains(".m3u8", ignoreCase = true)) {
                    callback.invoke(
                        newExtractorLink(
                            source  = this.name,
                            name    = "${this.name} [$name]",
                            url     = capturedUrl,
                            referer = realUrl,
                            quality = Qualities.Unknown.value,
                            type    = ExtractorLinkType.M3U8,
                        )
                    )
                    true
                } else false
            }
        } catch (_: Exception) { false }
    }

    // ── Download links from verifying.php ─────────────────────────
    // Confirmed response:
    //   <a href="https://t21.press/download-540p/{slug}">Download</a>
    //   <a href="https://download.openx.xyz/?v={id}">Download</a>
    private suspend fun loadDownloadLinks(
        slug    : String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return try {
            val dlHtml = app.post(
                "$T21_API/verifying.php?movie=$slug",
                headers = mapOf(
                    "User-Agent"   to ua,
                    "Referer"      to "https://terbit21.tv/get/?movie=$slug",
                    "Origin"       to "https://terbit21.tv",
                    "Content-Type" to "application/x-www-form-urlencoded",
                ),
                data = mapOf("movie" to slug),
            ).text

            if (dlHtml.isBlank()) return false

            val dlDoc = Jsoup.parse(dlHtml)
            var found = false

            dlDoc.select("table a[href]").forEach { a ->
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                if (href.isBlank() || href.contains("facebook") || href.contains("twitter")
                    || href.contains("t.me") || href.contains("subscene")) return@forEach

                // Quality from row context
                val row    = a.closest("tr")
                val rowText = row?.text()?.uppercase() ?: ""
                val quality = when {
                    rowText.contains("1080") -> Qualities.P1080.value
                    rowText.contains("720")  -> Qualities.P720.value
                    rowText.contains("480")  -> Qualities.P480.value
                    rowText.contains("540")  -> Qualities.P480.value // treat 540 as 480
                    rowText.contains("360")  -> Qualities.P360.value
                    else                     -> Qualities.Unknown.value
                }

                // Provider name from row
                val provider = row?.selectFirst("td")?.text()?.trim()
                    ?.ifBlank { "Download" } ?: "Download"

                callback.invoke(
                    newExtractorLink(
                        source  = name,
                        name    = "$name [DL] $provider",
                        url     = href,
                        referer = "https://terbit21.tv/",
                        quality = quality,
                        type    = ExtractorLinkType.VIDEO,
                    )
                )
                found = true
            }
            found
        } catch (_: Exception) { false }
    }

    // ── Regex scraper: static m3u8/mp4 in page HTML/JS ──────────
    private fun extractVideoUrl(html: String): String? {
        // JWPlayer file: "https://...m3u8"
        Regex("""["']file["']\s*:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            .find(html)?.groupValues?.get(1)
            ?.takeIf { !it.contains("github.com") && !it.contains("loading") }
            ?.let { return it }

        // Generic m3u8 in JS strings
        Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""")
            .find(html)?.groupValues?.get(1)
            ?.takeIf { !it.contains("github.com") }
            ?.let { return it }

        // Generic mp4
        Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""")
            .find(html)?.groupValues?.get(1)
            ?.takeIf { !it.contains("github.com") && !it.contains("loading") }
            ?.let { return it }

        // Iframe to known players
        Regex("""<iframe[^>]+src=["'](https?://(?!ayononton|terbit21\.com|terbit21\.media)[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
            ?.let { return it }

        return null
    }

    // ── URL encode helper ─────────────────────────────────────────
    private fun String.encodeUri() =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
