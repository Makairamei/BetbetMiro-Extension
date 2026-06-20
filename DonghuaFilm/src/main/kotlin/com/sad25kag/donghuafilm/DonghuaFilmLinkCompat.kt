package com.sad25kag.donghuafilm

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking

/**
 * Compatibility bridge for direct links emitted from non-suspend helper code.
 * CloudStream's newExtractorLink is suspend in the current API, while
 * DonghuaFilm.emitDirect() is intentionally a small non-suspend callback helper.
 */
fun newExtractorLink(
    source: String,
    name: String,
    url: String,
    type: ExtractorLinkType? = null,
    initializer: suspend ExtractorLink.() -> Unit = {},
): ExtractorLink = runBlocking {
    com.lagradost.cloudstream3.utils.newExtractorLink(
        source = source,
        name = name,
        url = url,
        type = type,
        initializer = initializer,
    )
}
