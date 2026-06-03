package com.PornhoarderPlugin

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class PornhoarderPlaymogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = normalizeUrl(url, mainUrl)
        val html = app.get(
            embedUrl,
            referer = referer ?: mainUrl,
            headers = headers
        ).text

        directMediaRegex.find(html.cleanEscaped())?.groupValues?.getOrNull(1)?.let { direct ->
            emitVideo(direct, embedUrl, callback)
            return
        }

        val passMatch = passMd5Regex.find(html) ?: return
        val passPath = passMatch.value.cleanEscaped()
        val expiry = passMatch.groupValues.getOrNull(1).orEmpty()
        val token = passMatch.groupValues.getOrNull(2).orEmpty()
        val passUrl = normalizeUrl(passPath, mainUrl)
        val base = app.get(
            passUrl,
            referer = embedUrl,
            headers = headers
        ).text.trim().trim('"', '\'', ' ', '\n', '\r', '\t')
            .takeIf { it.startsWith("http", ignoreCase = true) } ?: return

        val finalUrl = buildString {
            append(base)
            if (!base.substringBefore('?').endsWith(".mp4", ignoreCase = true)) {
                append("BetbetMiro")
            }
            if (token.isNotBlank() && expiry.isNotBlank()) {
                append(if (contains('?')) "&" else "?")
                append("token=").append(token)
                append("&expiry=").append(expiry).append("000")
            }
        }

        emitVideo(finalUrl, embedUrl, callback)
    }


    private fun normalizeUrl(url: String, base: String = mainUrl): String {
        val cleaned = url.cleanEscaped().trim()
        return when {
            cleaned.startsWith("http://", ignoreCase = true) ||
                cleaned.startsWith("https://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> base.trimEnd('/') + cleaned
            else -> base.trimEnd('/') + "/" + cleaned.trimStart('/')
        }
    }

    private suspend fun emitVideo(
        videoUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!videoUrl.startsWith("http", ignoreCase = true)) return
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "video/mp4,video/*;q=0.9,*/*;q=0.8"
                )
            }
        )
    }

    private companion object {
        val passMd5Regex = Regex("""/pass_md5/([^/'"\s<>]+)/([^/'"\s<>]+)""")
        val directMediaRegex = Regex("""['"](https?://[^'"\s<>]+(?:\.mp4|\.m3u8)[^'"\s<>]*)['"]""", RegexOption.IGNORE_CASE)
    }
}

private fun String.cleanEscaped(): String {
    return replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
}
