package com.duniafilm21

internal object DuniaFilm21Seed {
    const val MAIN_URL = "http://178.128.28.74"
    const val SITE_NAME = "DuniaFilm21"
    const val LANGUAGE = "id"

    val mirrors = listOf(
        MAIN_URL,
        "https://duniafilm.id"
    )

    val mainPages = listOf(
        DuniaFilm21Category("/", "Film Terbaru"),
        DuniaFilm21Category("/movie/", "Movie"),
        DuniaFilm21Category("/tvshows/", "Drama / Series"),
        DuniaFilm21Category("/episode/", "Episode Terbaru"),
        DuniaFilm21Category("/genre/action/", "Action"),
        DuniaFilm21Category("/genre/action-adventure/", "Action & Adventure"),
        DuniaFilm21Category("/genre/adventure/", "Adventure"),
        DuniaFilm21Category("/genre/animation/", "Animation"),
        DuniaFilm21Category("/genre/comedy/", "Comedy"),
        DuniaFilm21Category("/genre/crime/", "Crime"),
        DuniaFilm21Category("/genre/documentary/", "Documentary"),
        DuniaFilm21Category("/genre/drama/", "Drama"),
        DuniaFilm21Category("/genre/family/", "Family"),
        DuniaFilm21Category("/genre/fantasy/", "Fantasy"),
        DuniaFilm21Category("/genre/history/", "History"),
        DuniaFilm21Category("/genre/horror/", "Horror"),
        DuniaFilm21Category("/genre/music/", "Music"),
        DuniaFilm21Category("/genre/mystery/", "Mystery"),
        DuniaFilm21Category("/genre/romance/", "Romance"),
        DuniaFilm21Category("/genre/sci-fi-fantasy/", "Sci-Fi & Fantasy"),
        DuniaFilm21Category("/genre/science-fiction/", "Science Fiction"),
        DuniaFilm21Category("/genre/thriller/", "Thriller"),
        DuniaFilm21Category("/genre/tv-movie/", "TV Movie"),
        DuniaFilm21Category("/genre/war/", "War"),
        DuniaFilm21Category("/genre/western/", "Western"),
        DuniaFilm21Category("/genre/lk21/", "LK21"),
        DuniaFilm21Category("/genre/18-lk21-semi/", "18+ LK21 Semi"),
        DuniaFilm21Category("/genre/adult-18/", "Adult 18+"),
        DuniaFilm21Category("/genre/dewasa/", "Dewasa"),
        DuniaFilm21Category("/genre/film-semi/", "Film Semi"),
        DuniaFilm21Category("/genre/korean-adult/", "Korean Adult"),
        DuniaFilm21Category("/genre/semi/", "Semi"),
        DuniaFilm21Category("/genre/semi-japan/", "Semi Japan"),
        DuniaFilm21Category("/genre/semi-korea/", "Semi Korea"),
        DuniaFilm21Category("/country/indonesia/", "Indonesia"),
        DuniaFilm21Category("/country/korea/", "Korea"),
        DuniaFilm21Category("/country/japan/", "Japan"),
        DuniaFilm21Category("/country/china/", "China"),
        DuniaFilm21Category("/country/thailand/", "Thailand"),
        DuniaFilm21Category("/country/philippines/", "Philippines"),
        DuniaFilm21Category("/country/india/", "India"),
        DuniaFilm21Category("/country/usa/", "USA"),
        DuniaFilm21Category("/country/united-states/", "United States"),
        DuniaFilm21Category("/country/united-kingdom/", "United Kingdom"),
        DuniaFilm21Category("/country/hong-kong/", "Hong Kong"),
        DuniaFilm21Category("/country/canada/", "Canada"),
        DuniaFilm21Category("/year/2026/", "2026"),
        DuniaFilm21Category("/year/2025/", "2025"),
        DuniaFilm21Category("/year/2024/", "2024"),
        DuniaFilm21Category("/year/2023/", "2023"),
        DuniaFilm21Category("/year/2022/", "2022"),
        DuniaFilm21Category("/year/2021/", "2021"),
        DuniaFilm21Category("/year/2020/", "2020"),
        DuniaFilm21Category("/year/2019/", "2019"),
        DuniaFilm21Category("/year/2018/", "2018"),
        DuniaFilm21Category("/year/2017/", "2017"),
        DuniaFilm21Category("/year/2016/", "2016"),
        DuniaFilm21Category("/year/2015/", "2015")
    )

    val playerNumbers = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")

    val ajaxActions = listOf(
        "doo_player_ajax",
        "dooplay_player",
        "dt_player_ajax",
        "muvipro_player_content",
        "player_ajax",
        "player_ajax_request",
        "get_player",
        "get_video",
        "load_player",
        "fetch_player",
        "ajax_player",
        "player_content",
        "df21_player",
        "duniafilm21_player"
    )
}
