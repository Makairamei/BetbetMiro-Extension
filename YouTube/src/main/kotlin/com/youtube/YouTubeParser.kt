package com.youtube

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.youtube.YouTubeUtils.canonicalWatchUrl
import com.youtube.YouTubeUtils.cleanTitle
import com.youtube.YouTubeUtils.decodeJsonString
import com.youtube.YouTubeUtils.extractObjectAfter
import com.youtube.YouTubeUtils.extractObjectsByKey
import com.youtube.YouTubeUtils.firstJsonValue
import com.youtube.YouTubeUtils.normalizeUrl
import com.youtube.YouTubeUtils.textFromJsonObject
import com.youtube.YouTubeUtils.videoIdFromUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

object YouTubeParser {
    fun parseHtml(api: MainAPI, html: String, baseUrl: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val initialData = extractInitialData(html)
        if (initialData != null) {
            parseVideoRenderers(api, initialData).forEach { results[it.url] = it }
            parseGridVideoRenderers(api, initialData).forEach { results[it.url] = it }
            parseReelRenderers(api, initialData).forEach { results[it.url] = it }
        }

        if (results.isEmpty()) {
            val document = Jsoup.parse(html, baseUrl)
            parseAnchors(api, document, baseUrl).forEach { results[it.url] = it }
        }
        return results.values.distinctBy { it.url }.take(40)
    }

    fun parseRss(api: MainAPI, document: Document): List<SearchResponse> {
        return document.getElementsByTag("entry").mapNotNull { entry ->
            val videoId = entry.getElementsByTag("yt:videoId").firstOrNull()?.text()
                ?: entry.getElementsByTag("videoId").firstOrNull()?.text()
                ?: videoIdFromUrl(entry.getElementsByTag("link").firstOrNull()?.attr("href").orEmpty())
                ?: return@mapNotNull null
            val title = cleanTitle(entry.getElementsByTag("title").firstOrNull()?.text()).takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val url = canonicalWatchUrl(videoId)
            val poster = entry.getElementsByTag("media:thumbnail").firstOrNull()?.attr("url")
                ?.takeIf { it.isNotBlank() }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            val channel = entry.getElementsByTag("author").firstOrNull()?.getElementsByTag("name")?.firstOrNull()?.text()
            val published = entry.getElementsByTag("published").firstOrNull()?.text()
            val description = entry.getElementsByTag("media:description").firstOrNull()?.text()

            api.toSearchResponse(
                YouTubeVideo(
                    id = videoId,
                    title = title,
                    url = url,
                    poster = poster,
                    channel = channel,
                    description = description,
                    published = published
                )
            )
        }.distinctBy { it.url }.take(40)
    }

    fun parseMeta(document: Document, html: String, pageUrl: String): YouTubeMeta? {
        val initialData = extractInitialData(html).orEmpty()
        val playerResponse = extractPlayerResponse(html).orEmpty()
        val videoId = videoIdFromUrl(pageUrl) ?: firstJsonValue(playerResponse, "videoId") ?: firstJsonValue(initialData, "videoId")
        val url = videoId?.let { canonicalWatchUrl(it) } ?: pageUrl
        val title = cleanTitle(
            document.selectFirst("meta[property=og:title], meta[name=twitter:title]")?.attr("content")
                ?: firstJsonValue(playerResponse, "title")
                ?: textFromJsonObject(initialData, "title")
        ).takeIf { it.isNotBlank() } ?: return null
        val poster = normalizeUrl(
            document.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
                ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
            pageUrl
        ).takeIf { it.isNotBlank() }
        val description = (
            document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
                ?: firstJsonValue(playerResponse, "shortDescription")
                ?: textFromJsonObject(initialData, "descriptionSnippet")
        )?.decodeJsonString()?.takeIf { it.isNotBlank() }
        val channel = document.selectFirst("link[itemprop=name], span[itemprop=author] link[itemprop=name]")?.attr("content")
            ?: textFromJsonObject(initialData, "ownerText")
            ?: textFromJsonObject(initialData, "longBylineText")
        val published = textFromJsonObject(initialData, "dateText") ?: textFromJsonObject(initialData, "publishedTimeText")
        val keywords = document.selectFirst("meta[name=keywords]")?.attr("content")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && it.length <= 40 }
            .orEmpty()

        return YouTubeMeta(
            title = title,
            url = url,
            poster = poster,
            description = description,
            channel = channel?.decodeJsonString()?.takeIf { it.isNotBlank() },
            published = published,
            tags = keywords.take(12)
        )
    }

    fun parseRecommendations(api: MainAPI, html: String): List<SearchResponse> {
        val initialData = extractInitialData(html) ?: return emptyList()
        return parseVideoRenderers(api, initialData).take(20)
    }

    private fun parseVideoRenderers(api: MainAPI, json: String): List<SearchResponse> {
        return extractObjectsByKey(json, "videoRenderer")
            .mapNotNull { parseVideoRenderer(it) }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.id }
            .map { api.toSearchResponse(it) }
    }

    private fun parseGridVideoRenderers(api: MainAPI, json: String): List<SearchResponse> {
        return extractObjectsByKey(json, "gridVideoRenderer")
            .mapNotNull { parseVideoRenderer(it) }
            .filter { it.title.isNotBlank() }
            .distinctBy { it.id }
            .map { api.toSearchResponse(it) }
    }

    private fun parseReelRenderers(api: MainAPI, json: String): List<SearchResponse> {
        return extractObjectsByKey(json, "reelItemRenderer")
            .mapNotNull { block ->
                val id = firstJsonValue(block, "videoId") ?: return@mapNotNull null
                val title = cleanTitle(textFromJsonObject(block, "headline") ?: firstJsonValue(block, "headline"))
                    .takeIf { it.isNotBlank() } ?: "YouTube Shorts"
                val poster = extractThumbnail(block, id)
                YouTubeVideo(
                    id = id,
                    title = title,
                    url = canonicalWatchUrl(id),
                    poster = poster,
                    duration = "Shorts"
                )
            }
            .distinctBy { it.id }
            .map { api.toSearchResponse(it) }
    }

    private fun parseVideoRenderer(block: String): YouTubeVideo? {
        val id = firstJsonValue(block, "videoId") ?: return null
        val title = cleanTitle(textFromJsonObject(block, "title") ?: firstJsonValue(block, "title"))
            .takeIf { it.isNotBlank() } ?: return null
        if (title.equals("Shorts", true) || title.equals("YouTube", true)) return null
        val poster = extractThumbnail(block, id)
        val channel = textFromJsonObject(block, "ownerText") ?: textFromJsonObject(block, "longBylineText") ?: textFromJsonObject(block, "shortBylineText")
        val views = textFromJsonObject(block, "viewCountText")
        val published = textFromJsonObject(block, "publishedTimeText")
        val duration = textFromJsonObject(block, "lengthText")
        val description = textFromJsonObject(block, "descriptionSnippet")
        return YouTubeVideo(
            id = id,
            title = title,
            url = canonicalWatchUrl(id),
            poster = poster,
            channel = channel,
            description = description,
            published = published,
            views = views,
            duration = duration
        )
    }

    private fun parseAnchors(api: MainAPI, document: Document, baseUrl: String): List<SearchResponse> {
        return document.select("a[href*=/watch?v=], a[href*=youtu.be/], a[href*=/shorts/]")
            .mapNotNull { anchor ->
                val href = normalizeUrl(anchor.attr("href"), baseUrl)
                val videoId = videoIdFromUrl(href) ?: return@mapNotNull null
                val title = cleanTitle(anchor.attr("title").ifBlank { anchor.text() }).takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                api.toSearchResponse(
                    YouTubeVideo(
                        id = videoId,
                        title = title,
                        url = canonicalWatchUrl(videoId),
                        poster = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    )
                )
            }
            .distinctBy { it.url }
            .take(40)
    }

    private fun MainAPI.toSearchResponse(video: YouTubeVideo): SearchResponse {
        return newMovieSearchResponse(video.title, video.url, TvType.Movie, initializer = {
            posterUrl = video.poster
        })
    }

    private fun extractThumbnail(block: String, videoId: String): String? {
        val thumbnailBlock = extractObjectAfter(block, "\"thumbnail\":") ?: block
        return Regex(""""url"\s*:\s*"((?:https?:)?//[^"\\]*(?:ytimg|ggpht)[^"\\]*)""", RegexOption.IGNORE_CASE)
            .findAll(thumbnailBlock)
            .map { normalizeUrl(it.groupValues.getOrNull(1), YouTubeSeeds.MAIN_URL) }
            .filter { it.isNotBlank() }
            .lastOrNull()
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun extractInitialData(html: String): String? {
        return extractObjectAfter(html, "var ytInitialData =")
            ?: extractObjectAfter(html, "window[\"ytInitialData\"] =")
            ?: extractObjectAfter(html, "ytInitialData")
    }

    private fun extractPlayerResponse(html: String): String? {
        return extractObjectAfter(html, "var ytInitialPlayerResponse =")
            ?: extractObjectAfter(html, "ytInitialPlayerResponse")
    }
}
