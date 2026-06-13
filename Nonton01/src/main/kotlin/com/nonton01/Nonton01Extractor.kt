package com.nonton01

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.nonton01.Nonton01Utils.absoluteUrl
import com.nonton01.Nonton01Utils.decodeMaybe
import com.nonton01.Nonton01Utils.originOf
import com.nonton01.Nonton01Utils.videoHeaders
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Nonton01Extractor {
    private const val TAG = "Nonton01"
    private const val MAX_AJAX_PROBES = 24
    private const val MAX_EMBEDS = 12

    private data class AjaxProbe(
        val post: String,
        val nume: String,
        val type: String
    )

    private data class AbyssPayload(
        val slug: String,
        val md5Id: String,
        val userId: String,
        val media: String
    )

    private val iframeRegex = Regex("""(?i)<iframe[^>]+src\s*=\s*['"]([^'"]+)['"]""")
    private val quotedUrlRegex = Regex("""(?i)['"]((?:https?:)?//[^'"<>\s]+|/[^'"<>\s]+)['"]""")
    private val adminAjaxRegex = Regex("""(?i)(?:https?:)?//[^'"<>\s]+admin-ajax\.php[^'"<>\s]*|/[^'"<>\s]*admin-ajax\.php[^'"<>\s]*""")
    private val dataRegex = Regex("""(?:const|let|var)?\s*datas\s*=\s*['"]([A-Za-z0-9+/=]+)['"]""")

    suspend fun loadLinks(
        providerName: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "loadLinks start: $data")
        val emitted = linkedSetOf<String>()
        val candidates = if (isKnownPlaybackHost(data)) listOf(data) else Nonton01Utils.mirrorUrlsFor(data)
        for (candidate in candidates) {
            val found = if (isKnownPlaybackHost(candidate)) {
                resolveEmbed(providerName, candidate, data, emitted, subtitleCallback, callback)
            } else {
                resolveDetail(providerName, candidate, emitted, subtitleCallback, callback)
            }
            if (found) return true
        }
        Log.e(TAG, "loadLinks no playable link: $data")
        return false
    }

    private suspend fun resolveDetail(
        providerName: String,
        pageUrl: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = runCatching {
            app.get(pageUrl, headers = Nonton01Utils.siteHeadersFor(pageUrl), referer = pageUrl)
        }.onFailure { Log.e(TAG, "detail GET failed $pageUrl: ${it.message}") }.getOrNull() ?: return false

        val document = response.document
        collectSubtitles(pageUrl, document, subtitleCallback)

        val ajaxEmbeds = fetchAjaxEmbeds(pageUrl, document)
        val inlineEmbeds = extractEmbeds(pageUrl, response.text)
        val embeds = (ajaxEmbeds + inlineEmbeds)
            .mapNotNull { normalizeUrl(pageUrl, it) }
            .filter { isKnownPlaybackHost(it) }
            .distinct()
            .take(MAX_EMBEDS)

        Log.e(TAG, "detail=$pageUrl embeds=${embeds.size} sample=${embeds.take(4)}")
        var found = false
        for (embed in embeds) {
            found = resolveEmbed(providerName, embed, pageUrl, emitted, subtitleCallback, callback) || found
            if (found) break
        }
        return found
    }

    private suspend fun fetchAjaxEmbeds(pageUrl: String, document: Document): List<String> {
        val probes = collectAjaxProbes(document).take(MAX_AJAX_PROBES)
        if (probes.isEmpty()) return emptyList()
        val origin = originOf(pageUrl) ?: Nonton01Seeds.SOURCE_URL
        val endpoints = listOf(
            "$origin/wp-admin/admin-ajax.php",
            "${Nonton01Seeds.SOURCE_URL}/wp-admin/admin-ajax.php"
        ) + adminAjaxRegex.findAll(document.html()).mapNotNull { normalizeUrl(pageUrl, it.value) }

        val embeds = linkedSetOf<String>()
        for (endpoint in endpoints.distinct()) {
            for (probe in probes) {
                val text = runCatching {
                    app.post(
                        url = endpoint,
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to probe.post,
                            "nume" to probe.nume,
                            "type" to probe.type
                        ),
                        headers = Nonton01Utils.ajaxHeaders(pageUrl),
                        referer = pageUrl
                    ).text
                }.onFailure { Log.e(TAG, "ajax failed endpoint=$endpoint probe=$probe err=${it.message}") }.getOrNull().orEmpty()

                val normalized = decodeMaybe(text)
                val fromJson = runCatching { JSONObject(normalized).optString("embed_url") }.getOrNull().orEmpty()
                if (fromJson.isNotBlank()) embeds += fromJson
                embeds += extractEmbeds(pageUrl, normalized)
            }
        }
        return embeds.toList()
    }

    private fun collectAjaxProbes(document: Document): List<AjaxProbe> {
        val probes = linkedSetOf<AjaxProbe>()
        val selectors = listOf(
            ".dooplay_player_option",
            "#playeroptionsul li",
            "ul#playeroptionsul li",
            ".player_sorces li",
            ".player option",
            ".player-option",
            ".server",
            ".server-item",
            "[data-post][data-nume]",
            "[data-id][data-nume]"
        ).joinToString(",")

        document.select(selectors).forEach { element ->
            val post = element.attr("data-post").ifBlank { element.attr("data-id") }.ifBlank { element.attr("data-movie") }
            val nume = element.attr("data-nume").ifBlank { element.attr("data-server") }.ifBlank { element.attr("data-episode") }.ifBlank { "1" }
            val type = element.attr("data-type").ifBlank { if (document.location().contains("tvshows", true)) "tv" else "movie" }
            if (post.isNotBlank() && nume.isNotBlank()) probes += AjaxProbe(post, nume, type)
        }

        val html = document.html()
        Regex("""(?i)data-(?:post|id)\s*=\s*['"]?(\d+)['"]?""").findAll(html).forEach { postMatch ->
            val start = (postMatch.range.first - 240).coerceAtLeast(0)
            val end = (postMatch.range.last + 240).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val post = postMatch.groupValues[1]
            val nume = Regex("""(?i)data-(?:nume|server|episode)\s*=\s*['"]?(\d+)['"]?""").find(block)?.groupValues?.getOrNull(1) ?: "1"
            val type = Regex("""(?i)data-type\s*=\s*['"]?([a-zA-Z0-9_-]+)""").find(block)?.groupValues?.getOrNull(1) ?: "movie"
            probes += AjaxProbe(post, nume, type)
        }
        return probes.toList()
    }

    private suspend fun resolveEmbed(
        providerName: String,
        embedUrl: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = normalizeUrl(referer, embedUrl) ?: embedUrl
        val low = url.lowercase()
        return when {
            low.contains("cinemaz.cc") || low.contains("cinemaz.top") -> extractCinemaz(providerName, url, referer, emitted, subtitleCallback, callback)
            low.contains("01player.cc") || low.contains("abyssplayer.com") -> extractAbyssFamily(providerName, url, referer, emitted, subtitleCallback, callback)
            else -> false
        }
    }

    private suspend fun extractCinemaz(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedUrl = normalizeUrl(referer, url) ?: url
        val firstHash = hashFromCinemazUrl(fixedUrl).ifBlank { return false }
        val topHash = if (fixedUrl.contains("cinemaz.cc", true)) {
            val fireJson = runCatching {
                app.post(
                    url = fixedUrl.substringBefore("?") + "?do=getVideo",
                    data = mapOf("hash" to firstHash, "r" to referer, "s" to ""),
                    referer = referer,
                    headers = mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Accept" to "application/json, text/javascript, */*; q=0.01"
                    )
                ).text
            }.onFailure { Log.e(TAG, "cinemaz fire ajax failed $fixedUrl: ${it.message}") }.getOrNull().orEmpty()
            val videoSrc = runCatching { JSONObject(decodeMaybe(fireJson)).optString("videoSrc") }.getOrNull().orEmpty()
                .ifBlank { extractEmbeds(fixedUrl, fireJson).firstOrNull { it.contains("cinemaz.top", true) }.orEmpty() }
            hashFromCinemazUrl(videoSrc).ifBlank { firstHash }
        } else {
            firstHash
        }

        val topPlayer = "https://cinemaz.top/player/index.php?data=$topHash&do=getVideo"
        val topJson = runCatching {
            app.post(
                url = topPlayer,
                data = mapOf("hash" to topHash, "r" to "https://cinemaz.cc/"),
                referer = "https://cinemaz.top/video/$topHash",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                )
            ).text
        }.onFailure { Log.e(TAG, "cinemaz top ajax failed hash=$topHash: ${it.message}") }.getOrNull().orEmpty()

        collectSubtitlesFromText(topPlayer, topJson, subtitleCallback)
        val stream = runCatching {
            val json = JSONObject(decodeMaybe(topJson))
            json.optString("securedLink").ifBlank { json.optString("videoSource") }
        }.getOrNull().orEmpty()
            .ifBlank { extractEmbeds(topPlayer, topJson).firstOrNull { looksLikeMedia(it) }.orEmpty() }
        if (stream.isBlank()) return false

        val finalUrl = normalizeUrl(topPlayer, stream) ?: stream
        val found = emitDirect(providerName, "Cinemaz", finalUrl, "https://cinemaz.top/video/$topHash", emitted, callback)
        Log.e(TAG, "cinemaz found=$found stream=$finalUrl")
        return found
    }

    private fun hashFromCinemazUrl(url: String): String {
        val fixed = decodeMaybe(url)
        return when {
            fixed.contains("data=") -> fixed.substringAfter("data=").substringBefore("&").substringBefore("#")
            fixed.contains("/video/") -> fixed.trimEnd('/').substringAfterLast('/').substringBefore("?")
            else -> fixed.trimEnd('/').substringAfterLast('/').substringBefore("?")
        }.trim()
    }

    private suspend fun extractAbyssFamily(
        providerName: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = normalizeUrl(referer, url) ?: url
        val response = runCatching { app.get(pageUrl, headers = Nonton01Utils.siteHeadersFor(referer), referer = referer) }
            .onFailure { Log.e(TAG, "01Player GET failed $pageUrl: ${it.message}") }
            .getOrNull() ?: return false
        val html = response.text
        collectSubtitles(pageUrl, response.document, subtitleCallback)
        collectSubtitlesFromText(pageUrl, html, subtitleCallback)

        val payload = parseAbyssPayload(html) ?: return false
        val mediaJson = decryptAbyssMedia(payload) ?: return false
        val root = runCatching { JSONObject(mediaJson) }.getOrNull() ?: return false
        val mp4 = root.optJSONObject("mp4") ?: return false
        val sources = mp4.optJSONArray("sources") ?: JSONArray()
        val firstDatas = mp4.optJSONArray("fristDatas") ?: mp4.optJSONArray("firstDatas") ?: JSONArray()
        val labelsByRes = mutableMapOf<Int, String>()
        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (!source.optBoolean("status", true)) continue
            val resId = source.optInt("res_id", -1)
            val label = source.optString("label", "01Player")
            if (resId >= 0) labelsByRes[resId] = label
        }

        val playerOrigin = originOf(pageUrl) ?: "https://01player.cc"
        val playerReferer = "${playerOrigin.trimEnd('/')}/"
        var found = false

        for (i in 0 until firstDatas.length()) {
            val item = firstDatas.optJSONObject(i) ?: continue
            val videoUrl = item.optString("url").takeIf { it.isNotBlank() } ?: continue
            val resId = item.optInt("res_id", -1)
            val label = labelsByRes[resId] ?: qualityFromUrl(videoUrl)?.let { "${it}p" } ?: "01Player"
            found = emitDirect(providerName, "01Player $label", videoUrl, playerReferer, emitted, callback) || found
        }

        for (i in 0 until sources.length()) {
            val source = sources.optJSONObject(i) ?: continue
            if (!source.optBoolean("status", true)) continue
            val host = source.optString("url").trimEnd('/')
            val path = source.optString("path").trimStart('/')
            if (host.isBlank() || path.isBlank()) continue
            val label = source.optString("label", "01Player")
            val videoUrl = "$host/$path"
            found = emitDirect(providerName, "01Player $label", videoUrl, playerReferer, emitted, callback) || found
        }

        Log.e(TAG, "01Player found=$found url=$pageUrl")
        return found
    }

    private fun parseAbyssPayload(html: String): AbyssPayload? {
        val encoded = dataRegex.find(html)?.groupValues?.getOrNull(1) ?: return null
        val jsonText = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.ISO_8859_1)
        }.getOrNull() ?: return null
        val node = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val slug = node.optString("slug")
        val md5Id = node.optString("md5_id")
        val userId = node.optString("user_id")
        val media = node.optString("media")
        if (slug.isBlank() || md5Id.isBlank() || userId.isBlank() || media.isBlank()) return null
        return AbyssPayload(slug, md5Id, userId, media)
    }

    private fun decryptAbyssMedia(payload: AbyssPayload): String? {
        return runCatching {
            val key = md5Hex("${payload.userId}:${payload.slug}:${payload.md5Id}").toByteArray(Charsets.UTF_8)
            val counter = key.copyOfRange(0, 16)
            val encrypted = payload.media.toByteArray(Charsets.ISO_8859_1)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(counter))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.onFailure { Log.e(TAG, "01Player decrypt failed: ${it.message}") }.getOrNull()
    }

    private fun md5Hex(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private suspend fun emitDirect(
        providerName: String,
        label: String,
        url: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val stream = decodeMaybe(url).trim()
        if (stream.isBlank() || !emitted.add(stream)) return false
        return try {
            if (looksLikeHls(stream)) {
                val links = generateM3u8(
                    source = providerName,
                    streamUrl = stream,
                    referer = referer,
                    headers = videoHeaders(referer)
                )
                links.forEach { link -> callback(link) }
                links.isNotEmpty()
            } else {
                callback(
                    newExtractorLink(providerName, label, stream) {
                        this.referer = referer
                        this.quality = qualityFromUrl(label) ?: qualityFromUrl(stream) ?: Qualities.Unknown.value
                        this.headers = videoHeaders(referer)
                    }
                )
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "emitDirect failed $stream: ${e.message}")
            false
        }
    }

    private fun extractEmbeds(baseUrl: String, text: String): List<String> {
        val normalized = decodeMaybe(text)
        val urls = linkedSetOf<String>()
        iframeRegex.findAll(normalized).mapNotNullTo(urls) { it.groupValues.getOrNull(1) }
        quotedUrlRegex.findAll(normalized).mapNotNullTo(urls) { it.groupValues.getOrNull(1) }
        return urls.mapNotNull { normalizeUrl(baseUrl, it) }
            .filter { isKnownPlaybackHost(it) || looksLikeMedia(it) }
            .distinct()
    }

    private fun normalizeUrl(baseUrl: String, raw: String?): String? = absoluteUrl(baseUrl, raw)?.let { decodeMaybe(it) }

    private fun isKnownPlaybackHost(url: String): Boolean {
        val low = url.lowercase()
        return low.contains("cinemaz.cc") || low.contains("cinemaz.top") || low.contains("01player.cc") || low.contains("abyssplayer.com")
    }

    private fun looksLikeMedia(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains(".mp4") || low.contains("/master") || low.contains("playlist") || low.endsWith(".fd") || low.contains("sssrr.org/")
    }

    private fun looksLikeHls(url: String): Boolean {
        val low = url.lowercase()
        return low.contains(".m3u8") || low.contains("/hls/") || low.contains("master.m3u8")
    }

    private fun qualityFromUrl(value: String): Int? {
        return Regex("""(?i)(\d{3,4})p?""").find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private suspend fun collectSubtitles(pageUrl: String, document: Document, subtitleCallback: (SubtitleFile) -> Unit) {
        val urls = document.select("track[src], a[href$=.srt], a[href$=.vtt], a[href*='subtitle']")
            .mapNotNull { absoluteUrl(pageUrl, it.attr("src").ifBlank { it.attr("href") }) }
        urls.forEach { subtitleUrl ->
            val lang = when {
                subtitleUrl.contains("id", true) || subtitleUrl.contains("ind", true) -> "Indonesian"
                subtitleUrl.contains("en", true) -> "English"
                else -> "Subtitle"
            }
            runCatching { subtitleCallback(newSubtitleFile(lang, subtitleUrl)) }
        }
    }

    private suspend fun collectSubtitlesFromText(pageUrl: String, text: String, subtitleCallback: (SubtitleFile) -> Unit) {
        Regex("""https?://[^'"<>\s]+\.(?:srt|vtt)(?:\?[^'"<>\s]*)?""", RegexOption.IGNORE_CASE)
            .findAll(decodeMaybe(text))
            .map { it.value }
            .distinct()
            .forEach { subtitleUrl ->
                val lang = when {
                    subtitleUrl.contains("id", true) || subtitleUrl.contains("ind", true) -> "Indonesian"
                    subtitleUrl.contains("en", true) -> "English"
                    else -> "Subtitle"
                }
                runCatching { subtitleCallback(newSubtitleFile(lang, subtitleUrl)) }
            }
    }
}
