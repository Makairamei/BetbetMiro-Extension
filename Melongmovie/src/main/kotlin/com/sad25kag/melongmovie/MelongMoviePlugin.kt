package com.melongmovie

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager.registerMainAPI

@CloudstreamPlugin
class MelongMoviePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MelongMovieProvider())
    }
}
