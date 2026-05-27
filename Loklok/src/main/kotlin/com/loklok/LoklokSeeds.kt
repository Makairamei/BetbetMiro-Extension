package com.loklok

object LoklokSeeds {
    const val API_WEB = "https://h5-api.loklok.site/cms/web"
    const val API_H5_V2 = "https://h5-api.loklok.site/cms/v2/h5"
    const val H5_SITE = "https://h5.loklok.site"
    const val IMAGE_PROXY = "https://images.weserv.nl"

    object Token {
        const val HOME = "home:"
        const val SEARCH = "search:"
    }

    object ApiPath {
        const val HOME = "homePage/getHome"
        const val SEARCH = "search/v1/searchWithKeyWord"
        const val DETAIL = "movieDrama/get"
        const val PREVIEW = "media/previewInfo"
    }

    /**
     * Fresh rows: the first rows pull Loklok's live API sections, while the
     * remaining rows are clean search-backed categories. This avoids carrying
     * old hard-coded category URLs that no longer exist on the H5 API.
     */
    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        "${Token.HOME}0" to "Loklok Featured",
        "${Token.HOME}1" to "Trending Now",
        "${Token.HOME}2" to "Recommended",
        "${Token.SEARCH}Movie" to "Movies",
        "${Token.SEARCH}TV Series" to "TV Series",
        "${Token.SEARCH}Anime" to "Anime",
        "${Token.SEARCH}Korean Drama" to "K-Drama",
        "${Token.SEARCH}Chinese Drama" to "C-Drama",
        "${Token.SEARCH}Japanese Drama" to "J-Drama",
        "${Token.SEARCH}Thai Drama" to "Thai Drama",
        "${Token.SEARCH}Indonesian" to "Indonesian",
        "${Token.SEARCH}Hollywood" to "Hollywood",
        "${Token.SEARCH}Action" to "Action",
        "${Token.SEARCH}Romance" to "Romance",
        "${Token.SEARCH}Comedy" to "Comedy",
        "${Token.SEARCH}Horror" to "Horror"
    )
}
