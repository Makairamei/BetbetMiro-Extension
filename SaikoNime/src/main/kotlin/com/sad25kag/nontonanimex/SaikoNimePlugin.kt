package com.sad25kag.saikonime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SaikoNimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SaikoNime())
    }
}
