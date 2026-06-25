package com.sad25kag.bioskopkeren

import android.content.Context
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

@CloudstreamPlugin
class BioskopKerenPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BioskopKeren())
        registerExtractorAPI(BioskopKerenVidHide())
    }
}

class BioskopKerenVidHide : ExtractorApi() {
    override val name = "VidHide"
    override val mainUrl = "https://vidhide.org"
    override val requiresReferer = true

    private val delegates = listOf(
        BioskopKerenVidHideCore(),
        BioskopKerenVidHideProCore(),
        BioskopKerenVidHideFilesimCore()
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val realReferer = referer ?: "http://134.209.20.140/"
        val candidates = linkedSetOf<String>()
        candidates.add(url)

        val page = runCatching {
            app.get(url, referer = realReferer, timeout = 30L)
        }.getOrNull()

        val html = page?.text?.decodeEscaped().orEmpty()
        if (html.isNotBlank()) {
            val document = Jsoup.parse(html, url)

            document.select("#servers a[data-url], a[data-url*='/embed/'], form[action*='/embed/']")
                .mapNotNull { element ->
                    listOf("data-url", "action", "href")
                        .mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                        .firstOrNull()
                }
                .mapNotNull { resolveUrl(it, url) }
                .forEach { candidates.add(it) }

            extractWindowUrl(html, "downloadURL")
                ?.let { resolveUrl(it, url) }
                ?.let { candidates.add(it) }
        }

        candidates.distinct().forEach { candidate ->
            delegates.forEach { extractor ->
                runCatching {
                    extractor.getUrl(candidate, realReferer, subtitleCallback, callback)
                }
            }
        }
    }

    private fun extractWindowUrl(html: String, key: String): String? {
        return Regex("""(?:window\.)?$key\s*=\s*["']([^"']+)["']""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.decodeEscaped()
            ?.takeIf { it.isNotBlank() }
    }

    private fun resolveUrl(raw: String?, base: String): String? {
        val clean = raw
            ?.trim()
            ?.decodeEscaped()
            ?.takeIf { it.isNotBlank() && it != "#" }
            ?: return null

        if (clean.startsWith("javascript", true)) return null

        return runCatching {
            when {
                clean.startsWith("http://", true) || clean.startsWith("https://", true) -> clean
                clean.startsWith("//") -> "https:$clean"
                clean.startsWith("/") -> {
                    val uri = URI(base)
                    "${uri.scheme ?: "https"}://${uri.host}$clean"
                }
                else -> URI(base).resolve(clean).toString()
            }
        }.getOrNull()
    }

    private fun String.decodeEscaped(): String {
        val cleaned = replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("\\u003A", ":")
            .replace("\\u0026", "&")
            .replace("\\u003D", "=")
            .replace("&amp;", "&")
            .replace("&#038;", "&")
            .replace("&quot;", "\"")

        return if (cleaned.contains("%3A%2F%2F", true) || cleaned.contains("%3C", true)) {
            runCatching { URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
        } else {
            cleaned
        }
    }
}

private class BioskopKerenVidHideCore : VidhideExtractor() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.org"
}

private class BioskopKerenVidHideProCore : VidHidePro() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.org"
    override val requiresReferer = true
}

private class BioskopKerenVidHideFilesimCore : Filesim() {
    override var name = "VidHide"
    override var mainUrl = "https://vidhide.org"
    override val requiresReferer = true
}
