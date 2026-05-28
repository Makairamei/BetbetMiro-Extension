package com.putarflix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object PutarFlixExtractor {
    private val directVideoRegex = Regex("""https?:\\?/\\?/[^\"'<>\\s]+?\\.(?:m3u8|mp4|mkv|mpd)(?:\?[^\"'<>\\s]+)?""", RegexOption.IGNORE_CASE)
    private val iframeRegex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val jsonEmbedRegex = Regex("""["'](?:embed_url|file|url|source|src)["']\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

    suspend fun extract(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = PutarFlixUtils.decodeKnownRedirect(data.trim())
        if (startUrl.isBlank()) return false

        if (!startUrl.startsWith(PutarFlixSeeds.MAIN_URL) && PutarFlixUtils.looksDirectVideo(startUrl)) {
            return emitDirect(startUrl, PutarFlixSeeds.MAIN_URL, "PutarFlix Direct", callback)
        }

        val candidates = linkedSetOf<PutarFlixServer>()

        if (!startUrl.startsWith(PutarFlixSeeds.MAIN_URL)) {
            candidates += PutarFlixServer("PutarFlix External", startUrl, PutarFlixSeeds.MAIN_URL, "data")
        } else {
            val playerPages = buildList {
                add(startUrl)
                val clean = startUrl.substringBefore("?")
                PutarFlixSeeds.playerNumbers.forEach { number ->
                    add("$clean?player=$number")
                }
            }.distinct()

            for (page in playerPages) {
                val doc = runCatching { app.get(page, referer = PutarFlixSeeds.MAIN_URL).document }.getOrNull() ?: continue
                candidates += collectServersFromDocument(page, doc)
                candidates += collectAjaxServers(page, doc)
            }
        }

        var found = false
        for (server in candidates.distinctBy { it.url }) {
            val finalUrl = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (PutarFlixUtils.isRejectedVideoCandidate(finalUrl)) continue
            found = resolveServer(finalUrl, server.referer, server.label, subtitleCallback, callback) || found
        }
        return found
    }

    private fun collectServersFromDocument(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val servers = mutableListOf<PutarFlixServer>()

        doc.select("iframe[src], embed[src], video[src], source[src], a[href]").forEach { element ->
            val raw = firstAttr(element, "src", "data-src", "data-lazy-src", "href", "data-link", "data-url") ?: return@forEach
            val url = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
            if (PutarFlixUtils.isRejectedVideoCandidate(url)) return@forEach
            servers += PutarFlixServer(PutarFlixUtils.extractLabelNear(element), url, pageUrl, element.tagName())
        }

        val scriptText = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        directVideoRegex.findAll(scriptText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.value) ?: return@forEach
            servers += PutarFlixServer("PutarFlix Direct", url, pageUrl, "script-direct")
        }
        PutarFlixUtils.extractUrlsFromText(pageUrl, scriptText).forEach { url ->
            if (!PutarFlixUtils.isRejectedVideoCandidate(url) && !url.startsWith(PutarFlixSeeds.MAIN_URL)) {
                servers += PutarFlixServer("PutarFlix Script", url, pageUrl, "script-url")
            }
        }
        jsonEmbedRegex.findAll(scriptText).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            if (!PutarFlixUtils.isRejectedVideoCandidate(url)) {
                servers += PutarFlixServer("PutarFlix Embed", url, pageUrl, "script-json")
            }
        }

        return servers.distinctBy { it.url }
    }

    private suspend fun collectAjaxServers(pageUrl: String, doc: Document): List<PutarFlixServer> {
        val players = collectAjaxPlayers(doc)
        if (players.isEmpty()) return emptyList()

        val output = mutableListOf<PutarFlixServer>()
        for (player in players) {
            for (action in PutarFlixSeeds.ajaxActions) {
                val response = runCatching {
                    app.post(
                        "${PutarFlixSeeds.MAIN_URL}/wp-admin/admin-ajax.php",
                        referer = pageUrl,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                        ),
                        data = mapOf(
                            "action" to action,
                            "post" to player.postId,
                            "nume" to player.nume,
                            "type" to player.type
                        )
                    ).text
                }.getOrNull() ?: continue

                output += collectServersFromAjaxText(pageUrl, response, player.label)
                if (output.isNotEmpty()) break
            }
        }
        return output.distinctBy { it.url }
    }

    private fun collectAjaxPlayers(doc: Document): List<PutarFlixAjaxPlayer> {
        val players = mutableListOf<PutarFlixAjaxPlayer>()
        doc.select(".dooplay_player_option, [data-post][data-nume], [data-type][data-post], li[id*=player-option], a[data-post]").forEach { element ->
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }
            val nume = element.attr("data-nume").ifBlank { element.attr("data-server") }.ifBlank { element.attr("data-player") }
            val type = element.attr("data-type").ifBlank { if (doc.location().contains("/tv/") || doc.location().contains("/eps/")) "tv" else "movie" }
            if (post.isNotBlank() && nume.isNotBlank()) {
                players += PutarFlixAjaxPlayer(post, type, nume, PutarFlixUtils.extractLabelNear(element))
            }
        }

        val html = doc.html()
        Regex("""data-post=["'](\d+)["'][^>]+data-nume=["'](\d+)["'][^>]+data-type=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match ->
                players += PutarFlixAjaxPlayer(match.groupValues[1], match.groupValues[3], match.groupValues[2], "Server ${match.groupValues[2]}")
            }

        return players.distinctBy { "${it.postId}:${it.type}:${it.nume}" }
    }

    private fun collectServersFromAjaxText(pageUrl: String, response: String, label: String): List<PutarFlixServer> {
        val decoded = response
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\\"", "\"")

        val output = mutableListOf<PutarFlixServer>()
        jsonEmbedRegex.findAll(decoded).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            output += PutarFlixServer(label, url, pageUrl, "ajax-json")
        }
        iframeRegex.findAll(decoded).forEach { match ->
            val url = PutarFlixUtils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            output += PutarFlixServer(label, url, pageUrl, "ajax-iframe")
        }
        PutarFlixUtils.extractUrlsFromText(pageUrl, decoded).forEach { url ->
            output += PutarFlixServer(label, url, pageUrl, "ajax-url")
        }

        val htmlDoc = Jsoup.parse(decoded, pageUrl)
        htmlDoc.select("iframe[src], source[src], video[src], a[href]").forEach { element ->
            val raw = firstAttr(element, "src", "href", "data-src", "data-link", "data-url") ?: return@forEach
            val url = PutarFlixUtils.absoluteUrl(pageUrl, raw) ?: return@forEach
            output += PutarFlixServer(label, url, pageUrl, "ajax-html")
        }
        return output.filterNot { PutarFlixUtils.isRejectedVideoCandidate(it.url) }.distinctBy { it.url }
    }

    private suspend fun resolveServer(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (PutarFlixUtils.looksDirectVideo(url)) {
            return emitDirect(url, referer, label, callback)
        }

        val loaded = runCatching { loadExtractor(url, referer, subtitleCallback, callback) }.getOrDefault(false)
        if (loaded) return true

        val doc = runCatching { app.get(url, referer = referer).document }.getOrNull() ?: return false
        val nested = collectServersFromDocument(url, doc)
        var found = false
        for (server in nested.distinctBy { it.url }) {
            val fixed = PutarFlixUtils.decodeKnownRedirect(server.url)
            if (PutarFlixUtils.isRejectedVideoCandidate(fixed)) continue
            found = resolveServer(fixed, server.referer, server.label, subtitleCallback, callback) || found
        }
        return found
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val type = when {
            url.substringBefore("?").endsWith(".m3u8", true) -> ExtractorLinkType.M3U8
            url.substringBefore("?").endsWith(".mpd", true) -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
        callback.invoke(
            newExtractorLink(
                source = "PutarFlix",
                name = label.ifBlank { "PutarFlix" },
                url = url,
                type = type
            ) {
                this.referer = referer
                this.quality = getQualityFromName(url)
            }
        )
        return true
    }

    private fun firstAttr(element: Element, vararg attrs: String): String? {
        return attrs.firstNotNullOfOrNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
    }
}
