package com.sad25kag.alqanime

import com.fasterxml.jackson.annotation.JsonProperty

data class EpisodeLink(
    @param:JsonProperty("url") val url: String,
    @param:JsonProperty("quality") val quality: String
)

data class PixeldrainList(
    @param:JsonProperty("files") val files: List<PixeldrainFile> = emptyList()
)

data class PixeldrainFile(
    @param:JsonProperty("id") val id: String,
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("mime_type") val mimeType: String = ""
)