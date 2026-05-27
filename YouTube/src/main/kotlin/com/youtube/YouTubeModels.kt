package com.youtube

data class YouTubeCategory(
    val name: String,
    val data: String,
    val mode: YouTubeCategoryMode,
    val channelId: String? = null,
    val paged: Boolean = false
)

enum class YouTubeCategoryMode {
    Channel,
    Search,
    Trending
}

data class YouTubeVideo(
    val id: String,
    val title: String,
    val url: String,
    val poster: String? = null,
    val channel: String? = null,
    val description: String? = null,
    val published: String? = null,
    val views: String? = null,
    val duration: String? = null
)

data class YouTubeMeta(
    val title: String,
    val url: String,
    val poster: String? = null,
    val description: String? = null,
    val channel: String? = null,
    val published: String? = null,
    val tags: List<String> = emptyList()
)
