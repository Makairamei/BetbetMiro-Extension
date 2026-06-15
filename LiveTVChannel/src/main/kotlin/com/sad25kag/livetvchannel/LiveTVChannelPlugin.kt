package com.sad25kag.livetvchannel

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LiveTVChannelPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LiveTVChannelProvider())
    }
}
