package com.sad25kag.doronime

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray

class DoronimeOkRu : ExtractorApi() {
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

        val headers = mapOf(
            "Accept" to "*/*",
            "Origin" to mainUrl,
            "User-Agent" to USER_AGENT,
            "Referer" to embedUrl
        )

        val body = runCatching {
            app.get(embedUrl, headers = headers, referer = referer ?: mainUrl).text.decodePlayerText()
        }.getOrNull().orEmpty()

        val videos = Regex(""""videos"\s*:\s*(\[[^]]+])""")
            .find(body)
            ?.groupValues
            ?.getOrNull(1)
            ?.let(::parseVideos)
            .orEmpty()

        videos.forEach { video ->
            val videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            val quality = video.name.uppercase()
                .replace("MOBILE", "144p")
                .replace("LOWEST", "240p")
                .replace("LOW", "360p")
                .replace("SD", "480p")
                .replace("HD", "720p")
                .replace("FULL", "1080p")
                .replace("QUAD", "1440p")
                .replace("ULTRA", "2160p")

            callback(
                newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                    this.quality = getQualityFromName(quality)
                    this.headers = headers
                }
            )
        }
    }

    private fun parseVideos(value: String): List<OkRuVideo> {
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val label = item.optString("name").trim()
                    val url = item.optString("url").trim()
                    if (label.isNotBlank() && url.isNotBlank()) add(OkRuVideo(label, url))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun String.decodePlayerText(): String {
        return replace("\\u002F", "/")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
    }

    private data class OkRuVideo(val name: String, val url: String)
}
