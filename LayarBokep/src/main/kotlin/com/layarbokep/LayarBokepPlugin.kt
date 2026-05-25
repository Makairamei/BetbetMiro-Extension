package com.layarbokep

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class LayarBokepPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(LayarBokep())

        registerExtractorAPI(Jeniusplay())
        registerExtractorAPI(Majorplay())
        registerExtractorAPI(E2eMajorplay())
        registerExtractorAPI(M3u8Majorplay())
        registerExtractorAPI(DoodStream())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(FileMoon())
        registerExtractorAPI(Vidhide())
        registerExtractorAPI(Voe())
        registerExtractorAPI(Mixdrop())
        registerExtractorAPI(StreamWish())
        registerExtractorAPI(WishFast())
        registerExtractorAPI(Hglink())
    }
}
