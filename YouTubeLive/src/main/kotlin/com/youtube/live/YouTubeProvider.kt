package com.youtube.live

import android.content.SharedPreferences
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeService
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

open class YouTubeProvider(
    language: String,
    private val sharedPrefs: SharedPreferences?
) : MainAPI() {

    override var mainUrl = MAIN_URL
    override var name = "YouTube"

    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live
    )

    override val hasMainPage = true

    override var lang = language

    open val SEARCH_CONTENT_FILTER = "videos"

    override var sequentialMainPage = true

    val service: YoutubeService = ServiceList.YouTube

    companion object {

        const val MAIN_URL =
            "https://www.youtube.com"

        var SEARCH_PAGE: Page? = null

        lateinit var SEARCH_HANDLER:
                SearchQueryHandler
    }

    init {

        // DEFAULT INDONESIA

        val savedLanguage =
            sharedPrefs?.getString(
                "language",
                "id"
            ) ?: "id"

        val savedCountry =
            sharedPrefs?.getString(
                "country",
                "ID"
            ) ?: "ID"

        NewPipe.setupLocalization(
            Localization(savedLanguage),
            ContentCountry(savedCountry)
        )
    }

    // =========================
    // TRENDING
    // =========================

    fun getTrendingVideoUrls(
        page: Int
    ): HomePageList? {

        val kiosks =
            service.kioskList

        val trendingsUrl =
            kiosks.defaultKioskExtractor.url

        val infoItem =
            KioskInfo.getInfo(
                service,
                trendingsUrl
            )

        val videos =
            if (page == 1) {
                infoItem.relatedItems.toMutableList()
            } else {
                mutableListOf<StreamInfoItem>()
            }

        if (page > 1) {

            var hasNext =
                infoItem.hasNextPage()

            if (!hasNext) {
                return null
            }

            var count = 1

            var nextPage =
                infoItem.nextPage

            while (
                count < page &&
                hasNext
            ) {

                val more =
                    KioskInfo.getMoreItems(
                        service,
                        trendingsUrl,
                        nextPage
                    )

                if (count == page - 1) {
                    videos.addAll(more.items)
                }

                hasNext =
                    more.hasNextPage()

                nextPage =
                    more.nextPage

                count++
            }
        }

        val searchResponses =
            videos
                .filter {
                    !it.isShortFormContent
                }
                .map {

                    newMovieSearchResponse(
                        it.name,
                        it.url,
                        TvType.Others
                    ) {
                        this.posterUrl =
                            it.thumbnails.last().url
                    }
                }

        return HomePageList(
            name = "🔥 Trending Indonesia",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    // =========================
    // CATEGORY
    // =========================

    suspend fun getCategoryVideos(
        query: String,
        title: String
    ): HomePageList? {

        val handlerFactory =
            service.searchQHFactory

        val searchHandler =
            handlerFactory.fromQuery(
                query,
                listOf("videos"),
                null
            )

        val searchInfo =
            SearchInfo.getInfo(
                service,
                SearchQueryHandler(searchHandler)
            )

        val videos =
            searchInfo.relatedItems
                .filter {
                    it.infoType == InfoType.STREAM
                }
                .take(20)

        val results =
            videos.mapNotNull {

                newMovieSearchResponse(
                    it.name,
                    it.url,
                    TvType.Others
                ) {
                    this.posterUrl =
                        it.thumbnails.last().url
                }
            }

        return HomePageList(
            name = title,
            list = results,
            isHorizontalImages = true
        )
    }

    // =========================
    // LIVE
    // =========================

    suspend fun getLiveVideos(
        query: String,
        title: String
    ): HomePageList? {

        val handlerFactory =
            service.searchQHFactory

        val searchHandler =
            handlerFactory.fromQuery(
                "$query live",
                listOf("videos"),
                null
            )

        val searchInfo =
            SearchInfo.getInfo(
                service,
                SearchQueryHandler(searchHandler)
            )

        val videos =
            searchInfo.relatedItems
                .filter {
                    it.infoType == InfoType.STREAM
                }
                .take(20)

        val results =
            videos.mapNotNull {

                newMovieSearchResponse(
                    it.name,
                    it.url,
                    TvType.Live
                ) {
                    this.posterUrl =
                        it.thumbnails.last().url
                }
            }

        return HomePageList(
            name = title,
            list = results,
            isHorizontalImages = true
        )
    }

    // =========================
    // PLAYLIST
    // =========================

    fun playlistToSearchResponseList(
        url: String,
        page: Int
    ): HomePageList? {

        val playlistInfo =
            PlaylistInfo.getInfo(url)

        val videos =
            if (page == 1) {
                playlistInfo.relatedItems.toMutableList()
            } else {
                mutableListOf<StreamInfoItem>()
            }

        val searchResponses =
            videos.map {

                newMovieSearchResponse(
                    it.name,
                    it.url,
                    TvType.Others
                ) {
                    this.posterUrl =
                        it.thumbnails.last().url
                }
            }

        return HomePageList(
            name =
                "${playlistInfo.uploaderName}: ${playlistInfo.name}",
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    // =========================
    // CHANNEL
    // =========================

    fun channelToSearchResponseList(
        url: String,
        page: Int
    ): HomePageList? {

        val channelInfo =
            ChannelInfo.getInfo(url)

        val tabsLinkHandlers =
            channelInfo.tabs

        val tabs =
            tabsLinkHandlers.map {
                ChannelTabInfo.getInfo(
                    service,
                    it
                )
            }

        val videoTab =
            tabs.first {
                it.name == "videos"
            }

        val videos =
            if (page == 1) {
                videoTab.relatedItems.toMutableList()
            } else {
                mutableListOf<InfoItem>()
            }

        val searchResponses =
            videos.map {

                newMovieSearchResponse(
                    it.name,
                    it.url,
                    TvType.Others
                ) {
                    this.posterUrl =
                        it.thumbnails.last().url
                }
            }

        return HomePageList(
            name = channelInfo.name,
            list = searchResponses,
            isHorizontalImages = true
        )
    }

    // =========================
    // MAIN PAGE
    // =========================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val sections =
            mutableListOf<HomePageList>()

        // TRENDING

        getTrendingVideoUrls(page)?.let {
            sections.add(it)
        }

        // LIVE

        getLiveVideos(
            "tv indonesia",
            "📡 Live TV Indonesia"
        )?.let {
            sections.add(it)
        }

        getLiveVideos(
            "news indonesia",
            "📰 Live News Indonesia"
        )?.let {
            sections.add(it)
        }

        getLiveVideos(
            "gaming indonesia",
            "🎮 Live Gaming Indonesia"
        )?.let {
            sections.add(it)
        }

        getLiveVideos(
            "musik indonesia",
            "🎵 Live Musik Indonesia"
        )?.let {
            sections.add(it)
        }

        // CATEGORY

        getCategoryVideos(
            "film indonesia terbaru",
            "🎬 Film Indonesia"
        )?.let {
            sections.add(it)
        }

        getCategoryVideos(
            "anime indonesia",
            "🌸 Anime Indonesia"
        )?.let {
            sections.add(it)
        }

        getCategoryVideos(
            "musik indonesia",
            "🎵 Musik Indonesia"
        )?.let {
            sections.add(it)
        }

        getCategoryVideos(
            "gaming indonesia",
            "🎮 Gaming Indonesia"
        )?.let {
            sections.add(it)
        }

        getCategoryVideos(
            "podcast indonesia",
            "🎙 Podcast Indonesia"
        )?.let {
            sections.add(it)
        }

        getCategoryVideos(
            "berita indonesia",
            "📰 Berita Indonesia"
        )?.let {
            sections.add(it)
        }

        // CUSTOM PLAYLIST

        val playlistsData =
            sharedPrefs?.getStringSet(
                "playlists",
                emptySet()
            ) ?: emptySet()

        if (playlistsData.isNotEmpty()) {

            val triples =
                playlistsData.map {
                    parseJson<Triple<String, String, Long>>(it)
                }

            val list =
                triples.amap { data ->

                    val playlistUrl =
                        data.first

                    val urlPath =
                        playlistUrl
                            .substringAfter("youtu")
                            .substringAfter("/")

                    val isPlaylist =
                        urlPath.startsWith(
                            "playlist?list="
                        )

                    val isChannel =
                        urlPath.startsWith("@") ||
                                urlPath.startsWith("channel")

                    val customSections =
                        if (
                            isPlaylist &&
                            !isChannel
                        ) {

                            playlistToSearchResponseList(
                                playlistUrl,
                                page
                            )

                        } else if (
                            !isPlaylist &&
                            isChannel
                        ) {

                            channelToSearchResponseList(
                                playlistUrl,
                                page
                            )

                        } else {
                            null
                        }

                    customSections to data.third
                }

            list.sortedBy {
                it.second
            }.forEach {

                val homepageSection =
                    it.first

                if (homepageSection != null) {
                    sections.add(homepageSection)
                }
            }
        }

        return newHomePageResponse(
            sections,
            true
        )
    }

    // =========================
    // SEARCH
    // =========================

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val handlerFactory =
            service.searchQHFactory

        val pageResults =
            mutableListOf<InfoItem>()

        var hasNextPage = true

        if (page < 2) {

            SEARCH_HANDLER =
                handlerFactory.fromQuery(
                    query,
                    listOf(SEARCH_CONTENT_FILTER),
                    null
                )

            val searchInfo =
                SearchInfo.getInfo(
                    service,
                    SearchQueryHandler(
                        SEARCH_HANDLER
                    )
                )

            pageResults.addAll(
                searchInfo.relatedItems.toMutableList()
            )

            SEARCH_PAGE =
                searchInfo.nextPage

            hasNextPage =
                searchInfo.hasNextPage()
        }

        if (page > 1) {

            val more =
                SearchInfo.getMoreItems(
                    service,
                    SEARCH_HANDLER,
                    SEARCH_PAGE
                )

            pageResults.addAll(more.items)

            hasNextPage =
                more.hasNextPage()

            SEARCH_PAGE =
                more.nextPage
        }

        val finalResults =
            pageResults.mapNotNull {

                when (it.infoType) {

                    InfoType.PLAYLIST,
                    InfoType.CHANNEL -> {

                        newTvSeriesSearchResponse(
                            it.name,
                            it.url,
                            TvType.Others
                        ) {
                            this.posterUrl =
                                it.thumbnails.last().url
                        }
                    }

                    InfoType.STREAM -> {

                        newMovieSearchResponse(
                            it.name,
                            it.url,
                            TvType.Others
                        ) {
                            this.posterUrl =
                                it.thumbnails.last().url
                        }
                    }

                    else -> null
                }
            }

        return newSearchResponseList(
            finalResults,
            hasNextPage
        )
    }

    // =========================
    // FORMAT NUMBER
    // =========================

    protected fun formatThousands(
        bigNumber: Long
    ): String {

        val number =
            bigNumber.toString().reversed()

        return number
            .chunked(3)
            .joinToString(" ")
            .reversed()
    }

    // =========================
    // LOAD
    // =========================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val extractor =
            service.getStreamExtractor(url)

        extractor.fetchPage()

        val videoInfo =
            StreamInfo.getInfo(extractor)

        val views =
            "👀: ${formatThousands(videoInfo.viewCount)}"

        val likes =
            "👍: ${formatThousands(videoInfo.likeCount)}"

        val length =
            videoInfo.duration / 60

        return newMovieLoadResponse(
            videoInfo.name,
            url,
            TvType.Others,
            url
        ) {

            this.posterUrl =
                videoInfo.thumbnails.last().url

            this.plot =
                videoInfo.description.content

            this.duration =
                length.toInt()

            this.tags =
                listOf(
                    views,
                    likes
                )

            this.actors =
                listOf(
                    ActorData(
                        Actor(
                            videoInfo.uploaderName,
                            videoInfo
                                .uploaderAvatars
                                .lastOrNull()
                                ?.url
                        )
                    )
                )
        }
    }

    // =========================
    // LOAD LINKS
    // =========================

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