package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Minioppai : MainAPI() {
    override var mainUrl = "https://huntersekai.bio"
    override var name = "Hunter No Sekai"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true

    override val supportedTypes = setOf(
        TvType.NSFW,
    )

    companion object {
        const val libPaistream = "https://lb.paistream.my.id"
        const val paistream = "https://paistream.my.id"

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String?): ShowStatus {
            return when (t) {
                "Completed" -> ShowStatus.Completed
                "Ongoing" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }

    }

    override val mainPage = mainPageOf(
        "$mainUrl/watch" to "New Episode",
        "$mainUrl/popular" to "Popular Hentai",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("${request.data}/page/$page").document
        val home = document.select("div.latest a").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = request.name == "New Episode"
            ),
            hasNext = true
        )
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("-episode-")) {
            uri.substringBefore("-episode-")
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("h2.entry-title")?.text()?.trim() ?: return null
        val href = getProperAnimeLink(this.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val epNum = this.selectFirst("i.dot")?.text()?.filter { it.isDigit() }?.toIntOrNull()
        return newAnimeSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            addSub(epNum)
        }

    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        return app.post(
            "$mainUrl/wp-admin/admin-ajax.php", data = mapOf(
                "action" to "ts_ac_do_search",
                "ts_ac_query" to query,
            ), headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).parsedSafe<SearchResponses>()?.post?.firstOrNull()?.all?.mapNotNull { item ->
            newAnimeSearchResponse(
                item.postTitle ?: "",
                item.postLink ?: return@mapNotNull null,
                TvType.NSFW
            ) {
                this.posterUrl = item.postImage
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.limage img")?.attr("src"))
        val table = document.select("ul.data")
        val tags = table.select("ul.data li:nth-child(1) a").map { it.text() }
        val year =
            document.selectFirst("ul.data time[itemprop=dateCreated]")?.text()?.substringBefore("-")
                ?.toIntOrNull()
        val status = getStatus(document.selectFirst("ul.data li:nth-child(2) span")?.text()?.trim())
        val description = document.select("div[itemprop=description] > p").text()

        val episodes = document.select("div.epsdlist ul li").mapNotNull {
            val name = it.selectFirst("div.epl-num")?.text()
            val link = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            Episode(link, name = name)
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.NSFW) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        document.select("div.server ul.mirror li a").mapNotNull {
            fixUrl(
                Jsoup.parse(base64Decode(it.attr("data-em"))).select("iframe").attr("src")
            ) to it.text()
        }.apmap { (link, server) ->
            if (link.startsWith(paistream)) {
                invokeLocal(link, server, subtitleCallback, callback)
            } else {
                loadExtractor(fixUrl(decode(link.substringAfter("data="))), mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun invokeLocal(
        url: String,
        server: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val script = getAndUnpack(app.get(url, referer="$mainUrl/").text)
        val sources = script.substringAfter("sources:[").substringBefore("]").replace("'", "\"")
        val subtitles = script.substringAfter("\"tracks\":[").substringBefore("]")

        tryParseJson<List<Sources>>("[$sources]")?.map {
            callback.invoke(
                ExtractorLink(
                    server,
                    server,
                    fixLink(it.file ?: return@map, if(server == "Stream 1") libPaistream else paistream),
                    "$paistream/",
                    getQualityFromName(it.label)
                )
            )
        }

        tryParseJson<List<Subtitles>>("[$subtitles]")?.map {
            subtitleCallback.invoke(
                SubtitleFile(
                    it.label ?: "",
                    fixLink(it.file ?: return@map, paistream)
                )
            )
        }

    }

    private fun decode(input: String): String = URLDecoder.decode(input, "utf-8")

    private fun fixLink(url: String, domain: String): String {
        if (url.startsWith("http")) {
            return url
        }
        if (url.isEmpty()) {
            return ""
        }

        val startsWithNoHttp = url.startsWith("//")
        if (startsWithNoHttp) {
            return "https:$url"
        } else {
            if (url.startsWith('/')) {
                return domain + url
            }
            return "$domain/$url"
        }
    }

    data class Subtitles(
        @JsonProperty("file") var file: String? = null,
        @JsonProperty("label") var label: String? = null,
    )

    data class Sources(
        @JsonProperty("label") var label: String? = null,
        @JsonProperty("file") var file: String? = null,
    )

    data class SearchResponses(
        @JsonProperty("post") var post: ArrayList<Post> = arrayListOf()
    )

    data class All(
        @JsonProperty("ID") var ID: Int? = null,
        @JsonProperty("post_image") var postImage: String? = null,
        @JsonProperty("post_title") var postTitle: String? = null,
        @JsonProperty("post_link") var postLink: String? = null,
    )

    data class Post(
        @JsonProperty("all") var all: ArrayList<All> = arrayListOf(),
    )

}