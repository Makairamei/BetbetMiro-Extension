package com.astronime

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AstronimePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AstronimeProvider())
    }
}
