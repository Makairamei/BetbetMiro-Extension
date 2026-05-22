package com.dracinema

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DracinemaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dracinema())
    }
}package com.cdramayes

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CDramaYesPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CDramaYes())
    }
}