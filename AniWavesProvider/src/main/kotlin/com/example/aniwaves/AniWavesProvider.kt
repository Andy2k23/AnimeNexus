package com.example.aniwaves

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI

class AniWavesProvider : MainAPI() {
    override var mainUrl = "https://aniwaves.tv"
    override var name = "AniWaves"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to userAgent,
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
        val plot = document.selectFirst(".synopsis .content, .shorting .content")?.text()?.trim()

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

            newEpisode("$mainUrl/ajax/server/list?servers=$ids") {
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
        val serverResp = app.get(data, headers = ajaxHeaders).text
        val serverHtml = if (serverResp.trim().startsWith("{")) {
            JSONObject(serverResp).optString("result", "")
        } else {
            serverResp
        }
        if (serverHtml.isBlank()) return false

        val serverDoc = Jsoup.parse(serverHtml)

        serverDoc.select("li[data-link-id]").amap { serverLi ->
            try {
                val linkId = serverLi.attr("data-link-id")
                val serverName = serverLi.text().trim().ifBlank { "Server" }
                val typeLabel = serverLi.closest(".type")?.attr("data-type")?.uppercase()?.ifBlank { "SUB" } ?: "SUB"

                val sourceResp = app.get(
                    "$mainUrl/ajax/sources?id=$linkId&asi=0&autoPlay=1",
                    headers = ajaxHeaders
                ).text

                val rawUrl = if (sourceResp.trim().startsWith("{")) {
                    JSONObject(sourceResp).optJSONObject("result")?.optString("url", "")
                } else {
                    ""
                }

                if (!rawUrl.isNullOrBlank()) {
                    val cleanUrl = rawUrl.substringBefore("?")
                    val baseUrl = cleanUrl.substringBeforeLast("/")
                    val embedId = cleanUrl.substringAfterLast("/")
                    val getSourcesUrl = "$baseUrl/getSources?id=$embedId"
                    val embedOrigin = try {
                        val uri = URI(cleanUrl)
                        "${uri.scheme}://${uri.host}"
                    } catch (_: Exception) {
                        mainUrl
                    }

                    val embedResp = app.get(
                        getSourcesUrl,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Accept" to "application/json",
                            "Referer" to rawUrl,
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "same-origin"
                        )
                    ).text

                    if (embedResp.trim().startsWith("{")) {
                        val json = JSONObject(embedResp)

                        val tracks = json.optJSONArray("tracks")
                        if (tracks != null) {
                            for (i in 0 until tracks.length()) {
                                val track = tracks.optJSONObject(i) ?: continue
                                val kind = track.optString("kind", "")
                                val file = track.optString("file", "")
                                val label = track.optString("label", "English")
                                if (file.isNotBlank() && kind != "thumbnails") {
                                    subtitleCallback.invoke(SubtitleFile(label, file))
                                }
                            }
                        }

                        val streamUrls = mutableListOf<String>()
                        when (val sourcesAny = json.opt("sources")) {
                            is String -> if (sourcesAny.isNotBlank()) streamUrls.add(sourcesAny)
                            is JSONArray -> {
                                for (i in 0 until sourcesAny.length()) {
                                    val file = sourcesAny.optJSONObject(i)?.optString("file", "")
                                    if (!file.isNullOrBlank()) streamUrls.add(file)
                                }
                            }
                        }

                        streamUrls.forEach { m3u8Url ->
                            val parsedLinks = try {
                                M3u8Helper.generateM3u8(
                                    source = "$name [$typeLabel - $serverName]",
                                    streamUrl = m3u8Url,
                                    referer = "$embedOrigin/"
                                )
                            } catch (_: Exception) {
                                emptyList()
                            }

                            if (parsedLinks.isNotEmpty()) {
                                parsedLinks.forEach(callback)
                            } else {
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name [$typeLabel - $serverName]",
                                        url = m3u8Url,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "$embedOrigin/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignoră serverele care nu răspund
            }
        }
        return true
    }
}
