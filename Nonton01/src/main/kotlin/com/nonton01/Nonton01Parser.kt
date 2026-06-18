package com.nonton01

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.nonton01.Nonton01Utils.absoluteUrl
import com.nonton01.Nonton01Utils.cleanText
import com.nonton01.Nonton01Utils.durationMinutes
import com.nonton01.Nonton01Utils.isValidPoster
import com.nonton01.Nonton01Utils.isFilteredContent
import com.nonton01.Nonton01Utils.isVideoUrl
import com.nonton01.Nonton01Utils.typeFromUrlOrTitle
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object Nonton01Parser {
    private val cardSelectors = listOf(
        "article.item.movies",
        "article.item.tvshows",
        "article.item",
        ".items article",
        ".result-item",
        ".movie-item",
        ".ml-item",
        ".post:has(img)",
        ".item:has(img)",
        "a[href*='/movies/']:has(img)",
        "a[href*='/tvshows/']:has(img)",
        "a[href*='/movie/']:has(img)",
        "a[href*='/episode/']:has(img)"
    ).joinToString(",")

    private const val IMAGE_SELECTOR =
        "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[srcset]"

    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val containers = document.select(cardSelectors).ifEmpty { document.select("a[href]:has(img)") }
        return containers
            .mapNotNull { parseCard(api, it) }
            .distinctBy { it.url }
            .take(60)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val link = when {
            element.tagName().equals("a", true) && element.hasAttr("href") -> element
            else -> element.selectFirst("a[href]:has(img)")
                ?: element.selectFirst("h2 a[href], h3 a[href], .title a[href], .data h3 a[href], a[title][href]")
                ?: return null
        }
        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val image = link.selectFirst(IMAGE_SELECTOR)
            ?: element.selectFirst(IMAGE_SELECTOR)
            ?: element.parent()?.selectFirst(IMAGE_SELECTOR)
        val title = cleanTitle(
            link.attr("title")
                .ifBlank { link.attr("aria-label") }
                .ifBlank { link.text() }
                .ifBlank { element.selectFirst(".data h3, h3, h2, .title")?.text().orEmpty() }
                .ifBlank { image?.attr("alt").orEmpty() }
                .ifBlank { image?.attr("title").orEmpty() }
        ).ifBlank { return null }
        if (isFilteredContent("$href $title ${element.text()}")) return null

        val poster = extractPoster(api.mainUrl, image, element)
        val type = typeFromUrlOrTitle(href, title)
        return api.newMovieSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = extractDetailTitle(document, url) ?: return null
        val metaText = listOf(
            url,
            title,
            document.title(),
            document.selectFirst("meta[name=description]")?.attr("content").orEmpty(),
            document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty(),
            document.select("a[href*='/genre/']").joinToString(" ") { it.text() + " " + it.attr("href") }
        ).joinToString(" ")
        if (isFilteredContent(metaText)) return null

        val poster = extractDetailPoster(api.mainUrl, title, document)
            ?: extractPoster(
                api.mainUrl,
                document.selectFirst(".poster img, .thumb img, .mvic-thumb img, .wp-post-image, img[src*='uploads'], img[alt], img[title]"),
                document
            )
            ?: absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))
                ?.takeIf { isValidPoster(it) }

        val plot = cleanText(
            document.selectFirst(".desc, .description, .entry-content p, .sinopsis, .synopsis, .wp-content p")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )

        val tags = document.select("a[href*='/genre/'], a[href*='/country/'], a[href*='/year/'], a[href*='/quality/']")
            .map { cleanTitle(it.text()) }
            .filter { it.length in 2..35 }
            .filterNot { tag -> tag.equals("Watch", true) || tag.equals("Trailer", true) || tag.equals("Download", true) || tag.equals("Home", true) || isFilteredContent(tag) }
            .distinct()
            .take(16)

        val recommendations = parseRecommendations(api, document, url)
        val episodes = parseEpisodes(api, document, url)
        val detectedType = typeFromUrlOrTitle(url, title)
        val type = if (detectedType == TvType.TvSeries || episodes.size > 1) TvType.TvSeries else detectedType

        return if (type == TvType.TvSeries) {
            api.newTvSeriesLoadResponse(title, url, type, episodes) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            api.newMovieLoadResponse(title, url, type, url) {
                posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
                duration = durationMinutes(document.text())
            }
        }
    }

    private fun parseEpisodes(api: MainAPI, document: Document, fallbackUrl: String): List<Episode> {
        val seen = linkedSetOf<String>()
        val selectors = listOf(
            "#seasons a[href]",
            ".se-c a[href]",
            ".episodios a[href]",
            ".episodes a[href]",
            ".episodelist a[href]",
            ".episode a[href]",
            ".eps a[href]",
            "a[href*='/episode/']",
            "a[href*='/episodes/']",
            "a[href*='season']"
        ).joinToString(",")

        val episodes = document.select(selectors).mapNotNull { anchor ->
            val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return@mapNotNull null
            if (!isVideoUrl(href)) return@mapNotNull null
            if (isFilteredContent("$href ${anchor.text()} ${anchor.attr("title")}")) return@mapNotNull null
            val normalized = href.substringBefore("#")
            if (!seen.add(normalized)) return@mapNotNull null
            val rawText = cleanTitle(anchor.text())
                .ifBlank { cleanTitle(anchor.attr("title")) }
                .ifBlank { titleFromUrl(href) ?: "Episode" }
            api.newEpisode(href) {
                name = rawText
                episode = episodeNumber(rawText, href)
                season = seasonNumber(rawText, href)
            }
        }

        return episodes.ifEmpty {
            listOf(api.newEpisode(fallbackUrl) { name = if (fallbackUrl.contains("tvshows", true)) "Episode" else "Movie" })
        }
    }

    private fun parseRecommendations(api: MainAPI, document: Document, currentUrl: String): List<SearchResponse> {
        val relatedSelectors = listOf(
            ".related",
            ".recommended",
            ".recommend",
            ".similar",
            ".more-like-this",
            ".film-recommend",
            ".movie-related",
            ".movies-related",
            "#related",
            "#recommended"
        ).joinToString(",")
        val related = document.selectFirst(relatedSelectors)?.select(cardSelectors)?.mapNotNull { parseCard(api, it) }.orEmpty()
        return (related.ifEmpty { parseListing(api, document) })
            .filterNot { it.url == currentUrl }
            .distinctBy { it.url }
            .take(12)
    }

    private fun extractDetailPoster(baseUrl: String, title: String, document: Document): String? {
        val tokens = titleTokens(title)
        val candidates = mutableListOf<Pair<Int, String>>()
        document.select(IMAGE_SELECTOR).forEach { image ->
            val sourceText = listOf(
                image.attr("alt"), image.attr("title"), image.attr("aria-label"),
                image.attr("src"), image.attr("data-src"), image.attr("data-lazy-src"), image.attr("data-original")
            ).joinToString(" ").lowercase()
            val ancestry = generateSequence(image) { it.parent() }
                .take(5)
                .joinToString(" ") { "${it.id()} ${it.className()}" }
                .lowercase()
            imageSourceCandidates(image).forEach { raw ->
                val poster = absoluteUrl(baseUrl, raw)?.let { upscalePosterUrl(it) } ?: return@forEach
                if (!isValidPoster(poster)) return@forEach
                val candidateText = "$sourceText ${poster.substringAfterLast('/').substringBefore('?').lowercase()}"
                val tokenHits = tokens.count { candidateText.contains(it) }
                var score = tokenHits * 30
                if (tokens.isNotEmpty() && tokenHits >= minOf(2, tokens.size)) score += 80
                if (ancestry.contains("poster") || ancestry.contains("thumb") || ancestry.contains("mvic") || ancestry.contains("post-thumbnail")) score += 40
                if (poster.contains("/wp-content/uploads/", true)) score += 20
                candidates += score to poster
            }
        }
        return candidates.filter { it.first > 0 }
            .distinctBy { it.second }
            .sortedByDescending { it.first }
            .firstOrNull()
            ?.second
    }

    fun extractPoster(baseUrl: String, image: Element?, container: Element? = null): String? {
        val candidates = mutableListOf<String?>()
        if (image != null) candidates += imageSourceCandidates(image)
        if (container != null) {
            candidates += container.selectFirst("noscript img[src]")?.attr("src")
            candidates += container.selectFirst("img[data-src], img[data-lazy-src], img[data-original], img[src]")?.let { imageSourceCandidates(it).firstOrNull() }
            val style = container.attr("style").ifBlank { container.selectFirst("[style*=background]")?.attr("style").orEmpty() }
            candidates += Regex("""url\((['\"]?)(.*?)\1\)""").find(style)?.groupValues?.getOrNull(2)
            candidates += container.selectFirst("meta[property=og:image]")?.attr("content")
        }
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(baseUrl, it)?.let { poster -> upscalePosterUrl(poster) } }
            .firstOrNull { isValidPoster(it) }
    }

    private fun imageSourceCandidates(image: Element): List<String> {
        val sources = mutableListOf<String>()
        listOf("data-src", "data-lazy-src", "data-original", "data-wpfc-original-src", "src").forEach { attr ->
            image.attr(attr).takeIf { it.isNotBlank() }?.let { sources += it }
        }
        listOf("data-srcset", "srcset").forEach { attr ->
            image.attr(attr).split(',').map { it.trim().substringBefore(' ') }.filter { it.isNotBlank() }.forEach { sources += it }
        }
        return sources.distinct()
    }

    private fun extractDetailTitle(document: Document, url: String): String? {
        val candidates = listOfNotNull(
            document.selectFirst("h1.entry-title, h1.post-title, .single-title h1, .entry-header h1, h1")?.text(),
            document.selectFirst("meta[property=og:title]")?.attr("content"),
            document.selectFirst("meta[name=twitter:title]")?.attr("content"),
            document.selectFirst("meta[name=title]")?.attr("content"),
            document.title(),
            titleFromUrl(url)
        )
        return candidates.asSequence().map { cleanDetailTitle(it) }.firstOrNull { it.length >= 3 }
    }

    private fun cleanTitle(value: String?): String = cleanText(value)
        .replace(Regex("(?i)\\b(nonton|streaming|download|subtitle indonesia|sub indo|bluray|webrip|web-dl|hdrip|brrip|dvdrip)\\b"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '|', ':')

    private fun cleanDetailTitle(value: String): String = cleanTitle(value)
        .substringBefore(" - 01NONTON", "")
        .substringBefore(" | 01NONTON", "")
        .substringBefore(" - Nonton", "")
        .ifBlank { cleanTitle(value) }

    private fun titleFromUrl(url: String): String? {
        val slug = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (slug.isBlank()) return null
        return slug.replace('-', ' ').replace('_', ' ').split(' ')
            .joinToString(" ") { part -> part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
    }

    private fun titleTokens(title: String): List<String> {
        val ignored = setOf("sub", "dub", "the", "movie", "film", "and", "with", "untuk")
        return cleanText(title.lowercase())
            .replace(Regex("[^a-z0-9]+"), " ")
            .split(' ')
            .map { it.trim() }
            .filter { it.length >= 3 && it !in ignored && it.toIntOrNull() == null }
            .take(6)
    }

    private fun upscalePosterUrl(url: String): String = url.replace(
        Regex("-\\d+x\\d+(?=\\.(?:jpg|jpeg|png|webp)(?:\\?|$))", RegexOption.IGNORE_CASE),
        ""
    )

    private fun episodeNumber(text: String, url: String): Int? {
        val raw = "$text $url"
        return Regex("(?i)(?:episode|eps|ep)[^0-9]{0,4}(\\d{1,4})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("(?i)[?&]episode=(\\d{1,4})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun seasonNumber(text: String, url: String): Int? {
        val raw = "$text $url"
        return Regex("(?i)(?:season|seas|s)[^0-9]{0,4}(\\d{1,3})").find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}
