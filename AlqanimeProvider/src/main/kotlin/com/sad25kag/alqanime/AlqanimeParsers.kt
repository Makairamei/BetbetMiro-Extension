package com.sad25kag.alqanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

suspend fun MainAPI.loadMainPageEntries(
    url: String,
    page: Int,
    headers: Map<String, String>
): List<SearchResponse> {
    val document = app.get(url.format(page), headers = headers, referer = mainUrl, timeout = 15000L).document
    return document.select("div.listupd:not(.popularslider) article.bs")
        .mapNotNull { it.toSearchResult(mainUrl) }
}

fun Element.toSearchResult(mainUrl: String): AnimeSearchResponse? {
    val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
    val title = selectFirst(".ntitle")?.text()?.trim() ?: return null
    val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
    val typeText = selectFirst(".typez")?.text()?.trim() ?: ""
    val epNum = selectFirst("a")?.attr("title")
        ?.let {
            Regex("Episode\\s*\\((\\d+)\\)", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

    return newAnimeSearchResponse(title, href, getType(typeText)) {
        this.posterUrl = posterUrl
        addDubStatus("Sub Indo", epNum)
    }
}

suspend fun MainAPI.parseSearchPage(url: String, headers: Map<String, String>): List<SearchResponse> {
    val document = app.get(url, headers = headers).document
    return document.select("article.bs").mapNotNull { it.toSearchResult(mainUrl) }
}

suspend fun MainAPI.parseLoadPage(url: String, headers: Map<String, String>): LoadResponse? {
    val document = app.get(url, headers = headers).document

    val rawTitle = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
    val title = rawTitle
        .replace(Regex("\\s*\\(Episode[^)]*\\)", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*Sub Indo\\b.*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*\\(BD\\).*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s*BD Batch.*", RegexOption.IGNORE_CASE), "")
        .trim()

    val poster = document.selectFirst(
        "div.thumb img, div.bigcontent div.thumb img, div.postbody div.thumb img, div.infox div.thumb img, div.ime img, div.bigcover img, img.wp-post-image, meta[property=og:image], meta[name=twitter:image]"
    )?.imageUrl(document.selectFirst("img") ?: return null)

    val coverBg = poster
    val description = document.select("div.entry-content > p")
        .filter { it.text().length > 10 }
        .joinToString("

") { it.text().trim() }
        .ifBlank { null }

    val genres = document.select("div.genxed a").map { it.text() }
    val speMap = document.select("div.spe > span").associate { span ->
        val label = span.selectFirst("b")?.text()?.trim() ?: ""
        val value = span.text().replace(label, "").trim()
        label to value
    }

    val type = getType(speMap.entries.find { it.key.contains("Tipe", true) }?.value ?: "")
    val status = getStatus(speMap.entries.find { it.key.contains("Status", true) }?.value ?: "")
    val year = Regex("(\\d{4})").find(speMap.entries.find { it.key.contains("Dirilis", true) }?.value ?: "")
        ?.groupValues?.getOrNull(1)?.toIntOrNull()

    val episodes = parseEpisodes(document, mainUrl)

    return newAnimeLoadResponse(title, url, type) {
        posterUrl = poster
        backgroundPosterUrl = coverBg
        this.year = year
        plot = description
        showStatus = status
        this.tags = genres
        addEpisodes(DubStatus.Subbed, episodes.reversed())
    }
}

private fun parseEpisodes(document: org.jsoup.nodes.Document, mainUrl: String): List<Episode> {
    val episodes = mutableListOf<Episode>()

    for (col in document.select("div.sorattl.collapsible")) {
        val epTitle = col.selectFirst("h3")?.text()?.trim() ?: continue
        if (epTitle.equals("Batch", ignoreCase = true)) continue

        val epNum = Regex("Episode\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val contentDiv = col.nextElementSibling()?.takeIf { it.hasClass("content") } ?: continue
        val linkList = mutableListOf<EpisodeLink>()

        for (tr in contentDiv.select("tr")) {
            val quality = tr.selectFirst("div.res")?.text()?.trim() ?: continue
            for (a in tr.select("div.slink a")) {
                linkList.add(EpisodeLink(a.attr("href"), quality))
            }
        }

        if (linkList.isNotEmpty()) {
            episodes.add(Episode(linkList.toEpisodeJson(), epTitle, epNum, null))
        }
    }

    return episodes
}

fun List<EpisodeLink>.toEpisodeJson(): String {
    val array = org.json.JSONArray()
    forEach { link ->
        array.put(org.json.JSONObject().put("url", link.url).put("quality", link.quality))
    }
    return array.toString()
}