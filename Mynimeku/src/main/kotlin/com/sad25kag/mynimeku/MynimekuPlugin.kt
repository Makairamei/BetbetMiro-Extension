package com.sad25kag.mynimeku

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MynimekuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MynimekuProvider())
    }
}
