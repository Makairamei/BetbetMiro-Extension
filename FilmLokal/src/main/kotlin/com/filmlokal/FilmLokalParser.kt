package com.filmlokal

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.filmlokal.FilmLokalUtils.absoluteUrl
import com.filmlokal.FilmLokalUtils.cleanText
import com.filmlokal.FilmLokalUtils.durationMinutes
import com.filmlokal.FilmLokalUtils.isValidPoster
import com.filmlokal.FilmLokalUtils.isVideoUrl
import com.filmlokal.FilmLokalUtils.typeFromUrlOrTitle
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object FilmLokalParser {
    private val cardSelectors = listOf(
        "article",
        ".ml-item",
        ".movie",
        ".movie-item",
        ".item",
        ".result-item",
        ".film",
        ".box",
        ".col-md-2",
        ".col-md-3",
        ".col-sm-3",
        ".owl-item"
    ).joinToString(",")

    private const val IMAGE_SELECTOR =
        "img[src], img[data-src], img[data-lazy-src], img[data-original], img[data-wpfc-original-src], img[srcset]"

    fun parseListing(api: MainAPI, document: Document): List<SearchResponse> {
        val primary = document.select(cardSelectors)
        val containers: List<Element> = if (primary.isNotEmpty()) {
            primary.toList()
        } else {
            document.select("a[href]")
                .mapNotNull { it.parent() }
                .distinct()
        }

        return containers
            .mapNotNull { parseCard(api, it) }
            .distinctBy { it.url }
            .take(48)
    }

    private fun parseCard(api: MainAPI, element: Element): SearchResponse? {
        val image = element.selectFirst(IMAGE_SELECTOR)
            ?: element.parent()?.selectFirst(IMAGE_SELECTOR)
            ?: return null

        val link = element.selectFirst("a[href]:has(img), h2 a[href], h3 a[href], .title a[href], a[title][href]")
            ?: image.parents().select("a[href]").first()
            ?: element.selectFirst("a[href]")
            ?: return null

        val href = absoluteUrl(api.mainUrl, link.attr("href")) ?: return null
        if (!isVideoUrl(href)) return null

        val title = cleanText(
            link.attr("title").ifBlank { link.text() }.ifBlank {
                image.attr("alt").ifBlank { image.attr("title") }
            }
        ).ifBlank { return null }

        val poster = extractPoster(api.mainUrl, image, element)
        if (!isValidPoster(poster)) return null

        val type = typeFromUrlOrTitle(href, title)
        return api.newMovieSearchResponse(title, href, type) {
            posterUrl = poster
        }
    }

    suspend fun parseLoadResponse(api: MainAPI, url: String, document: Document): LoadResponse? {
        val title = cleanText(
            document.selectFirst("h1.entry-title, h1, .title, .heading")?.text()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")
        ).removeSuffix(" - Filmlokal").ifBlank { return null }

        val poster = extractPoster(
            api.mainUrl,
            document.selectFirst(".poster img, .thumb img, .mvic-thumb img, article img, img[alt]"),
            document
        ) ?: absoluteUrl(api.mainUrl, document.selectFirst("meta[property=og:image]")?.attr("content"))

        val plot = cleanText(
            document.selectFirst(".desc, .description, .entry-content p, .sinopsis, .synopsis")?.text()
                ?: document.selectFirst("meta[name=description]")?.attr("content")
        )

        val tags = document.select(
            "a[href*='/genre/'], " +
                "a[href*='/country/'], " +
                "a[href*='/year/'], " +
                "a[href*='/quality/'], " +
                "a[href*='/action/'], " +
                "a[href*='/adventure/'], " +
                "a[href*='/animation/'], " +
                "a[href*='/comedy/'], " +
                "a[href*='/crime/'], " +
                "a[href*='/drama/'], " +
                "a[href*='/fantasy/'], " +
                "a[href*='/horror/'], " +
                "a[href*='/mystery/'], " +
                "a[href*='/romance/'], " +
                "a[href*='/sci-fi/'], " +
                "a[href*='/thriller/']"
        )
            .map { cleanText(it.text()) }
            .filter { it.length in 2..30 }
            .filterNot { tag ->
                tag.equals("Watch", true) ||
                    tag.equals("Trailer", true) ||
                    tag.equals("Download", true) ||
                    tag.equals("Home", true)
            }
            .distinct()
            .take(16)

        val episodes = parseEpisodes(api, document, url)
        val recommendations = parseListing(api, document).filterNot { it.url == url }.take(12)
        val type = if (episodes.size > 1) TvType.TvSeries else typeFromUrlOrTitle(url, title)

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
        val selectors = ".eps a[href], .episode a[href], .episodes a[href], .episodelist a[href], a[href*='episode'], a[href*='season']"
        val episodes = document.select(selectors).mapNotNull { anchor ->
            val href = absoluteUrl(api.mainUrl, anchor.attr("href")) ?: return@mapNotNull null
            if (!isVideoUrl(href)) return@mapNotNull null
            val normalized = href.substringBefore("#")
            if (!seen.add(normalized)) return@mapNotNull null

            val text = cleanText(anchor.text())
                .ifBlank { cleanText(anchor.attr("title")) }
                .ifBlank { "Episode" }

            api.newEpisode(href) {
                name = text
            }
        }

        return episodes.ifEmpty {
            listOf(
                api.newEpisode(fallbackUrl) {
                    name = "Movie"
                }
            )
        }
    }

    fun extractPoster(baseUrl: String, image: Element?, container: Element? = null): String? {
        val candidates = mutableListOf<String?>()
        if (image != null) {
            candidates += image.attr("data-src")
            candidates += image.attr("data-lazy-src")
            candidates += image.attr("data-original")
            candidates += image.attr("data-wpfc-original-src")
            candidates += image.attr("srcset")
                .split(",")
                .firstOrNull()
                ?.trim()
                ?.substringBefore(" ")
            candidates += image.attr("src")
        }
        if (container != null) {
            candidates += container.selectFirst("meta[property=og:image]")?.attr("content")
            candidates += container.selectFirst("noscript img[src]")?.attr("src")
            val style = container.attr("style")
                .ifBlank { container.selectFirst("[style*=background]")?.attr("style").orEmpty() }
            candidates += Regex("""url\((['\"]?)(.*?)\1\)""").find(style)?.groupValues?.getOrNull(2)
        }
        return candidates.asSequence()
            .mapNotNull { absoluteUrl(baseUrl, it) }
            .firstOrNull { isValidPoster(it) }
    }
}
