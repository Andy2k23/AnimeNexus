package com.example.aniwaves

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AniWavesProvider : MainAPI() {
    override var mainUrl = "https://aniwaves.tv"
    override var name = "AniWaves"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/home"
    )

    private val ajaxHeaders = commonHeaders + ("X-Requested-With" to "XMLHttpRequest")

    override val mainPage = mainPageOf(
        "$mainUrl/updated?page=" to "Recently Updated",
        "$mainUrl/newest?page=" to "Recently Added",
        "$mainUrl/ongoing?page=" to "Ongoing"
    )

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val linkEl = this.selectFirst("a.d-title, .info a.name, a.name, .poster a, a[href*='/watch/']") ?: return null
        val href = fixUrl(linkEl.attr("href").ifBlank { return null })
        val title = this.selectFirst(".d-title, .info .name, .name")?.text()?.trim()
            ?.ifBlank { null }
            ?: linkEl.attr("title").ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null

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
        val response = app.get(request.data + page, headers = commonHeaders)
        val document = response.document
        val items = document.select(".ani.items .item, .items .item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("$mainUrl/filter?keyword=$query", headers = commonHeaders)
        return response.document.select(".ani.items .item, .items .item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = commonHeaders).document
        val watchBlock = document.selectFirst(".layout-page-watchtv, [data-id]")
        val animeId = watchBlock?.attr("data-id")
            ?: throw ErrorLoadingException("Nu am găsit data-id pe pagina anime-ului")

        val title = document.selectFirst("h1.title, .info .title, h2.title, .d-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".poster img, #w-info img")?.attr("src")

        val epJson = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = ajaxHeaders + ("Referer" to url)
        ).text
        val epHtml = JSONObject(epJson).optString("result", "")
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

        // Debug pentru primul episod direct în descriere
        var debugInfo = "No episodes"
        val firstIds = episodes.firstOrNull()?.data
        if (!firstIds.isNullOrBlank()) {
            val srvResp = app.get("$mainUrl/ajax/server/list?servers=$firstIds", headers = ajaxHeaders + ("Referer" to url)).text
            val srvHtml = if (srvResp.trim().startsWith("{")) JSONObject(srvResp).optString("result", "") else srvResp
            val firstLinkId = Jsoup.parse(srvHtml).selectFirst("li[data-link-id]")?.attr("data-link-id")
            if (firstLinkId.isNullOrBlank()) {
                debugInfo = "SRV_EMPTY | ids=$firstIds | resp=${srvResp.take(150)}"
            } else {
                val srcResp = app.get("$mainUrl/ajax/sources?id=$firstLinkId&asi=0&autoPlay=0", headers = ajaxHeaders + ("Referer" to url)).text
                debugInfo = "LINK_ID=$firstLinkId | SRC=${srcResp.take(250)}"
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = "DEBUG: $debugInfo"
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val serverResp = app.get("$mainUrl/ajax/server/list?servers=$data", headers = ajaxHeaders).text
        val serverHtml = if (serverResp.trim().startsWith("{")) {
            JSONObject(serverResp).optString("result", "")
        } else {
            serverResp
        }

        val serverDoc = Jsoup.parse(serverHtml)
        serverDoc.select("li[data-link-id]").forEach { serverLi ->
            val linkId = serverLi.attr("data-link-id")
            val sourceResp = app.get(
                "$mainUrl/ajax/sources?id=$linkId&asi=0&autoPlay=0",
                headers = ajaxHeaders
            ).text

            val sourceUrl = if (sourceResp.trim().startsWith("{")) {
                JSONObject(sourceResp).optJSONObject("result")?.optString("url", "")
            } else {
                ""
            }

            if (!sourceUrl.isNullOrBlank()) {
                loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
            }
        }
        return true
    }
}
