// Extractors.kt
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
        val document = app.get(url, referer = referer).document
        val links = document.select("ul#dropdown-server li a")
        
        // Menggunakan for-loop, aman dan stabil
        for (a in links) {
            val encodedFrame = a.attr("data-frame")
            if (encodedFrame.isNotBlank()) {
                loadExtractor(
                    base64Decode(encodedFrame),
                    "$mainUrl/",
                    subtitleCallback,
                    callback
                )
            }
        }
    }
}