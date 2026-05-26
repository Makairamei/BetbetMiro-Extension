package com.samehadaku

object SamehadakuSeeds {
    const val MAIN_URL = "https://v2.samehadaku.how"
    const val DIRECT_URL = "https://samehadaku.care"
    const val BATCH_URL = "https://v1.samehadaku.how/batch/"

    val websiteGenres = listOf(
        "Fantasy" to "fantasy",
        "Action" to "action",
        "Adventure" to "adventure",
        "Comedy" to "comedy",
        "Shounen" to "shounen",
        "School" to "school",
        "Romance" to "romance",
        "Drama" to "drama",
        "Supernatural" to "supernatural",
        "Isekai" to "isekai",
        "Sci-Fi" to "sci-fi",
        "Seinen" to "seinen",
        "Reincarnation" to "reincarnation",
        "Super Power" to "super-power",
        "Historical" to "historical",
        "Mystery" to "mystery",
        "Harem" to "harem",
        "Slice of Life" to "slice-of-life",
        "Ecchi" to "ecchi",
        "Sports" to "sports"
    )

    val mainPage = buildList {
        add(SamehadakuCategory("Anime Terbaru", "$MAIN_URL/%page%", true, SamehadakuCategoryMode.HomeLatest))
        add(SamehadakuCategory("Top 10 Minggu Ini", "$MAIN_URL/", false, SamehadakuCategoryMode.HomeTop))
        add(SamehadakuCategory("Project Movie Samehadaku", "$MAIN_URL/", false, SamehadakuCategoryMode.HomeMovie))
        add(SamehadakuCategory("Daftar Anime", "$MAIN_URL/daftar-anime-2/%page%"))
        add(SamehadakuCategory("Batch", BATCH_URL, false, SamehadakuCategoryMode.Listing))
        add(SamehadakuCategory("Jadwal Rilis", "$MAIN_URL/jadwal-rilis/%page%", true, SamehadakuCategoryMode.Schedule))

        add(SamehadakuCategory("Status: Ongoing", "$MAIN_URL/anime-status/ongoing/%page%"))
        add(SamehadakuCategory("Status: Completed", "$MAIN_URL/anime-status/completed/%page%"))

        add(SamehadakuCategory("Type: TV", "$MAIN_URL/anime-type/tv/%page%"))
        add(SamehadakuCategory("Type: OVA", "$MAIN_URL/anime-type/ova/%page%"))
        add(SamehadakuCategory("Type: ONA", "$MAIN_URL/anime-type/ona/%page%"))
        add(SamehadakuCategory("Type: Special", "$MAIN_URL/anime-type/special/%page%"))
        add(SamehadakuCategory("Type: Movie", "$MAIN_URL/anime-type/movie/%page%"))

        websiteGenres.forEach { (name, slug) ->
            add(SamehadakuCategory("Genre: $name", "$MAIN_URL/genre/$slug/%page%"))
        }
    }
}
