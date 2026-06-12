package com.sad25kag.mynimeku

import com.lagradost.cloudstream3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

object MynimekuParser {
    fun pageUrl(path: String, page: Int): String {
        val first = MynimekuUtils.normalizeUrl(path)
        if (page <= 1) return first
        return "${first.trimEnd('/')}/page/$page/"
    }

    fun hasNextPage(document: Document, nextPage: Int): Boolean {
        return document.selectFirst("a.next, a[rel=next], a[href*='/page/$nextPage/'], .pagination a[href*='/page/$nextPage/']") != null
    }

    fun cards(document: Document): List<MynimekuCard> {
        val exact = document.select(
            "article.mynimeku-mix-feed__item, " +
                "article.mynimeku-update-feed__item, " +
                "li.mynimeku-update-widget__item"
        ).mapNotNull { cardFromElement(it) }
            .distinctBy { it.url }

        if (exact.isNotEmpty()) return exact

        return document.select(
            "a.mynimeku-mix-feed__series-title[href*='/series/'], " +
                "a.mynimeku-update-feed__series-title[href*='/series/'], " +
                "a.mynimeku-update-widget__series-title[href*='/series/'], " +
                "a[href*='/series/']"
        ).mapNotNull { cardFromElement(it) }
            .distinctBy { it.url }
    }

    private fun cardFromElement(element: Element): MynimekuCard? {
        val anchor = if (element.tagName().equals("a", true)) {
            element
        } else {
            element.selectFirst(
                "a.mynimeku-mix-feed__series-title[href*='/series/'], " +
                    "a.mynimeku-update-feed__series-title[href*='/series/'], " +
                    "a.mynimeku-update-widget__series-title[href*='/series/'], " +
                    "a.mynimeku-mix-feed__cover[href*='/series/'], " +
                    "a.mynimeku-update-feed__cover[href*='/series/'], " +
                    "a.mynimeku-update-widget__cover[href*='/series/'], " +
                    "a[href*='/series/']"
            )
        } ?: return null

        val url = MynimekuUtils.normalizeUrl(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
        if (!MynimekuUtils.isMynimekuContent(url) || MynimekuUtils.isBadContentOrPlayer(url)) return null

        val rawTitle = element.selectFirst(
            ".mynimeku-mix-feed__series-title, " +
                ".mynimeku-update-feed__series-title, " +
                ".mynimeku-update-widget__series-title"
        )?.text()?.trim().orEmpty().ifBlank {
            element.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()
        }.ifBlank {
            anchor.attr("title").trim()
        }.ifBlank {
            url.trimEnd('/').substringAfterLast('/').replace("-", " ")
        }

        val title = MynimekuUtils.cleanTitle(rawTitle)
        if (title.length < 2 || MynimekuUtils.isNoiseText(title)) return null

        val sourceType = element.selectFirst(
            ".mynimeku-mix-feed__type, " +
                ".mynimeku-update-feed__badge, " +
                ".mynimeku-update-widget__type"
        )?.text()?.trim()

        val episode = MynimekuUtils.parseEpisodeNumber(
            element.selectFirst(".mynimeku-update-feed__latest-pill, .mynimeku-update-widget__latest-pill")?.text()?.trim()
        ) ?: MynimekuUtils.parseEpisodeNumber(element.text())

        return MynimekuCard(
            title = title,
            url = url,
            posterUrl = posterFrom(element, url),
            tvType = detectType(url, sourceType, rawTitle),
            episode = episode
        )
    }

    fun recommendations(document: Document): List<MynimekuCard> {
        return cards(document).take(20)
    }

    fun detailTitle(document: Document, fallbackUrl: String): String {
        return document.selectFirst("h1.komik-series-hero__title")?.text()?.trim().orEmpty()
            .ifBlank { document.selectFirst("h1.mynimeku-episode-head__title")?.text()?.trim().orEmpty() }
            .ifBlank {
                document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.substringBefore(" - MyNimeku")
                    ?.substringBefore(" – MyNimeku")
                    ?.substringBefore(" | MyNimeku")
                    ?.trim()
                    .orEmpty()
            }
            .ifBlank {
                document.title()
                    .substringBefore(" - MyNimeku")
                    .substringBefore(" – MyNimeku")
                    .substringBefore(" | MyNimeku")
                    .trim()
            }
            .ifBlank { fallbackUrl.trimEnd('/').substringAfterLast('/').replace("-", " ") }
            .let { MynimekuUtils.cleanTitle(it) }
    }

    fun poster(document: Document, base: String): String? {
        val cover = document.selectFirst(".komik-series-hero__cover")
        return posterFrom(cover ?: document, base)
            ?: MynimekuUtils.resolveUrlOrNull(document.selectFirst("meta[property=og:image]")?.attr("content"), base)
                ?.takeIf { MynimekuUtils.isValidImage(it) }
            ?: MynimekuUtils.resolveUrlOrNull(document.selectFirst("meta[name=twitter:image]")?.attr("content"), base)
                ?.takeIf { MynimekuUtils.isValidImage(it) }
    }

    private fun posterFrom(element: Element, base: String): String? {
        return element.select("img, picture").asSequence()
            .mapNotNull { with(MynimekuUtils) { it.imageCandidate() } }
            .mapNotNull { MynimekuUtils.resolveUrlOrNull(it, base) }
            .firstOrNull { MynimekuUtils.isValidImage(it) }
    }

    fun description(document: Document): String? {
        return listOf(
            document.selectFirst(".komik-series-entry")?.text()?.trim(),
            document.selectFirst(".komik-series-hero__synopsis .komik-series-entry")?.text()?.trim(),
            document.selectFirst(".komik-series-hero__synopsis")?.text()?.replace(Regex("""(?i)^\s*sinopsis\s*"""), "")?.trim(),
            document.selectFirst("meta[property=og:description]")?.attr("content")?.trim(),
            document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        ).firstOrNull { !it.isNullOrBlank() && it.length > 20 && !MynimekuUtils.isNoiseText(it) }
    }

    fun tags(document: Document): List<String> {
        return document.select(
            ".komik-series-taxonomy a[href*='/genre/'], " +
                ".komik-series-hero__genres a[href*='/genre/'], " +
                "a[href*='/genre/']"
        ).map { it.text().trim() }
            .filter { it.length in 2..40 && !MynimekuUtils.isNoiseText(it) }
            .distinct()
            .take(12)
    }

    fun infoValue(document: Document, label: String): String? {
        val wanted = label.lowercase()
        document.select(".komik-series-info tr, .komik-series-card tr").forEach { row ->
            val key = row.selectFirst("th")?.text()?.trim(':', ' ')?.lowercase()
            if (key == wanted || key?.startsWith(wanted) == true) {
                val value = row.selectFirst("td")?.text()?.trim()
                if (!value.isNullOrBlank()) return value
            }
        }

        val infoText = document.select(".komik-series-info, .komik-series-card").text()
        return Regex(
            """(?i)\b${Regex.escape(label)}\s+(.+?)(?=\s+(?:Status|Type|Japanese|Synonyms|English|Rating|Release Date|Episodes|Duration|Rate|Years|Season)\b|$)"""
        ).find(infoText)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    fun year(document: Document): Int? {
        val raw = infoValue(document, "Years") ?: infoValue(document, "Release Date") ?: infoValue(document, "Released")
        return Regex("""\b(19\d{2}|20\d{2})\b""").find(raw.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(document.select(".komik-series-info, .komik-series-card").text())?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun status(document: Document): ShowStatus? {
        val raw = infoValue(document, "Status") ?: document.select(".mynimeku-mix-feed__status").text()
        return when {
            raw.contains("on-going", true) || raw.contains("ongoing", true) || raw.contains("airing", true) || raw.contains("masih", true) -> ShowStatus.Ongoing
            raw.contains("completed", true) || raw.contains("finished", true) || raw.contains("tamat", true) -> ShowStatus.Completed
            else -> null
        }
    }

    fun detectType(url: String, typeText: String?, titleHint: String = ""): TvType {
        val haystack = "${url.lowercase()} ${typeText.orEmpty().lowercase()} ${titleHint.lowercase()}"
        return when {
            haystack.contains("movie") -> TvType.AnimeMovie
            haystack.contains("ova") -> TvType.OVA
            else -> TvType.Anime
        }
    }

    fun episodes(document: Document, referer: String): List<Episode> {
        return document.select(
            "a.komik-series-chapter-item[href*='/episode/'], " +
                ".komik-series-chapter-list a[href*='/episode/'], " +
                ".komik-series-chapters a[href*='/episode/']"
        ).mapNotNull { element ->
            val url = MynimekuUtils.normalizeUrl(element.attr("abs:href").ifBlank { element.attr("href") })
            if (!MynimekuUtils.isMynimekuContent(url) || url.equals(referer, true)) return@mapNotNull null
            val numText = element.attr("data-episode-number").ifBlank {
                element.selectFirst(".komik-series-chapter-item__num")?.text()?.trim().orEmpty()
            }
            val titleText = element.selectFirst(".komik-series-chapter-item__title")?.text()?.trim().orEmpty()
                .ifBlank { element.attr("data-episode-search-text").trim() }
                .ifBlank { url.trimEnd('/').substringAfterLast('/').replace("-", " ") }
            val episode = MynimekuUtils.parseEpisodeNumber(numText) ?: MynimekuUtils.parseEpisodeNumber(titleText) ?: MynimekuUtils.parseEpisodeNumber(url)
            val cleanTitle = MynimekuUtils.cleanEpisodeTitle(titleText)
            newEpisode(url) {
                name = cleanTitle.ifBlank { "Episode ${episode ?: 1}" }
                this.episode = episode
            }
        }.distinctBy { it.data }
            .sortedWith(compareBy<Episode> { it.episode ?: Int.MAX_VALUE }.thenBy { it.name })
    }

    fun players(document: Document, sourcePage: String): List<MynimekuPlayer> {
        val out = arrayListOf<MynimekuPlayer>()

        document.select(".mynimeku-episode-server-btn[data-player-url], [data-player-url]").forEach { element ->
            val url = element.attr("data-player-url").trim()
            val label = element.attr("data-player-host").ifBlank { element.text() }.trim()
            val group = element.attr("data-player-type").trim()
            if (url.isNotBlank()) out.add(MynimekuPlayer(url, label, group, sourcePage))
        }

        document.select("a[href]").forEach { element ->
            val href = element.attr("abs:href").ifBlank { element.attr("href") }
            if (href.contains("file.mydriveku.my.id/api/v1", true)) {
                val label = element.text().trim()
                out.add(MynimekuPlayer(href, label, "file", sourcePage))
                MynimekuUtils.mydrivekuToPlayer(href)?.let { converted ->
                    out.add(MynimekuPlayer(converted, label, "converted", sourcePage))
                }
            }
        }

        document.select("iframe[src], iframe[data-src], video[src], video[data-src], source[src], embed[src]").forEach { element ->
            val url = with(MynimekuUtils) { element.iframeCandidate() }.orEmpty().ifBlank { element.attr("src") }
            if (url.isNotBlank()) out.add(MynimekuPlayer(url, element.attr("title"), "embed", sourcePage))
        }

        Regex("""https?:\\?/\\?/[^"'<>\s]+""")
            .findAll(document.html())
            .forEach { out.add(MynimekuPlayer(it.value, "script", "script", sourcePage)) }

        return out.mapNotNull { player ->
            val url = MynimekuUtils.resolveUrlOrNull(player.url, sourcePage) ?: return@mapNotNull null
            if (!MynimekuUtils.isPlayableCandidate(url)) return@mapNotNull null
            player.copy(url = url)
        }.distinctBy { MynimekuUtils.canonical(it.url) + "|" + it.label }
            .sortedWith(compareByDescending<MynimekuPlayer> { priority(it) }.thenBy { it.url })
    }

    private fun priority(player: MynimekuPlayer): Int {
        val url = player.url.lowercase()
        val quality = MynimekuUtils.qualityFrom(player.label).let {
            when (it) {
                Qualities.P2160.value -> 40
                Qualities.P1080.value -> 30
                Qualities.P720.value -> 20
                Qualities.P480.value -> 10
                Qualities.P360.value -> 5
                else -> 0
            }
        }
        val base = when {
            MynimekuUtils.isGoogleDriveApiMedia(player.url) -> 1000
            url.contains("players.myplayerku.my.id/drive/") -> 950
            MynimekuUtils.isWorkersMedia(player.url) -> 900
            url.contains("players.myplayerku.my.id/public/") -> 850
            url.contains("players.myplayerku.my.id/private/") -> 830
            url.contains("players.myplayerku.my.id/proxy/") -> 800
            MynimekuUtils.isDirectMedia(player.url) -> 760
            MynimekuUtils.isMyPlayerku(player.url) -> 700
            else -> 100
        }
        return base + quality
    }

    fun mediaFromRawText(raw: String): List<String> {
        val blocks = mutableListOf(raw, MynimekuUtils.decodeJsEscapes(raw))
        blocks.addAll(unpackPacker(raw))
        val urls = linkedSetOf<String>()
        blocks.forEach { block ->
            Regex("""https?:\\?/\\?/[^"'<>\s]+""").findAll(block).forEach { urls.add(it.value) }
            Regex("""(?i)["'](?:file|url|src|source)["']\s*:\s*["']([^"']+)["']""").findAll(block).forEach { urls.add(it.groupValues[1]) }
            Regex("""(?i)(?:file|url|src|source)\s*[:=]\s*["']([^"']+)["']""").findAll(block).forEach { urls.add(it.groupValues[1]) }
            Regex("""(?i)<(?:iframe|source|video|embed)[^>]+(?:src|data-src)=['"]([^'"]+)['"]""").findAll(block).forEach { urls.add(it.groupValues[1]) }
        }
        return urls.map { MynimekuUtils.cleanCandidate(it) }
            .mapNotNull { MynimekuUtils.resolveUrlOrNull(it, MynimekuSeeds.MAIN_URL) }
            .filter { MynimekuUtils.isPlayableCandidate(it) && !MynimekuUtils.isAdOrTracker(it) }
            .distinct()
    }

    private fun unpackPacker(raw: String): List<String> {
        val regex = Regex(
            """eval\(function\(p,a,c,k,e,d\).*?\}\('((?:\\'|[^'])*)',(\d+),(\d+),'([^']*)'\.split\('\|'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        return regex.findAll(raw).mapNotNull { match ->
            val payload = MynimekuUtils.decodeJsEscapes(match.groupValues[1])
            val radix = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val count = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val words = match.groupValues[4].split("|")
            var output = payload
            for (index in count - 1 downTo 0) {
                val replacement = words.getOrNull(index).orEmpty()
                if (replacement.isBlank()) continue
                val encoded = MynimekuUtils.intToRadix(index, radix)
                output = Regex("""\b${Regex.escape(encoded)}\b""").replace(output, replacement)
            }
            MynimekuUtils.decodeJsEscapes(output)
        }.toList()
    }
}
