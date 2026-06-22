package com.sad25kag.cinemaindo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CinemaIndoPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CinemaIndo())
    }
}
