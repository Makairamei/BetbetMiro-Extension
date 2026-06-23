version = 1

cloudstream {
    authors = listOf("sad25kag")
    language = "id"
    description = "BetbetTV live TV provider. Runtime channel resolver is evidence-based from the attached Sumaleng TV APK Firebase Realtime Database configuration and scans the database tree for live stream entries."

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3
    tvTypes = listOf("Live")
    iconUrl = "https://www.google.com/s2/favicons?domain=sumaleng-tv.firebasestorage.app&sz=%size%"
    isCrossPlatform = true
}
