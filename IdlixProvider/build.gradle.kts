version = 36

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Idlix provider temporarily disabled while unresolved Majorplay playback returns zero links from the current z2.idlixku.com play-session flow."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 0

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AsianDrama"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=z2.idlixku.com&sz=%size%"
}
