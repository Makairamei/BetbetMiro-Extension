package com.sad25kag.movieku

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieKuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieKuProvider())
    }
}
