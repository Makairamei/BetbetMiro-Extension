package com.sad25kag.doronime

import com.lagradost.cloudstream3.SearchResponse

internal val SearchResponse.title: String
    get() = name
