package com.sad25kag.mynimeku

object MynimekuSeeds {
    const val MAIN_URL = "https://www.mynimeku.com"
    const val PLAYER_HOST = "players.myplayerku.my.id"
    const val FILE_HOST = "file.mydriveku.my.id"

    val MAIN_PAGE = listOf(
        "full-list/mix/o:popular/" to "Popular",
        "full-list/mix/s:completed~t:BD,LA,MOVIE,ONA,OVA,SPECIAL,TV/" to "Completed",
        "full-list/mix/s:on-going~t:BD,LA,MOVIE,ONA,OVA,SPECIAL,TV/" to "On-Going",
        "latest-series/" to "Latest",
        "full-list/mix/t:TV/" to "TV",
        "full-list/mix/t:BD/" to "BD",
        "full-list/mix/t:MOVIE/" to "Movie",
        "full-list/mix/t:ONA/" to "ONA",
        "full-list/mix/t:OVA/" to "OVA",
        "full-list/mix/t:SPECIAL/" to "Special",
        "full-list/mix/t:LA/" to "LA"
    )

    val AD_HOST_KEYWORDS = listOf(
        "odqghulazoz",
        "guidepaparazzisurface",
        "wpadmngr",
        "doubleclick",
        "googlesyndication",
        "google-analytics",
        "googletagmanager",
        "histats",
        "popcash",
        "adsterra",
        "adnxs",
        "cloudflareinsights"
    )

    val SOCIAL_HOST_KEYWORDS = listOf(
        "facebook.com",
        "twitter.com",
        "x.com",
        "instagram.com",
        "telegram",
        "whatsapp",
        "youtube.com",
        "reddit.com",
        "trakteer.id"
    )
}
