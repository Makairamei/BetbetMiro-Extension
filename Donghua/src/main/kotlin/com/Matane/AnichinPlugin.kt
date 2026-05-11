package com.Matane

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.extractors.*
// import android.content.Context

@CloudstreamPlugin
class AnichinPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(Anichin())
        registerMainAPI(Kazefuri())
        registerMainAPI(Animexin())
        registerMainAPI(Animekhor())
        registerMainAPI(Donghuaword())
        registerMainAPI(Auratail())
        registerMainAPI(Donghub())
        registerExtractorAPI(ArchiveOrgExtractor())
        registerExtractorAPI(embedwish())
        registerExtractorAPI(Filelions())
        registerExtractorAPI(VidHidePro5())
        registerExtractorAPI(Swhoi())
        registerExtractorAPI(EmturbovidExtractor())
        registerExtractorAPI(Dailymotion())
        registerExtractorAPI(PixelDrain())
        registerExtractorAPI(LuluStream())
        registerExtractorAPI(Rumble())
        registerExtractorAPI(Mp4Upload())
        registerExtractorAPI(Okrulink())
        registerExtractorAPI(StreamRuby())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(DoodLaExtractor())
        registerExtractorAPI(Gdriveplayer())  
        registerExtractorAPI(XStreamCdn())
        registerExtractorAPI(Vidtren())    
        registerExtractorAPI(svilla())
        registerExtractorAPI(svanila())
        registerExtractorAPI(Vidguardto())
        registerExtractorAPI(Vidguardto1())
        registerExtractorAPI(Vidguardto2())
        registerExtractorAPI(Vidguardto3()) 
        registerExtractorAPI(Vtbe())
        registerExtractorAPI(waaw())
        registerExtractorAPI(wishfast())
        registerExtractorAPI(FileMoonSx())
    }
}
