package com.sad25kag.livetvcentral

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LiveTVCentralPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LiveTVCentralProvider())
    }
}
