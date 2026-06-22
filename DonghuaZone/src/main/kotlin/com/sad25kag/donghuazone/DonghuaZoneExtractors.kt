package com.sad25kag.donghuazone

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

class DonghuaZoneOkRu : ExtractorApi() {
    override var name = "OkRu"
    override var mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url
            .replace("/video/", "/videoembed/")
            .replace("/videoembed/videoembed/", "/videoembed/")
            .replace("https://odnoklassniki.ru", "https://ok.ru")

        val headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: mainUrl),
            "Origin" to mainUrl
        )

        val body = runCatching {
            app.get(embedUrl, headers = headers, referer = referer ?: mainUrl).text
        }.getOrNull().orEmpty().decodeOkRuText()

        Regex("""\{\s*['\"]name['\"]\s*:\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]url['\"]\s*:\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
            .findAll(body)
            .forEach { match ->
                val label = match.groupValues.getOrNull(1).orEmpty().decodeOkRuText()
                val videoUrl = match.groupValues.getOrNull(2).orEmpty().decodeOkRuText().normalizeOkRuUrl()
                if (videoUrl.isBlank()) return@forEach

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = getQualityFromName(label)
                        this.headers = headers + mapOf("Referer" to embedUrl)
                    }
                )
            }
    }

    private fun String.normalizeOkRuUrl(): String {
        return when {
            startsWith("//") -> "https:$this"
            startsWith("http://") || startsWith("https://") -> this
            else -> ""
        }
    }

    private fun String.decodeOkRuText(): String {
        return replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
    }
}
