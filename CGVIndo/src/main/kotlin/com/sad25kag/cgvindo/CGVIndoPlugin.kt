package com.sad25kag.cgvindo

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CGVIndoPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CGVIndo())
    }
}
