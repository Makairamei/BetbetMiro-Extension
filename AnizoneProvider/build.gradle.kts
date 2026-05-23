// use an integer for version numbers
version = 4

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "AniZone.to - anime subtitle dan multi-dubbing"
    language = "id"
    authors = listOf("BetbetMiro")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Anime")

    // Menggunakan icon asli langsung dari website AniZone
    iconUrl = "https://anizone.to/favicon.ico"
}