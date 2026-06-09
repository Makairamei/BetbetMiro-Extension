package com.sad25kag.jagoanhentai

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class JagoanPlaymogo : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val response = app.get(url, referer = referer ?: mainUrl)
        val html = response.text
        val passPath = Regex("""['\"](/pass_md5/[^'\"]+)['\"]""").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""(/pass_md5/[A-Za-z0-9_./-]+)""").find(html)?.groupValues?.getOrNull(1)
            ?: return
        val token = passPath.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() } ?: return
        val base = app.get("$mainUrl$passPath", referer = url).text.trim().trim('"', '\'', ' ', '\n', '\r', '\t')
            .takeIf { it.startsWith("http", true) } ?: return
        val finalUrl = base + randomSuffix() + "?token=$token&expiry=${System.currentTimeMillis()}"
        val quality = getQualityFromName(response.document.selectFirst("title")?.text())
        callback.invoke(
            newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = quality
            },
        )
    }

    private fun randomSuffix(): String = "BetbetMiro"
}
