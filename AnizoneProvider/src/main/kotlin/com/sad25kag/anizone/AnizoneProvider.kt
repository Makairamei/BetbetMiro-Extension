package com.sad25kag.anizone

import android.util.Log
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class AnizoneProvider : MainAPI() {

    override var mainUrl = "https://anizone.to"
    override var name = "AniZone"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
    )

    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val mainPage = mainPageOf(
        "sort:release-desc" to "Rilis Terbaru",
        "sort:title-asc" to "A-Z",
        "sort:title-desc" to "Z-A",
        "sort:release-asc" to "Rilis Terlama",
        "sort:added-asc" to "Pertama Ditambahkan",
        "sort:added-desc" to "Terakhir Ditambahkan",
        "tag:/tag/xottt75h" to "Aksi",
        "tag:/tag/bio3ygrp" to "Komedi",
        "tag:/tag/s1ssghb1" to "Fantasi",
        "tag:/tag/hmi0gccz" to "Manga"
    )

    private val snapshotAnimeKey = "anime_snapshot_key"
    private val snapshotEpisodeKey = "episode_snapshot_key"
    private val snapshotVideoKey = "video_snapshot_key"

    private var token: String = ""
    private val snapshots = mutableMapOf(
        snapshotAnimeKey to "",
        snapshotEpisodeKey to "",
        snapshotVideoKey to ""
    )

    private var cookies = mutableMapOf<String, String>()

    private fun translateGenre(name: String): String {
        return when (name.trim().lowercase(Locale.ROOT)) {
            "action" -> "Aksi"
            "adventure" -> "Petualangan"
            "comedy" -> "Komedi"
            "daily life", "slice of life" -> "Kehidupan Sehari-hari"
            "fantasy" -> "Fantasi"
            "high school" -> "SMA"
            "historical" -> "Sejarah"
            "manga" -> "Manga"
            "novel" -> "Novel"
            "romance" -> "Romantis"
            "school life" -> "Kehidupan Sekolah"
            "seinen" -> "Seinen"
            "shounen" -> "Shounen"
            "sports" -> "Olahraga"
            "thriller" -> "Thriller"
            "tragedy" -> "Tragedi"
            "violence" -> "Kekerasan"
            else -> name.trim()
        }
    }

    private suspend fun initializeLiveWire(initialSlug: String = "/anime"): Boolean {
        if (!snapshots[snapshotAnimeKey].isNullOrBlank() && token.isNotBlank()) return true

        return try {
            val initReq = app.get("$mainUrl$initialSlug")
            val doc = initReq.document

            val csrfToken = doc.selectFirst("script[data-csrf]")
                ?.attr("data-csrf")
                ?.takeIf { it.isNotBlank() }
                ?: ""

            val snapshot = getSnapshot(doc)

            if (csrfToken.isBlank() || snapshot.isBlank()) {
                Log.e("AniZone Init", "Inisialisasi gagal: token atau snapshot kosong.")
                false
            } else {
                cookies = initReq.cookies.toMutableMap()
                token = csrfToken
                snapshots[snapshotAnimeKey] = snapshot
                true
            }
        } catch (e: Exception) {
            Log.e("AniZone Init", "Error initializeLiveWire: ${e.message}")
            false
        }
    }

    private fun getSnapshot(doc: Document): String {
        return doc.selectFirst("main > div[wire:snapshot], main div[wire:snapshot], div[wire:snapshot]")
            ?.attr("wire:snapshot")
            ?.replace("&quot;", "\"")
            ?: ""
    }

    private fun getSnapshot(json: JSONObject): String {
        return json.getJSONArray("components")
            .getJSONObject(0)
            .getString("snapshot")
            .replace("\\\"", "\"")
    }

    private fun getHtmlFromWire(json: JSONObject): Document {
        return Jsoup.parse(
            json.getJSONArray("components")
                .getJSONObject(0)
                .getJSONObject("effects")
                .getString("html")
                .replace("\\\"", "\"")
                .replace("\\n", "")
        )
    }

    private suspend fun liveWireBuilder(
        snapshotKey: String,
        updates: Map<String, Any?>,
        calls: List<Map<String, Any?>>,
        biscuit: MutableMap<String, String>,
        remember: Boolean,
        refererPath: String = "/anime"
    ): JSONObject {
        if (token.isBlank() || snapshots[snapshotKey].isNullOrBlank()) {
            val initReq = app.get("$mainUrl$refererPath")
            val initDoc = initReq.document
            token = initDoc.selectFirst("script[data-csrf]")?.attr("data-csrf") ?: token
            snapshots[snapshotKey] = getSnapshot(initDoc)
            biscuit.putAll(initReq.cookies)
        }

        val payload = mapOf(
            "_token" to token,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshots[snapshotKey],
                    "updates" to updates,
                    "calls" to calls
                )
            )
        )

        val jsonString = payload.toJson()
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonString.toRequestBody(mediaType)

        val req = app.post(
            url = "$mainUrl/livewire/update",
            requestBody = requestBody,
            headers = mapOf(
                "X-CSRF-TOKEN" to token,
                "X-Livewire" to ""
            ),
            cookies = biscuit,
            referer = "$mainUrl$refererPath"
        )

        val bodyString = req.text

        if (bodyString.isBlank()) {
            throw Exception("Respons Livewire kosong. HTTP ${req.code}.")
        }

        if (
            bodyString.trim().startsWith("<!DOCTYPE", ignoreCase = true) ||
            bodyString.trim().startsWith("<html", ignoreCase = true)
        ) {
            throw Exception("Livewire tidak mengembalikan JSON. HTTP ${req.code}. URL: ${req.url}")
        }

        val responseJson = JSONObject(bodyString)

        if (remember) {
            snapshots[snapshotKey] = getSnapshot(responseJson)
            biscuit.putAll(req.cookies)
        }

        return responseJson
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return try {
            val data = request.data
            val path = when {
                data.startsWith("tag:") -> data.removePrefix("tag:")
                else -> "/anime"
            }
            val sort = when {
                data.startsWith("sort:") -> data.removePrefix("sort:").ifBlank { "release-desc" }
                else -> "release-desc"
            }

            buildLiveWireHomePage(path, sort, page, request.name)
        } catch (e: Exception) {
            Log.e("AniZone", "Gagal memproses getMainPage: ${e.message}")
            emptyHomePage(request.name)
        }
    }

    private suspend fun buildLiveWireHomePage(
        path: String,
        sort: String,
        page: Int,
        title: String
    ): HomePageResponse {
        snapshots[snapshotAnimeKey] = ""
        val initialized = initializeLiveWire(path)
        if (!initialized) return emptyHomePage(title)

        var responseJson = liveWireBuilder(
            snapshotAnimeKey,
            mapOf("sort" to sort),
            emptyList(),
            cookies,
            true,
            path
        )

        var doc = getHtmlFromWire(responseJson)

        for (i in 1 until page) {
            if (!hasLoadMore(doc)) break

            responseJson = liveWireBuilder(
                snapshotAnimeKey,
                emptyMap(),
                listOf(
                    mapOf(
                        "path" to "",
                        "method" to "loadMore",
                        "params" to emptyList<String>()
                    )
                ),
                cookies,
                true,
                path
            )

            doc = getHtmlFromWire(responseJson)
        }

        val home = parseAnimeElements(doc).map { toResult(it) }

        return if (home.isEmpty()) {
            emptyHomePage(title)
        } else {
            newHomePageResponse(
                HomePageList(title, home, isHorizontalImages = false),
                hasNext = hasLoadMore(doc)
            )
        }
    }

    private fun emptyHomePage(name: String): HomePageResponse {
        return newHomePageResponse(
            HomePageList(name, emptyList(), isHorizontalImages = false),
            hasNext = false
        )
    }

    private fun hasLoadMore(doc: Document): Boolean {
        return doc.selectFirst("div[x-intersect~=loadMore], .h-12[x-intersect~=loadMore]") != null
    }

    private fun parseAnimeElements(doc: Document): List<Element> {
        val gridCards = doc.select("div.grid > div")
            .filter { animeUrlFromElement(it).isNotBlank() }

        if (gridCards.isNotEmpty()) return gridCards.distinctBy { animeUrlFromElement(it) }

        return doc.select("div[wire:key]:has(a[href*='/anime/']), article:has(a[href*='/anime/']), li:has(a[href*='/anime/'])")
            .filter { animeUrlFromElement(it).isNotBlank() }
            .distinctBy { animeUrlFromElement(it) }
    }

    private fun animeUrlFromElement(element: Element): String {
        val href = element.selectFirst("a.inline[href*='/anime/'], a[href*='/anime/']")
            ?.attr("href")
            ?: ""
        return normalizeUrl(href)
    }

    private fun normalizeUrl(url: String): String {
        return when {
            url.isBlank() -> ""
            url.startsWith("//") -> "https:$url"
            url.startsWith("http", ignoreCase = true) -> url
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun pathFromUrl(url: String): String {
        val path = url.substringAfter(mainUrl, url)
            .substringBefore("?")
            .substringBefore("#")
            .trim()
        return if (path.startsWith("/")) path else "/$path"
    }

    private fun toResult(post: Element): SearchResponse {
        val link = post.selectFirst("a.inline[href*='/anime/'], a[href*='/anime/']")
        val title = link?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: post.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: post.selectFirst("h2, h3")?.text()?.trim()
            ?: ""

        val url = normalizeUrl(link?.attr("href") ?: "")
        val type = if (post.text().contains("Movie", ignoreCase = true)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newMovieSearchResponse(title, url, type) {
            this.posterUrl = post.selectFirst("img")?.attr("src")
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        snapshots[snapshotAnimeKey] = ""
        val initialized = initializeLiveWire()
        if (!initialized) return emptyList()

        val doc = getHtmlFromWire(
            liveWireBuilder(
                snapshotAnimeKey,
                mapOf("search" to query, "sort" to "release-desc"),
                emptyList(),
                cookies,
                false
            )
        )

        return parseAnimeElements(doc).map { toResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url)
        val doc = response.document
        val cookie = response.cookies.toMutableMap()
        val slug = pathFromUrl(url)

        val pageToken = doc.select("script[data-csrf]").attr("data-csrf")
        if (pageToken.isNotBlank()) token = pageToken
        snapshots[snapshotEpisodeKey] = getSnapshot(doc)

        val title = doc.selectFirst("h1")?.text()
            ?: throw NotImplementedError("Unable to find title")

        val bgImage = doc.selectFirst("main img")?.attr("src")
        val synopsis = doc.selectFirst(".sr-only + div, div:has(>h3:contains(Synopsis)) > div")?.text() ?: ""
        val rowLines = doc.select("span.inline-block, span.flex").map { it.text() }
        val releasedYear = rowLines.firstOrNull { it.matches(Regex("""\d{4}""")) }

        val status = when {
            rowLines.any { it.equals("Completed", ignoreCase = true) } -> ShowStatus.Completed
            rowLines.any { it.equals("Ongoing", ignoreCase = true) } -> ShowStatus.Ongoing
            else -> null
        }

        val genres = doc.select("a[wire:navigate][wire:key], div > a[href*='/tag/']").map {
            translateGenre(it.text())
        }.distinct()

        val imdbLink = doc.selectFirst("a[href*='imdb.com']")
        val imdbId = imdbLink?.attr("href")
            ?.substringAfter("title/")
            ?.trimEnd('/', ' ', '?')
            ?.let {
                if (it.startsWith("tt") && it.length > 2) it else "tt0000000"
            }
            ?: "tt0000000"

        Log.d("AniZoneIMDB", "IMDB ID ditemukan di LOAD: $imdbId")

        var currentDoc = doc
        var previousEpisodeCount = parseEpisodeElements(currentDoc).size
        var attempts = 0
        val maxAttempts = 100

        while (hasLoadMore(currentDoc) && attempts < maxAttempts) {
            attempts++

            try {
                val responseJson = liveWireBuilder(
                    snapshotEpisodeKey,
                    emptyMap(),
                    listOf(
                        mapOf(
                            "path" to "",
                            "method" to "loadMore",
                            "params" to emptyList<String>()
                        )
                    ),
                    cookie,
                    true,
                    slug
                )

                val nextDoc = getHtmlFromWire(responseJson)
                val nextCount = parseEpisodeElements(nextDoc).size
                if (nextCount <= previousEpisodeCount) break

                currentDoc = nextDoc
                previousEpisodeCount = nextCount
            } catch (e: Exception) {
                Log.e("AniZone Load", "Error loadMore episode percobaan $attempts: ${e.message}")
                break
            }
        }

        val episodes = parseEpisodeElements(currentDoc).map { elt ->
            val epUrl = normalizeUrl(elt.selectFirst("a[href]")?.attr("href") ?: "")

            newEpisode(epUrl) {
                this.name = elt.selectFirst("h3")?.text()
                    ?.substringAfter(":")
                    ?.trim()
                    ?: elt.selectFirst("h3")?.text()?.trim()

                this.season = 0
                this.posterUrl = elt.selectFirst("img")?.attr("src")
                this.data = "$epUrl|||$imdbId"
                this.date = parseEpisodeDate(elt)
            }
        }

        val tvType = if (rowLines.any { it.equals("Movie", ignoreCase = true) } && episodes.size <= 1) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = bgImage
            this.plot = synopsis
            this.tags = genres
            this.year = releasedYear?.toIntOrNull()
            this.showStatus = status
            addEpisodes(DubStatus.None, episodes)
        }
    }

    private fun parseEpisodeElements(doc: Document): List<Element> {
        return doc.select("ul > li:has(a[href]), li[x-data]:has(a[href])")
            .filter { isEpisodeUrl(it.selectFirst("a[href]")?.attr("href") ?: "") }
            .distinctBy { normalizeUrl(it.selectFirst("a[href]")?.attr("href") ?: "") }
    }

    private fun isEpisodeUrl(href: String): Boolean {
        val parts = pathFromUrl(normalizeUrl(href)).split("/").filter { it.isNotBlank() }
        return parts.size >= 3 && parts.firstOrNull() == "anime"
    }

    private fun parseEpisodeDate(element: Element): Long {
        val dateText = element.select("div.flex-row > span").getOrNull(1)?.text()
            ?: element.selectFirst("span[title] span.line-clamp-1")?.text()
            ?: ""

        return dateText.trim()
            .replace(Regex("\\s+"), "")
            .ifEmpty { null }
            ?.let {
                try {
                    SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(it)?.time
                } catch (e: Exception) {
                    Log.e("AniZone", "Gagal parse tanggal '$it': ${e.message}")
                    null
                }
            } ?: 0L
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = data.split("|||").firstOrNull()?.takeIf { it.isNotBlank() } ?: return false
        val episodePath = pathFromUrl(episodeUrl)

        Log.d("AniZoneSub", "Mulai loadLinks: $episodeUrl")

        val webReq = app.get(episodeUrl)
        val web = webReq.document
        val cookie = webReq.cookies.toMutableMap()

        val pageToken = web.select("script[data-csrf]").attr("data-csrf")
        if (pageToken.isNotBlank()) token = pageToken
        snapshots[snapshotVideoKey] = getSnapshot(web)

        val emittedUrls = mutableSetOf<String>()
        var emitted = false

        suspend fun emitPlayer(doc: Document, sourceName: String) {
            val mediaPlayer = doc.selectFirst("media-player")
            val directSource = doc.selectFirst("video source[src], source[type*=mpegurl][src], source[src*=.m3u8], a[href*=.m3u8]")
            val rawUrl = mediaPlayer?.attr("src")?.takeIf { it.isNotBlank() }
                ?: directSource?.attr("src")?.takeIf { it.isNotBlank() }
                ?: directSource?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return

            if (rawUrl.startsWith("blob:", ignoreCase = true)) return

            val masterUrl = normalizeUrl(rawUrl)
            if (!emittedUrls.add(masterUrl)) return

            mediaPlayer?.select("track[kind=subtitles], track")?.forEach {
                val subtitleUrl = it.attr("src").takeIf { src -> src.isNotBlank() } ?: return@forEach
                subtitleCallback.invoke(
                    newSubtitleFile(
                        it.attr("label").ifBlank { "Subtitle" },
                        normalizeUrl(subtitleUrl)
                    )
                )
            }

            val baseHeaders = mapOf(
                "Origin" to mainUrl,
                "Accept" to "*/*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Cookie" to cookie.map { "${it.key}=${it.value}" }.joinToString("; ")
            )

            callback.invoke(
                newExtractorLink(
                    sourceName.ifBlank { name },
                    name,
                    masterUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = 0
                    this.headers = baseHeaders
                }
            )

            emitted = true
        }

        val serverButtons = web.getAllElements()
            .filter { it.attr("wire:click").contains("setVideo") }
            .distinctBy { it.attr("wire:click") }

        val firstName = serverButtons.firstOrNull()?.text()?.trim()
            ?: web.selectFirst("span.truncate")?.text()
            ?: name

        emitPlayer(web, firstName)

        for (button in serverButtons.drop(1)) {
            val videoParam = Regex("""setVideo\(([^)]*)\)""")
                .find(button.attr("wire:click"))
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.trim('\'', '"')
                ?.takeIf { it.isNotBlank() }
                ?: continue
            val param: Any = videoParam.toIntOrNull() ?: videoParam

            try {
                val responseJson = liveWireBuilder(
                    snapshotVideoKey,
                    emptyMap(),
                    listOf(
                        mapOf(
                            "path" to "",
                            "method" to "setVideo",
                            "params" to listOf(param)
                        )
                    ),
                    cookie,
                    true,
                    episodePath
                )

                val doc = getHtmlFromWire(responseJson)
                emitPlayer(doc, button.text().trim())
            } catch (e: Exception) {
                Log.e("AniZoneSub", "Gagal resolve server ${button.text()}: ${e.message}")
            }
        }

        return emitted
    }
}
