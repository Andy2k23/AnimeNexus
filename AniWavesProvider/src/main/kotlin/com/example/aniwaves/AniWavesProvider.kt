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

    private val ajaxHeaders = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/home"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ajax/home/widget/updated-all?page=" to "Recently Updated",
        "$mainUrl/ajax/home/widget/updated-sub?page=" to "Recently Updated (Sub)",
        "$mainUrl/ajax/home/widget/updated-dub?page=" to "Recently Updated (Dub)"
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
        val responseText = app.get(request.data + page, headers = ajaxHeaders).text
        val html = if (responseText.trim().startsWith("{")) {
            JSONObject(responseText).optString("result", "")
        } else {
            responseText
        }

        val document = Jsoup.parse(html)
        var items = document.select(".ani.items .item, .items .item, .item").mapNotNull { it.toSearchResult() }

        // Fallback pe pagina /home dacă widget-ul AJAX nu returnează nimic pe pagina 1
        if (items.isEmpty() && page == 1) {
            val homeDoc = app.get("$mainUrl/home").document
            items = homeDoc.select(".ani.items .item, .items .item").mapNotNull { it.toSearchResult() }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val responseText = app.get(
            "$mainUrl/ajax/anime/search?keyword=$query",
            headers = ajaxHeaders
        ).text
        val html = if (responseText.trim().startsWith("{")) {
            JSONObject(responseText).optJSONObject("result")?.optString("html", "") ?: ""
        } else {
            responseText
        }
        val document = Jsoup.parse(html)
        return document.select(".item, a.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("Referer" to "$mainUrl/home")).document
        val watchBlock = document.selectFirst(".layout-page-watchtv, [data-id]")
        val animeId = watchBlock?.attr("data-id")
            ?: throw ErrorLoadingException("Nu am găsit data-id pe pagina anime-ului")

        val title = document.selectFirst("h1.title, .info .title, h2.title, .d-title")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".poster img, #w-info img")?.attr("src")
        val plot = document.selectFirst(".synopsis .content, .shorting .content")?.text()?.trim()

        val epJson = app.get(
            "$mainUrl/ajax/episode/list/$animeId",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)
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
            headers = ajaxHeaders
        ).text
        val serverHtml = JSONObject(serverJson).optString("result", "")
        if (serverHtml.isBlank()) return false
        val serverDoc = Jsoup.parse(serverHtml)

        serverDoc.select(".servers .type").forEach { typeGroup ->
            typeGroup.select("li[data-link-id]").forEach { serverLi ->
                val linkId = serverLi.attr("data-link-id")
                val sourceJson = app.get(
                    "$mainUrl/ajax/sources?id=$linkId&asi=0&autoPlay=0",
                    headers = ajaxHeaders
                ).text

                val sourceUrl = JSONObject(sourceJson).optJSONObject("result")?.optString("url", "")
                if (!sourceUrl.isNullOrBlank()) {
                    loadExtractor(sourceUrl, "$mainUrl/", subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
