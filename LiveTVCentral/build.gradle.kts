version = 1

cloudstream {
    authors = listOf("sad25kag")
    language = "en"
    description = "LiveTVCentral live TV provider for selected country pages. Parses country cards from livetvcentral.com and resolves source-backed player/video URLs without private tokens, DRM bypass, proxy, or restreaming."

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=livetvcentral.com&sz=%size%"
    isCrossPlatform = true
}
