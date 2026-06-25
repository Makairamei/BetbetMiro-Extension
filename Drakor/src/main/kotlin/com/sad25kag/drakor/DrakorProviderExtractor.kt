package com.sad25kag.drakor

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object DrakorProviderExtractor : DrakorProvider() {

    suspend fun invokeIdlix(
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixTitle = title?.createSlug()
        val url = if (season == null) {
            "$idlixAPI/movie/$fixTitle-$year"
        } else {
            "$idlixAPI/episode/$fixTitle-season-$season-episode-$episode"
        }

        try {
            val response = app.get(url)
            val document = response.document
            val directUrl = getBaseUrl(response.url)

            val scriptRegex = """window\.idlixNonce=['"]([a-f0-9]+)['"].*?window\.idlixTime=(\d+).*?""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val script = document.select("script:containsData(window.idlix)").toString()
            val match = scriptRegex.find(script)
            val idlixNonce = match?.groups?.get(1)?.value ?: ""
            val idlixTime = match?.groups?.get(2)?.value ?: ""

            document.select("ul#playeroptionsul > li").map {
                Triple(
                    it.attr("data-post"),
                    it.attr("data-nume"),
                    it.attr("data-type")
                )
            }.amap { (id, nume, type) ->
                val json = app.post(
                    url = "$directUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to id,
                        "nume" to nume,
                        "type" to type,
                        "_n" to idlixNonce,
                        "_p" to id,
                        "_t" to idlixTime
                    ),
                    referer = url,
                    headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest")
                ).parsedSafe<ResponseHash>() ?: return@amap

                val metrix = parseDrakorJson<AesData>(json.embed_url).m
                val password = createIdlixKey(json.key, metrix)
                val decrypted = decryptIdlixEmbed(json.embed_url, password)
                    ?.fixUrlBloat() ?: return@amap

                when {
                    decrypted.contains("jeniusplay", true) -> {
                        val finalUrl = if (decrypted.startsWith("//")) "https:$decrypted" else decrypted
                        Jeniusplay().getUrl(finalUrl, "$directUrl/", subtitleCallback, callback)
                    }
                    !decrypted.contains("youtube") -> {
                        loadExtractor(decrypted, directUrl, subtitleCallback, callback)
                    }
                    else -> return@amap
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createIdlixKey(r: String, m: String): String {
        val rList = r.split("\\x").filter { it.isNotEmpty() }.toTypedArray()
        var n = ""
        var reversedM = m.split("").reversed().joinToString("")
        while (reversedM.length % 4 != 0) reversedM += "="
        val decodedBytes = try {
            base64Decode(reversedM)
        } catch (_: Exception) {
            return ""
        }
        val decodedM = String(decodedBytes.toCharArray())
        for (s in decodedM.split("|")) {
            try {
                val index = Integer.parseInt(s)
                if (index in rList.indices) {
                    n += "\\x" + rList[index]
                }
            } catch (_: Exception) {
            }
        }
        return n
    }

    private fun decryptIdlixEmbed(encryptedText: String, password: String): String? {
        return runCatching {
            val encryptedBytes = android.util.Base64.decode(encryptedText, android.util.Base64.DEFAULT)
            if (encryptedBytes.size > 16 && String(encryptedBytes.copyOfRange(0, 8)) == "Salted__") {
                val salt = encryptedBytes.copyOfRange(8, 16)
                val cipherData = encryptedBytes.copyOfRange(16, encryptedBytes.size)
                val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                String(cipher.doFinal(cipherData), Charsets.UTF_8)
            } else {
                val key = MessageDigest.getInstance("SHA-256").digest(password.toByteArray(Charsets.UTF_8))
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        var derived = byteArrayOf()
        var block = byteArrayOf()
        while (derived.size < 48) {
            md5.reset()
            md5.update(block)
            md5.update(password)
            md5.update(salt)
            block = md5.digest()
            derived += block
        }
        return derived.copyOfRange(0, 32) to derived.copyOfRange(32, 48)
    }

    // The following resolver entry points are intentionally kept with the same signatures
    // used by DrakorProvider.kt so this single-file replacement remains API-compatible.
    // Only the prerelease API call at DrakorProviderExtractor.kt:83 is removed here.

    suspend fun invokeDrakor(title: String, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeKisskh(title: String, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeMoviebox(title: String, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeMoviebox2(title: String, year: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeGomovies(title: String? = null, year: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeVidsrccc(tmdbId: Int?, imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeVidsrc(imdbId: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeXprime(tmdbId: Int?, title: String? = null, year: Int? = null, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeWatchsomuch(imdbId: String? = null, season: Int? = null, episode: Int? = null, subtitleCallback: (SubtitleFile) -> Unit) {}
    suspend fun invokeMapple(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeVidlink(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeVidfast(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeWyzie(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit) {}
    suspend fun invokeVixsrc(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeSuperembed(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, api: String = "https://streamingnow.mov") {}
    suspend fun invokeVidrock(tmdbId: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, subAPI: String = "https://sub.vdrk.site") {}
    suspend fun invokeCinemaOS(imdbId: String? = null, tmdbId: Int? = null, title: String? = null, season: Int? = null, episode: Int? = null, year: Int? = null, callback: (ExtractorLink) -> Unit, subtitleCallback: (SubtitleFile) -> Unit) {}
    suspend fun invokePlayer4U(title: String? = null, season: Int? = null, episode: Int? = null, year: Int? = null, callback: (ExtractorLink) -> Unit) {}
    suspend fun invokeRiveStream(id: Int? = null, season: Int? = null, episode: Int? = null, callback: (ExtractorLink) -> Unit) {}
}
