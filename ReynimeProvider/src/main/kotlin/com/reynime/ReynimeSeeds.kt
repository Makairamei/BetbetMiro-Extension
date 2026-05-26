package com.reynime

object ReynimeSeeds {
    private val cache = linkedMapOf<Int, ReynimeSeedEntry>()

    fun all(): List<ReynimeSeedEntry> = cache.values.toList()

    fun byId(id: Int?): ReynimeSeedEntry? = id?.let { cache[it] }

    fun put(entry: ReynimeSeedEntry) {
        cache[entry.id] = entry
    }

    fun bootstrap(): List<ReynimeSeedEntry> = listOf(
        ReynimeSeedEntry(1, "Donghua Update", "https://reynime.my.id/", null),
        ReynimeSeedEntry(2, "Anime Movie", "https://reynime.my.id/movies/", null, "AnimeMovie"),
        ReynimeSeedEntry(3, "Ongoing", "https://reynime.my.id/ongoing/", null, "TvSeries"),
        ReynimeSeedEntry(4, "Completed", "https://reynime.my.id/completed/", null, "TvSeries")
    ).onEach { put(it) }
}
