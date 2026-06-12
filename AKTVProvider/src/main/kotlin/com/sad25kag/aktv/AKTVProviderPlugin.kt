package com.sad25kag.aktv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AKTVProviderPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AKTVProvider())
    }
}
