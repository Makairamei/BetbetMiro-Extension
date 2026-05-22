package com.gojodesu

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

open class Kotakajaib : ExtractorApi() {
    override val name = "Kotakajaib"
    override val mainUrl = "https://kotakajaib.me"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer ?: mainUrl).document
        val links = document.select("ul#dropdown-server li a[data-frame], a[data-frame]")

        for (a in links) {
            val encodedFrame = a.attr("data-frame").trim()
            if (encodedFrame.isBlank()) continue

            val frameUrl = runCatching {
                base64Decode(encodedFrame).trim()
            }.getOrNull()

            if (!frameUrl.isNullOrBlank()) {
                loadExtractor(
                    normalizeUrl(frameUrl),
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }
        }
    }

    private fun normalizeUrl(url: String): String {
        val cleanUrl = url.trim()

        return when {
            cleanUrl.startsWith("http", ignoreCase = true) -> cleanUrl
            cleanUrl.startsWith("//") -> "https:$cleanUrl"
            cleanUrl.startsWith("/") -> "$mainUrl$cleanUrl"
            else -> "$mainUrl/$cleanUrl"
        }
    }
}