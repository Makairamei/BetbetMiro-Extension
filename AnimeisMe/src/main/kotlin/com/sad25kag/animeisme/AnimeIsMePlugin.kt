package com.sad25kag.animeisme

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeIsMePlugin : Plugin() {
    override fun load(context: android.content.Context) {
        registerMainAPI(AnimeIsMe())
    }
}
