package com.betbet.yunshanid

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YunshanIDPlugin : Plugin() {

    override fun load(context: Context) {
        addProvider(YunshanIDProvider())
    }
}