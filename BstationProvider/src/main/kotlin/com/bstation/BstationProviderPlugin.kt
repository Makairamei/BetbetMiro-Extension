package com.bstation

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BstationProviderPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BstationProvider())
    }
}