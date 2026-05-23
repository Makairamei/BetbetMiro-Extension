version = 2

cloudstream {
    language = "id"
    authors = listOf("BetbetMiro")
    description = "IndoAV provider for indoav.com with filters, categories, genres, search, video detail parsing, direct playback, iframe fallback, and download-host extraction."

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "NSFW"
    )

    isCrossPlatform = false
    iconUrl = "https://www.google.com/s2/favicons?domain=indoav.com&sz=%size%"
}