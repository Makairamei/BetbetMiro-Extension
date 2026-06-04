package com.sad25kag.maonime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MaonimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Maonime())
    }
}
