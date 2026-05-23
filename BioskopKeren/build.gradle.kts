version = 2

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "BioskopKeren provider for bioskop-keren.com with movie, Korean series, western series, category rows, search, detail parsing, iframe fallback, and direct playback extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=bioskop-keren.com&sz=%size%"
}