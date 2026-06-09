// use an integer for version numbers
version = 2

cloudstream {
    description = "Astronime - anime subtitle Indonesia, HAR-backed player_ajax and Turbovid HLS flow"
    authors = listOf("sad25kag")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA"
    )

    language = "id"
    isCrossPlatform = false
}
