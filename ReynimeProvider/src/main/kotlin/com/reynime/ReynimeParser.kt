package com.reynime

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject

object ReynimeParser {
    val backendVideoKeys = listOf(
        "file", "url", "src", "source", "video", "video_url", "stream_url", "embed", "iframe", "player"
    )

    fun parseSearchItems(doc: Document, mainUrl: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        val selectors = listOf(
            "article", ".bsx", ".bs", ".listupd article", ".listupd .bs", ".animepost", ".item"
        )
        selectors.forEach { selector ->
            doc.select(selector).forEach { el ->
                val link = el.selectFirst("a[href]")?.attr("abs:href")?.ifBlank { el.selectFirst("a[href]")?.attr("href") }.orEmpty()
                val title = el.selectFirst("h1, h2, h3, h4, .tt, .title")?.text()?.trim()
                    ?: el.selectFirst("a[title]")?.attr("title")?.trim()
                    ?: return@forEach
                if (link.isBlank() || !seen.add(link)) return@forEach
                val poster = el.selectFirst("img")?.attr("abs:src")?.ifBlank {
                    el.selectFirst("img")?.attr("data-src")?.let { ReynimeUtils.fixUrl(mainUrl, it) }
                }
                results.add(newAnimeSearchResponse(title, link, TvType.Anime) {
                    this.posterUrl = poster
                })
            }
            if (results.isNotEmpty()) return@forEach
        }
        return results
    }

    fun parseLoad(doc: Document, url: String): LoadResponse {
        val title = doc.selectFirst("h1, .entry-title, .infox h1")?.text()?.trim()
            ?: doc.title().ifBlank { "Reynime" }
        val poster = doc.selectFirst("img")?.attr("abs:src")?.ifBlank {
            doc.selectFirst("img")?.attr("data-src")
        }
        val plot = doc.selectFirst(".entry-content p, .desc, .synp, .info-content, .summary")?.text()?.trim()
        val episodeId = doc.selectFirst("[data-episode-id]")?.attr("data-episode-id")
        val seriesId = doc.selectFirst("[data-series-id]")?.attr("data-series-id")
        val seedSlug = url.substringAfterLast('/').substringBefore('?').ifBlank { null }
        val epNum = ReynimeUtils.extractEpisodeNumber(title)
        val payload = ReynimeUtils.encode(
            ReynimePlaybackData(
                pageUrl = url,
                episodeId = episodeId,
                seriesId = seriesId,
                episodeNumber = epNum,
                seedSlug = seedSlug,
                title = title
            )
        )
        return newAnimeLoadResponse(title, payload, TvType.Anime) {
            posterUrl = poster
            this.plot = plot
            val episodes = doc.select(".eplister a[href], .episodelist a[href], .episode-list a[href], a[href*='episode']")
                .mapIndexed { index, el ->
                    Episode(
                        data = el.attr("abs:href").ifBlank { el.attr("href") },
                        name = el.text().ifBlank { "Episode ${index + 1}" }
                    )
                }
            if (episodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, episodes.reversed())
        }
    }

    fun parseBackendEpisodeRecords(text: String): List<ReynimeBackendEpisode> {
        val results = mutableListOf<ReynimeBackendEpisode>()
        val root = parseJsonRoot(text) ?: return emptyList()
        val objects = mutableListOf<JSONObject>()
        collectJsonObjects(root, objects)
        objects.forEach { obj ->
            val id = obj.stringValue("id", "episode_id", "post_id")
            val title = obj.stringValue("title", "name")
            val episodeNumber = obj.stringValue("episode", "ep", "episode_number")
            val urls = linkedSetOf<String>()
            backendVideoKeys.mapNotNull { key -> obj.stringValue(key) }.forEach(urls::add)
            obj.optJSONArray("urls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i)?.takeIf { it.isNotBlank() }?.let(urls::add)
                }
            }
            if (urls.isNotEmpty()) {
                results.add(ReynimeBackendEpisode(id, title, episodeNumber, urls.toList()))
            }
        }
        return results
    }

    fun parseJsonRoot(text: String): JSONObject? {
        val trimmed = text.trim()
        return runCatching {
            when {
                trimmed.startsWith("{") -> JSONObject(trimmed)
                trimmed.startsWith("[") -> JSONObject().put("items", JSONArray(trimmed))
                else -> null
            }
        }.getOrNull()
    }

    fun collectJsonObjects(root: JSONObject, objects: MutableList<JSONObject>) {
        fun walk(any: Any?) {
            when (any) {
                is JSONObject -> {
                    objects.add(any)
                    any.keys().forEach { key -> walk(any.opt(key)) }
                }
                is JSONArray -> {
                    for (i in 0 until any.length()) walk(any.opt(i))
                }
            }
        }
        walk(root)
    }

    fun JSONObject.stringValue(vararg keys: String): String? {
        keys.forEach { key ->
            val value = optString(key)
            if (!value.isNullOrBlank() && value != "null") return value
        }
        return null
    }
}
