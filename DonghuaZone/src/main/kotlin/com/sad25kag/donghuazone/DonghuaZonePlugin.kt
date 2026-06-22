package com.sad25kag.donghuazone

import android.content.Context
import com.lagradost.cloudstream3.extractors.Dailymotion
import com.lagradost.cloudstream3.extractors.Geodailymotion
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DonghuaZonePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DonghuaZone())

        // Source uses dynamic third-party server/iframe values; these are registered
        // so loadExtractor() can resolve source-provided embed URLs without hardcoding IDs.
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(Geodailymotion())
        registerExtractorAPI(DonghuaZoneOkRu())
    }
}
