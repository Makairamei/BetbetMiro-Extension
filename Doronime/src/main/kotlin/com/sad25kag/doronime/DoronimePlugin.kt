package com.sad25kag.doronime

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.Geodailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DoronimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Doronime())

        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(DoronimeOkRu())
    }
}
