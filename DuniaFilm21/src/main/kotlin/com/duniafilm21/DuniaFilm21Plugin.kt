package com.duniafilm21

import com.lagradost.cloudstream3.extractors.Mp4Upload
import com.lagradost.cloudstream3.extractors.StreamTape
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DuniaFilm21Plugin : Plugin() {
    override fun load() {
        registerMainAPI(DuniaFilm21Provider())

        registerExtractorAPI(DuniaFilm21P2P())
        registerExtractorAPI(DuniaFilm21Hydrax())
        registerExtractorAPI(DuniaFilm21Turbovid())
        registerExtractorAPI(DuniaFilm21Stbturbo())
        registerExtractorAPI(DuniaFilm21TurboVipCast())
        registerExtractorAPI(DuniaFilm21StreamHg())
        registerExtractorAPI(DuniaFilm21HgVip())
        registerExtractorAPI(DuniaFilm21Jeniusplay())
        registerExtractorAPI(DuniaFilm21Majorplay())
        registerExtractorAPI(DuniaFilm21E2eMajorplay())
        registerExtractorAPI(DuniaFilm21M3u8Majorplay())
        registerExtractorAPI(DuniaFilm21Hglink())
        registerExtractorAPI(DuniaFilm21Ghbrisk())
        registerExtractorAPI(DuniaFilm21Dhcplay())
        registerExtractorAPI(DuniaFilm21Streamcasthub())
        registerExtractorAPI(DuniaFilm21Dm21embed())
        registerExtractorAPI(DuniaFilm21Meplayer())
        registerExtractorAPI(DuniaFilm21StreamWish())
        registerExtractorAPI(DuniaFilm21FileMoon())
        registerExtractorAPI(DuniaFilm21Dood())
        registerExtractorAPI(DuniaFilm21BloggerVideo())
        registerExtractorAPI(DuniaFilm21Gdplayer())
        registerExtractorAPI(DuniaFilm21AWSStream())
        registerExtractorAPI(StreamTape())
        registerExtractorAPI(Mp4Upload())
    }
}
