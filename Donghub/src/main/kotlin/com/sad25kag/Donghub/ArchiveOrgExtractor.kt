package com.sad25kag.Donghub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class ArchiveOrgExtractor : ExtractorApi() {
    override val name = "ArchiveOrg"
    override val mainUrl = "https://archive.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.isBlank()) return

        callback.invoke(
            newExtractorLink(
                name,
                name,
                url,
                INFER_TYPE
            ) {
                this.referer = referer ?: mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
