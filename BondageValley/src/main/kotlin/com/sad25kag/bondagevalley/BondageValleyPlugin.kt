package com.sad25kag.bondagevalley

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BondageValleyPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(BondageValley())
    }
}
