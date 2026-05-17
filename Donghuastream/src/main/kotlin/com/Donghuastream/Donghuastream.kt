package com.Donghuastream

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

open class Donghuastream : MainAPI() {
    
    companion object {
        var context: android.content.Context? = null
    }

    override var mainUrl = "https://donghuastream.org"
    override var name = "Donghuastream"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    // User-Agent browser resmi untuk mengamankan koneksi dari proteksi firewall web
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "id,en-US;q=0.7,en;q=0.3"
    )

    // Menyusun ulang data path parameter beranda agar rapi sesuai arsitektur tema WordPress
    override val mainPage = mainPageOf(
        "" to "Latest Update",
        "donghua/" to "Donghua Series",
        "donghua/?status=completed&order=update" to "Completed Donghua",
        "donghua/?order=popular" to "Popular Donghua"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val url = when {
            data.isEmpty() -> if (page == 1) mainUrl else "$mainUrl/page/$page/"
            data.contains("?") -> {
                val path = data.substringBefore("?")
                val query = data.substringAfter("?")
                if (page == 1) "$mainUrl/$path?$query" else "$mainUrl/$path/page/$page/?$query"
            }
            else -> if (page == 1) "$mainUrl/$data" else "$mainUrl/$data/page/$page/"
        }.replace("//", "/").replace("https:/", "https://")

        val document = app.get(url, headers = browserHeaders).document
        // Menambahkan selector .utao yang menjadi standar list beranda donghuastream.org
        val home = document.select("div.listupd .utao, div.listupd article.bs, div.listupd div.bs, .listupd .chor").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".tt, h2, h3, .tta, .title")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = this.selectFirst("img")?.getImageAttr()?.fixImageQuality()
        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = browserHeaders).document
        return document.select("div.listupd .utao, div.listupd article.bs, div.listupd div.bs, .listupd .chor").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document
        val title = document.selectFirst("h1.entry-title, title")?.text()?.replace("Subtitle Indonesia", "")?.trim() ?: ""
        val poster = document.selectFirst("div.thumb img, .poster img")?.getImageAttr()?.fixImageQuality()
        val description = document.selectFirst(".entry-content p, .contt")?.text()?.trim()
        
        val episodes = document.select("div.eplister ul li, ul.clor li").mapNotNull { li ->
            val epHref = fixUrl(li.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val epTitle = li.selectFirst(".epl-num, .epl-title")?.text()?.trim() ?: "Episode"
            newEpisode(epHref) {
                this.name = epTitle
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = browserHeaders).document

        // 1. Ambil Link dari Dropdown Server / Mirror Options (Mendukung Base64 & Data Embed)
        document.select("select.mirror option, div.player-source select option, ul.mplayer li, .mirroroption .option").forEach { server ->
            val value = server.attr("value").takeIf { it.isNotEmpty() }
                ?: server.attr("data-embed").takeIf { it.isNotEmpty() }
                ?: server.attr("data-video").takeIf { it.isNotEmpty() }

            if (value != null) {
                val decoded = try {
                    base64Decode(value)
                } catch (_: Exception) { "" }

                val iframeUrl = if (decoded.isNotEmpty() && decoded.contains("iframe", true)) {
                    org.jsoup.Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")?.let(::httpsify)
                } else {
                    value.let(::httpsify)
                }

                if (!iframeUrl.isNullOrEmpty() && iframeUrl.startsWith("http")) {
                    val label = server.text().trim().ifEmpty { "Server" }
                    if (iframeUrl.endsWith(".mp4")) {
                        callback(
                            newExtractorLink(label, label, url = iframeUrl, INFER_TYPE) {
                                this.referer = ""
                                this.quality = getQualityFromName(label)
                            }
                        )
                    } else {
                        loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        // 2. Ambil Link dari Iframe Langsung di Halaman (Metode Fallback Lama)
        document.select("div.embed-holder iframe, div.video-content iframe, div.player iframe, iframe#player").forEach { iframe ->
            val iframeUrl = iframe.getIframeAttr()?.let(::httpsify)
            if (!iframeUrl.isNullOrEmpty() && iframeUrl.startsWith("http")) {
                val label = "Iframe Player"
                if (iframeUrl.endsWith(".mp4")) {
                    callback(
                        newExtractorLink(label, label, url = iframeUrl, INFER_TYPE) {
                            this.referer = ""
                            this.quality = getQualityFromName(label)
                        }
                    )
                } else {
                    loadExtractor(iframeUrl, referer = iframeUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun Element?.getIframeAttr(): String? {
        return this?.attr("data-litespeed-src").takeIf { it?.isNotEmpty() == true }
                ?: this?.attr("src")
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }
}
