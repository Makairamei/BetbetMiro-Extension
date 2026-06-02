package com.sad25kag.idlix

import com.lagradost.api.Log
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.text.Normalizer

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://178.62.105.101/"
    override var name = "Idlix"

    private val baseUrl: String
        get() = mainUrl.trimEnd('/')

    private val hostHeader: Map<String, String>
        get() = if (baseUrl.isIpv4HttpsUrl()) mapOf("Host" to IDLIX_HOST) else emptyMap()
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama,
    )

    override val mainPage = mainPageOf(
        "$baseUrl/api/movies?page=%d&limit=36&sort=createdAt" to "Film Terbaru",
        "$baseUrl/api/series?page=%d&limit=36&sort=createdAt" to "Serial TV Terbaru",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest" to "Update Terbaru",
        "$baseUrl/api/browse?page=%d&limit=36&sort=popular" to "Populer",
        "$baseUrl/api/browse?page=%d&limit=36&sort=rating" to "Papan Peringkat",

        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=action" to "Action",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=adventure" to "Adventure",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=animation" to "Animation",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=comedy" to "Comedy",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=crime" to "Crime",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=documentary" to "Documentary",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=drama" to "Drama",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=family" to "Family",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=fantasy" to "Fantasy",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=history" to "History",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=horror" to "Horror",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=kids" to "Kids",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=music" to "Music",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=mystery" to "Mystery",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=reality" to "Reality",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=romance" to "Romance",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=science-fiction" to "Science Fiction",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=soap" to "Soap",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=talk" to "Talk",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=thriller" to "Thriller",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=tv-movie" to "TV Movie",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=war" to "War",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&genre=western" to "Western",


        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&year=2026" to "2026",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&year=2025" to "2025",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&year=2024" to "2024",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&year=2023" to "2023",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&year=2022" to "2022",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&year=2021" to "2021",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&year=2020" to "2020",

        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&network=netflix" to "Netflix",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&network=hbo" to "HBO",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&network=prime-video" to "Prime Video",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&network=disney-plus" to "Disney+",
        "$baseUrl/api/browse?page=%d&limit=36&sort=latest&network=apple-tv-plus" to "Apple TV+",
    )

    private val deviceId = UUID.randomUUID().toString().replace("-", "")

    private val playbackCookies: Map<String, String>
        get() = mapOf(
            "NEXT_LOCALE" to "id",
            "did" to deviceId,
        )

    private val apiHeaders: Map<String, String>
        get() = idlixHeadersFor(baseUrl, "$baseUrl/")

    private val idlixOrigins: List<String>
        get() = listOf(baseUrl, LEGACY_MAIN_URL).distinct()

    private fun idlixHeadersFor(
        origin: String,
        referer: String,
        accept: String = "application/json, text/plain, */*",
        contentType: String? = null,
    ): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        if (origin.isIpv4HttpsUrl()) headers["Host"] = IDLIX_HOST
        headers["Referer"] = referer
        headers["Origin"] = origin
        headers["Accept"] = accept
        headers["User-Agent"] = USER_AGENT
        if (!contentType.isNullOrBlank()) headers["Content-Type"] = contentType
        return headers
    }

    private suspend inline fun <reified T> getIdlixJson(
        url: String,
        referer: String = "$baseUrl/",
        timeout: Long = 10000L,
    ): T? {
        for (origin in idlixOrigins) {
            val requestUrl = url.fixAgainstOrigin(origin) ?: continue
            val requestReferer = referer.fixAgainstOrigin(origin) ?: "$origin/"
            val parsed = runCatching {
                app.get(
                    requestUrl,
                    headers = idlixHeadersFor(origin, requestReferer),
                    referer = requestReferer,
                    timeout = timeout,
                ).parsedSafe<T>()
            }.getOrNull()

            if (parsed != null) return parsed
        }

        return null
    }

    private companion object {
        const val IDLIX_HOST = "z2.idlixku.com"
        const val LEGACY_MAIN_URL = "https://z2.idlixku.com"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) {
            request.data.format(page.coerceAtLeast(1))
        } else {
            request.data
        }

        val parsed = getIdlixJson<ApiResponse>(url)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val fallbackType = when {
            url.contains("/api/movies", true) -> "movie"
            url.contains("/api/series", true) -> "series"
            else -> null
        }

        val items = parsed.data
            .mapNotNull { it.toSearchResponse(fallbackType) }
            .distinctBy { it.url }

        val totalPages = parsed.pagination?.totalPages ?: 0

        return newHomePageResponse(
            request.name,
            items,
            hasNext = if (totalPages > 0) page < totalPages else items.isNotEmpty(),
        )
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(
        query: String,
        page: Int,
    ): SearchResponseList? {
        val keyword = query.trim()
        if (keyword.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()

        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "$baseUrl/api/search?q=$encoded&page=${page.coerceAtLeast(1)}&limit=12"
        val parsed = getIdlixJson<SearchApiResponse>(url) ?: return null

        val results = parsed.results
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }

        return results.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse {
        val contentUrl = url.toProviderUrl()
        val data = getIdlixJson<DetailResponse>(contentUrl)
            ?: throw ErrorLoadingException("Invalid IDLIX API response")

        val title = data.title?.takeIf { it.isNotBlank() } ?: "Unknown"
        val poster = data.posterPath?.tmdbPoster("w500")
        val backdrop = data.backdropPath?.tmdbPoster("w780")
        val logo = data.logoPath?.tmdbPoster("w500")
        val year = (data.releaseDate ?: data.firstAirDate)
            ?.substringBefore("-")
            ?.toIntOrNull()

        val tags = data.genres?.mapNotNull { it.name }?.filter { it.isNotBlank() }.orEmpty()
        val actors = data.cast?.mapNotNull { cast ->
            cast.name?.takeIf { it.isNotBlank() }?.let { name ->
                Actor(name, cast.profilePath?.tmdbPoster("w185"))
            }
        }.orEmpty()

        val isSeries = !data.seasons.isNullOrEmpty()
        val webUrl = if (isSeries) {
            "$baseUrl/series/${data.slug.orEmpty()}"
        } else {
            "$baseUrl/movie/${data.slug.orEmpty()}"
        }

        val recommendations = loadRecommendations(data, isSeries)

        return if (isSeries) {
            val episodes = loadEpisodes(data)

            newTvSeriesLoadResponse(title, webUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                this.duration = data.runtime ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        } else {
            newMovieLoadResponse(
                title,
                webUrl,
                TvType.Movie,
                LoadData(
                    id = data.id.orEmpty(),
                    type = "movie",
                    refererUrl = webUrl,
                ).toJson(),
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.logoUrl = logo
                this.year = year
                this.plot = data.overview
                this.tags = tags
                this.score = Score.from10(data.voteAverage?.toString())
                this.duration = data.runtime ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(data.trailerUrl)
                addTMDbId(data.tmdbId)
                addImdbId(data.imdbId)
            }
        }
    }

    private suspend fun loadEpisodes(data: DetailResponse): List<Episode> {
        val slug = data.slug.orEmpty()
        val episodes = mutableListOf<Episode>()

        data.firstSeason?.episodes?.forEach { ep ->
            val id = ep.id ?: return@forEach
            episodes.add(ep.toCloudstreamEpisode(data.firstSeason.seasonNumber ?: 1, id, slug))
        }

        data.seasons.orEmpty().forEach { season ->
            val seasonNumber = season.seasonNumber ?: return@forEach
            if (seasonNumber == data.firstSeason?.seasonNumber) return@forEach

            val seasonData = runCatching {
                getIdlixJson<SeasonWrapper>("$baseUrl/api/series/$slug/season/$seasonNumber")?.season
            }.getOrNull()

            seasonData?.episodes?.forEach { ep ->
                val id = ep.id ?: return@forEach
                episodes.add(ep.toCloudstreamEpisode(seasonNumber, id, slug))
            }
        }

        return episodes.sortedWith(
            compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 1 }
        )
    }

    private fun com.sad25kag.idlix.Episode.toCloudstreamEpisode(
        seasonNumber: Int,
        id: String,
        seriesSlug: String,
    ): Episode {
        return newEpisode(
            LoadData(
                id = id,
                type = "episode",
                refererUrl = "$baseUrl/series/$seriesSlug/season/$seasonNumber/episode/${episodeNumber ?: 1}",
            ).toJson(),
        ) {
            this.name = name
            this.season = seasonNumber
            this.episode = episodeNumber
            this.description = overview
            this.runTime = runtime
            this.score = Score.from10(voteAverage?.toString())
            addDate(airDate)
            this.posterUrl = stillPath?.tmdbPoster("w300")
        }
    }

    private suspend fun loadRecommendations(
        data: DetailResponse,
        isSeries: Boolean,
    ): List<SearchResponse> {
        val slug = data.slug ?: return emptyList()
        val relatedUrl = if (isSeries) {
            "$baseUrl/api/series/$slug/related"
        } else {
            "$baseUrl/api/movies/$slug/related"
        }

        return runCatching {
            getIdlixJson<ApiResponse>(relatedUrl)
                ?.data
                ?.mapNotNull { it.toSearchResponse(null) }
                ?.distinctBy { it.url }
                .orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsed = runCatching { parseJson<LoadData>(data) }.getOrNull() ?: return false
        if (parsed.id.isBlank() || parsed.type.isBlank()) return false

        val contentReferer = parsed.refererUrl
            ?.takeIf { it.isNotBlank() }
            ?.toProviderUrl()
            ?.fixAgainstMainUrl()
            ?: "$baseUrl/"

        var delivered = false

        for (playbackOrigin in idlixOrigins) {
            val originReferer = contentReferer.fixAgainstOrigin(playbackOrigin) ?: "$playbackOrigin/"
            val jsonHeaders = idlixHeadersFor(
                origin = playbackOrigin,
                referer = originReferer,
                contentType = "application/json",
            )
            val baseCookies = playbackCookies
            val playResponse = runCatching {
                app.get(
                    "$playbackOrigin/api/watch/play-info/${parsed.type}/${parsed.id}",
                    headers = idlixHeadersFor(playbackOrigin, originReferer),
                    cookies = baseCookies,
                    referer = originReferer,
                    timeout = 10000L,
                )
            }.getOrNull() ?: continue

            val playInfo = playResponse.parsedSafe<WatchSessionResponse>() ?: continue
            val resolvedSession = resolvePlaySession(
                initial = playInfo,
                playbackOrigin = playbackOrigin,
                contentReferer = originReferer,
                cookies = baseCookies + playResponse.cookies,
                headers = jsonHeaders,
            ) ?: continue
            val claimSession = resolvedSession.first
            val sessionCookies = resolvedSession.second

            val claim = claimSession.claim?.takeIf { it.isNotBlank() } ?: continue
            val redeemUrl = claimSession.redeemUrl
                ?.unescapeEmbedPayload()
                ?.fixAgainstOrigin(playbackOrigin)
                ?: continue
            val redeemResponse = runCatching {
                app.post(
                    redeemUrl,
                    headers = idlixHeadersFor(
                        origin = playbackOrigin,
                        referer = originReferer,
                        contentType = "application/json",
                    ),
                    cookies = sessionCookies,
                    referer = originReferer,
                    requestBody = mapOf("claim" to claim)
                        .toJson()
                        .toRequestBody("application/json".toMediaType()),
                    timeout = 10000L,
                )
            }.getOrNull() ?: continue

            val redeemText = redeemResponse.text
            val iframeResponse = mergeIframeResponse(
                parsed = redeemResponse.parsedSafe<Iframe>(),
                fallback = redeemText.toIframeFallback(),
            )

            val streamUrls = resolveIframeCandidates(
                iframeResponse = iframeResponse,
                redeemUrl = redeemUrl,
                contentReferer = originReferer,
                playbackOrigin = playbackOrigin,
            )
            val inlineCode = iframeResponse.code.orEmpty().unescapeEmbedPayload()

            for (streamUrl in streamUrls) {
                val iframePage = runCatching {
                    app.get(
                        url = streamUrl,
                        headers = hostHeadersFor(streamUrl) + mapOf(
                            "Referer" to originReferer,
                            "Origin" to playbackOrigin,
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                            "User-Agent" to USER_AGENT,
                        ),
                        cookies = sessionCookies,
                        referer = originReferer,
                        timeout = 10000L,
                    )
                }.getOrNull()

                val iframeText = iframePage?.text.orEmpty()
                val iframeDoc = iframePage?.document
                val sourceCandidate = iframeText.trimStart().startsWith("#EXTM3U") || streamUrl.isLikelyPlayableUrl()
                val mediaUrls = extractIframeMediaUrls(
                    iframeText = iframeText,
                    iframeUrl = streamUrl,
                    sourceUrl = streamUrl,
                    sourceCandidate = sourceCandidate,
                ).ifEmpty {
                    iframeDoc
                        ?.select("source[src], video[src], track[src]")
                        ?.mapNotNull { it.attr("src").fixAgainst(streamUrl) }
                        ?.filter { it.isLikelyPlayableUrl() }
                        ?.distinct()
                        .orEmpty()
                }.ifEmpty {
                    if (streamUrl.isLikelyPlayableUrl()) listOf(streamUrl) else emptyList()
                }

                if (emitMediaLinks(
                        mediaUrls = mediaUrls,
                        iframeUrl = streamUrl,
                        contentReferer = originReferer,
                        quality = claimSession.maxHeight?.toQuality() ?: Qualities.Unknown.value,
                        callback = callback,
                    )
                ) {
                    delivered = true
                }

                if (!delivered) {
                    Log.d(name, "Direct source extraction failed for $streamUrl, delegating to loadExtractor.")
                    if (loadExtractor(streamUrl, originReferer, subtitleCallback, callback) ||
                        loadExtractor(streamUrl, streamUrl, subtitleCallback, callback)
                    ) {
                        delivered = true
                    }
                }

                for (subtitle in extractIframeSubtitles(iframeText, streamUrl)) {
                    subtitleCallback(subtitle)
                }
            }

            if (!delivered && inlineCode.isNotBlank()) {
                val mediaUrls = extractIframeMediaUrls(
                    iframeText = inlineCode,
                    iframeUrl = redeemUrl,
                    sourceUrl = redeemUrl,
                    sourceCandidate = false,
                )

                if (emitMediaLinks(
                        mediaUrls = mediaUrls,
                        iframeUrl = redeemUrl,
                        contentReferer = originReferer,
                        quality = claimSession.maxHeight?.toQuality() ?: Qualities.Unknown.value,
                        callback = callback,
                    )
                ) {
                    delivered = true
                }

                for (subtitle in extractIframeSubtitles(inlineCode, redeemUrl)) {
                    subtitleCallback(subtitle)
                }
            }

            for (subtitle in iframeResponse.subtitles.orEmpty()) {
                val path = subtitle.path?.takeIf { it.isNotBlank() }?.fixAgainstOrigin(playbackOrigin) ?: continue
                subtitleCallback(
                    newSubtitleFile(
                        subtitle.label?.takeIf { it.isNotBlank() } ?: subtitle.lang ?: "Subtitle",
                        path,
                    ),
                )
            }

            if (delivered) return true
        }

        return false
    }


    private fun resolveIframeCandidates(
        iframeResponse: Iframe,
        redeemUrl: String,
        contentReferer: String,
        playbackOrigin: String,
    ): List<String> {
        val urls = mutableListOf<String>()

        iframeResponse.url
            ?.takeIf { it.isNotBlank() }
            ?.unescapeEmbedPayload()
            ?.fixAgainstOrigin(playbackOrigin)
            ?.takeIf { it.isCandidateEmbedOrPlayableUrl() }
            ?.let(urls::add)

        val code = iframeResponse.code.orEmpty().unescapeEmbedPayload()
        val patterns = listOf(
            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<embed[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:iframe|embed|file|src|url|link|source)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\\s<>]+""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { regex ->
            regex.findAll(code).forEach { match ->
                val candidate = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: match.value
                candidate
                    .unescapeEmbedPayload()
                    .fixAgainst(redeemUrl)
                    ?.takeIf { it.isCandidateEmbedOrPlayableUrl() }
                    ?.let(urls::add)
            }
        }

        if (urls.isEmpty() && contentReferer.isNotBlank()) {
            Log.d(name, "IDLIX redeem returned no iframe URL for $contentReferer")
        }

        return urls.distinct()
    }


    private suspend fun emitMediaLinks(
        mediaUrls: List<String>,
        iframeUrl: String,
        contentReferer: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var delivered = false
        val queue = mediaUrls.distinct().toMutableList()
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty()) {
            val actualM3uLink = queue.removeAt(0)
            if (!visited.add(actualM3uLink)) continue

            val streamReferer = when {
                actualM3uLink == iframeUrl -> contentReferer
                iframeUrl.startsWith("http", true) -> iframeUrl
                else -> contentReferer
            }
            val streamHeaders = mapOf(
                "Referer" to streamReferer,
                "Origin" to streamReferer.originOfUrl(),
                "Accept" to "*/*",
                "User-Agent" to USER_AGENT,
            )

            if (actualM3uLink.isConfigJsonUrl()) {
                val nested = resolveNestedMediaUrls(
                    url = actualM3uLink,
                    referer = streamReferer,
                    headers = streamHeaders,
                )
                queue.addAll(nested.filterNot { it in visited || it in queue })
                continue
            }

            var helperDelivered = false
            if (actualM3uLink.isHlsManifestUrl()) {
                runCatching {
                    generateM3u8(
                        source = name,
                        streamUrl = actualM3uLink,
                        referer = streamReferer,
                        headers = streamHeaders,
                    ).forEach { link ->
                        callback(link)
                        delivered = true
                        helperDelivered = true
                    }
                }.onFailure { error ->
                    Log.d(name, "M3U8 helper failed for $actualM3uLink: ${error.message}")
                }
            }

            if (!helperDelivered) {
                val directLink = buildDirectPlayableLink(
                    url = actualM3uLink,
                    referer = streamReferer,
                    headers = streamHeaders,
                    quality = quality,
                )
                if (directLink != null) {
                    callback(directLink)
                    delivered = true
                }
            }
        }

        return delivered
    }


    private suspend fun resolveNestedMediaUrls(
        url: String,
        referer: String,
        headers: Map<String, String>,
    ): List<String> {
        val responseText = runCatching {
            app.get(
                url = url,
                headers = headers,
                referer = referer,
                timeout = 10000L,
            ).text
        }.getOrNull().orEmpty()

        if (responseText.isBlank()) return emptyList()

        return extractIframeMediaUrls(
            iframeText = responseText,
            iframeUrl = url,
            sourceUrl = url,
            sourceCandidate = responseText.trimStart().startsWith("#EXTM3U"),
        ).filter { it != url }
    }


    private fun extractIframeMediaUrls(
        iframeText: String,
        iframeUrl: String,
        sourceUrl: String,
        sourceCandidate: Boolean,
    ): List<String> {
        val urls = mutableListOf<String>()
        val normalizedText = iframeText.unescapeEmbedPayload()

        if (sourceCandidate) {
            urls.add(sourceUrl)
        }

        val patterns = listOf(
            Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""<(?:video|track)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](?:file|src|url|link|source|hls|dash)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\\s<>]+(?:m3u8|mp4|(?:config|data)-[^"'\\\s<>]+?\.json)[^"'\\\s<>]*""", RegexOption.IGNORE_CASE),
        )

        patterns.forEach { regex ->
            regex.findAll(normalizedText).forEach { match ->
                val candidate = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }
                    ?: match.value
                candidate
                    .unescapeEmbedPayload()
                    .fixAgainst(iframeUrl)
                    ?.takeIf { it.isLikelyPlayableUrl() }
                    ?.let(urls::add)
            }
        }

        return urls.distinct()
    }


    private suspend fun buildDirectPlayableLink(
        url: String,
        referer: String,
        headers: Map<String, String>,
        quality: Int,
    ): ExtractorLink? {
        val type = when {
            url.isHlsManifestUrl() -> ExtractorLinkType.M3U8
            url.contains(".mp4", true) -> ExtractorLinkType.VIDEO
            else -> return null
        }

        return newExtractorLink(
            source = name,
            name = "$name Majorplay",
            url = url,
            type = type,
        ) {
            this.referer = referer
            this.headers = headers
            this.quality = quality
        }
    }

    private suspend fun extractIframeSubtitles(
        iframeText: String,
        iframeUrl: String,
    ): List<SubtitleFile> {
        val subtitles = mutableListOf<SubtitleFile>()
        val subRegex = Regex("""\"label\"\s*:\s*\"([^\"]*?)\"[^}]*?\"path\"\s*:\s*\"([^\"]*?)\"""")

        for (match in subRegex.findAll(iframeText)) {
            val label = match.groupValues[1].ifBlank { "Subtitle" }
            val path = match.groupValues[2]
                .replace("\\/", "/")
                .fixAgainst(iframeUrl)
                ?: continue
            subtitles.add(newSubtitleFile(label, path))
        }

        return subtitles
    }

    private suspend fun resolvePlaySession(
        initial: WatchSessionResponse,
        playbackOrigin: String,
        contentReferer: String,
        cookies: Map<String, String>,
        headers: Map<String, String>,
    ): Pair<WatchSessionResponse, Map<String, String>>? {
        var session = initial
        val sessionCookies = cookies.toMutableMap()

        repeat(12) {
            if (session.kind.equals("pentos", true) &&
                !session.claim.isNullOrBlank() &&
                !session.redeemUrl.isNullOrBlank()
            ) {
                return session to sessionCookies.toMap()
            }

            val gateToken = session.gateToken?.takeIf { it.isNotBlank() } ?: return null
            waitForGate(session)

            val claimResponse = runCatching {
                app.post(
                    "$playbackOrigin/api/watch/session/claim",
                    headers = headers + mapOf("User-Agent" to USER_AGENT),
                    cookies = sessionCookies,
                    referer = contentReferer,
                    requestBody = mapOf("gateToken" to gateToken)
                        .toJson()
                        .toRequestBody("application/json".toMediaType()),
                    timeout = 10000L,
                )
            }.getOrNull() ?: return null
            sessionCookies.putAll(claimResponse.cookies)
            session = claimResponse.parsedSafe<WatchSessionResponse>() ?: return null

            if (session.kind.equals("pentos", true) &&
                !session.claim.isNullOrBlank() &&
                !session.redeemUrl.isNullOrBlank()
            ) {
                return session to sessionCookies.toMap()
            }

            val remaining = session.remainingMs?.coerceAtLeast(0L) ?: 0L
            if (remaining > 0L) {
                delay((remaining + 125L).coerceAtMost(5000L))
            }
        }

        return null
    }

    private suspend fun waitForGate(playInfo: WatchSessionResponse) {
        val remaining = playInfo.remainingMs?.coerceAtLeast(0L) ?: run {
            val serverNow = playInfo.serverNow ?: return
            val unlockAt = playInfo.unlockAt ?: return
            (unlockAt - serverNow).coerceAtLeast(0L)
        }

        if (remaining > 25L) {
            Log.d(name, "Waiting IDLIX gate: ${remaining}ms")
            delay((remaining + 125L).coerceAtMost(16000L))
        }
    }

    private fun Long.toQuality(): Int {
        return when {
            this >= 2160L -> Qualities.P2160.value
            this >= 1080L -> Qualities.P1080.value
            this >= 720L -> Qualities.P720.value
            this >= 480L -> Qualities.P480.value
            this >= 360L -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun ApiItem.toSearchResponse(defaultType: String?): SearchResponse? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val slug = slug?.takeIf { it.isNotBlank() } ?: return null
        val type = contentType ?: defaultType.orEmpty()
        val poster = posterPath?.tmdbPoster("w342")
        val year = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val rating = voteAverage?.toString()
        val qualityText = quality

        return if (type.isSeriesType()) {
            newTvSeriesSearchResponse(title, "$baseUrl/api/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        } else {
            newMovieSearchResponse(title, "$baseUrl/api/movies/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        }
    }

    private fun SearchApiResult.toSearchResponse(): SearchResponse? {
        val title = title?.takeIf { it.isNotBlank() } ?: return null
        val slug = slug?.takeIf { it.isNotBlank() } ?: return null
        val poster = posterPath?.tmdbPoster("w342")
        val year = (releaseDate ?: firstAirDate)?.substringBefore("-")?.toIntOrNull()
        val rating = voteAverage?.toString()
        val qualityText = quality

        return if (contentType.isSeriesType()) {
            newTvSeriesSearchResponse(title, "$baseUrl/api/series/$slug", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        } else {
            newMovieSearchResponse(title, "$baseUrl/api/movies/$slug", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.score = Score.from10(rating)
                this.quality = getSearchQuality(qualityText)
            }
        }
    }

    private fun String?.isSeriesType(): Boolean {
        val value = this.orEmpty().lowercase()
        return value == "tv_series" || value == "series" || value == "tv" || value.contains("series")
    }

    private fun String.tmdbPoster(size: String): String {
        return if (startsWith("http", true)) this else "https://image.tmdb.org/t/p/$size$this"
    }



    private fun String.unescapeEmbedPayload(): String {
        return replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u003F", "?", ignoreCase = true)
            .replace("\\u0025", "%", ignoreCase = true)
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("\\\"", "\"")
            .trim()
    }

    private fun hostHeadersFor(url: String): Map<String, String> {
        return if (baseUrl.isIpv4HttpsUrl() && url.trim().startsWith(baseUrl, true)) hostHeader else emptyMap()
    }

    private fun String.toIdlixPathOrNull(): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.startsWith("/")) return value

        val knownOrigins = listOf(
            baseUrl,
            LEGACY_MAIN_URL,
            "https://www.idlixku.com",
            "https://idlixku.com",
            "https://idlix.com",
        ).distinct()

        for (origin in knownOrigins) {
            val trimmedOrigin = origin.trimEnd('/')
            if (value.equals(trimmedOrigin, true) || value.equals("$trimmedOrigin/", true)) return "/"
            if (value.startsWith("$trimmedOrigin/", true)) {
                return value.substring(trimmedOrigin.length).ifBlank { "/" }
            }
        }

        return null
    }

    private fun String.fixAgainstOrigin(origin: String): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:$value"

        val idlixPath = value.toIdlixPathOrNull()
        if (idlixPath != null) {
            val path = if (idlixPath.startsWith("/")) idlixPath else "/$idlixPath"
            return origin.trimEnd('/') + path
        }

        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI("${origin.trimEnd('/')}/").resolve(value).toString() }.getOrNull()
    }

    private fun String.toProviderUrl(): String {
        val value = trim()
        if (value.isBlank()) return value
        return when {
            value.startsWith(LEGACY_MAIN_URL, true) -> baseUrl + value.substring(LEGACY_MAIN_URL.length)
            value.startsWith("https://www.idlixku.com", true) -> baseUrl + value.substring("https://www.idlixku.com".length)
            value.startsWith("https://idlixku.com", true) -> baseUrl + value.substring("https://idlixku.com".length)
            value.startsWith("https://idlix.com", true) -> baseUrl + value.substring("https://idlix.com".length)
            else -> value
        }
    }

    private fun String.isIpv4HttpsUrl(): Boolean {
        return runCatching {
            val uri = URI(this)
            uri.scheme.equals("https", true) &&
                Regex("""\d{1,3}(?:\.\d{1,3}){3}""").matches(uri.host.orEmpty())
        }.getOrDefault(false)
    }


    private fun String.fixAgainst(baseUrl: String): String? {
        val value = trim()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrNull()
    }

    private fun String.originOfUrl(): String {
        return runCatching {
            val uri = URI(this)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return@runCatching baseUrl
            "$scheme://$host"
        }.getOrDefault(baseUrl)
    }

    private fun String.isConfigJsonUrl(): Boolean {
        val value = lowercase()
        return value.contains(".json") &&
            (value.contains("config-") || value.contains("data-") || value.contains("/config") || value.contains("/data"))
    }

    private fun String.isHlsManifestUrl(): Boolean {
        val value = lowercase()
        return value.contains(".m3u8") || value.contains("application/vnd.apple.mpegurl")
    }

    private fun String.isLikelyPlayableUrl(): Boolean {
        val value = lowercase()
        return isHlsManifestUrl() || value.contains(".mp4") || isConfigJsonUrl()
    }

    private fun String.isCandidateEmbedOrPlayableUrl(): Boolean {
        val value = lowercase()
        if (isLikelyPlayableUrl()) return true
        if (!value.startsWith("http")) return false
        if (value.contains("majorplay", true) || value.contains("jeniusplay", true)) return true
        if (value.contains("/embed", true) || value.contains("/player", true) || value.contains("/watch", true)) return true
        return !value.containsAny(
            ".js", ".css", ".png", ".jpg", ".jpeg", ".webp", ".svg", ".ico", ".woff", ".ttf"
        )
    }

    private fun String.containsAny(vararg needles: String): Boolean {
        return needles.any { contains(it, true) }
    }

    private fun String.fixAgainstMainUrl(): String? {
        val value = trim().toProviderUrl()
        if (value.isBlank()) return null
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        return runCatching { URI("$baseUrl/").resolve(value).toString() }.getOrNull()
    }

    private fun mergeIframeResponse(parsed: Iframe?, fallback: Iframe): Iframe {
        if (parsed == null) return fallback

        return Iframe(
            code = parsed.code?.takeIf { it.isNotBlank() } ?: fallback.code,
            url = parsed.url?.takeIf { it.isNotBlank() } ?: fallback.url,
            expiresAt = parsed.expiresAt ?: fallback.expiresAt,
            subtitles = (parsed.subtitles.orEmpty() + fallback.subtitles.orEmpty())
                .distinctBy { it.path ?: it.label ?: it.lang },
            videoId = parsed.videoId?.takeIf { it.isNotBlank() } ?: fallback.videoId,
        )
    }


    private fun String.toIframeFallback(): Iframe {
        val normalized = unescapeEmbedPayload()
        val url = normalized.firstJsonStringValue("url")
            ?: normalized.firstJsonStringValue("iframe")
            ?: normalized.firstJsonStringValue("embed")
            ?: normalized.firstJsonStringValue("embedUrl")
            ?: normalized.firstJsonStringValue("player")
            ?: normalized.firstJsonStringValue("src")
            ?: Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(normalized)
                ?.groupValues
                ?.getOrNull(1)

        val code = normalized.firstJsonStringValue("code")
            ?: normalized.firstJsonStringValue("html")
            ?: normalized.firstJsonStringValue("embedCode")
            ?: normalized.takeIf { it.contains("<iframe", true) || it.contains("<source", true) || it.contains("<video", true) }

        return Iframe(
            code = code,
            url = url,
            subtitles = extractSubtitleObjects(normalized),
        )
    }

    private fun String.firstJsonStringValue(key: String): String? {
        val pattern = Regex("""["']$key["']\s*:\s*["']((?:\\.|[^"'])*)["']""", RegexOption.IGNORE_CASE)
        return pattern.find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.unescapeEmbedPayload()
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractSubtitleObjects(text: String): List<Subtitle> {
        val subtitles = mutableListOf<Subtitle>()
        val objectRegex = Regex("""\{[^{}]*(?:"lang"|"label"|"path"|"file")[^{}]*\}""", RegexOption.IGNORE_CASE)
        for (match in objectRegex.findAll(text)) {
            val block = match.value
            val path = block.firstJsonStringValue("path")
                ?: block.firstJsonStringValue("file")
                ?: continue
            subtitles.add(
                Subtitle(
                    lang = block.firstJsonStringValue("lang"),
                    label = block.firstJsonStringValue("label"),
                    path = path,
                ),
            )
        }
        return subtitles.distinctBy { it.path }
    }

}

fun getSearchQuality(check: String?): SearchQuality? {
    val value = check ?: return null
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase()
    val patterns = listOf(
        Regex("\\b(4k|ds4k|uhd|2160p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.FourK,
        Regex("\\b(hdts|hdcam|hdtc)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HdCam,
        Regex("\\b(camrip|cam[- ]?rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
        Regex("\\b(cam)\\b", RegexOption.IGNORE_CASE) to SearchQuality.Cam,
        Regex("\\b(web[- ]?dl|webrip|webdl)\\b", RegexOption.IGNORE_CASE) to SearchQuality.WebRip,
        Regex("\\b(bluray|bdrip|blu[- ]?ray)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1440p|qhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.BlueRay,
        Regex("\\b(1080p|fullhd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(720p)\\b", RegexOption.IGNORE_CASE) to SearchQuality.SD,
        Regex("\\b(hdrip|hdtv)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HD,
        Regex("\\b(dvd)\\b", RegexOption.IGNORE_CASE) to SearchQuality.DVD,
        Regex("\\b(hq)\\b", RegexOption.IGNORE_CASE) to SearchQuality.HQ,
        Regex("\\b(rip)\\b", RegexOption.IGNORE_CASE) to SearchQuality.CamRip,
    )

    for ((regex, quality) in patterns) {
        if (regex.containsMatchIn(normalized)) return quality
    }

    return null
}
