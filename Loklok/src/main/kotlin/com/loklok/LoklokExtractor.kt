package com.loklok

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.loklok.LoklokUtils.apiGet
import com.loklok.LoklokUtils.getLanguageName
import com.loklok.LoklokUtils.getQualityFromDefinition
import com.loklok.LoklokUtils.streamHeaders

object LoklokExtractor {
    private const val TAG = "Loklok"

    suspend fun loadLinks(
        providerName: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = runCatching { parseJson<EpisodeData>(data) }
            .onFailure { Log.e(TAG, "Invalid EpisodeData payload: ${it.message}") }
            .getOrNull() ?: return false

        val contentId = episode.id?.takeIf { it.isNotBlank() } ?: return false
        val episodeId = episode.epId ?: return false
        val category = episode.category ?: 1
        val emitted = linkedSetOf<String>()
        var found = false

        episode.definitionList.orEmpty().amap { definition ->
            val code = definition.code ?: return@amap null
            val preview = runCatching {
                val text = apiGet(
                    "${LoklokSeeds.ApiPath.PREVIEW}?category=$category&contentId=$contentId&episodeId=$episodeId&definition=$code"
                )
                parseJson<PreviewResponse>(text)?.data
            }.onFailure {
                Log.e(TAG, "previewInfo failed: ${it.message}")
            }.getOrNull()

            val mediaUrl = preview?.mediaUrl?.trim().orEmpty()
            if (mediaUrl.isBlank() || mediaUrl == "null" || mediaUrl == "undefined") return@amap null
            if (!emitted.add(mediaUrl)) return@amap null

            val current = preview?.currentDefinition ?: definition.description ?: code
            val linkType = if (mediaUrl.contains(".m3u8", ignoreCase = true) || mediaUrl.contains("m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            found = true
            callback(
                newExtractorLink(
                    providerName,
                    "$providerName $current",
                    mediaUrl,
                    linkType
                ) {
                    quality = getQualityFromDefinition(current)
                    referer = LoklokSeeds.H5_SITE
                    headers = streamHeaders()
                }
            )
        }

        episode.subtitlingList.orEmpty().forEach { subtitle ->
            val url = subtitle.subtitlingUrl?.trim().orEmpty()
            if (url.isBlank() || url == "null" || !emitted.add("sub:$url")) return@forEach
            val label = subtitle.languageAbbr?.let { getLanguageName(it) }
                ?: subtitle.language
                ?: "Subtitle"
            subtitleCallback(SubtitleFile(label, url))
        }

        return found
    }
}
