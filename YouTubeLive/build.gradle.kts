// use an integer for version numbers
version = 13

cloudstream {
    description = "Videos, playlists and channels from YouTube"
    authors = listOf("BetbetMiro")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Others")

    requiresResources = true

    iconUrl = "https://www.youtube.com/s/desktop/711fd789/img/logos/favicon_144x144.png"
}

android {
    namespace = "com.youtube.live"
    compileSdk = 34

defaultConfig {
        minSdk = 21
        targetSdk = 34
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.25.2")
}
