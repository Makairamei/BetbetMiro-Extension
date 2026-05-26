version = 33

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "Oploverz provider for anime.oploverz.ac with source-backed anime categories, metadata, episode loading, and Oploverz host extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=anime.oploverz.ac&sz=%size%"
}
