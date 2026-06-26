package com.sad25kag.Donghuastream


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.extractors.StreamSB
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.newExtractorLink

open class Vtbe : ExtractorApi() {
    override var name = "Vtbe"
    override var mainUrl = "https://vtbe.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val response = app.get(url,referer=mainUrl).document
        val extractedpack =response.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data().toString()
        JsUnpacker(extractedpack).unpack()?.let { unPacked ->
            Regex("sources:\\[\\{file:\"(.*?)\"").find(unPacked)?.groupValues?.get(1)?.let { link ->
                return listOf(
                    newExtractorLink(
                        this.name,
                        this.name,
                        url = link,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer ?: ""
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return null
    }
}

class wishfast : StreamWishExtractor() {
    override var mainUrl = "https://wishfast.top"
    override var name = "StreamWish"
}

class waaw : StreamSB() {
    override var mainUrl = "https://waaw.to"
}

class FileMoonSx : Filesim() {
    override val mainUrl = "https://filemoon.sx"
    override val name = "FileMoonSx"
}


open class Ultrahd : ExtractorApi() {
    override var name = "Ultrahd Streamplay"
    override var mainUrl = "https://ultrahd.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
            val response = app.get(url,referer=mainUrl).document
            val extractedpack =response.toString()
            Regex("\\$\\.\\s*ajax\\(\\s*\\{\\s*url:\\s*\"(.*?)\"").find(extractedpack)?.groupValues?.get(1)?.let { link ->
                app.get(link).parsedSafe<Root>()?.sources?.map {
                    val m3u8= httpsify( it.file)
                    if (m3u8.contains(".mp4"))
                    {
                        callback.invoke(
                            newExtractorLink(
                                "Ultrahd Streamplay",
                                "Ultrahd Streamplay",
                                url = m3u8,
                                INFER_TYPE
                            ) {
                                this.referer = ""
                                this.quality = getQualityFromName("")
                            }
                        )
                    }
                    else
                    {
                        M3u8Helper.generateM3u8(
                            this.name,
                            m3u8,
                            "$referer",
                        ).forEach(callback)
                    }
                }
                app.get(link).parsedSafe<Root>()?.tracks?.map {
                    val langurl=it.file
                    val lang=it.label
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang,  // Use label for the name
                            langurl     // Use extracted URL
                        )
                    )
                }
            }
    }
}

class Rumble : ExtractorApi() {
    override var name = "Rumble"
    override var mainUrl = "https://rumble.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(url, referer = referer ?: "$mainUrl/")
        val document = response.document

        val playerScript = document.selectFirst("script:containsData(jwplayer)")?.data()
            ?: return

        // Extract sources (mp4 or m3u8)
        val sourceRegex = """"file"\s*:\s*"(https:[^"]+\.(?:mp4|m3u8)[^"]*)"""".toRegex()
        val sources = sourceRegex.findAll(playerScript)

        for ((index, source) in sources.withIndex()) {
            val serverIndex = index + 1
            val fileUrl = source.groupValues[1].replace("\\/", "/")
            if (fileUrl.contains(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name Video Server $serverIndex",
                        url = fileUrl,
                        INFER_TYPE
                    ) {
                        this.referer = ""
                        this.quality = getQualityFromName("")
                    }
                )
            } else {
                M3u8Helper.generateM3u8(name, fileUrl, mainUrl).forEach(callback)
            }
        }

        // Extract subtitle tracks
        val trackRegex = """"file"\s*:\s*"(https:[^"]+\.vtt[^"]*)"\s*,\s*"label"\s*:\s*"([^"]+)"""".toRegex()
        val tracks = trackRegex.findAll(playerScript)

        for (track in tracks) {
            val fileUrl = track.groupValues[1].replace("\\/", "/")
            val label = track.groupValues[2]

            subtitleCallback.invoke(
                newSubtitleFile(label, fileUrl)
            )
        }
    }
}

open class PlayStreamplay : ExtractorApi() {
    override var name = "All sub player"
    override var mainUrl = "https://play.streamplay.co.in"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, timeout = 10000).document
        val packedScript = doc.selectFirst("script:containsData(function(p,a,c,k,e,d))")?.data() ?: return
        val evalRegex = Regex("""eval\(.*?\)\)\)""", RegexOption.DOT_MATCHES_ALL)
        val packedCode = evalRegex.find(packedScript)?.value ?: return
        val unpackedJs = JsUnpacker(packedCode).unpack() ?: return
        val token = Regex("""kaken="(.*?)"""").find(unpackedJs)?.groupValues?.getOrNull(1) ?: return
        val apiUrl = "$mainUrl/api/?$token"
        val apiResponse = app.get(apiUrl, timeout = 10000)
        val response = apiResponse.parsedSafe<Response>()
        val apiText = apiResponse.text

        val headers = mapOf(
            "pragma" to "no-cache",
            "priority" to "u=0, i",
            "sec-ch-ua" to "\"Not)A;Brand\";v=\"8\", \"Chromium\";v=\"138\", \"Google Chrome\";v=\"138\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-site" to "none",
            "sec-fetch-user" to "?1",
            "upgrade-insecure-requests" to "1",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
        )

        val parsedSources = response?.sources
            .orEmpty()
            .mapNotNull { it.file.takeIf { file -> file.isNotBlank() } }

        val fallbackSources = if (parsedSources.isEmpty()) {
            Regex("""\"file\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
                .findAll(apiText)
                .mapNotNull { match -> match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } }
                .toList()
        } else {
            emptyList()
        }

        (parsedSources + fallbackSources)
            .map { httpsify(it.replace("\\/", "/")) }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { mediaUrl ->
                if (mediaUrl.contains(".mp4", true)) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            url = mediaUrl,
                            INFER_TYPE
                        ) {
                            this.referer = ""
                            this.quality = getQualityFromName("")
                        }
                    )
                } else {
                    M3u8Helper.generateM3u8(name, mediaUrl, mainUrl, headers = headers).forEach(callback)
                }
            }

        val parsedTracks = response?.tracks
            .orEmpty()
            .mapNotNull { track ->
                track.file.takeIf { it.isNotBlank() }?.let { file -> track.label to file }
            }

        val fallbackTracks = if (parsedTracks.isEmpty()) {
            Regex("""\"file\"\s*:\s*\"([^\"]+\.vtt[^\"]*)\"\s*,\s*\"label\"\s*:\s*\"([^\"]+)\"""", RegexOption.IGNORE_CASE)
                .findAll(apiText)
                .mapNotNull { match ->
                    val file = match.groupValues.getOrNull(1)?.replace("\\/", "/").orEmpty()
                    val label = match.groupValues.getOrNull(2).orEmpty()
                    file.takeIf { it.isNotBlank() }?.let { label to it }
                }
                .toList()
        } else {
            emptyList()
        }

        (parsedTracks + fallbackTracks)
            .distinctBy { it.second }
            .forEach { (label, file) ->
                subtitleCallback.invoke(
                    newSubtitleFile(
                        label.ifBlank { "Subtitle" },
                        httpsify(file)
                    )
                )
            }
    }

    data class Response(
        val query: Query? = null,
        val status: String? = null,
        val message: String? = null,
        @param:JsonProperty("embed_url")
        val embedUrl: String? = null,
        @param:JsonProperty("download_url")
        val downloadUrl: String? = null,
        val title: String? = null,
        val poster: String? = null,
        val filmstrip: String? = null,
        val sources: List<Source> = emptyList(),
        val tracks: List<Track> = emptyList(),
    )

    data class Query(
        val source: String? = null,
        val id: String? = null,
        val download: String? = null,
    )

    data class Source(
        val file: String = "",
        val type: String? = null,
        val label: String? = null,
        val default: Boolean? = null,
    )

    data class Track(
        val file: String = "",
        val label: String = "Subtitle",
        val default: Boolean? = null,
    )

}
