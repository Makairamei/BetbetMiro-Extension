package com.youtube

object YouTubeSeeds {
    const val MAIN_URL = "https://www.youtube.com"

    val mainPage = listOf(
        YouTubeCategory(
            name = "Cerita Kamai",
            data = "$MAIN_URL/@ceritakamai",
            mode = YouTubeCategoryMode.Channel
        ),
        YouTubeCategory(
            name = "Gameplay Proplayer",
            data = "$MAIN_URL/@gameplayproplayer",
            mode = YouTubeCategoryMode.Channel,
            channelId = "UCVZAfLep-Ju-zAJsAGSVUNA"
        ),
        YouTubeCategory(
            name = "Calon Sarjana",
            data = "$MAIN_URL/@calonsarjanaid",
            mode = YouTubeCategoryMode.Channel,
            channelId = "UCkCa2NoZlJlfJlMKgZhtKOQ"
        ),
        YouTubeCategory(
            name = "Trending Indonesia",
            data = "$MAIN_URL/feed/trending?gl=ID&hl=id",
            mode = YouTubeCategoryMode.Trending
        ),
        YouTubeCategory(
            name = "Gaming Indonesia",
            data = "gaming indonesia gameplay terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Fakta & Edukasi",
            data = "fakta unik edukasi indonesia",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Teknologi Indonesia",
            data = "teknologi gadget indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Podcast Indonesia",
            data = "podcast indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Musik Indonesia",
            data = "musik indonesia official video terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Anime Indonesia",
            data = "anime indonesia review subtitle indonesia",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Kuliner Indonesia",
            data = "kuliner indonesia street food terbaru",
            mode = YouTubeCategoryMode.Search
        ),
        YouTubeCategory(
            name = "Berita Indonesia",
            data = "berita indonesia terbaru",
            mode = YouTubeCategoryMode.Search
        )
    )
}
