package com.sad25kag.gomunime

import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId as addCloudstreamAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId as addCloudstreamMalId

internal fun AnimeLoadResponse.addMalId(id: Int?) {
    this.addCloudstreamMalId(id)
}

internal fun AnimeLoadResponse.addAniListId(id: Int?) {
    this.addCloudstreamAniListId(id)
}
