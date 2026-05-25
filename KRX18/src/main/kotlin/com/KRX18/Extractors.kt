package com.KRX18

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

class PlayKrx18 : ExtractorApi() {
    override val name = "PlayKrx18"
    override val mainUrl = "https://playkrx18.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = url.cleanEscaped()
        val pageReferer = referer ?: "https://krx18.com/"

        val response = runCatching {
            app.get(
                fixedUrl,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to pageReferer
                ),
                timeout = 20L
            )
        }.getOrNull() ?: return

        val html = response.text.cleanEscaped()
        val links = linkedSetOf<String>()

        if (html.trimStart().startsWith("#EXTM3U")) {
            emitPlayable(fixedUrl, pageReferer, callback)
            return
        }

        extractPlayableUrls(html).forEach { links.add(it) }

        response.document.select("source[src], video[src], iframe[src], iframe[data-src], a[href]").forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("href") }
                .cleanEscaped()

            if (raw.isNotBlank()) links.add(resolveUrl(raw, fixedUrl))
        }

        val unpacked = runCatching {
            if (!getPacked(html).isNullOrEmpty()) getAndUnpack(html) else null
        }.getOrNull()

        if (!unpacked.isNullOrBlank()) {
            extractPlayableUrls(unpacked.cleanEscaped()).forEach { links.add(it) }
        }

        links
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { it.startsWith("http", true) }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) }
            .distinct()
            .forEach { stream -> emitPlayable(stream, fixedUrl, callback) }
    }

    private suspend fun emitPlayable(
        streamUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = streamUrl.cleanEscaped().replace(".txt", ".m3u8")

        if (clean.contains(".m3u8", true)) {
            generateM3u8(
                source = name,
                streamUrl = clean,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer,
                    "Origin" to mainUrl
                )
            ).forEach(callback)
            return
        }

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = clean,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = getQualityFromName(clean).takeIf { it != Qualities.Unknown.value }
                    ?: qualityFromUrl(clean)
            }
        )
    }

    private fun extractPlayableUrls(text: String): List<String> {
        val urls = linkedSetOf<String>()
        val clean = text.cleanEscaped()

        Regex(
            """https?://[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { it.value.cleanEscaped().replace(".txt", ".m3u8") }
            .forEach { urls.add(it) }

        Regex(
            """//[^"'\\\s<>]+?\.(?:m3u8|mp4|webm|txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map { "https:${it.value.cleanEscaped().replace(".txt", ".m3u8")}" }
            .forEach { urls.add(it) }

        Regex(
            """https?%3A%2F%2F[^"'\\\s<>]+?(?:\.m3u8|\.mp4|\.webm|\.txt)[^"'\\\s<>]*""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .map {
                runCatching { URLDecoder.decode(it.value, "UTF-8") }.getOrDefault(it.value)
            }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .forEach { urls.add(it) }

        Regex(
            """(?:file|src|source|url|videoUrl|video_url|hls|hlsUrl|hls_url)\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(clean)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .map { it.cleanEscaped().replace(".txt", ".m3u8") }
            .filter { it.contains(".m3u8", true) || it.contains(".mp4", true) || it.contains(".webm", true) }
            .forEach { urls.add(resolveUrl(it, mainUrl)) }

        return urls.toList()
    }

    private fun resolveUrl(raw: String, baseUrl: String): String {
        val clean = raw.cleanEscaped()
        return when {
            clean.startsWith("http", true) -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> mainUrl.trimEnd('/') + clean
            else -> baseUrl.substringBeforeLast("/") + "/" + clean.trimStart('/')
        }
    }

    private fun qualityFromUrl(url: String): Int {
        return when {
            url.contains("2160", true) || url.contains("4k", true) -> Qualities.P2160.value
            url.contains("1080", true) -> Qualities.P1080.value
            url.contains("720", true) -> Qualities.P720.value
            url.contains("480", true) -> Qualities.P480.value
            url.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun String.cleanEscaped(): String {
        return this
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }
}
