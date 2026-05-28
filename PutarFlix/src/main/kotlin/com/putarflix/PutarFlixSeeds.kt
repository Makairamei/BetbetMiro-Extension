package com.putarflix

internal object PutarFlixSeeds {
    const val MAIN_URL = "https://putarflix.com"
    const val SITE_NAME = "PutarFlix"
    const val LANGUAGE = "id"

    val mainPages = listOf(
        PutarFlixCategory("/", "Beranda"),
        PutarFlixCategory("/category/film-bioskop-terbaru/", "Film Bioskop Terbaru"),
        PutarFlixCategory("/category/film-indonesia-terbaru/", "Film Indonesia"),
        PutarFlixCategory("/category/film-china-terbaru/", "Film China"),
        PutarFlixCategory("/category/film-india-terbaru/", "Film India"),
        PutarFlixCategory("/category/series-indonesia/", "Series Indonesia"),
        PutarFlixCategory("/category/tv-show/", "TV Show"),
        PutarFlixCategory("/category/box-office/", "Box Office"),
        PutarFlixCategory("/category/action/", "Action"),
        PutarFlixCategory("/category/drama/", "Drama"),
        PutarFlixCategory("/category/horror/", "Horror"),
        PutarFlixCategory("/category/thriller/", "Thriller"),
        PutarFlixCategory("/category/comedy/", "Comedy"),
        PutarFlixCategory("/category/romance/", "Romance"),
        PutarFlixCategory("/category/fantasy/", "Fantasy"),
        PutarFlixCategory("/category/crime/", "Crime"),
        PutarFlixCategory("/category/mystery/", "Mystery"),
        PutarFlixCategory("/category/science-fiction/", "Science Fiction"),
        PutarFlixCategory("/category/film-semi/", "Film Semi"),
        PutarFlixCategory("/category/vivamax/", "Vivamax")
    )

    val playerNumbers = listOf("1", "2", "3", "4")

    val ajaxActions = listOf(
        "doo_player_ajax",
        "dooplay_player",
        "player_ajax",
        "get_player",
        "getPlayer"
    )
}
