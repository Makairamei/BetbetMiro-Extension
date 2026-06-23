package com.sad25kag.betbettv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BetbetTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BetbetTVProvider())
    }
}
