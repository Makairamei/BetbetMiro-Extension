package com.pasarbokep

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newMovieSearchResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object PasarBokepParser {
    fun parseCards(document: Document, api: MainAPI, scoped: Boolean = false): List<SearchResponse> {
        val cardList = if (scoped) {
            val scopedCards = parseScopedCards(document, api.mainUrl)
            if (scopedCards.isNotEmpty()) scopedCards else parseLooseCards(document, api.mainUrl)
        } else {
            parseLooseCards(document, api.mainUrl)
        }

        return cardList.map { card ->
            api.newMovieSearchResponse(card.title, card.url, TvType.NSFW) {
                this.posterUrl = card.posterUrl
            }
        }
    }

    fun parseTags(document: Document): List<String> {
        return document.select("a[href*=/category/], a[rel=category tag], .cat-links a")
            .map { PasarBokepUtils.cleanText(it.text()) }
            .filter { it.length > 2 }
            .distinct()
            .take(8)
    }

    fun parsePlot(document: Document): String? {
        val content = document.selectFirst(
            ".entry-content, .post-content, .single-content, article .content, article, main"
        ) ?: return null

        val text = content.text()
            .substringBefore("Related videos")
            .substringBefore("Bokep Terbaru")
            .substringBefore("Latest videos")
            .substringBefore("Registration is disabled")

        return PasarBokepUtils.cleanText(text).takeIf { it.length > 12 }
    }

    private fun parseLooseCards(document: Document, mainUrl: String): List<PasarBokepCard> {
        val cards = linkedMapOf<String, PasarBokepCard>()

        document.select("article, .post, .video, .item, .thumb, .loop, .entry, li").forEach { block ->
            parseBlock(block, mainUrl)?.let { card ->
                cards.putIfAbsent(card.url, card)
            }
        }

        document.select("main a[href], #content a[href], .site-content a[href], body a[href]").forEach { anchor ->
            parseAnchor(anchor, mainUrl)?.let { card ->
                cards.putIfAbsent(card.url, card)
            }
        }

        return cards.values.toList()
    }

    private fun parseScopedCards(document: Document, mainUrl: String): List<PasarBokepCard> {
        val body = document.body() ?: return emptyList()
        val cards = linkedMapOf<String, PasarBokepCard>()
        var collecting = false

        for (element in body.getAllElements()) {
            if (!collecting) {
                if (isCategoryStart(element)) collecting = true
                continue
            }

            if (isScopedStop(element)) break
            if (isUiOnlyNode(element)) continue

            val card = when {
                isCardBlock(element) -> parseBlock(element, mainUrl)
                element.tagName().equals("a", ignoreCase = true) && element.hasAttr("href") -> parseAnchor(element, mainUrl)
                else -> null
            } ?: continue

            cards.putIfAbsent(card.url, card)
        }

        return cards.values.toList()
    }

    private fun parseBlock(block: Element, mainUrl: String): PasarBokepCard? {
        val anchor = block.selectFirst("a[href]") ?: return null
        val href = PasarBokepUtils.absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        val title = PasarBokepUtils.cleanText(
            block.selectFirst("h1, h2, h3, .entry-title, .post-title, .title")?.text()
                ?.ifBlank { anchor.attr("title") }
                ?.ifBlank { anchor.text() }
                ?.ifBlank { block.selectFirst("img")?.attr("alt").orEmpty() }
                ?: anchor.attr("title")
                    .ifBlank { anchor.text() }
                    .ifBlank { block.selectFirst("img")?.attr("alt").orEmpty() }
        ).ifBlank { PasarBokepUtils.titleFromUrl(href) }

        if (!PasarBokepUtils.isLikelyVideoPage(href, title, mainUrl)) return null
        return PasarBokepCard(title = title, url = href, posterUrl = block.bestPosterFromBlock(mainUrl))
    }

    private fun parseAnchor(anchor: Element, mainUrl: String): PasarBokepCard? {
        val href = PasarBokepUtils.absoluteUrl(anchor.attr("href"), mainUrl) ?: return null
        val title = PasarBokepUtils.cleanText(
            anchor.attr("title")
                .ifBlank { anchor.text() }
                .ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
        ).ifBlank { PasarBokepUtils.titleFromUrl(href) }

        if (!PasarBokepUtils.isLikelyVideoPage(href, title, mainUrl)) return null
        val block = anchor.closest("article, .post, .video, .item, .thumb, .loop, .entry, li") ?: anchor
        return PasarBokepCard(title = title, url = href, posterUrl = block.bestPosterFromBlock(mainUrl))
    }

    private fun isCategoryStart(element: Element): Boolean {
        val tag = element.tagName().lowercase()
        if (tag != "h1" && tag != "h2") return false
        val text = PasarBokepUtils.cleanText(element.text()).lowercase()
        return text.startsWith("category:") || text.contains("category:")
    }

    private fun isScopedStop(element: Element): Boolean {
        val tag = element.tagName().lowercase()
        if (tag != "h2" && tag != "h3" && tag != "form" && tag != "footer") return false
        val text = PasarBokepUtils.cleanText(element.text()).lowercase()
        return text.contains("bokep terbaru") ||
            text.contains("latest videos") ||
            text.contains("related videos") ||
            text.contains("registration is disabled") ||
            text.contains("login to") ||
            text.contains("reset password")
    }

    private fun isUiOnlyNode(element: Element): Boolean {
        val text = PasarBokepUtils.cleanText(element.text()).lowercase()
        return text == "latest videos" ||
            text == "random videos" ||
            text == "show more" ||
            text.length < 2
    }

    private fun isCardBlock(element: Element): Boolean {
        return element.`is`("article, .post, .video, .item, .thumb, .loop, .entry, li")
    }

    private fun Element.bestPosterFromBlock(mainUrl: String): String? {
        return this.bestImage(mainUrl)
            ?: parent()?.bestImage(mainUrl)
            ?: selectFirst("img")?.let { PasarBokepUtils.absoluteUrl(it.attr("src"), mainUrl) }
    }
}
