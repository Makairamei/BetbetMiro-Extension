package com.youtube.live

import android.content.SharedPreferences
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.InfoItem.InfoType
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

open class YouTubeProvider(language: String, val sharedPref: SharedPreferences?) : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = language
    override val supportedTypes = setOf(TvType.Others)

    open val SEARCH_CONTENT_FILTER = "videos"
    val service = ServiceList.YouTube

    // Menambahkan shortcut kategori hiburan otomatis langsung di halaman utama
    override val mainPage = listOf(
        MainPageData("Alur Cerita Manhua", "manhua_story"),
        MainPageData("Kumpulan Lagu Santai", "relaxing_music"),
        MainPageData("Film Full Movie Sub Indo", "indonesian_movies"),
        MainPageData("Rekomendasi Anime", "anime_rec"),
        MainPageData("Live Streaming", "live_stream")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val query = when (request.data) {
            "manhua_story" -> "alur cerita manhua manhwa lengkap"
            "relaxing_music" -> "lagu santai akustik lofi"
            "indonesian_movies" -> "film aksi bioskop full movie sub indo"
            "anime_rec" -> "rekomendasi anime populer sub indo"
            "live_stream" -> "live streaming music news"
            else -> "trending"
        }

        val items = getYouTubeQueryItems(query, page)
        val homePageList = HomePageList(request.name, items)
        return newHomePageResponse(listOf(homePageList), hasNext = items.isNotEmpty())
    }

    private suspend fun getYouTubeQueryItems(query: String, page: Int): List<SearchResponse> {
        return try {
            val filter = listOf(SEARCH_CONTENT_FILTER)
            val searchInfo = if (page == 1) {
                SearchInfo.getInfo(
                    service,
                    service.searchQHFactory.fromQuery(query, filter, "")
                )
            } else {
                // Untuk menangani pemuatan halaman berikutnya (Infinity Scroll)
                SearchInfo.getMoreItems(
                    service,
                    service.searchQHFactory.fromQuery(query, filter, ""),
                    Page(null, null, null, null, null) // Cadangan page token kosong jika tidak terdeteksi
                )
            }

            searchInfo.relatedItems.mapNotNull { item ->
                if (item.infoType == InfoType.STREAM) {
                    val streamItem = item as StreamInfoItem
                    newMovieSearchResponse(
                        streamItem.name,
                        streamItem.url,
                        TvType.Others
                    ) {
                        this.posterUrl = streamItem.thumbnails.lastOrNull()?.url
                    }
                } else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val filter = listOf(SEARCH_CONTENT_FILTER)
        val searchInfo = SearchInfo.getInfo(
            service,
            service.searchQHFactory.fromQuery(query, filter, "")
        )

        return searchInfo.relatedItems.mapNotNull { item ->
            if (item.infoType == InfoType.STREAM) {
                val streamItem = item as StreamInfoItem
                newMovieSearchResponse(
                    streamItem.name,
                    streamItem.url,
                    TvType.Others
                ) {
                    this.posterUrl = streamItem.thumbnails.lastOrNull()?.url
                }
            } else null
        }
    }

    fun formatThousands(count: Long): String {
        return when {
            count >= 1_000_000 -> "${String.format("%.1f", count / 1_000_000.0)}M"
            count >= 1_000 -> "${String.format("%.1f", count / 1_000.0)}K"
            else -> count.toString()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val videoInfo = StreamInfo.getInfo(service, url)
        val views = "👀: ${formatThousands(videoInfo.viewCount)}"
        val likes = "👍: ${formatThousands(videoInfo.likeCount)}"
        val length = videoInfo.duration / 60

        return newMovieLoadResponse(
            videoInfo.name,
            url,
            TvType.Others,
            url
        ) {
            this.posterUrl = videoInfo.thumbnails.lastOrNull()?.url
            this.plot = videoInfo.description?.content ?: ""
            this.duration = length.toInt()
            this.tags = listOf(views, likes)
            this.actors = listOf(
                ActorData(
                    Actor(
                        videoInfo.uploaderName,
                        videoInfo.uploaderAvatars.lastOrNull()?.url
                    )
                )
            )
            // Properti score tidak dipasang manual demi keamanan kompatibilitas SDK
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        return loadExtractor(
            data,
            null,
            subtitleCallback,
            callback
        )
    }
}