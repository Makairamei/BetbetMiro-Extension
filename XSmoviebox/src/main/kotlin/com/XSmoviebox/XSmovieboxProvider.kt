package com.XSmoviebox

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class XSmovieboxProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(XSmoviebox())
    }
}
