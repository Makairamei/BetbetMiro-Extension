package com.sad25kag.otakupoi

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class OtakuPoiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OtakuPoi())
    }
}
