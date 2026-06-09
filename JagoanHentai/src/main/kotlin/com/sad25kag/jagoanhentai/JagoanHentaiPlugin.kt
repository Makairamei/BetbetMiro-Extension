package com.sad25kag.jagoanhentai

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class JagoanHentaiPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(JagoanHentai())
        registerExtractorAPI(JagoanPlaymogo())
    }
}
