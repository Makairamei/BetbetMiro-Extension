package com.duniafilm21

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getPacked
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object DuniaFilm21Extractor {
    private const val EXTRACT_TIMEOUT_MS = 45_000L
    private const val REQUEST_TIMEOUT_MS = 12_000L
    private const val LOAD_EXTRACTOR_TIMEOUT_MS = 14_000L
    private const val MAX_DEPTH = 5

    private val directVideoRegex = Regex(
        """https?:\\?/\\?/[^\"'<>)\]\[\s]+?(?:(?:\.(?:m3u8|mp4|mkv|mpd|webm)(?:\?[^\"'<>)\]\[\s]+)?)|(?:\?[^\"'<>)\]\[\s]*(?:m3u8|mp4|mkv|mpd|webm)[^\"'<>)\]\[\s]*))""",
        RegexOption.IGNORE_CASE
    )
    private val jsonEmbedRegex = Regex(
        """["'](?:embed_url|file|url|source|src|link|download|download_url|direct_link|downloadLink)["']\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val rawHtmlRegex = Regex("""<iframe|<source|<video|&lt;iframe|&lt;source|&lt;video""", RegexOption.IGNORE_CASE)

    private val payloadAttributes = listOf(
        "content", "src", "data-src", "data-lazy-src", "data-litespeed-src", "data-iframe", "data-embed",
        "data-link", "data-url", "data-video", "data-video-url", "data-stream", "data-stream-url", "data-file",
        "data-href", "data-content", "data-html", "data-frame", "data-player", "data-play", "data-server",
        "data-hls", "data-m3u8", "value", "href", "srcdoc"
    )

    private val playerContainers = listOf(
        "#player", "#player2", "#video", ".player", ".player-area", ".playex", ".movieplay", ".video-content",
        ".responsive-embed", ".embed-responsive", ".pembed", ".dooplay_player", ".dooplay_player_content",
        ".dooplay_player_option", "#playeroptionsul", ".server", ".servers", ".server-item", ".player-option",
        ".player-option-item", ".muvipro-player-tabs", ".gmr-embed-responsive", ".tab-content", ".tab-pane",
        ".download", ".dllinks", "#download", ".entry-content", "article"
    ).joinToString(",")

    suspend fun extract(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withTimeoutOrNull(EXTRACT_TIMEOUT_MS) {
            extractInternal(data.trim(), subtitleCallback, callback)
        } ?: false
    }

    private suspend fun extractInternal(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val startUrl = DuniaFilm21Utils.decodeKnownRedirect(data)
        if (startUrl.isBlank()) return false

        if (!DuniaFilm21Utils.isDuniaFilm21Url(startUrl)) {
            if (DuniaFilm21Utils.looksDirectVideo(startUrl)) {
                return emitDirect(startUrl, DuniaFilm21Seed.MAIN_URL, "DuniaFilm21 Direct", callback)
            }
            return resolveServer(startUrl, DuniaFilm21Seed.MAIN_URL, "DuniaFilm21 External", subtitleCallback, callback)
        }

        val base = startUrl.substringBefore("#").substringBefore("?")
        val playerPages = buildList {
            add(base)
            DuniaFilm21Seed.playerNumbers.forEach { num -> if (num != "1") add("$base?player=$num") }
        }.distinct()

        val candidates = linkedSetOf<DuniaFilm21Server>()
        for (page in playerPages) {
            val doc = safeGetDocument(page, DuniaFilm21Seed.MAIN_URL) ?: continue
            candidates += collectServersFromDocument(page, doc)
            candidates += collectAjaxServers(page, doc)
            candidates += collectMuviproServers(page, doc)
        }

        var found = false
        val visited = linkedSetOf<String>()
        for (server in candidates.sortedBy { rankServer(it.url) }.distinctBy { DuniaFilm21Utils.decodeKnownRedirect(it.url) }) {
            val fixed = DuniaFilm21Utils.decodeKnownRedirect(server.url)
            if (shouldSkipCandidate(fixed, allowInternalPage = true, allowShortener = true)) continue
            if (resolveServer(fixed, server.referer, server.label, subtitleCallback, callback, visited, 0)) found = true
        }
        return found
    }

    private fun collectServersFromDocument(pageUrl: String, doc: Document): List<DuniaFilm21Server> {
        val servers = linkedSetOf<DuniaFilm21Server>()

        doc.select("iframe[src], iframe[data-src], iframe[srcdoc], embed[src], video[src], video[data-src], source[src], source[data-src], meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url], meta[name=twitter:player]")
            .forEach { addServerFromElement(servers, pageUrl, it, allowInternalPage = true, allowShortener = true) }

        doc.select(playerContainers).forEach { container ->
            container.select("iframe[src], iframe[data-src], iframe[srcdoc], embed[src], video[src], video[data-src], source[src], source[data-src], a[href], button, div, li, span, [data-content], [data-html], [data-url], [data-link], [data-file], [data-video]")
                .forEach { addServerFromElement(servers, pageUrl, it, allowInternalPage = true, allowShortener = true) }
        }

        doc.select("[srcdoc], [data-content], [data-html], [data-iframe], [data-embed], [data-player], [data-url], [data-video], [data-file]").forEach { element ->
            payloadAttributes.mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }
                .forEach { payload -> servers += collectServersFromText(pageUrl, payload, DuniaFilm21Utils.extractLabelNear(element)) }
        }

        doc.select("a[href]").forEach { anchor ->
            val absolute = DuniaFilm21Utils.absoluteUrl(pageUrl, anchor.attr("href")) ?: return@forEach
            val decoded = DuniaFilm21Utils.decodeKnownRedirect(absolute)
            if ((DuniaFilm21Utils.isKnownPlayableHost(decoded) || DuniaFilm21Utils.looksDirectVideo(decoded) || DuniaFilm21Utils.isShortenerUrl(decoded)) &&
                !shouldSkipCandidate(decoded, allowInternalPage = true, allowShortener = true)
            ) {
                servers += DuniaFilm21Server(DuniaFilm21Utils.extractLabelNear(anchor), decoded, pageUrl, "anchor")
            }
        }

        val scriptText = doc.select("script").joinToString("\n") { it.data() + "\n" + it.html() }
        val normalizedScript = normalizeText(scriptText)
        collectFromRawText(pageUrl, normalizedScript, "DuniaFilm21 Script", servers)

        val unpacked = runCatching {
            if (!getPacked(normalizedScript).isNullOrEmpty()) getAndUnpack(normalizedScript) else null
        }.getOrNull()
        if (!unpacked.isNullOrBlank()) collectFromRawText(pageUrl, unpacked, "DuniaFilm21 Unpacked", servers)

        val fullHtml = normalizeText(doc.outerHtml())
        collectFromRawText(pageUrl, fullHtml, "DuniaFilm21 HTML", servers)
        DuniaFilm21Utils.decodeBase64Payloads(fullHtml).forEach { decoded ->
            collectFromRawText(pageUrl, decoded, "DuniaFilm21 Encoded", servers)
        }

        return servers.distinctBy { DuniaFilm21Utils.decodeKnownRedirect(it.url) }
    }

    private fun collectFromRawText(pageUrl: String, text: String, label: String, servers: MutableSet<DuniaFilm21Server>) {
        directVideoRegex.findAll(text).forEach { match ->
            val url = DuniaFilm21Utils.absoluteUrl(pageUrl, match.value) ?: return@forEach
            if (!shouldSkipCandidate(url, allowInternalPage = true, allowShortener = true)) servers += DuniaFilm21Server(label, url, pageUrl, "direct")
        }
        jsonEmbedRegex.findAll(text).forEach { match ->
            val url = DuniaFilm21Utils.absoluteUrl(pageUrl, match.groupValues[1]) ?: return@forEach
            val fixed = DuniaFilm21Utils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowInternalPage = true, allowShortener = true)) servers += DuniaFilm21Server(label, fixed, pageUrl, "json")
        }
        DuniaFilm21Utils.extractUrlsFromText(pageUrl, text).forEach { raw ->
            val fixed = DuniaFilm21Utils.decodeKnownRedirect(raw)
            if (!shouldSkipCandidate(fixed, allowInternalPage = true, allowShortener = true)) servers += DuniaFilm21Server(label, fixed, pageUrl, "url")
        }
        if (rawHtmlRegex.containsMatchIn(text)) {
            val doc = Jsoup.parse(DuniaFilm21Utils.decodeHtml(text))
            doc.select("iframe[src], iframe[data-src], iframe[srcdoc], video[src], source[src], a[href]").forEach { element ->
                addServerFromElement(servers, pageUrl, element, allowInternalPage = true, allowShortener = true)
            }
        }
    }

    private fun addServerFromElement(
        servers: MutableSet<DuniaFilm21Server>,
        pageUrl: String,
        element: Element,
        allowInternalPage: Boolean,
        allowShortener: Boolean
    ) {
        val label = DuniaFilm21Utils.extractLabelNear(element)
        payloadAttributes.mapNotNull { attr -> element.attr(attr).takeIf { it.isNotBlank() } }.distinct().forEach { payload ->
            val raw = DuniaFilm21Utils.cleanUrlText(payload)
            if (raw.isBlank()) return@forEach
            if (rawHtmlRegex.containsMatchIn(raw) || (raw.contains("http", true) && raw.contains("src=", true))) {
                servers += collectServersFromText(pageUrl, raw, label)
                return@forEach
            }
            DuniaFilm21Utils.decodeBase64Payloads(raw).forEach { decoded -> servers += collectServersFromText(pageUrl, decoded, label) }
            val url = DuniaFilm21Utils.absoluteUrl(pageUrl, raw) ?: return@forEach
            val fixed = DuniaFilm21Utils.decodeKnownRedirect(url)
            if (!shouldSkipCandidate(fixed, allowInternalPage, allowShortener)) {
                servers += DuniaFilm21Server(label, fixed, pageUrl, element.tagName())
            }
        }
    }

    private fun collectServersFromText(pageUrl: String, text: String, label: String): List<DuniaFilm21Server> {
        val servers = linkedSetOf<DuniaFilm21Server>()
        collectFromRawText(pageUrl, normalizeText(text), label, servers)
        return servers.toList()
    }

    private suspend fun collectAjaxServers(pageUrl: String, doc: Document): List<DuniaFilm21Server> {
        val players = collectAjaxPlayers(pageUrl, doc)
        if (players.isEmpty()) return emptyList()
        val output = linkedSetOf<DuniaFilm21Server>()
        for (player in players) {
            for (action in DuniaFilm21Seed.ajaxActions) {
                val forms = listOf(
                    mapOf("action" to action, "post" to player.postId, "nume" to player.nume, "type" to player.type),
                    mapOf("action" to action, "post_id" to player.postId, "server" to player.nume, "type" to player.type),
                    mapOf("action" to action, "id" to player.postId, "nume" to player.nume, "type" to player.type),
                    mapOf("action" to action, "movie" to player.postId, "player" to player.nume, "type" to player.type),
                    mapOf("action" to action, "tab" to "player-option-${player.nume}", "post_id" to player.postId)
                )
                for (form in forms) {
                    val response = safePostAjaxText("${DuniaFilm21Utils.originOf(pageUrl) ?: DuniaFilm21Seed.MAIN_URL}/wp-admin/admin-ajax.php", pageUrl, form) ?: continue
                    val servers = collectServersFromText(pageUrl, response, player.label)
                    if (servers.isNotEmpty()) {
                        output += servers
                        break
                    }
                }
                if (output.isNotEmpty()) break
            }
        }
        return output.distinctBy { DuniaFilm21Utils.decodeKnownRedirect(it.url) }
    }

    private fun collectAjaxPlayers(pageUrl: String, doc: Document): List<DuniaFilm21AjaxPlayer> {
        val players = linkedSetOf<DuniaFilm21AjaxPlayer>()
        val fallbackType = if (pageUrl.contains("/tv/", true) || pageUrl.contains("/episode/", true)) "tv" else "movie"
        doc.select("#playeroptionsul li[data-post][data-nume], [data-post][data-nume], [data-type][data-post], [data-postid][data-nume], [data-post-id][data-nume], .dooplay_player_option, .dooplay_player_option[data-post], li[id*=player-option], .player-option[data-post], .player-option-item[data-post], .server-item[data-id], .server[data-post]")
            .forEach { element ->
                val post = firstAttr(element, "data-post", "data-id", "data-postid", "data-post-id", "data-movie", "data-movieid") ?: return@forEach
                val nume = firstAttr(element, "data-nume", "data-server", "data-player", "data-number", "data-no", "data-episode") ?: return@forEach
                val type = firstAttr(element, "data-type", "data-kind") ?: fallbackType
                players += DuniaFilm21AjaxPlayer(post, type, nume, DuniaFilm21Utils.extractLabelNear(element))
            }

        val postId = extractPostId(doc)
        if (!postId.isNullOrBlank()) {
            DuniaFilm21Seed.playerNumbers.forEach { nume ->
                players += DuniaFilm21AjaxPlayer(postId, fallbackType, nume, "Server $nume")
            }
        }
        return players.distinctBy { "${it.postId}:${it.type}:${it.nume}" }.take(12)
    }

    private suspend fun collectMuviproServers(pageUrl: String, doc: Document): List<DuniaFilm21Server> {
        val output = linkedSetOf<DuniaFilm21Server>()
        doc.select("ul.muvipro-player-tabs li a[href], .muvipro-player-tabs a[href], a[href*='?player='], a[href*='&player=']").forEach { tab ->
            val tabUrl = DuniaFilm21Utils.absoluteUrl(pageUrl, tab.attr("href")) ?: return@forEach
            if (!shouldSkipCandidate(tabUrl, allowInternalPage = true, allowShortener = true)) output += DuniaFilm21Server(DuniaFilm21Utils.extractLabelNear(tab), tabUrl, pageUrl, "muvipro-tab")
        }

        val postId = extractPostId(doc) ?: return output.toList()
        val tabIds = linkedSetOf<String>()
        doc.select("div.tab-content-ajax[id], .tab-content-ajax[id], div[id^=muvipro_player_content], div[id*=muvipro][id]").map { it.id() }.filter { it.isNotBlank() }.forEach { tabIds += it }
        doc.select("ul.muvipro-player-tabs li a[href^=#], .muvipro-player-tabs a[href^=#]").map { it.attr("href").removePrefix("#") }.filter { it.isNotBlank() }.forEach { tabIds += it }
        if (tabIds.isEmpty()) DuniaFilm21Seed.playerNumbers.forEach { number -> tabIds += "muvipro_player_content_$number" }

        for (tabId in tabIds.take(10)) {
            val response = safePostAjaxText(
                "${DuniaFilm21Utils.originOf(pageUrl) ?: DuniaFilm21Seed.MAIN_URL}/wp-admin/admin-ajax.php",
                pageUrl,
                mapOf("action" to "muvipro_player_content", "tab" to tabId, "post_id" to postId)
            ) ?: continue
            output += collectServersFromText(pageUrl, response, "Muvipro ${tabId.substringAfterLast('_')}")
        }
        return output.distinctBy { DuniaFilm21Utils.decodeKnownRedirect(it.url) }
    }

    private suspend fun resolveServer(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String> = linkedSetOf(),
        depth: Int = 0
    ): Boolean {
        if (depth > MAX_DEPTH) return false
        val fixedUrl = DuniaFilm21Utils.decodeKnownRedirect(url)
        if (!fixedUrl.startsWith("http", true) || !visited.add(fixedUrl)) return false
        if (DuniaFilm21Utils.isBadAssetUrl(fixedUrl)) return false

        if (DuniaFilm21Utils.looksDirectVideo(fixedUrl)) {
            return emitDirect(fixedUrl, referer, label, callback)
        }

        if (fixedUrl.lowercase().contains("filepress") && fixedUrl.contains("/file/")) {
            resolveFilePress(fixedUrl, referer, label, subtitleCallback, callback, visited, depth + 1)?.let { if (it) return true }
        }

        if (safeLoadExtractor(fixedUrl, referer, subtitleCallback, callback)) return true

        if (shouldSkipCandidate(fixedUrl, allowInternalPage = true, allowShortener = true)) return false
        val doc = safeGetDocument(fixedUrl, referer) ?: return false
        val nested = linkedSetOf<DuniaFilm21Server>()
        nested += collectServersFromDocument(fixedUrl, doc)
        nested += collectAjaxServers(fixedUrl, doc)
        if (DuniaFilm21Utils.isDuniaFilm21Url(fixedUrl)) nested += collectMuviproServers(fixedUrl, doc)

        var found = false
        for (server in nested.sortedBy { rankServer(it.url) }.distinctBy { DuniaFilm21Utils.decodeKnownRedirect(it.url) }) {
            val nestedUrl = DuniaFilm21Utils.decodeKnownRedirect(server.url)
            if (nestedUrl == fixedUrl || shouldSkipCandidate(nestedUrl, allowInternalPage = true, allowShortener = true)) continue
            if (resolveServer(nestedUrl, fixedUrl, server.label.ifBlank { label }, subtitleCallback, callback, visited, depth + 1)) found = true
        }
        return found
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = DuniaFilm21Utils.decodeKnownRedirect(url)
        if (!clean.startsWith("http", true)) return false
        return if (DuniaFilm21Utils.isHls(clean)) {
            M3u8Helper.generateM3u8(
                label.ifBlank { "DuniaFilm21 HLS" },
                clean,
                referer = referer,
                headers = DuniaFilm21Utils.videoHeaders(referer)
            ).forEach(callback)
            true
        } else {
            callback(
                newExtractorLink(
                    source = label.ifBlank { "DuniaFilm21" },
                    name = label.ifBlank { "DuniaFilm21" },
                    url = clean,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = getQualityFromName(clean).takeIf { it != Qualities.Unknown.value } ?: Qualities.Unknown.value
                    this.headers = DuniaFilm21Utils.videoHeaders(referer)
                }
            )
            true
        }
    }

    private suspend fun safeLoadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var emitted = false
        withTimeoutOrNull(LOAD_EXTRACTOR_TIMEOUT_MS) {
            runCatching {
                loadExtractor(url, referer, subtitleCallback) { link ->
                    emitted = true
                    callback(link)
                }
            }.getOrDefault(false)
        }
        return emitted
    }

    private suspend fun safeGetDocument(url: String, referer: String): Document? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.get(url, referer = referer, timeout = REQUEST_TIMEOUT_MS, headers = DuniaFilm21Utils.browserHeaders).document
            }.getOrNull()
        }
    }

    private suspend fun safePostAjaxText(url: String, referer: String, data: Map<String, String>): String? {
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                app.post(
                    url = url,
                    referer = referer,
                    timeout = REQUEST_TIMEOUT_MS,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Origin" to DuniaFilm21Seed.MAIN_URL,
                        "User-Agent" to USER_AGENT
                    ),
                    data = data
                ).text
            }.getOrNull()
        }
    }

    private suspend fun resolveFilePress(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int
    ): Boolean? {
        val origin = DuniaFilm21Utils.originOf(url) ?: return false
        val fileId = Regex("""/file/([^/?#]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.getOrNull(1) ?: return false
        val servers = linkedSetOf<DuniaFilm21Server>()
        val endpoints = listOf("$origin/api/file/downlaod/", "$origin/api/file/download/", "$origin/api/file/downlaod2/", "$origin/api/file/download2/")
        val methods = listOf("publicDownlaod", "publicDownload", "download", "telegramDownload")
        for (endpoint in endpoints) {
            for (method in methods) {
                val response = safePostAjaxText(endpoint, url, mapOf("id" to fileId, "method" to method)) ?: continue
                servers += collectServersFromText(url, response, label.ifBlank { "FilePress" })
                DuniaFilm21Utils.extractUrlsFromText(url, response).forEach { found -> servers += DuniaFilm21Server(label.ifBlank { "FilePress" }, found, url, "filepress") }
            }
        }
        for (server in servers.distinctBy { DuniaFilm21Utils.decodeKnownRedirect(it.url) }) {
            if (resolveServer(server.url, url, server.label, subtitleCallback, callback, visited, depth + 1)) return true
        }
        return false
    }

    private fun firstAttr(element: Element, vararg names: String): String? = names.firstNotNullOfOrNull { name -> element.attr(name).trim().takeIf { it.isNotBlank() } }

    private fun extractPostId(doc: Document): String? {
        val shortLink = doc.selectFirst("link[rel=shortlink]")?.attr("href").orEmpty()
        Regex("""[?&]p=(\d+)""").find(shortLink)?.groupValues?.getOrNull(1)?.let { return it }
        val bodyClasses = doc.body()?.className().orEmpty()
        Regex("""postid-(\d+)""", RegexOption.IGNORE_CASE).find(bodyClasses)?.groupValues?.getOrNull(1)?.let { return it }
        val full = doc.outerHtml()
        return listOf(
            Regex("""postid-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""data-(?:post|id|postid|movie|movieid)\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?postId["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?movie_id["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""["']?post["']?\s*[:=]\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex -> regex.find(full)?.groupValues?.getOrNull(1) }
    }

    private fun normalizeText(text: String): String = DuniaFilm21Utils.decodeHtml(DuniaFilm21Utils.decodeUrlRepeated(text))
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\'", "'")

    private fun shouldSkipCandidate(url: String, allowInternalPage: Boolean, allowShortener: Boolean): Boolean {
        if (!url.startsWith("http", true)) return true
        if (DuniaFilm21Utils.isBadAssetUrl(url)) return true
        if (!allowShortener && DuniaFilm21Utils.isShortenerUrl(url)) return true
        if (!allowInternalPage && DuniaFilm21Utils.isDuniaFilm21Url(url)) return true
        val lower = url.lowercase()
        return listOf("/wp-content/", "/wp-json/", "/xmlrpc.php", "/feed/", "/comments/", "#respond", "?replytocom=").any { lower.contains(it) }
    }

    private fun rankServer(url: String): Int {
        val lower = url.lowercase()
        return when {
            DuniaFilm21Utils.looksDirectVideo(lower) -> 0
            lower.contains("googlevideo") -> 1
            lower.contains("filepress") -> 2
            lower.contains("jeniusplay") || lower.contains("majorplay") || lower.contains("streamwish") || lower.contains("filemoon") -> 3
            DuniaFilm21Utils.isKnownPlayableHost(lower) -> 4
            DuniaFilm21Utils.isShortenerUrl(lower) -> 8
            DuniaFilm21Utils.isDuniaFilm21Url(lower) -> 9
            else -> 6
        }
    }
}

open class DuniaFilm21HostExtractor : ExtractorApi() {
    override var name = "DuniaFilm21 Host"
    override var mainUrl = "https://example.com"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = DuniaFilm21Utils.decodeKnownRedirect(url)
        val ref = referer ?: DuniaFilm21Seed.MAIN_URL
        val html = runCatching { app.get(pageUrl, referer = ref, headers = DuniaFilm21Utils.browserHeaders, timeout = 15L).text }.getOrNull().orEmpty()
        val normalized = DuniaFilm21Utils.decodeHtml(DuniaFilm21Utils.decodeUrlRepeated(html)).replace("\\/", "/")
        val urls = DuniaFilm21Utils.extractUrlsFromText(pageUrl, normalized)
            .filter { DuniaFilm21Utils.looksDirectVideo(it) || DuniaFilm21Utils.isKnownPlayableHost(it) }
            .distinct()
        for (candidate in urls) {
            if (DuniaFilm21Utils.looksDirectVideo(candidate)) {
                if (DuniaFilm21Utils.isHls(candidate)) {
                    M3u8Helper.generateM3u8(name, candidate, referer = pageUrl, headers = DuniaFilm21Utils.videoHeaders(pageUrl)).forEach(callback)
                } else {
                    callback(newExtractorLink(name, name, candidate, ExtractorLinkType.VIDEO) {
                        this.referer = pageUrl
                        this.quality = getQualityFromName(candidate)
                        this.headers = DuniaFilm21Utils.videoHeaders(pageUrl)
                    })
                }
            } else {
                loadExtractor(candidate, pageUrl, subtitleCallback, callback)
            }
        }
    }
}

class DuniaFilm21Jeniusplay : DuniaFilm21HostExtractor() {
    override var name = "Jeniusplay"
    override var mainUrl = "https://jeniusplay.com"
}

class DuniaFilm21Majorplay : DuniaFilm21HostExtractor() {
    override var name = "Majorplay"
    override var mainUrl = "https://majorplay.xyz"
}

class DuniaFilm21E2eMajorplay : DuniaFilm21HostExtractor() {
    override var name = "E2eMajorplay"
    override var mainUrl = "https://e2e.majorplay.xyz"
}

class DuniaFilm21M3u8Majorplay : DuniaFilm21HostExtractor() {
    override var name = "M3u8Majorplay"
    override var mainUrl = "https://m3u8.majorplay.xyz"
}

class DuniaFilm21BloggerVideo : DuniaFilm21HostExtractor() {
    override var name = "BloggerVideo"
    override var mainUrl = "https://www.blogger.com"
}

class DuniaFilm21Gdplayer : DuniaFilm21HostExtractor() {
    override var name = "Gdplayer"
    override var mainUrl = "https://gdplayer.to"
}

class DuniaFilm21AWSStream : DuniaFilm21HostExtractor() {
    override var name = "AWSStream"
    override var mainUrl = "https://awsstream.com"
}

class DuniaFilm21StreamWish : StreamWishExtractor() {
    override var name = "StreamWish"
    override var mainUrl = "https://streamwish.to"
}

class DuniaFilm21FileMoon : StreamWishExtractor() {
    override var name = "FileMoon"
    override var mainUrl = "https://filemoon.sx"
}

class DuniaFilm21Hglink : StreamWishExtractor() {
    override var name = "Hglink"
    override var mainUrl = "https://hglink.to"
}

class DuniaFilm21Ghbrisk : StreamWishExtractor() {
    override var name = "Ghbrisk"
    override var mainUrl = "https://ghbrisk.com"
}

class DuniaFilm21Dhcplay : StreamWishExtractor() {
    override var name = "Dhcplay"
    override var mainUrl = "https://dhcplay.com"
}

class DuniaFilm21Dood : DoodLaExtractor() {
    override var name = "Dood"
    override var mainUrl = "https://doodstream.com"
}

class DuniaFilm21Streamcasthub : VidStack() {
    override var name = "Streamcasthub"
    override var mainUrl = "https://streamcasthub.com"
}

class DuniaFilm21Dm21embed : VidStack() {
    override var name = "Dm21embed"
    override var mainUrl = "https://dm21embed.com"
}

class DuniaFilm21Meplayer : VidStack() {
    override var name = "Meplayer"
    override var mainUrl = "https://meplayer.xyz"
}

class DuniaFilm21P2P : DuniaFilm21HostExtractor() {
    override var name = "P2P"
    override var mainUrl = "https://cloud.hownetwork.xyz"
}

class DuniaFilm21Hydrax : VidHidePro() {
    override var name = "Hydrax"
    override var mainUrl = "https://playhydrax.com"
}

class DuniaFilm21Turbovid : DuniaFilm21HostExtractor() {
    override var name = "Turbovid"
    override var mainUrl = "https://turbovid.xyz"
}

class DuniaFilm21Stbturbo : DuniaFilm21HostExtractor() {
    override var name = "Stbturbo"
    override var mainUrl = "https://stbturbo.xyz"
}

class DuniaFilm21TurboVipCast : DuniaFilm21HostExtractor() {
    override var name = "TurboVipCast"
    override var mainUrl = "https://turbovipcast.com"
}

class DuniaFilm21StreamHg : DuniaFilm21HostExtractor() {
    override var name = "StreamHG"
    override var mainUrl = "https://streamhg.com"
}

class DuniaFilm21HgVip : DuniaFilm21HostExtractor() {
    override var name = "HGVIP"
    override var mainUrl = "https://hgvip.com"
}
