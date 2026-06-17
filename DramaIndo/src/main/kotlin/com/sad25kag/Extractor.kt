package com.sad25kag

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.Filesim
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BysetayicoFileMoon : Filesim() {
    override val mainUrl = "https://bysetayico.com"
    override val name = "FileMoon"
}

class DramaIndoFileMoon : Filesim() {
    override val mainUrl = "https://filemoon.to"
    override val name = "FileMoon"
}

class DrakorkitaStream : DramaIndoEncryptedStream(
    extractorName = "Drakorkita",
    extractorMainUrl = "https://drakorkita.stream",
)

class NunaUpnsStream : DramaIndoEncryptedStream(
    extractorName = "NunaUpns",
    extractorMainUrl = "https://nuna.upns.pro",
)

open class DramaIndoEncryptedStream(
    private val extractorName: String,
    private val extractorMainUrl: String,
) : ExtractorApi() {
    override val name = extractorName
    override val mainUrl = extractorMainUrl
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        DramaIndoStreamResolver.resolve(
            extractorName = name,
            url = url,
            referer = referer ?: "https://dramaindo.my/",
            callback = callback,
        )
    }
}

object DramaIndoStreamResolver {
    private const val AES_KEY = "kiemtienmua911ca"
    private const val AES_IV = "1234567890oiuytr"
    private const val DEFAULT_REFERER_HOST = "dramaindo.my"

    suspend fun resolve(
        extractorName: String,
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host ?: return false
        val scheme = uri.scheme ?: "https"
        val baseUrl = "$scheme://$host"
        val playerReferer = "$baseUrl/"
        val videoId = extractVideoId(uri, url) ?: return false
        val refererHost = runCatching { URI(referer).host?.removePrefix("www.") }
            .getOrNull()
            .takeUnless { it.isNullOrBlank() }
            ?: DEFAULT_REFERER_HOST

        val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36",
        )

        runCatching {
            app.get(
                playerReferer,
                referer = referer,
                headers = browserHeaders + mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Referer" to referer,
                ),
            )
        }

        val encrypted = listOf(refererHost, DEFAULT_REFERER_HOST)
            .distinct()
            .firstNotNullOfOrNull { hostParam ->
                val apiUrl = "$baseUrl/api/v1/video?id=$videoId&w=421&h=935&r=$hostParam"
                runCatching {
                    app.get(
                        apiUrl,
                        referer = playerReferer,
                        headers = browserHeaders + mapOf(
                            "Accept" to "*/*",
                            "Referer" to playerReferer,
                            "Origin" to baseUrl,
                            "Sec-Fetch-Site" to "same-origin",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Dest" to "empty",
                        ),
                    ).text.trim().takeIf { it.isNotBlank() }
                }.getOrNull()
            } ?: return false

        val decrypted = decryptHex(encrypted) ?: return false
        val json = runCatching { JsonParser.parseString(decrypted).asJsonObject }.getOrNull() ?: return false
        val stream = json.pickStreamUrl() ?: return false
        val headers = mapOf(
            "Referer" to playerReferer,
            "Origin" to baseUrl,
        )

        var emitted = false
        runCatching {
            generateM3u8(
                source = extractorName,
                streamUrl = stream.url,
                referer = playerReferer,
                headers = headers,
            )
        }.getOrDefault(emptyList()).forEach { link ->
            callback(link)
            emitted = true
        }

        if (!emitted) {
            callback(
                newExtractorLink(extractorName, stream.name, stream.url, ExtractorLinkType.M3U8) {
                    this.referer = playerReferer
                    this.quality = stream.quality ?: Qualities.Unknown.value
                    this.headers = headers
                }
            )
            emitted = true
        }

        return emitted
    }

    private fun extractVideoId(uri: URI, rawUrl: String): String? {
        return uri.fragment
            ?.substringBefore("?")
            ?.substringBefore("&")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: Regex("(?i)[#/]([a-z0-9]{5,})(?:[/?&]|$)")
                .find(rawUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.pickStreamUrl(): StreamCandidate? {
        listOf("source", "cf").forEach { key ->
            val value = runCatching { get(key)?.takeIf { it.isJsonPrimitive }?.asString }.getOrNull()
                ?.replace("\\/", "/")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return@forEach
            if (value.isHlsLike()) {
                return StreamCandidate(
                    name = if (key.equals("cf", ignoreCase = true)) "${titleText()} Cloudflare HLS" else "${titleText()} HLS",
                    url = value,
                    quality = value.qualityFromText(),
                )
            }
        }
        return null
    }

    private fun JsonObject.titleText(): String {
        return runCatching { get("title")?.takeIf { it.isJsonPrimitive }?.asString }
            .getOrNull()
            ?.substringBeforeLast(".")
            ?.replace('.', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "DramaIndo"
    }

    private fun String.isHlsLike(): Boolean {
        val lower = lowercase()
        return lower.contains(".m3u8") || lower.contains("master") || lower.contains("cf-master") || lower.endsWith(".txt")
    }

    private fun String.qualityFromText(): Int? {
        return Regex("(?i)(2160|1080|720|480|360)p").find(this)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun decryptHex(hex: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val keySpec = SecretKeySpec(AES_KEY.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            String(cipher.doFinal(hexToBytes(hex)), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val cleanHex = hex.trim()
        require(cleanHex.length % 2 == 0)
        return ByteArray(cleanHex.length / 2) { index ->
            cleanHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private data class StreamCandidate(
        val name: String,
        val url: String,
        val quality: Int?,
    )
}
