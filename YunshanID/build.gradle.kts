plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    mainClassName = "com.betbet.yunshanid.YunshanIDPlugin"
    language = "id"
    description = "Donghua & Anime provider YunshanID"
    authors = listOf("Betbet")
}

android {
    namespace = "com.betbet.yunshanid"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
    }
}