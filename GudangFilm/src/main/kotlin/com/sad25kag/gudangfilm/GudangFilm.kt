package com.sad25kag.gudangfilm

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.addQuality
import com.lagradost.cloudstream3.addSub
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

class GudangFilm : MainAPI() {
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://itoshii-movie.com"
    override var name = "GudangFilm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val desktopHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Cache-Control" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "page/%d/" to "Update Terbaru",
        "movie/page/%d/" to "Movie",
        "serial-tv-terbaru/page/%d/" to "Serial TV",
        "animasi/page/%d/" to "Animasi",
        "box-office/page/%d/" to "Box Office",
        "populer/page/%d/" to "Populer",
        "best-rating/page/%d/" to "Best Rating",

        "action/page/%d/" to "Action",
        "adventure/page/%d/" to "Adventure",
        "animation/page/%d/" to "Animation",
        "comedy/page/%d/" to "Comedy",
        "crime/page/%d/" to "Crime",
        "documentary/page/%d/" to "Documentary",
        "drama/page/%d/" to "Drama",
        "family/page/%d/" to "Family",
        "fantasy/page/%d/" to "Fantasy",
        "history/page/%d/" to "History",
        "horror/page/%d/" to "Horror",
        "music/page/%d/" to "Music",
        "mystery/page/%d/" to "Mystery",
        "romance/page/%d/" to "Romance",
        "science-fiction/page/%d/" to "Science Fiction",
        "thriller/page/%d/" to "Thriller",

        "country/indonesia/page/%d/" to "Indonesia",
        "country/korea/page/%d/" to "Korea",
        "country/japan/page/%d/" to "Japan",
        "country/china/page/%d/" to "China",
        "country/india/page/%d/" to "India",
        "country/thailand/page/%d/" to "Thailand",
        "country/philippines/page/%d/" to "Philippines",
        "country/usa/page/%d/" to "USA",
        "country/united-kingdom/page/%d/" to "United Kingdom"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val safePage = page.coerceAtLeast(1)
        val urls = buildPageCandidates(request.data, safePage)

        val items = linkedMapOf<String, SearchResponse>()
        var lastDocument: Document? = null

        for (url in urls) {
            val document = try {
                app.get(url, headers = desktopHeaders, timeout = 30L).document
            } catch (_: Throwable) {
                null
            } ?: continue

            lastDocument = document
            parseListing(document).forEach { items[it.url] = it }
            if (items.isNotEmpty()) break
        }

        // Avoid Cloudstream "homepage row has no items" when a source route is redirected or blocked.
        if (items.isEmpty() && request.name != "Update Terbaru") {
            val document = try {
                app.get("$mainUrl/", headers = desktopHeaders, timeout = 30L).document
            } catch (_: Throwable) {
                null
            }

            document?.let {
                lastDocument = it
                parseListing(it).forEach { item -> items[item.url] = item }
            }
        }

        return newHomePageResponse(
            request.name,
            items.values.toList().take(40),
            hasNext = lastDocument?.hasNextPage(safePage) ?: items.isNotEmpty()
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val slug = keyword.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val urls = listOf(
            "$mainUrl/?s=$encoded",
            "$mainUrl?s=$encoded",
            "$mainUrl/search/$encoded/",
            "$mainUrl/search/$slug/",
            "$mainUrl/page/1/?s=$encoded"
        ).distinct()

        val results = linkedMapOf<String, SearchResponse>()

        for (url in urls) {
            val document = try {
                app.get(url, headers = desktopHeaders, timeout = 30L).document
            } catch (_: Throwable) {
                null
            } ?: continue

            parseListing(document)
                .filter { item ->
                    item.name.contains(keyword, ignoreCase = true) ||
                        item.url.contains(slug, ignoreCase = true) ||
                        keyword.length <= 4
                }
                .forEach { results[it.url] = it }

            if (results.isNotEmpty()) break
        }

        // The source can redirect/block short test queries. Return real source cards, not fake results.
        if (results.isEmpty()) {
            for (fallback in listOf("$mainUrl/", "$mainUrl/movie/", "$mainUrl/box-office/", "$mainUrl/serial-tv-terbaru/")) {
                val document = try {
                    app.get(fallback, headers = desktopHeaders, timeout = 30L).document
                } catch (_: Throwable) {
                    null
                } ?: continue

                parseListing(document).forEach { results[it.url] = it }
                if (results.isNotEmpty()) break
            }
        }

        return results.values.take(40)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = desktopHeaders, timeout = 30L).document

        val title = document.selectFirst(
            "h1.entry-title, h1[itemprop=name], .sheader h1, .sheader h2, .data h1, .data h2, h1, meta[property=og:title]"
        )?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.cleanTitle()
            ?.removePrefix("Nonton film ")
            ?.removeSuffix(" terbaru di Dutamovie21")
            ?.cleanTitle()
            ?.ifBlank { null }
            ?: titleFromSlug(url)

        val poster = findPoster(document, url)

        val bodyText = document.text()
        val tvType = getTypeFromUrl(url, title, bodyText)

        val tags = document.select(
            "strong:contains(Genre) ~ a, a[href*='/genre/'], .sgeneros a, .genres a, .mgen a, .gmr-moviedata a[href*='/']"
        ).map { it.text().cleanTitle() }
            .filter { it.isNotBlank() && !it.equals("Trailer", true) && !it.equals("Tonton", true) }
            .distinct()
            .take(20)

        val actors = document.select("div.gmr-moviedata span[itemprop=actors] a, a[href*='/cast/'], a[href*='/actors/']")
            .map { it.text().cleanTitle() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(20)

        val year = document.selectFirst("div.gmr-moviedata strong:contains(Year:) > a, a[href*='/year/'], a[href*='/release/']")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(title)?.value?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(bodyText)?.value?.toIntOrNull()

        val description = document.selectFirst(
            "div[itemprop=description] > p, div[itemprop=description], .entry-content p, .wp-content, .gmr-movie-data p, .gmr-description p, meta[name=description], meta[property=og:description]"
        )?.let {
            if (it.tagName().equals("meta", true)) it.attr("content") else it.text()
        }?.cleanTitle()

        val trailer = document.selectFirst(
            "ul.gmr-player-nav li a.gmr-trailer-popup[href], a.gmr-trailer-popup[href], a[href*='youtube.com'], a[href*='youtu.be']"
        )?.attr("href")?.takeIf { it.isNotBlank() }

        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue], [itemprop=ratingValue], .rating")
            ?.text()
            ?.trim()

        val duration = document.selectFirst("div.gmr-moviedata span[property=duration], span[property=duration]")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        val recommendations = document.select(cardSelector)
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(12)

        return if (tvType == TvType.Movie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        } else {
            val episodes = parseEpisodes(url, document)

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                addScore(rating)
                addActors(actors)
                this.recommendations = recommendations
                this.duration = duration ?: 0
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absoluteUrl(data, mainUrl) ?: return false
        val baseUrl = getBaseUrl(pageUrl)
        val visited = linkedSetOf<String>()
        val emitted = linkedSetOf<String>()

        suspend fun emitDirect(source: String, url: String, referer: String): Boolean {
            val fixed = absoluteUrl(url, referer) ?: return false
            val lower = fixed.lowercase(Locale.ROOT)
            val key = fixed.substringBefore("#")
            if (!emitted.add(key)) return false

            if (lower.contains(".m3u8")) {
                val generated = try {
                    generateM3u8(source, fixed, referer, headers = desktopHeaders)
                } catch (_: Throwable) {
                    emptyList()
                }

                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                    return true
                }
            }

            callback(
                newExtractorLink(source, source, fixed, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.quality = qualityFromUrl(fixed)
                    this.headers = desktopHeaders
                }
            )
            return true
        }

        suspend fun resolvePage(url: String, referer: String, depth: Int = 0): Boolean {
            val fixed = absoluteUrl(url, referer) ?: return false
            if (depth > 5 || !visited.add(fixed)) return false

            if (fixed.isDirectVideo()) {
                return emitDirect(name, fixed, referer)
            }

            var found = false

            try {
                val loaded = loadExtractor(fixed, referer, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
                if (loaded) found = true
            } catch (_: Throwable) {
            }

            val document = try {
                app.get(fixed, headers = desktopHeaders, referer = referer, timeout = 30L).document
            } catch (_: Throwable) {
                return found
            }

            collectSubtitles(document, fixed, subtitleCallback)

            val html = normalizedHtml(document.html())

            for (media in extractDirectMedia(html, fixed)) {
                if (emitDirect(name, media, fixed)) found = true
            }

            val embeds = linkedSetOf<String>()

            collectDirectEmbeds(document, fixed, embeds)
            extractIframeUrls(html, fixed).forEach { embeds.add(it) }
            extractEmbedUrls(html, fixed).forEach { embeds.add(it) }

            for (embed in embeds.filterNot { it.isNoiseUrl() }.take(40)) {
                if (embed.isDirectVideo()) {
                    if (emitDirect(name, embed, fixed)) found = true
                } else if (resolvePage(embed, fixed, depth + 1)) {
                    found = true
                }
            }

            return found
        }

        var found = resolvePage(pageUrl, "$mainUrl/")

        val document = try {
            app.get(pageUrl, headers = desktopHeaders, timeout = 30L).document
        } catch (_: Throwable) {
            null
        }

        if (document != null) {
            val ajaxLinks = linkedSetOf<String>()

            collectMuviproAjax(pageUrl, baseUrl, document).forEach { ajaxLinks.add(it) }
            collectDooPlayAjax(pageUrl, baseUrl, document).forEach { ajaxLinks.add(it) }

            for (link in ajaxLinks.filterNot { it.isNoiseUrl() }.take(40)) {
                if (link.isDirectVideo()) {
                    if (emitDirect(name, link, pageUrl)) found = true
                } else if (resolvePage(link, pageUrl, 1)) {
                    found = true
                }
            }
        }

        return found
    }

    private fun parseListing(document: Document): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()

        document.select(cardSelector).forEach { element ->
            element.toSearchResult()?.let { results[it.url] = it }
        }

        if (results.size < 8) {
            document.select("article a[href], .gmr-box-content a[href], .content-thumbnail a[href], h2.entry-title a[href], h3.entry-title a[href]").forEach { anchor ->
                anchor.toSearchResult()?.let { results[it.url] = it }
            }
        }

        return results.values
            .filter { it.name.length > 2 }
            .take(60)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val container = closest("article, .gmr-box-content, .gmr-item-module, .gmr-grid-item, .post, .movie, .item, .type-post") ?: this
        val anchor = if (`is`("a[href]")) this else container.selectFirst(titleAnchorSelector) ?: container.selectFirst("a[href]") ?: return null
        val href = absoluteUrl(anchor.attr("href"), mainUrl) ?: return null

        if (!href.startsWith(mainUrl, ignoreCase = true)) return null
        if (isNavigationUrl(href)) return null

        val image = container.selectFirst("img") ?: anchor.selectFirst("img")
        val poster = image?.getImageAttr(mainUrl)?.fixImageQuality()
            ?: container.extractStyleImage(mainUrl)?.fixImageQuality()

        // This is the important cosmetic fix: menu/category links must not become posterless title rows.
        if (poster.isNullOrBlank()) return null

        val rawTitle = listOf(
            container.selectFirst("h2.entry-title > a")?.text()?.trim(),
            container.selectFirst("h3.entry-title > a")?.text()?.trim(),
            container.selectFirst(".entry-title a")?.text()?.trim(),
            container.selectFirst("h2 a")?.text()?.trim(),
            container.selectFirst("h3 a")?.text()?.trim(),
            anchor.attr("title").trim(),
            image?.attr("alt")?.trim(),
            anchor.text().trim(),
            titleFromSlug(href)
        ).firstOrNull { it.isUsefulTitle() } ?: return null

        val title = rawTitle.cleanTitle()
            .removePrefix("Nonton film ")
            .removeSuffix(" terbaru di Dutamovie21")
            .cleanTitle()

        if (!title.isUsefulTitle()) return null

        val quality = container.select("div.gmr-qual, div.gmr-quality-item > a, a[href*='/quality/'], .quality, .gmr-quality")
            .text()
            .trim()
            .replace("-", "")

        val ratingText = container.selectFirst("div.gmr-rating-item, .rating, [itemprop=ratingValue]")
            ?.text()
            ?.replace(",", ".")
            ?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.value }

        val tvType = getTypeFromUrl(href, title, container.text())

        return if (tvType == TvType.TvSeries || tvType == TvType.Anime) {
            val episode = extractEpisodeNumber(title) ?: extractEpisodeNumber(container.text())
            newAnimeSearchResponse(title, href, tvType) {
                posterUrl = poster
                addSub(episode)
                score = Score.from10(ratingText?.toDoubleOrNull())
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                posterUrl = poster
                if (quality.isNotBlank()) addQuality(quality)
                score = Score.from10(ratingText?.toDoubleOrNull())
            }
        }
    }

    private suspend fun parseEpisodes(url: String, currentDocument: Document): List<Episode> {
        val seriesUrl = currentDocument.selectFirst(
            "a.button.button-shadow.active[href], a[href*='/season/'][href], a[href*='/serial-tv/'][href]"
        )?.attr("href")?.takeIf { it.isNotBlank() }
            ?.let { absoluteUrl(it, url) }
            ?: url.substringBefore("/eps/")

        val seriesDoc = try {
            app.get(seriesUrl, headers = desktopHeaders, timeout = 30L).document
        } catch (_: Throwable) {
            currentDocument
        }

        val episodes = linkedMapOf<String, Episode>()
        var episodeCounter = 1

        seriesDoc.select(
            "div.gmr-listseries a.button.button-shadow[href], .gmr-listseries a[href], .episodelist a[href], .episode-list a[href], .episodes a[href], a[href*='/eps/'], a[href*='episode']"
        ).forEach { eps ->
            val href = absoluteUrl(eps.attr("href"), seriesUrl) ?: return@forEach
            if (isNavigationUrl(href)) return@forEach

            val name = eps.text().cleanTitle()
            if (name.contains("View All Episodes", ignoreCase = true)) return@forEach
            if (href == seriesUrl) return@forEach

            if (!name.contains("Eps", ignoreCase = true) &&
                !name.contains("Episode", ignoreCase = true) &&
                !href.contains("/eps/", true) &&
                !href.contains("episode", true)
            ) return@forEach

            val season = Regex("""(?:Season|S)\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(name)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            val epNum = extractEpisodeNumber(name)
                ?: Regex("""/eps/(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""episode[-\s]*(\d+)""", RegexOption.IGNORE_CASE).find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: episodeCounter++

            episodes[href] = newEpisode(href) {
                this.name = name.ifBlank { "Episode $epNum" }
                this.season = season
                this.episode = epNum
            }
        }

        return episodes.values
            .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 9999 })
            .ifEmpty {
                listOf(
                    newEpisode(url) {
                        name = "Episode 1"
                        season = 1
                        episode = 1
                    }
                )
            }
    }

    private suspend fun collectMuviproAjax(pageUrl: String, baseUrl: String, document: Document): List<String> {
        val output = linkedSetOf<String>()
        val postId = document.selectFirst("div#muvipro_player_content_id, [data-post], [data-postid], [data-id]")
            ?.let {
                it.attr("data-id")
                    .ifBlank { it.attr("data-post") }
                    .ifBlank { it.attr("data-postid") }
            }
            ?.takeIf { it.isNotBlank() }

        if (postId.isNullOrBlank()) return emptyList()

        document.select("div.tab-content-ajax[id], .tab-content[id], .player-content[id], [data-tab]").forEach { ele ->
            val tabId = ele.attr("id").ifBlank { ele.attr("data-tab") }.trim()
            if (tabId.isBlank()) return@forEach

            val text = try {
                app.post(
                    "$baseUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "muvipro_player_content",
                        "tab" to tabId,
                        "post_id" to postId
                    ),
                    referer = pageUrl,
                    headers = ajaxHeaders(pageUrl, baseUrl),
                    timeout = 30L
                ).text
            } catch (_: Throwable) {
                ""
            }

            collectFromAjaxText(text, pageUrl).forEach { output.add(it) }
        }

        return output.toList()
    }

    private suspend fun collectDooPlayAjax(pageUrl: String, baseUrl: String, document: Document): List<String> {
        val output = linkedSetOf<String>()

        document.select("li.dooplay_player_option, .dooplay_player_option, [data-post][data-nume][data-type]").forEach { option ->
            val post = option.attr("data-post")
            val nume = option.attr("data-nume")
            val type = option.attr("data-type")
            if (post.isBlank() || nume.isBlank() || type.isBlank()) return@forEach

            val text = try {
                app.post(
                    "$baseUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to post,
                        "nume" to nume,
                        "type" to type
                    ),
                    referer = pageUrl,
                    headers = ajaxHeaders(pageUrl, baseUrl),
                    timeout = 30L
                ).text
            } catch (_: Throwable) {
                ""
            }

            collectFromAjaxText(text, pageUrl).forEach { output.add(it) }
        }

        return output.toList()
    }

    private fun collectFromAjaxText(text: String, base: String): List<String> {
        val normalized = normalizedHtml(text)
        val output = linkedSetOf<String>()

        extractDirectMedia(normalized, base).forEach { output.add(it) }
        extractIframeUrls(normalized, base).forEach { output.add(it) }
        extractEmbedUrls(normalized, base).forEach { output.add(it) }

        Regex("""(?i)"(?:embed_url|iframe_url|url|src|file|source)"\s*:\s*"([^"]+)"""")
            .findAll(normalized)
            .mapNotNull { decodePossibleEmbed(it.groupValues[1], base) }
            .forEach { output.add(it) }

        return output.toList()
    }

    private fun collectDirectEmbeds(root: Element, pageUrl: String, output: MutableSet<String>) {
        root.select(
            "iframe[src], iframe[data-src], iframe[data-litespeed-src], embed[src], source[src], video[src], " +
                "a[data-url], a[data-src], a[data-iframe], a[data-link], a[data-href], a[data-file], " +
                "[data-url], [data-src], [data-embed], [data-iframe], [data-link], [data-file], [data-video], [data-player], " +
                "ul.gmr-download-list li a[href], .gmr-download-list a[href], .download a[href], " +
                "a[href*='/download/'], a[href*='/dl/'], a[href*='dood'], a[href*='streamtape'], " +
                "a[href*='filemoon'], a[href*='veev'], a[href*='hglink'], a[href*='hgcloud'], " +
                "a[href*='ghbrisk'], a[href*='ryderjet'], a[href*='movearnpre'], a[href*='minochinos'], " +
                "a[href*='mivalyo'], a[href*='bingezove'], a[href*='dintezuvio'], a[href*='dingtezuni'], " +
                "a[href*='p2pplay'], a[href*='4meplayer'], a[href*='embed4me'], a[href*='upns.live'], " +
                "a[href*='short'], a[href*='sht'], a[href*='gdplayer'], a[href*='gdriveplayer'], a[href*='hubcloud']"
        ).forEach { element ->
            listOf(
                element.attr("data-litespeed-src"),
                element.attr("data-url"),
                element.attr("data-src"),
                element.attr("data-embed"),
                element.attr("data-iframe"),
                element.attr("data-link"),
                element.attr("data-href"),
                element.attr("data-file"),
                element.attr("data-video"),
                element.attr("data-player"),
                element.attr("src"),
                element.attr("href")
            ).firstOrNull { it.isNotBlank() }?.let { raw ->
                decodePossibleEmbed(raw, pageUrl)?.let { output.add(it) }
            }
        }
    }

    private fun extractIframeUrls(html: String, base: String): List<String> {
        return Regex("""(?i)<iframe[^>]+(?:src|data-src|data-litespeed-src)=['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { absoluteUrl(it.groupValues[1], base) }
            .toList()
    }

    private fun extractEmbedUrls(html: String, base: String): List<String> {
        val urls = linkedSetOf<String>()

        Regex("""(?i)(?:embed_url|iframe_url|player_url|url|src|file|source|video)\s*[:=]\s*['"]([^'"]+)['"]""")
            .findAll(html)
            .mapNotNull { decodePossibleEmbed(it.groupValues[1], base) }
            .forEach { urls.add(it) }

        Regex("""(?i)['"]((?:https?:)?//[^'"]+(?:embed|player|stream|drive|dood|streamtape|filemoon|vidhide|vidguard|voe|mp4upload|uqload|krakenfiles|filelions|gdplayer|gdriveplayer|short|sht|goindex|hubcloud|hglink|hgcloud|upns\.live)[^'"]*)['"]""")
            .findAll(html)
            .mapNotNull { absoluteUrl(it.groupValues[1], base) }
            .forEach { urls.add(it) }

        Regex("""(?i)atob\(['"]([^'"]+)['"]\)""")
            .findAll(html)
            .mapNotNull { decodeBase64(it.groupValues[1]) }
            .forEach { decoded ->
                extractIframeUrls(decoded, base).forEach { urls.add(it) }
                extractDirectMedia(decoded, base).forEach { urls.add(it) }
                extractEmbedUrls(decoded.replace("atob", "base64"), base).forEach { urls.add(it) }
            }

        return urls.toList()
    }

    private fun extractDirectMedia(html: String, base: String): List<String> {
        val urls = linkedSetOf<String>()

        Regex("""(?i)['"]((?:https?:)?//[^'"]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^'"]+|videoplayback[^'"]*)(?:\?[^'"]*)?)['"]""")
            .findAll(html)
            .mapNotNull { absoluteUrl(it.groupValues[1], base) }
            .forEach { urls.add(it) }

        Regex("""(?i)(?:https?:)?//[^\s'"<>\\]+?(?:\.m3u8|\.mp4|\.webm|googlevideo\.com/[^\s'"<>\\]+|videoplayback[^\s'"<>\\]*)(?:\?[^\s'"<>\\]*)?""")
            .findAll(html)
            .mapNotNull { absoluteUrl(it.value, base) }
            .forEach { urls.add(it) }

        Regex("""https?%3A%2F%2F[^\s'"<>]+""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { absoluteUrl(decodeUrl(it.value), base) }
            .forEach { urls.add(it) }

        return urls.toList()
    }

    private fun collectSubtitles(document: Document, base: String, subtitleCallback: (SubtitleFile) -> Unit) {
        document.select("track[src], a[href$=.vtt], a[href$=.srt], a[href*='.vtt'], a[href*='.srt']").forEach { element ->
            val subUrl = absoluteUrl(element.attr("src").ifBlank { element.attr("href") }, base) ?: return@forEach
            val label = element.attr("label")
                .ifBlank { element.attr("srclang") }
                .ifBlank { element.text() }
                .ifBlank { "Subtitle" }
                .cleanTitle()

            subtitleCallback(SubtitleFile(label, subUrl))
        }
    }

    private fun buildPageCandidates(pattern: String, page: Int): List<String> {
        val safePattern = pattern.trimStart('/')

        val formatted = if (safePattern.contains("%d")) safePattern.format(page) else safePattern
        val noPage = formatted.replace(Regex("""/?page/\d+/?$"""), "/")

        val candidates = linkedSetOf<String>()
        candidates.add("$mainUrl/${formatted.trimStart('/')}")
        candidates.add("$mainUrl/${noPage.trimStart('/')}")

        if (formatted.startsWith("genre/")) {
            candidates.add("$mainUrl/${formatted.removePrefix("genre/")}")
        }

        if (!formatted.startsWith("genre/") && topLevelCategorySlugs.any { formatted.startsWith("$it/") }) {
            candidates.add("$mainUrl/genre/$formatted")
        }

        return candidates.toList()
    }

    private fun Document.hasNextPage(page: Int): Boolean {
        return selectFirst(
            "a.next, .pagination a:contains(Next), .page-numbers.next, .page-numbers:contains(»), a[href*='/page/${page + 1}/']"
        ) != null
    }

    private fun findPoster(document: Document, base: String): String? {
        val selectors = listOf(
            "meta[property=og:image]",
            "meta[name=twitter:image]",
            ".sheader .poster img",
            ".poster img",
            ".thumb img",
            ".cover img",
            "figure.pull-left > img",
            "img.wp-post-image",
            ".content-thumbnail img",
            "img[itemprop=image]"
        )

        selectors.forEach { selector ->
            val element = document.selectFirst(selector) ?: return@forEach
            if (element.tagName().equals("meta", true)) {
                absoluteUrl(element.attr("content"), base)?.let { return it.fixImageQuality() }
            } else {
                element.getImageAttr(base)?.let { return it.fixImageQuality() }
            }
        }

        return document.body()?.extractStyleImage(base)?.fixImageQuality()
    }

    private fun Element.getImageAttr(base: String): String? {
        return listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("data-wpfc-original-src"),
            attr("srcset").substringBefore(" "),
            attr("src")
        ).firstNotNullOfOrNull { absoluteUrl(it, base)?.takeIf { url -> url.isImageLike() } }
    }

    private fun Element.extractStyleImage(base: String): String? {
        val styleText = attr("style") + " " + select("[style]").joinToString(" ") { it.attr("style") }
        return Regex("""url\((['"]?)(.*?)\1\)""", RegexOption.IGNORE_CASE)
            .find(styleText)
            ?.groupValues
            ?.getOrNull(2)
            ?.let { absoluteUrl(it, base) }
    }

    private fun getTypeFromUrl(url: String, title: String = "", text: String = ""): TvType {
        val combined = "$url $title $text"
        return when {
            url.contains("/animasi/", true) || url.contains("/anime/", true) || combined.contains("Anime", true) -> TvType.Anime
            combined.contains("TV Show", true) -> TvType.TvSeries
            combined.contains("Eps:", true) || combined.contains("Episode", true) -> TvType.TvSeries
            url.contains("/tv/", true) -> TvType.TvSeries
            url.contains("/serial-tv", true) -> TvType.TvSeries
            url.contains("/eps/", true) -> TvType.TvSeries
            else -> TvType.Movie
        }
    }

    private fun extractEpisodeNumber(text: String): Int? {
        return Regex("""(?:Eps|Episode|Ep)\s*\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun isNavigationUrl(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: Throwable) {
            return true
        }

        val path = uri.path.orEmpty().trim('/').lowercase(Locale.ROOT)
        if (path.isBlank()) return true

        val first = path.substringBefore('/')
        val segments = path.split('/').filter { it.isNotBlank() }

        if (first in navigationFirstSegments) return true
        if (segments.size <= 2 && first in topLevelCategorySlugs) return true

        return url.contains("/tag/", true) ||
            url.contains("/country/", true) && segments.size <= 2 ||
            url.contains("/genre/", true) && segments.size <= 2 ||
            url.contains("/category/", true) ||
            url.contains("/quality/", true) ||
            url.contains("/director/", true) ||
            url.contains("/cast/", true) ||
            url.contains("/year/", true) ||
            url.contains("/page/", true) ||
            url.contains("youtube.com", true) ||
            url.contains("youtu.be", true) ||
            url.contains("nawala.", true) ||
            url.contains("ketik.live", true)
    }

    private fun absoluteUrl(value: String?, base: String): String? {
        val raw = decodeUrl(
            value.orEmpty()
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .trim()
                .trim('"', '\'', ',', ';')
        )

        if (
            raw.isBlank() ||
            raw == "#" ||
            raw.equals("null", true) ||
            raw.startsWith("javascript:", true) ||
            raw.startsWith("mailto:", true) ||
            raw.startsWith("tel:", true) ||
            raw.startsWith("data:", true) ||
            raw.startsWith("blob:", true)
        ) return null

        return when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://") || raw.startsWith("https://") -> httpsify(raw)
            raw.startsWith("/") -> getBaseUrl(base) + raw
            else -> try {
                URI(base).resolve(raw).toString()
            } catch (_: Throwable) {
                "${getBaseUrl(base)}/${raw.trimStart('/')}"
            }
        }
    }

    private fun decodePossibleEmbed(value: String, base: String): String? {
        val decoded = decodeUrl(value)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ',', ';')

        absoluteUrl(decoded, base)?.let { return it }

        decodeBase64(decoded)?.let { html ->
            extractIframeUrls(html, base).firstOrNull()?.let { return it }
            extractDirectMedia(html, base).firstOrNull()?.let { return it }
            absoluteUrl(html, base)?.let { return it }
        }

        return null
    }

    private fun normalizedHtml(value: String): String {
        return decodeUrl(
            value.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
        )
    }

    private fun decodeBase64(value: String): String? {
        val candidate = value.trim()
        if (candidate.length < 8) return null

        val normalized = candidate.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)

        return try {
            String(Base64.getDecoder().decode(padded))
        } catch (_: Throwable) {
            try {
                String(Base64.getUrlDecoder().decode(padded))
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun decodeUrl(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Throwable) {
            value
        }
    }

    private fun ajaxHeaders(pageUrl: String, baseUrl: String): Map<String, String> {
        return mapOf(
            "Referer" to pageUrl,
            "Origin" to baseUrl,
            "User-Agent" to USER_AGENT,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "*/*"
        )
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (_: Throwable) {
            mainUrl
        }
    }

    private fun titleFromSlug(url: String): String {
        val slug = try {
            URI(url).path.trim('/').substringAfterLast('/')
        } catch (_: Throwable) {
            url.substringAfterLast('/')
        }.substringBefore("?")
            .replace(Regex("(?i)-subtitle-indonesia.*$"), "")
            .replace(Regex("(?i)-sub-indo.*$"), "")

        return slug.split("-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
                }
            }
            .cleanTitle()
    }

    private fun String.cleanTitle(): String {
        return replace("\u00a0", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String?.isUsefulTitle(): Boolean {
        if (isNullOrBlank()) return false
        val clean = cleanTitle()
        val lower = clean.lowercase(Locale.ROOT)

        return clean.length >= 2 &&
            lower !in setOf(
                "home", "next", "previous", "tonton", "tonton film", "trailer",
                "movie", "movies", "series", "genre", "country", "download", "watch",
                "film lainnya", "iklan", "sharer", "tweet"
            ) &&
            !lower.contains("dutamovie21") &&
            !lower.contains("gudangfilm")
    }

    private fun String.fixImageQuality(): String {
        return replace(Regex("""-\d+x\d+(?=\.)"""), "")
    }

    private fun String.isImageLike(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".jpg") ||
            lower.contains(".jpeg") ||
            lower.contains(".png") ||
            lower.contains(".webp") ||
            lower.contains("image") ||
            lower.contains("/uploads/")
    }

    private fun String.isDirectVideo(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".webm") ||
            lower.contains("googlevideo.com") ||
            lower.contains("videoplayback")
    }

    private fun String.isNoiseUrl(): Boolean {
        val lower = lowercase(Locale.ROOT)
        return lower.contains("facebook.com") ||
            lower.contains("telegram") ||
            lower.contains("twitter.com") ||
            lower.contains("instagram") ||
            lower.contains("whatsapp") ||
            lower.contains("doubleclick") ||
            lower.contains("googlesyndication") ||
            lower.contains("google-analytics") ||
            lower.endsWith(".css") ||
            lower.endsWith(".js") ||
            lower.endsWith(".ico") ||
            lower.endsWith(".svg") ||
            lower.endsWith(".jpg") ||
            lower.endsWith(".jpeg") ||
            lower.endsWith(".png") ||
            lower.endsWith(".webp")
    }

    private fun qualityFromUrl(url: String): Int {
        val lower = url.lowercase(Locale.ROOT)
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720") -> Qualities.P720.value
            lower.contains("480") -> Qualities.P480.value
            lower.contains("360") -> Qualities.P360.value
            lower.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private val cardSelector = listOf(
        "article.item",
        "article.type-post",
        "article.post",
        ".gmr-box-content",
        ".gmr-item-module",
        ".gmr-grid-item",
        ".movies-list .movie",
        ".content-thumbnail",
        ".post"
    ).joinToString(", ")

    private val titleAnchorSelector = listOf(
        "h2.entry-title > a[href]",
        "h3.entry-title > a[href]",
        ".entry-title a[href]",
        "h2 a[href]",
        "h3 a[href]",
        "a[title][href]",
        "a[href]"
    ).joinToString(", ")

    private val topLevelCategorySlugs = setOf(
        "movie", "serial-tv-terbaru", "animasi", "box-office", "populer", "best-rating",
        "action", "action-adventure", "adventure", "animation", "comedy", "crime",
        "documentary", "drama", "family", "fantasy", "history", "horror", "music",
        "mystery", "reality", "romance", "sci-fi-fantasy", "science-fiction",
        "thriller", "tv-movie", "war", "semi", "semi-jav", "vivamax", "semi-indo",
        "semi-korea", "semi-barat"
    )

    private val navigationFirstSegments = setOf(
        "genre", "country", "tag", "category", "quality", "director", "cast",
        "actors", "year", "page", "dmca", "contact", "privacy-policy", "about",
        "iklan", "feed", "wp-admin", "wp-content", "wp-includes"
    )
}
