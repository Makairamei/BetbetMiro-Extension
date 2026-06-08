package com.sad25kag.movieon21

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MovieOn21Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MovieOn21())
    }
}
