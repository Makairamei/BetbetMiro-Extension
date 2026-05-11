package com.Matane

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class Donghub : Anichin() {
    companion object {
        var context: android.content.Context? = null
    }
    override var mainUrl = "https://donghub.vip"
    override var name = "Donghua Donghub"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "anime/?order=update" to "Rilisan Terbaru",
        "anime/?status=ongoing&order&order=popular" to "Paling Populer",
        "anime/?status=ongoing&order=update" to "Series Ongoing",
        "anime/?status=completed&order=update" to "Series Completed",
        "anime/?type=movie&order=update" to "Movie",
        "anime/?" to "Semua Donghua"
    )

 
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        document.select(".mobius option").forEach { item ->
            val base64 = item.attr("value")
            if (base64.isNotBlank()) {
                val decoded = base64Decode(base64)
                val doc = Jsoup.parse(decoded)
                val iframe = doc.select("iframe").attr("src")
                loadExtractor(fixUrl(iframe), subtitleCallback, callback)
            }
        }
        return true
    }

}
