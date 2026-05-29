package com.duniafilm21

internal data class DuniaFilm21Category(
    val path: String,
    val name: String
)

internal data class DuniaFilm21Server(
    val label: String,
    val url: String,
    val referer: String,
    val source: String = "html"
)

internal data class DuniaFilm21AjaxPlayer(
    val postId: String,
    val type: String,
    val nume: String,
    val label: String
)
