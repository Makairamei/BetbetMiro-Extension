package com.sad25kag.mynimeku

import com.lagradost.cloudstream3.TvType

data class MynimekuCard(
    val title: String,
    val url: String,
    val posterUrl: String?,
    val tvType: TvType,
    val episode: Int? = null
)

data class MynimekuPlayer(
    val url: String,
    val label: String = "",
    val group: String = "",
    val sourcePage: String = ""
)

data class MynimekuMedia(
    val url: String,
    val label: String,
    val referer: String,
    val source: String
)
