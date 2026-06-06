package com.sad25kag.terbit21

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Terbit21Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Terbit21Provider())
    }
}
