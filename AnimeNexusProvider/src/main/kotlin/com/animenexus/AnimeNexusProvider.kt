package com.animenexus

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class AnimeNexusProvider : MainAPI() {
    override var mainUrl = "https://anime-nexus.io"
    override var name = "AnimeNexus"
    override val hasMainPage = true
    override var lang = "ro"
    override val supportedTypes = setOf(TvType.Anime)

    private var cachedSeries: List<NexusSeries>? = null

    data class NexusResponse(
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("response") val response: List<NexusSeries>? = null
    )

    data class NexusSeries(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("link") val link: String? = null,
        @JsonProperty("picture") val picture: String? = null,
        @JsonProperty("date") val date: String? = null,
        @JsonProperty("seen_amount") val seenAmount: String? = null,
        @JsonProperty("series_rating") val seriesRating: Double? = null
    )

    override val mainPage = mainPageOf(
        "newest" to "Cele mai noi",
        "popular" to "Cele mai populare",
        "top_rated" to "Cel mai bun scor",
        "all" to "Toate Anime-urile (A-Z)"
    )

    private suspend fun getAllSeries(): List<NexusSeries> {
        cachedSeries?.let { if (it.isNotEmpty()) return it }

        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/nexus/portal-serii/anime"
        )
        val responseText = app.post(
            "$mainUrl/nexus/series_portal/full_data",
            headers = headers
        ).text

        val parsed = parseJson<NexusResponse>(responseText)
        val list = parsed.response ?: emptyList()
        if (list.isNotEmpty()) {
            cachedSeries = list
        }
        return list
    }

    private fun NexusSeries.toSearchResult(): SearchResponse? {
        val rawName = this.name ?: return null
        val slug = this.link ?: return null
        val title = rawName.replace("✓", "").replace("🕒", "").trim()
        val href = "$mainUrl/nexus/anime/$slug"
        val poster = this.picture?.replace("http://", "https://")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allSeries = getAllSeries()
        val sorted = when (request.data) {
            "newest" -> allSeries.sortedByDescending { it.date ?: "" }
            "popular" -> allSeries.sortedByDescending { it.seenAmount?.toLongOrNull() ?: 0L }
            "top_rated" -> allSeries.sortedByDescending { it.seriesRating ?: 0.0 }
            else -> allSeries
        }

        val pageSize = 40
        val start = (page - 1) * pageSize
        if (start >= sorted.size) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = sorted.drop(start).take(pageSize).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = start + pageSize < sorted.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allSeries = getAllSeries()
        return allSeries
            .filter { it.name?.contains(query, ignoreCase = true) == true }
            .take(50)
            .mapNotNull { it.toSearchResult() }
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
