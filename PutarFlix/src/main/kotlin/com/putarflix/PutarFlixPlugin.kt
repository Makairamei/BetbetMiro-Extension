package com.putarflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class PutarFlixPlugin : Plugin() {
    override fun load() {
        registerMainAPI(PutarFlixProvider())
    }
}
