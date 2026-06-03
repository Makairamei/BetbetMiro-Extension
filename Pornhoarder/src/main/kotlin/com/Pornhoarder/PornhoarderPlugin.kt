package com.PornhoarderPlugin

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody

class PornhoarderPlugin : MainAPI() {
    override var mainUrl              = "https://ww3.pornhoarder.org"
    override var name                 = "Pornhoarder"
    override val hasMainPage          = true
    override var lang                 = "id"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val ajaxUrl = "$mainUrl/ajax_search.php"

    override val mainPage = mainPageOf(
            "Latest" to "Latest Videos",
            "Popular" to "Popular Videos",
            "/trending-videos/" to "Trending Videos",
            "/random-videos/" to "Random Videos"
        )

    private fun getRequestBody (query: String, isLatest : Boolean, page:Int) : FormBody
    {
        return FormBody.Builder()
            .addEncoded("search", query)
            .addEncoded("sort", if (isLatest) {"0"} else {"2"})
            .addEncoded("date", "0")
            .addEncoded("servers[]", "40")
            .addEncoded("servers[]", "45")
            .addEncoded("servers[]", "12")
            .addEncoded("servers[]", "29")
            .addEncoded("servers[]", "25")
            .addEncoded("servers[]", "41")
            .addEncoded("servers[]", "46")
            .addEncoded("servers[]", "17")
            .addEncoded("servers[]", "44")
            .addEncoded("servers[]", "42")
            .addEncoded("servers[]", "43")
            .addEncoded("author", "0")
            .addEncoded("page", page.toString())
            .build()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if(request.data == "Latest" || request.data == "Popular")
        {
            val body = getRequestBody("",request.data == "Latest",page)
            val document = app.post(ajaxUrl, requestBody = body).document
            val responseList  = document.select(".video article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

        }
        else
        {
            val document = app.get("$mainUrl${request.data}?page=$page").document
            val responseList  = document.select(".video article").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)
        }
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select(".video-content h1").text().replace("| PornHoarder.tv","")
        val href = mainUrl + this.select(".video-link").attr("href")
        val posterUrl = this.selectFirst(".video-image.primary.b-lazy")?.attr("data-src")
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..10) {
            val requestBody = getRequestBody(query,true,i)
            val document = app.post(ajaxUrl, requestBody = requestBody).document
            //val document = app.get("${mainUrl}/page/$i/?s=$query").document

            val results = document.select(".video article").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse

    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString().replace("| PornHoarder.tv","")
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
    

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun absoluteUrl(url: String?, base: String = mainUrl): String? {
        val cleaned = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            cleaned.startsWith("http://") || cleaned.startsWith("https://") -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "${mainUrl.removeSuffix("/")}$cleaned"
            cleaned.startsWith("?") -> "${base.substringBefore("?")}$cleaned"
            cleaned.startsWith("javascript:", true) || cleaned.startsWith("#") -> null
            else -> "${base.substringBeforeLast("/", mainUrl).trimEnd('/')}/$cleaned"
        }
    }

    private fun String.isPlayableCandidate(): Boolean {
        val lower = lowercase()
        return lower.startsWith("http") && !listOf(
            "javascript:", "about:", "data:", "blob:", "googlesyndication", "doubleclick",
            "popads", "exoclick", "juicyads", "adsterra", "analytics"
        ).any { lower.contains(it) }
    }

    private suspend fun tryLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        return try {
            val loaded = loadExtractor(url, referer, subtitleCallback) { link ->
                emitted = true
                callback(link)
            }
            loaded || emitted
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        val serversList = mutableListOf<String>()

        doc.select(".video-player iframe[src], iframe[src]").forEach { iframe ->
            absoluteUrl(iframe.attr("src"), data)?.let(serversList::add)
        }

        doc.select(".video-detail-servers li a[href]").take(12).forEach { item ->
            val hostUrl = absoluteUrl(item.attr("href"), data) ?: return@forEach
            runCatching {
                val serverDoc = app.get(hostUrl, referer = data).document
                serverDoc.select(".video-player iframe[src], iframe[src]").forEach { iframe ->
                    absoluteUrl(iframe.attr("src"), hostUrl)?.let(serversList::add)
                }
            }
        }

        var linksLoaded = 0
        serversList.distinct().filter { it.isPlayableCandidate() }.take(12).forEach { playerUrl ->
            if (tryLoadExtractor(playerUrl, data, subtitleCallback) { link ->
                    linksLoaded++
                    callback(link)
                }
            ) return@forEach

            val requestBody = FormBody.Builder()
                .addEncoded("play", "")
                .build()

            val playerDoc = runCatching {
                app.post(playerUrl, requestBody = requestBody, referer = data).document
            }.getOrNull() ?: return@forEach

            val nestedLinks = mutableListOf<String>()
            playerDoc.select("iframe[src], source[src], video[src]").forEach { element ->
                absoluteUrl(element.attr("src"), playerUrl)?.let(nestedLinks::add)
            }
            Regex("""['"](https?://[^'"\s]+(?:mp4|m3u8|embed|e/)[^'"\s]*)['"]""")
                .findAll(playerDoc.html())
                .take(8)
                .map { it.groupValues[1] }
                .forEach(nestedLinks::add)

            nestedLinks.distinct().filter { it.isPlayableCandidate() }.take(6).forEach { nested ->
                tryLoadExtractor(nested, playerUrl, subtitleCallback) { link ->
                    linksLoaded++
                    callback(link)
                }
            }
        }

        return linksLoaded > 0
    }
}
