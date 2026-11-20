package com.animenexus

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AnimeNexusProvider : MainAPI() {
    override var mainUrl = "https://anime-nexus.io"
    override var name = "AnimeNexus"
    override var lang = "ro"
    override val hasMainPage = false
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document

        return document.select("div.anime-item").mapNotNull { element ->
            val title = element.selectFirst("h3, .title")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrl(element.selectFirst("a")?.attr("href") ?: return@mapNotNull null)
            val posterUrl = fixUrlNull(element.selectFirst("img")?.attr("src"))

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Fără titlu"
        val poster = fixUrlNull(document.selectFirst("img.poster")?.attr("src"))
        val description = document.selectFirst(".description")?.text()?.trim()

        val episodes = document.select("a.episode-link").mapIndexedNotNull { index, ep ->
            val epHref = fixUrl(ep.attr("href"))
            val epName = ep.text().trim()
            newEpisode(epHref) {
                this.name = epName
                this.episode = index + 1
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        document.select("iframe").forEach { iframe ->
            val embedUrl = iframe.attr("data-src").ifEmpty { iframe.attr("src") }
            if (embedUrl.isNotBlank()) {
                loadExtractor(embedUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
