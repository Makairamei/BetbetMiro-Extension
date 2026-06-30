package com.sad25kag.alqanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URLDecoder

suspend fun MainAPI.resolveLoadLinks(
    sourceName: String,
    data: String,
    mainUrl: String,
    headers: Map<String, String>,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val links = parseEpisodeLinks(data)
    if (links.isEmpty()) return false

    var emitted = false
    val emittedUrls = linkedSetOf<String>()

    fun markEmit(link: ExtractorLink) {
        if (emittedUrls.add(link.url.substringBefore("#"))) {
            callback(link)
            emitted = true
        }
    }

    for (linkData in links) {
        val resolvedUrl = resolvePlaybackUrl(linkData.url, mainUrl, headers)
        if (resolvedUrl.isBlank() || resolvedUrl.isArchiveDownloadUrl()) continue

        val qualityInt = linkData.quality.fixQuality()

        if (resolvedUrl.contains("pixeldrain.com/api/file/", true)) {
            markEmit(newExtractorLink("Pixeldrain", "Pixeldrain", resolvedUrl) {
                this.referer = "https://pixeldrain.com/"
                this.quality = qualityInt
                this.headers = headers + mapOf("Referer" to "https://pixeldrain.com/")
            })
            continue
        }

        if (resolvedUrl.contains("mediafire.com", true)) {
            if (tryMediafire(resolvedUrl, qualityInt, headers, ::markEmit)) continue
        }

        if (resolvedUrl.contains("acefile.co", true)) {
            if (tryAcefile(resolvedUrl, qualityInt, mainUrl, headers, ::markEmit, subtitleCallback)) continue
        }

        if (resolvedUrl.contains("resharer.org", true)) {
            if (tryReshare(resolvedUrl, qualityInt, mainUrl, headers, ::markEmit, subtitleCallback)) continue
        }

        if (resolvedUrl.isPlayableMediaUrl()) {
            markEmit(newExtractorLink(sourceName, sourceName, resolvedUrl, if (resolvedUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = mainUrl
                this.quality = qualityInt
                this.headers = headers + mapOf("Referer" to mainUrl)
            })
            continue
        }

        try {
            loadExtractor(resolvedUrl, mainUrl, subtitleCallback) { link -> markEmit(link) }
        } catch (_: Exception) {
        }
    }

    return emitted
}

private fun parseEpisodeLinks(data: String): List<EpisodeLink> {
    return runCatching {
        val array = JSONArray(data)
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val rawUrl = item.optString("url").trim()
                val quality = item.optString("quality").trim()
                if (rawUrl.isNotBlank()) add(EpisodeLink(rawUrl, quality))
            }
        }
    }.getOrDefault(emptyList())
}

private suspend fun resolvePlaybackUrl(url: String, mainUrl: String, headers: Map<String, String>): String {
    val clean = url.cleanEscaped()
    if (clean.contains("ouo.io", true) || clean.contains("ouo.press", true)) {
        val sParam = Regex("[?&]s=([^&]+)").find(clean)?.groupValues?.getOrNull(1)
        if (sParam != null) return URLDecoder.decode(sParam, "UTF-8").cleanEscaped()
    }
    return clean
}

private suspend fun tryMediafire(
    url: String,
    quality: Int,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = runCatching {
        app.get(url, headers = headers, referer = url, timeout = 20000L)
    }.getOrNull() ?: return false

    val candidates = linkedSetOf<String>()
    response.document.select("a[href]").forEach { element ->
        val href = fixUrlNull(element.attr("href"))
        if (href != null) candidates.add(href)
    }

    for (candidate in candidates) {
        if (candidate.contains("mediafire.com", true) || candidate.contains("download", true)) {
            callback(newExtractorLink("MediaFire", "MediaFire", candidate, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = quality
                this.headers = headers + mapOf("Referer" to url)
            })
            return true
        }
    }
    return false
}

private suspend fun tryAcefile(
    url: String,
    quality: Int,
    mainUrl: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit,
    subtitleCallback: (SubtitleFile) -> Unit
): Boolean {
    val candidates = linkedSetOf<String>()
    candidates.add(url)

    for (candidate in candidates) {
        if (candidate.isPlayableMediaUrl()) {
            callback(newExtractorLink("AceFile", "AceFile", candidate, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = quality
                this.headers = headers + mapOf("Referer" to url)
            })
            return true
        }
    }

    return false
}

private suspend fun tryReshare(
    url: String,
    quality: Int,
    mainUrl: String,
    headers: Map<String, String>,
    callback: (ExtractorLink) -> Unit,
    subtitleCallback: (SubtitleFile) -> Unit
): Boolean {
    if (url.isPlayableMediaUrl()) {
        callback(newExtractorLink("ReShare", "ReShare", url, ExtractorLinkType.VIDEO) {
            this.referer = mainUrl
            this.quality = quality
            this.headers = headers + mapOf("Referer" to mainUrl)
        })
        return true
    }
    return false
}