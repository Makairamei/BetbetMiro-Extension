package com.sad25kag.idlix

data class ApiResponse(
    val data: List<ApiItem> = emptyList(),
    val pagination: Pagination? = null,
    val meta: Meta? = null,
)

data class ApiItem(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val voteAverage: Any? = null,
    val viewCount: Any? = null,
    val quality: String? = null,
    val country: String? = null,
    val runtime: Int? = null,
    val createdAt: String? = null,
    val numberOfSeasons: Int? = null,
    val numberOfEpisodes: Int? = null,
    val contentType: String? = null,
    val commentCount: Int? = null,
    val originalLanguage: String? = null,
    val popularity: Any? = null,
    val genres: List<APIGenre>? = null,
    val hasVideo: Boolean? = null,
    val isPublished: Boolean? = null,
)

data class APIGenre(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
)

data class Pagination(
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null,
)

data class Meta(
    val genre: String? = null,
    val country: String? = null,
    val year: String? = null,
    val network: String? = null,
    val sort: String? = null,
)

data class DetailResponse(
    val id: String? = null,
    val title: String? = null,
    val slug: String? = null,
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val logoPath: String? = null,
    val backdrops: List<String>? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Any? = null,
    val popularity: Any? = null,
    val originalLanguage: String? = null,
    val country: String? = null,
    val status: String? = null,
    val trailerUrl: String? = null,
    val quality: String? = null,
    val director: String? = null,
    val genres: List<Genre>? = null,
    val cast: List<Cast>? = null,
    val seasons: List<Season>? = null,
    val firstSeason: Season? = null,
    val viewCount: Any? = null,
    val isPublished: Boolean? = null,
)

data class Genre(
    val id: String? = null,
    val name: String? = null,
    val slug: String? = null,
)

data class Cast(
    val id: String? = null,
    val name: String? = null,
    val character: String? = null,
    val profilePath: String? = null,
)

data class Season(
    val id: String? = null,
    val seasonNumber: Int? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val episodes: List<Episode>? = null,
)

data class Episode(
    val id: String? = null,
    val episodeNumber: Int? = null,
    val name: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val voteAverage: Any? = null,
)

data class SeasonWrapper(
    val season: Season? = null,
)

data class SearchApiResponse(
    val results: List<SearchApiResult> = emptyList(),
    val total: Long = 0,
)

data class SearchApiResult(
    val id: String? = null,
    val contentType: String? = null,
    val title: String? = null,
    val originalTitle: String? = null,
    val overview: String? = null,
    val genres: List<String>? = null,
    val originalLanguage: String? = null,
    val voteAverage: Any? = null,
    val viewCount: Any? = null,
    val popularity: Any? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val slug: String? = null,
    val firstAirDate: String? = null,
    val numberOfSeasons: Long? = null,
    val releaseDate: String? = null,
    val quality: String? = null,
)

data class LoadData(
    val id: String,
    val type: String,
    val refererUrl: String? = null,
)

data class WatchSessionResponse(
    val kind: String? = null,
    val gateToken: String? = null,
    val serverNow: Long? = null,
    val unlockAt: Long? = null,
    val remainingMs: Long? = null,
    val claim: String? = null,
    val redeemUrl: String? = null,
    val videoId: String? = null,
    val title: String? = null,
    val durationSec: Long? = null,
    val viewerTier: String? = null,
    val maxHeight: Long? = null,
)

data class Iframe(
    val code: String? = null,
    val url: String? = null,
    val expiresAt: Long? = null,
    val subtitles: List<Subtitle> = emptyList(),
    val videoId: String? = null,
)

data class Subtitle(
    val lang: String? = null,
    val label: String? = null,
    val path: String? = null,
)
