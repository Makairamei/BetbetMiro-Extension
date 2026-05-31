package com.sad25kag.animeindo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AnimeIndo : MainAPI() {
    override var mainUrl = "https://anime-indo.lol"
    override var name = "AnimeIndo"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Episode Terbaru",
        "$mainUrl/movie/" to "Movie",
        "$mainUrl/genres/action/" to "Action",
        "$mainUrl/genres/adventure/" to "Adventure",
        "$mainUrl/genres/comedy/" to "Comedy",
        "$mainUrl/genres/demons/" to "Demons",
        "$mainUrl/genres/donghua/" to "Donghua",
        "$mainUrl/genres/drama/" to "Drama",
        "$mainUrl/genres/fantasy/" to "Fantasy",
        "$mainUrl/genres/game/" to "Game",
        "$mainUrl/genres/historical/" to "Historical",
        "$mainUrl/genres/horror/" to "Horror",
        "$mainUrl/genres/isekai/" to "Isekai",
        "$mainUrl/genres/magic/" to "Magic",
        "$mainUrl/genres/martial-arts/" to "Martial Arts",
        "$mainUrl/genres/military/" to "Military",
        "$mainUrl/genres/mystery/" to "Mystery",
        "$mainUrl/genres/psychological/" to "Psychological",
        "$mainUrl/genres/reincarnation/" to "Reincarnation",
        "$mainUrl/genres/romance/" to "Romance",
        "$mainUrl/genres/school/" to "School",
        "$mainUrl/genres/sci-fi/" to "Sci-Fi",
        "$mainUrl/genres/seinen/" to "Seinen",
        "$mainUrl/genres/slice-of-life/" to "Slice of Life",
        "$mainUrl/genres/sports/" to "Sports",
        "$mainUrl/genres/super-power/" to "Super Power",
        "$mainUrl/genres/supernatural/" to "Supernatural",
        "$mainUrl/genres/thriller/" to "Thriller",
        "$mainUrl/genres/vampire/" to "Vampire"
    )

    private val blockedSlugs = setOf(
        "", "anime-indo-lol", "animeindo", "anime-indo", "anime", "list", "list-genre",
        "genre", "genre-list", "movie", "jadwal", "disclaimer", "privacy-police", "privacy-policy"
    )

    private fun buildPageUrl(data: String, page: Int): String {
        val base = data.trimEnd('/')
        return if (page <= 1) data else "$base/page/$page/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isMovie = request.data.contains("/movie/", true)
        val document = app.get(buildPageUrl(request.data, page)).document

        val candidates = document.select(
            ".listupd article, .list-anime-parent > *, .animepost, .bs, .bsx, .post, article, " +
                ".latest a[href], table.otable tr, .item, .ml-item, .movie"
        )

        val home = candidates.mapNotNull { it.toAnimeIndoSearchResult(preferMovie = isMovie, requireContentUrl = true) }
            .distinctBy { it.url }
            .filterNot { it.name.isBlank() }

        val hasNext = document.selectFirst(
            "a.next, a[rel=next], .pagination a[href*='/page/${page + 1}/'], a[href*='/page/${page + 1}/']"
        ) != null

        return newHomePageResponse(listOf(HomePageList(request.name, home, isHorizontalImages = isMovie)), hasNext)
    }

    private fun Element.imageAttr(): String? {
        val image = if (tagName().equals("img", true)) this else selectFirst("img")
        return image?.attr("data-original")?.takeIf { it.isNotBlank() && !it.contains("loading", true) }
            ?: image?.attr("data-src")?.takeIf { it.isNotBlank() && !it.contains("loading", true) }
            ?: image?.attr("src")?.takeIf { it.isNotBlank() && !it.contains("loading", true) }
    }

    private fun Element.bestCard(): Element {
        if (selectFirst("img") != null && selectFirst("a[href]") != null) return this
        return parents().firstOrNull { parent ->
            parent.selectFirst("img") != null && parent.selectFirst("a[href]") != null &&
                parent.text().length in 2..1200
        } ?: this
    }

    private fun normalizeTitle(raw: String): String {
        return raw.replace(Regex("\\s+"), " ")
            .replace(Regex("(?i)\\s*subtitle\\s*indonesia.*$"), "")
            .replace(Regex("(?i)\\s*sub\\s*indo.*$"), "")
            .trim()
            .trim('"')
            .trim()
    }

    private fun isBlockedTitle(title: String): Boolean {
        val normalized = title.trim().lowercase()
        return normalized in setOf(
            "list", "genre", "movie", "jadwal", "animeindo", "anime indo", "anime list",
            "genre list", "anime", "popular", "disclaimer", "privacy police", "privacy policy"
        )
    }

    private fun isValidContentUrl(url: String): Boolean {
        val fixed = fixUrl(url).substringBefore("#").trimEnd('/')
        val slug = fixed.substringAfter(mainUrl, "").trim('/').substringAfterLast("/")
        if (!fixed.startsWith(mainUrl)) return false
        if (slug.lowercase() in blockedSlugs) return false
        if (fixed.contains("/genres/", true) || fixed.contains("/genre/", true) || fixed.contains("/tag/", true)) return false
        if (fixed.contains("/page/", true)) return false
        if (fixed.endsWith("/list-genre", true) || fixed.endsWith("/jadwal", true)) return false
        return fixed.contains("/anime/", true) ||
            fixed.contains("/movie/", true) ||
            isEpisodeUrl(fixed) ||
            (!fixed.substringAfter(mainUrl, "").trim('/').contains("/") && slug.length > 3)
    }

    private fun Element.toAnimeIndoSearchResult(
        preferMovie: Boolean = false,
        requireContentUrl: Boolean = true
    ): SearchResponse? {
        val card = bestCard()
        val link = when {
            tagName().equals("a", true) && hasAttr("href") -> this
            else -> card.select("a[href]").firstOrNull { a -> isValidContentUrl(a.attr("href")) }
                ?: card.selectFirst("a[href]")
        } ?: return null

        val href = link.attr("href").trim()
        if (href.isBlank()) return null
        if (requireContentUrl && !isValidContentUrl(href)) return null

        val fixedHref = fixUrl(href)
        val rawTitle = link.attr("title").ifBlank {
            link.selectFirst("p, h2, h3, .title, .entry-title, td.videsc a")?.text()?.trim().orEmpty()
        }.ifBlank {
            link.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }.ifBlank {
            link.text().trim()
        }.ifBlank {
            card.selectFirst("h2, h3, .title, .entry-title, p")?.text()?.trim().orEmpty()
        }

        val title = normalizeTitle(rawTitle)
        if (title.length < 2 || isBlockedTitle(title)) return null

        val episodeNumber = parseEpisodeNumber(title).takeIf { isEpisodeUrl(fixedHref) }
        val resultUrl = if (isEpisodeUrl(fixedHref) && !preferMovie) episodeToAnimeUrl(fixedHref) else fixedHref
        val poster = (card.imageAttr() ?: link.imageAttr())?.let { fixUrlNull(it) }
        val tvType = when {
            preferMovie || fixedHref.contains("/movie/", true) || title.contains("movie", true) || title.contains("film", true) -> TvType.AnimeMovie
            title.contains("ova", true) || title.contains("special", true) -> TvType.OVA
            else -> TvType.Anime
        }

        return newAnimeSearchResponse(title, resultUrl, tvType) {
            posterUrl = poster
            episodeNumber?.let { addSub(it) }
        }
    }

    private fun isEpisodeUrl(url: String): Boolean {
        val slug = url.trimEnd('/').substringAfterLast('/')
        return slug.contains(Regex("-episode-\\d+", RegexOption.IGNORE_CASE))
    }

    private fun parseEpisodeNumber(text: String): Int? {
        return Regex("(?:episode\\s*)?(\\d+)(?:\\.\\d+)?", RegexOption.IGNORE_CASE)
            .findAll(text)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun episodeToAnimeUrl(url: String): String {
        val slug = url.trimEnd('/').substringAfterLast("/")
        val animeSlug = Regex("-episode-\\d+(?:\\.\\d+)?.*$", RegexOption.IGNORE_CASE).replace(slug, "")
        return "$mainUrl/anime/$animeSlug/"
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document
        return document.select(
            ".listupd article, .list-anime-parent > *, .animepost, .bs, .bsx, article, .post, " +
                ".latest a[href], table.otable tr, .item, .ml-item, main a[href]"
        ).mapNotNull { it.toAnimeIndoSearchResult(requireContentUrl = true) }
            .distinctBy { it.url }
            .filterNot { it.url.trimEnd('/') == mainUrl }
    }

    override suspend fun load(url: String): LoadResponse {
        val initialDocument = app.get(url).document
        val episodePage = isEpisodeUrl(url)
        val animeUrl = if (url.contains("/anime/", true)) {
            url
        } else if (episodePage) {
            initialDocument.selectFirst("div.navi a[href*=/anime/], a[href*=/anime/]")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: episodeToAnimeUrl(url)
        } else {
            url
        }

        val document = if (animeUrl == url) initialDocument else app.get(animeUrl).document

        val title = document.selectFirst("h1.title, h2.title, h1.entry-title, h1, h2, .entry-title")
            ?.text()
            ?.trim()
            ?.removePrefix("#")
            ?.let { normalizeTitle(it) }
            ?.takeIf { it.isNotBlank() && !isBlockedTitle(it) }
            ?: if (episodePage) normalizeTitle(
                initialDocument.selectFirst("h1.title, h1.entry-title, h1, h2")?.text().orEmpty()
            ).replace(Regex("(?i)\\s*episode\\s*\\d+.*$"), "").trim().takeIf { it.isNotBlank() }
            else null
            ?: throw ErrorLoadingException("Judul tidak ditemukan")

        val poster = document.selectFirst("div.detail img, td.vithumb img, .thumb img, .poster img, main img, article img")
            ?.imageAttr()
            ?.let { fixUrl(it) }

        val description = document.selectFirst("div.detail p, p.des, .entry-content p, .entry-content, main p, article p")
            ?.text()
            ?.trim()

        val rawGenres = document.select("div.detail li a, .genredesc a, a[href*=/genres/], a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val mappedGenres = rawGenres.map { AnimeIndoTagCategory.getCategoryByTag(it) }.distinct()

        val episodes = document.select("div.ep a[href], .episode-list a[href], a[href]")
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href"))
                if (!isEpisodeUrl(href)) return@mapNotNull null
                val epText = a.text().trim().ifBlank { href.trimEnd('/').substringAfterLast("/") }
                val ep = parseEpisodeNumber(epText) ?: parseEpisodeNumber(href)
                newEpisode(href) {
                    this.name = ep?.let { "Episode $it" } ?: epText
                    this.episode = ep
                }
            }
            .distinctBy { it.data }
            .sortedBy { it.episode ?: Int.MAX_VALUE }

        if (episodes.isEmpty() && !url.contains("/anime/", true) && !episodePage) {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                posterUrl = poster
                plot = description
                this.tags = mappedGenres
            }
        }

        val tracker = APIHolder.getTracker(listOf(title), TrackerType.getTypes(TvType.Anime), null, true)

        return newAnimeLoadResponse(title, animeUrl, TvType.Anime) {
            engName = title
            posterUrl = tracker?.image ?: poster
            backgroundPosterUrl = tracker?.cover
            addEpisodes(DubStatus.Subbed, episodes)
            plot = description
            this.tags = mappedGenres
            addMalId(tracker?.malId)
            addAniListId(tracker?.aniId?.toIntOrNull())
        }
    }

    private fun addServerUrl(serverUrls: MutableList<String>, rawUrl: String?) {
        val url = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (url == "#" || url.startsWith("javascript:", true)) return
        serverUrls.add(url)
    }

    private fun String.toFullPlayerUrl(): String {
        return when {
            startsWith("//") -> "https:$this"
            startsWith("/") -> "$mainUrl$this"
            else -> this
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val serverUrls = mutableListOf<String>()

        document.select("iframe[src], iframe[data-src], source[src], video[src]").forEach { element ->
            addServerUrl(serverUrls, element.attr("src").ifBlank { element.attr("data-src") })
        }

        document.select("a.server[data-video], [data-video], [data-url], [data-iframe], a[href]").forEach { element ->
            addServerUrl(serverUrls, element.attr("data-video"))
            addServerUrl(serverUrls, element.attr("data-url"))
            addServerUrl(serverUrls, element.attr("data-iframe"))
            addServerUrl(serverUrls, element.attr("href"))
        }

        val html = document.html()
        Regex("""(?i)(?:src|file|url)\s*[:=]\s*["']([^"']+(?:m3u8|mp4|embed|player|btube3|xtwap|gdriveplayer|gdplayer|mp4upload|yup)[^"']*)["']""")
            .findAll(html)
            .forEach { addServerUrl(serverUrls, it.groupValues[1].replace("\\/", "/")) }

        val distinctServers = serverUrls
            .map { it.toFullPlayerUrl() }
            .distinct()
            .filterNot {
                it.contains("/anime/", true) || it.contains("/genres/", true) ||
                    it.contains("disclaimer", true) || it.contains("privacy", true)
            }

        var found = false

        distinctServers.forEach { fullUrl ->
            if (fullUrl.contains("btube3.php", true) || fullUrl.contains("b-tube", true)) {
                try {
                    val playerDoc = app.get(fullUrl, referer = data).document
                    val videoSrc = playerDoc.selectFirst("source[src], video[src]")?.attr("src")
                    if (!videoSrc.isNullOrBlank()) {
                        callback(newExtractorLink("AnimeIndo", "B-TUBE", videoSrc) {
                            this.quality = Qualities.P1080.value
                            this.referer = fullUrl
                        })
                        found = true
                    }
                } catch (_: Exception) {
                }
            } else if (fullUrl.contains("xtwap.top", true)) {
                try {
                    val playerHtml = app.get(fullUrl, referer = data).text
                    val filePath = Regex(""""file"\s*:\s*"([^"]+)"""").find(playerHtml)?.groupValues?.getOrNull(1)
                    if (!filePath.isNullOrBlank()) {
                        val videoUrl = if (filePath.startsWith("/")) "https://xtwap.top$filePath" else filePath
                        callback(newExtractorLink("AnimeIndo", "CEPAT", videoUrl) {
                            this.quality = Qualities.P1080.value
                            this.referer = fullUrl
                        })
                        found = true
                    }
                } catch (_: Exception) {
                }
            } else if (fullUrl.endsWith(".mp4", true)) {
                callback(newExtractorLink("AnimeIndo", "MP4", fullUrl) {
                    this.quality = Qualities.Unknown.value
                    this.referer = data
                })
                found = true
            } else {
                try {
                    loadExtractor(fullUrl, data, subtitleCallback) {
                        found = true
                        callback(it)
                    }
                } catch (_: Exception) {
                }
            }
        }

        return found || distinctServers.isNotEmpty()
    }
}

enum class AnimeIndoTagCategory(val title: String, val tagsList: List<String>) {
    ACTION_ADVENTURE("Action & Adventure", listOf("Action", "Adventure", "Martial Arts", "Super Power", "Military")),
    COMEDY("Comedy", listOf("Comedy", "Gag Humor", "Parody")),
    DRAMA_ROMANCE("Drama & Romance", listOf("Drama", "Romance", "Boys Love", "Girls Love", "School")),
    FANTASY_SCIFI("Fantasy & Sci-Fi", listOf("Fantasy", "Sci-Fi", "Supernatural", "Isekai", "Magic", "Demons", "Vampire", "Mecha", "Space", "Time Travel", "Reincarnation")),
    MYSTERY_HORROR("Mystery & Horror", listOf("Mystery", "Thriller", "Suspense", "Detective", "Police", "Psychological", "Horror", "Gore")),
    SLICE_OF_LIFE("Slice of Life", listOf("Slice of Life", "Iyashikei", "Kids", "Workplace")),
    SPORTS_GAMES("Sports & Games", listOf("Sports", "Racing", "Strategy Game", "Game")),
    ARTS_CULTURE("Arts & Music", listOf("Music", "Idol", "Historical", "Performing Arts")),
    MATURE("Mature & Ecchi", listOf("Ecchi", "Harem", "Reverse Harem")),
    DEMOGRAPHICS("Demographics", listOf("Shounen", "Shoujo", "Seinen", "Josei")),
    OTHER("Other", listOf("Donghua"));

    companion object {
        fun getCategoryByTag(tag: String): String {
            return entries.find { category ->
                category.tagsList.any { it.equals(tag, ignoreCase = true) }
            }?.title ?: tag
        }
    }
}
