package com.animenexus

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeNexusProvider : MainAPI() {
    override var mainUrl = "https://anime-nexus.io"
    override var name = "AnimeNexus"
    override val hasMainPage = true
    override var lang = "ro"
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/nexus/portal-serii/anime" to "Anime-uri AnimeNexus"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("div.chart-series-name a") ?: return null
        val title = linkEl.text().trim()
        val href = fixUrl(linkEl.attr("href"))
        val posterUrl = fixUrlNull(
            this.selectFirst("div.chart-picture")?.attr("data-picture")
                ?.ifBlank { this.selectFirst("div.chart-picture img")?.attr("src") }
        )

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val doc = app.get(request.data).document
        val items = doc.select("table tbody tr").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/nexus/portal-serii/anime").document
        return doc.select("table tbody tr")
            .mapNotNull { it.toSearchResult() }
            .filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("div.page-title h1")?.text()?.trim() ?: "Necunoscut"
        val poster = fixUrlNull(doc.selectFirst("img.series-frame-image")?.attr("src"))
        val plot = doc.select("div.row")
            .firstOrNull { it.selectFirst("div.bold")?.text()?.contains("Descriere") == true }
            ?.select("div")
            ?.getOrNull(1)
            ?.text()
            ?.trim()

        val episodes = doc.select("div.episode-list a.btn.text-primary").mapIndexed { index, el ->
            val epHref = fixUrl(el.attr("href"))
            val epText = el.text().trim()
            val epNum = epText.substringBefore(" -").trim().toIntOrNull() ?: (index + 1)
            newEpisode(epHref) {
                this.name = epText
                this.episode = epNum
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
        val doc = app.get(data).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }
        return true
    }
}
