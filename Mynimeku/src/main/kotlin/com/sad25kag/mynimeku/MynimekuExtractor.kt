package com.sad25kag.mynimeku

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

object MynimekuExtractor {
    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bundle = MynimekuUtils.decodeBundle(data)
        val pageUrl = bundle?.first ?: MynimekuUtils.normalizeUrl(data)
        val emitted = linkedSetOf<String>()
        var hasLink = false

        fun emitOnce(link: ExtractorLink) {
            val key = "${link.source.lowercase()}|${MynimekuUtils.canonical(link.url)}"
            if (emitted.add(key)) {
                hasLink = true
                callback(link)
            }
        }

        suspend fun process(player: MynimekuPlayer, fallbackReferer: String) {
            val url = MynimekuUtils.resolveUrlOrNull(player.url, fallbackReferer) ?: return
            if (!MynimekuUtils.isPlayableCandidate(url)) return
            val normalized = player.copy(url = url, sourcePage = player.sourcePage.ifBlank { fallbackReferer })

            when {
                MynimekuUtils.isDirectMedia(url) -> emitDirect(normalized, fallbackReferer, ::emitOnce)
                MynimekuUtils.isMyPlayerku(url) -> resolveMyPlayerku(normalized, subtitleCallback, ::emitOnce)
                MynimekuUtils.isFileMydriveku(url) -> {
                    MynimekuUtils.mydrivekuToPlayer(url)?.let { converted ->
                        resolveMyPlayerku(normalized.copy(url = converted), subtitleCallback, ::emitOnce)
                    }
                }
                else -> runCatching { loadExtractor(url, fallbackReferer, subtitleCallback, ::emitOnce) }
            }
        }

        bundle?.second?.forEach { process(it, pageUrl) }
        if (hasLink) return true

        val page = runCatching {
            app.get(
                pageUrl,
                referer = MynimekuSeeds.MAIN_URL,
                headers = MynimekuUtils.pageHeaders(MynimekuSeeds.MAIN_URL),
                timeout = 25L
            ).document
        }.getOrNull()

        val players = page?.let { MynimekuParser.players(it, pageUrl) }.orEmpty()
        players.forEach { process(it, pageUrl) }
        if (hasLink) return true

        process(MynimekuPlayer(pageUrl, "Fallback", "page", pageUrl), MynimekuSeeds.MAIN_URL)
        return hasLink
    }

    private suspend fun resolveMyPlayerku(
        player: MynimekuPlayer,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val referer = player.sourcePage.ifBlank { MynimekuSeeds.MAIN_URL }
        val response = runCatching {
            app.get(
                player.url,
                referer = referer,
                headers = MynimekuUtils.pageHeaders(referer),
                timeout = 25L
            )
        }.getOrNull() ?: return false

        val html = response.text
        val title = Regex("""(?is)<title>(.*?)</title>""")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { Jsoup.parse(it).text().trim() }
            ?.takeIf { it.isNotBlank() }
            ?: player.label.ifBlank { "MyPlayerku" }

        var emitted = false
        val candidates = MynimekuParser.mediaFromRawText(html)
            .sortedWith(compareByDescending<String> { mediaPriority(it, player.label) }.thenBy { it })

        for (media in candidates) {
            when {
                MynimekuUtils.isDirectMedia(media) -> {
                    emitDirect(player.copy(url = media, label = player.label.ifBlank { title }), player.url, callback)
                    emitted = true
                }
                MynimekuUtils.isMyPlayerku(media) && !MynimekuUtils.canonical(media).equals(MynimekuUtils.canonical(player.url), true) -> {
                    if (resolveMyPlayerku(player.copy(url = media), subtitleCallback, callback)) emitted = true
                }
                else -> runCatching {
                    loadExtractor(media, player.url, subtitleCallback, callback)
                    emitted = true
                }
            }
        }

        if (!emitted) {
            runCatching {
                loadExtractor(player.url, referer, subtitleCallback, callback)
                emitted = true
            }
        }

        return emitted
    }

    private suspend fun emitDirect(
        player: MynimekuPlayer,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val media = player.url
        if (!MynimekuUtils.isDirectMedia(media)) return false
        val isM3u8 = MynimekuUtils.isM3u8(media)
        val sourceName = when {
            MynimekuUtils.isGoogleDriveApiMedia(media) -> "MyNimeku Drive"
            MynimekuUtils.isWorkersMedia(media) -> "MyNimeku Cloud"
            else -> "MyNimeku"
        }
        val quality = MynimekuUtils.qualityFrom(player.label).takeIf { it != Qualities.Unknown.value }
            ?: MynimekuUtils.qualityFrom(media)

        callback(
            newExtractorLink(
                source = sourceName,
                name = listOf(sourceName, player.label).filter { it.isNotBlank() }.joinToString(" - "),
                url = media,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = if (MynimekuUtils.isGoogleDriveApiMedia(media) || MynimekuUtils.isWorkersMedia(media)) "" else referer
                this.quality = quality
                this.headers = if (MynimekuUtils.isGoogleDriveApiMedia(media) || MynimekuUtils.isWorkersMedia(media)) {
                    MynimekuUtils.mediaHeaders()
                } else {
                    MynimekuUtils.mediaHeaders(referer)
                }
            }
        )
        return true
    }

    private fun mediaPriority(url: String, label: String): Int {
        val lower = url.lowercase()
        val base = when {
            MynimekuUtils.isGoogleDriveApiMedia(url) -> 1000
            MynimekuUtils.isWorkersMedia(url) -> 950
            MynimekuUtils.isDirectMedia(url) -> 900
            lower.contains("googlevideo.com") -> 850
            else -> 100
        }
        val quality = when (MynimekuUtils.qualityFrom(label)) {
            Qualities.P2160.value -> 40
            Qualities.P1080.value -> 30
            Qualities.P720.value -> 20
            Qualities.P480.value -> 10
            Qualities.P360.value -> 5
            else -> 0
        }
        return base + quality
    }
}
