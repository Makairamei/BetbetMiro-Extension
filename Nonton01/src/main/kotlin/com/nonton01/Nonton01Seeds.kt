package com.nonton01

object Nonton01Seeds {
    const val MAIN_URL = "https://01ntn.cc"
    const val SOURCE_URL = "https://91.208.197.221"
    const val PUBLIC_URL = "https://01nonton.com"
    const val LATEST_LINK_URL = "https://idmax.one/01nonton/"

    /**
     * HAR 2026-06-13 evidence:
     * - 01ntn.cc renders homepage/category/listing pages.
     * - Listing cards/detail/admin-ajax still resolve through 91.208.197.221.
     * - 01nonton.com is a gateway/landing page, not a movie listing source.
     */
    val MIRROR_URLS = listOf(
        MAIN_URL,
        SOURCE_URL
    )

    val KNOWN_HOSTS = setOf(
        "01ntn.cc",
        "www.01ntn.cc",
        "91.208.197.221"
    )

    fun mainPageRows(): Array<Pair<String, String>> = arrayOf(
        paths("/page/%d/") to "Upload Terbaru",
        paths("/movies/page/%d/") to "Movies",
        paths("/tvshows/page/%d/") to "TV Serial",
        paths("/genre/action/page/%d/") to "Action",
        paths("/genre/adventure/page/%d/") to "Adventure",
        paths("/genre/animation/page/%d/") to "Animation",
        paths("/genre/comedy/page/%d/") to "Comedy",
        paths("/genre/crime/page/%d/") to "Crime",
        paths("/genre/documentary/page/%d/") to "Documentary",
        paths("/genre/drakor/page/%d/") to "Drakor",
        paths("/genre/drama/page/%d/") to "Drama",
        paths("/genre/fantasy/page/%d/") to "Fantasy",
        paths("/genre/horror/page/%d/") to "Horror",
        paths("/genre/mystery/page/%d/") to "Mystery",
        paths("/genre/romance/page/%d/") to "Romance",
        paths("/genre/science-fiction/page/%d/") to "Science Fiction",
        paths("/genre/thriller/page/%d/") to "Thriller"
    )

    private fun paths(vararg values: String): String = "paths:" + values.joinToString("|")
}
