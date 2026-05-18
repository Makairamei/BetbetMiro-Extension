package com.drakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.drakor.DrakorProviderExtractor.invokeDrakor
import com.drakor.DrakorProviderExtractor.invokeKisskh
import com.drakor.DrakorProviderExtractor.invokeMoviebox
import com.drakor.DrakorProviderExtractor.invokeMoviebox2
import com.drakor.DrakorProviderExtractor.invokeGomovies
import com.drakor.DrakorProviderExtractor.invokeIdlix
import com.drakor.DrakorProviderExtractor.invokeMapple
import com.drakor.DrakorProviderExtractor.invokeSuperembed
import com.drakor.DrakorProviderExtractor.invokeVidfast
import com.drakor.DrakorProviderExtractor.invokeVidlink
import com.drakor.DrakorProviderExtractor.invokeVidsrc
import com.drakor.DrakorProviderExtractor.invokeVidsrccc
import com.drakor.DrakorProviderExtractor.invokeVixsrc
import com.drakor.DrakorProviderExtractor.invokeWatchsomuch
import com.drakor.DrakorProviderExtractor.invokeWyzie
import com.drakor.DrakorProviderExtractor.invokeXprime
import com.drakor.DrakorProviderExtractor.invokeCinemaOS
import com.drakor.DrakorProviderExtractor.invokePlayer4U
import com.drakor.DrakorProviderExtractor.invokeRiveStream
import com.drakor.DrakorProviderExtractor.invokeVidrock
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink

open class DrakorProvider : TmdbProvider() {
    override var name = "Drakor"
    override val hasMainPage = true
    override var lang = "id"
    override val instantLinkLoading = true
    override val useMetaLoadResponse = true
