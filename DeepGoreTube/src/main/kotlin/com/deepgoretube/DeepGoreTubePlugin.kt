package com.deepgoretube

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DeepGoreTubePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DeepGoreTube())
    }
}