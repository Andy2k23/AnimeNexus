package com.example.aniwaves

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AniWavesProvider : MainAPI() {
    override var mainUrl = "https://aniwaves.tv"
    override var name = "AniWaves"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    override val mainPage = mainPageOf(
        "$mainUrl/ajax/home/widget/updated-all?page=" to "Recently Updated",
        "$mainUrl/ajax/home/widget/updated-sub?page=" to "Recently Updated (Sub)",
        "$mainUrl/ajax/home/widget/updated-dub?page=" to "Recently Updated (Dub)"
    )

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkEl = this.selectFirst(".info a.name, a.name, .poster > a") ?: return null
        val title = this.selectFirst(".info .name, .name")?.text()?.trim() ?: return null
        val href = fixUrl(linkEl.attr("href"))
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val subCount = this.selectFirst(".ep-status.sub span")?.text()?.toIntOrNull()
        val dubCount = this.selectFirst(".ep-status.dub span")?.text()?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(
                dubExist = dubCount != null,
                subExist = subCount != null,
                dubEpisodes = dubCount,
                subEpisodes = subCount
            )
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val json = app.get(
            request.data + page,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text
        val parsed = parseJson<HtmlResultResponse>(json)
        val document = Jsoup.parse(parsed.result ?: "")
        val items = document.select(".ani.items .item, .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val json = app.get(
            "$mainUrl/ajax/anime/search?keyword=$query",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text
        val parsed = parseJson<SearchResultResponse>(json)
        val document = Jsoup.parse(parsed.result?.html ?: "")
        return document.select(".item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val watchBlock = document.selectFirst(".layout-page-watchtv")
        val animeId = watchBlock?.attr("data-id")
            ?: throw ErrorLoadingException("Nu am găsit data-id pe pagina anime-ului")

        val title = document.selectFirst("h1.title, .info .title, h2.title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".poster img, #w-info img")?.attr("src")
        val plot = document.selectFirst(".synopsis .content, .shorting .content")?.text()?.trim()

        val epJson = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)
        ).text
        val epHtml = parseJson<HtmlResultResponse>(epJson).result ?: ""
        val epDoc = Jsoup.parse(epHtml)

        val episodes = epDoc.select("a[data-ids]").mapNotNull { ep ->
            val ids = ep.attr("data-ids").ifBlank { return@mapNotNull null }
            val num = ep.attr("data-num").toIntOrNull()
            val epTitle = ep.selectFirst(".d-title")?.text()?.trim()
                ?: ep.parent()?.attr("title")?.substringAfterLast(" - ")?.trim()
                ?: "Episode ${ep.attr("data-num")}"

            newEpisode(ids) {
                this.episode = num
                this.name = if (ep.hasClass("filler")) "[Filler] $epTitle" else epTitle
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serverJson = app.get(
            "$mainUrl/ajax/server/list?servers=$data",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/")
        ).text
        val serverHtml = parseJson<HtmlResultResponse>(serverJson).result ?: return false
        val serverDoc = Jsoup.parse(serverHtml)

        serverDoc.select(".servers .type").forEach { typeGroup ->
            typeGroup.select("li[data-link-id]").forEach { serverLi ->
                val linkId = serverLi.attr("data-link-id")
                val sourceJson = app.get(
                    "$mainUrl/ajax/sources?id=$linkId&asi=0&autoPlay=0",
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to "$mainUrl/")
                ).text

                val sourceUrl = parseJson<SourceResponse>(sourceJson).result?.url
                if (!sourceUrl.isNullOrBlank()) {
                    loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        return true
    }

    data class HtmlResultResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: String? = null
    )

    data class SearchResultResponse(
        @JsonProperty("result") val result: SearchHtml? = null
    )

    data class SearchHtml(
        @JsonProperty("html") val html: String? = null
    )

    data class SourceResponse(
        @JsonProperty("status") val status: Int? = null,
        @JsonProperty("result") val result: SourceData? = null
    )

    data class SourceData(
        @JsonProperty("url") val url: String? = null
    )
}
