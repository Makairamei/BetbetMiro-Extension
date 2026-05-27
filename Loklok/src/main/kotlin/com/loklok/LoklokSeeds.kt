package com.loklok

object LoklokSeeds {
    /**
     * Runtime note:
     * The previous H5 endpoint (h5-api.loklok.site/cms/web) compiled, but runtime
     * failed in Cloudstream with API call failed for homePage/getHome. The public
     * LokLok API references still point to the mobile app endpoint, so v4 restores
     * that endpoint as the primary source and keeps H5 only as fallback.
     */
    const val API_APP = "https://ga-mobile-api.loklok.tv/cms/app"
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
        const val SEARCH_V1 = "search/v1/searchWithKeyWord"
        const val SEARCH_V2 = "search/v2/searchWithKeyWord"
        const val DETAIL = "movieDrama/get"
        const val PREVIEW = "media/previewInfo"
    }

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

    fun fallbackItems(query: String? = null): List<MediaItem> {
        val all = listOf(
            MediaItem(id = "8084", category = 0, domainType = 0, title = "Spider-Man: No Way Home", name = "Spider-Man: No Way Home", year = 2021),
            MediaItem(id = "6432", category = 1, domainType = 1, title = "Demon Slayer: Kimetsu no Yaiba", name = "Demon Slayer: Kimetsu no Yaiba", year = 2019),
            MediaItem(id = "2542", category = 1, domainType = 1, title = "Demon Slayer", name = "Demon Slayer", year = 2019)
        )
        val q = query.orEmpty().lowercase().trim()
        if (q.isBlank()) return all
        val filtered = all.filter { item ->
            val title = listOfNotNull(item.title, item.name).joinToString(" ").lowercase()
            title.contains(q) || q.contains("spider") || q.contains("demon") || q.length <= 4
        }
        return filtered.ifEmpty { all }
    }
}
