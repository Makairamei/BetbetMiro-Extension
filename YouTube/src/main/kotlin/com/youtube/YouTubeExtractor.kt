package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.youtube.YouTubeUtils.canonicalWatchUrl
import com.youtube.YouTubeUtils.embedUrl
import com.youtube.YouTubeUtils.videoIdFromUrl

object YouTubeExtractor {
    suspend fun loadLinks(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = videoIdFromUrl(data)
        val candidates = linkedSetOf<String>()
        if (videoId != null) {
            candidates.add(canonicalWatchUrl(videoId))
            candidates.add(embedUrl(videoId))
            candidates.add("https://youtu.be/$videoId")
        } else if (data.startsWith("http", true)) {
            candidates.add(data)
        }

        var found = false
        candidates.forEach { url ->
            runCatching {
                loadExtractor(url, YouTubeSeeds.MAIN_URL, subtitleCallback) { link ->
                    found = true
                    callback.invoke(link)
                }
            }
        }
        return found
    }
}
