package com.XSCinemax21

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XSCinemax21Plugin : Plugin() {
    override fun load(context: Context) {
        // Register Main Provider
        registerMainAPI(XSCinemax21())
        
        // Register Extractors
        registerExtractorAPI(Majorplay())
    }
}
