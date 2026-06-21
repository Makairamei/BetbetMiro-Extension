package com.sad25kag.xiaomitvplus

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class XiaomiTVPlusPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XiaomiTVPlusProvider())
    }
}
