package com.sad25kag.drakorasia

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

/**
 * DrakorAsia server options expose short.ink player values.
 * HAR evidence shows short.ink/<id> resolves to abyssplayer.com/<id>.
 *
 * This extractor intentionally does not emit abyssplayer.com as a video link.
 * It only normalizes the dynamic HOST option into the real iframe/player URL,
 * then delegates the Abyss player URL to CloudStream's extractor chain.
 */
class DrakorAsiaShortInkExtractor : ExtractorApi() {
    override var name = "DrakorAsia ShortInk"
    override var mainUrl = "https://short.ink"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val abyssUrl = url.toAbyssPlayerUrl() ?: return
        loadExtractor(
            abyssUrl,
            ABYSS_REFERER,
            subtitleCallback,
            callback
        )
    }

    private fun String.toAbyssPlayerUrl(): String? {
        val clean = replace("\\/", "/").trim().trim('"', '\'', ',', ';')
        val host = runCatching { java.net.URI(clean).host.orEmpty().lowercase() }.getOrDefault("")
        if (!host.endsWith("short.ink")) return null
        val id = clean.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
            ?: return null
        return "https://abyssplayer.com/$id"
    }

    companion object {
        private const val ABYSS_REFERER = "https://abyssplayer.com/"
    }
}
