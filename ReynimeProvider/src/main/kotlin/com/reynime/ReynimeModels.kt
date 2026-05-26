package com.reynime

data class ReynimePlaybackData(
    val pageUrl: String,
    val episodeId: String? = null,
    val seriesId: String? = null,
    val episodeNumber: String? = null,
    val seedSlug: String? = null,
    val title: String? = null
)

data class ReynimeBackendEpisode(
    val id: String? = null,
    val title: String? = null,
    val episodeNumber: String? = null,
    val urls: List<String> = emptyList()
)

data class ReynimeSeedEntry(
    val id: Int,
    val title: String,
    val url: String,
    val poster: String? = null,
    val type: String = "Anime"
)
