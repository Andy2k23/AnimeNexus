package com.animenexus

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

class AnimeNexusProvider(private val prefs: SharedPreferences) : MainAPI() {
    override var mainUrl = "https://anime-nexus.io"
    override var name = "AnimeNexus"
    override val hasMainPage = true
    override var lang = "ro"
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private var sessionCookies: Map<String, String> = emptyMap()
        private var cachedSeries: List<NexusSeries>? = null
        private val cachedDocs = ConcurrentHashMap<String, Document>()

        fun clearSession() {
            sessionCookies = emptyMap()
            cachedSeries = null
            cachedDocs.clear()
        }
    }

    data class NexusSeries(
        val name: String,
        val link: String,
        val picture: String?,
        val date: String,
        val seenAmount: Long,
        val seriesRating: Double
    )

    override val mainPage = mainPageOf(
        "newest" to "Cele mai noi",
        "popular" to "Cele mai populare",
        "top_rated" to "Cel mai bun scor",
        "all" to "Toate Anime-urile (A-Z)"
    )

    private suspend fun getSessionCookies(): Map<String, String> {
        if (sessionCookies.isNotEmpty()) return sessionCookies

        val username = prefs.getString("username", "")?.trim().orEmpty()
        val password = prefs.getString("password", "").orEmpty()

        if (username.isBlank() || password.isBlank()) {
            throw ErrorLoadingException("Configurează contul din Settings -> Extensions -> AnimeNexus (rotița de setări).")
        }

        val initCookies = app.get("$mainUrl/").cookies
        val loginRes = app.post(
            "$mainUrl/nexus/login_account",
            data = mapOf(
                "referer" to "$mainUrl/",
                "loginUsername" to username,
                "loginPassword" to password,
                "loginRememberme" to "on"
            ),
            headers = mapOf(
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            cookies = initCookies,
            allowRedirects = false
        )

        val merged = initCookies + loginRes.cookies
        sessionCookies = merged
        return merged
    }

    private suspend fun getSeriesDoc(url: String, cookies: Map<String, String>): Document {
        val key = url.trimEnd('/')
        cachedDocs[key]?.let { return it }
        val doc = app.get(url, cookies = cookies).document
        cachedDocs[key] = doc
        return doc
    }

    private suspend fun getAllSeries(): List<NexusSeries> {
        cachedSeries?.let { if (it.isNotEmpty()) return it }

        val cookies = getSessionCookies()
        val headers = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/nexus/portal-serii/anime"
        )
        val responseText = app.post(
            "$mainUrl/nexus/series_portal/full_data",
            data = mapOf("type" to "anime"),
            headers = headers,
            cookies = cookies
        ).text.trim()

        if (!responseText.startsWith("{")) {
            clearSession()
            throw ErrorLoadingException("Răspuns non-JSON: ${responseText.take(150)}")
        }

        val json = JSONObject(responseText)
        val arr = json.optJSONArray("response")
        if (arr == null || arr.length() == 0) {
            clearSession()
            throw ErrorLoadingException("JSON fără listă: ${responseText.take(200)}")
        }

        val list = mutableListOf<NexusSeries>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val link = obj.optString("link", "")
            if (name.isBlank() || link.isBlank()) continue

            val picture = obj.optString("picture", "").ifBlank { null }
            val date = obj.optString("date", "")
            val seenAmount = obj.optString("seen_amount", "0").toLongOrNull() ?: 0L
            val seriesRating = obj.optDouble("series_rating", 0.0).let { if (it.isNaN()) 0.0 else it }

            list.add(NexusSeries(name, link, picture, date, seenAmount, seriesRating))
        }

        if (list.isNotEmpty()) {
            cachedSeries = list
        }
        return list
    }

    private fun NexusSeries.toSearchResult(): SearchResponse {
        val title = this.name.replace("✓", "").replace("🕒", "").trim()
        val href = "$mainUrl/nexus/anime/${this.link}"
        val poster = this.picture?.replace("http://", "https://")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val allSeries = getAllSeries()
        val sorted = when (request.data) {
            "newest" -> allSeries.sortedByDescending { it.date }
            "popular" -> allSeries.sortedByDescending { it.seenAmount }
            "top_rated" -> allSeries.sortedByDescending { it.seriesRating }
            else -> allSeries
        }

        val pageSize = 40
        val start = (page - 1) * pageSize
        if (start >= sorted.size) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val items = sorted.drop(start).take(pageSize).map { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = start + pageSize < sorted.size)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allSeries = getAllSeries()
        return allSeries
            .filter { it.name.contains(query, ignoreCase = true) }
            .take(50)
            .map { it.toSearchResult() }
    }

    private fun findInfoValueElement(doc: Document, labelPrefix: String): Element? {
        val labelEl = doc.select("div.bold, strong, b, dt").firstOrNull {
            it.ownText().trim().startsWith(labelPrefix, ignoreCase = true) ||
                it.text().trim().startsWith(labelPrefix, ignoreCase = true)
        } ?: return null

        labelEl.nextElementSibling()?.let { return it }

        val parentRow = labelEl.parent()
        val children = parentRow?.children()
        if (children != null && children.size >= 2) {
            val idx = children.indexOf(labelEl)
            if (idx in 0 until children.size - 1) {
                return children[idx + 1]
            }
        }
        return null
    }

    private fun extractMalId(doc: Document): Int? {
        val malHref = doc.selectFirst("a[href*=myanimelist.net/anime/]")?.attr("href")
            ?: findInfoValueElement(doc, "Link extern")?.text()
            ?: return null

        return Regex("""myanimelist\.net/anime/(\d+)""")
            .find(malHref)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseEpisodes(
        doc: Document,
        seasonNumber: Int
    ): List<Episode> {
        return doc.select("div.episode-list a.btn.text-primary").mapIndexed { index, el ->
            val epHref = fixUrl(el.attr("href"))
            val epText = el.text().trim()
            val epNum = epText.substringBefore(" -").trim().toIntOrNull() ?: (index + 1)
            newEpisode(epHref) {
                this.name = epText
                this.season = seasonNumber
                this.episode = epNum
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val cookies = getSessionCookies()
        val initialDoc = getSeriesDoc(url, cookies)

        // Merge inapoi pe "Serie anterioara" / "Serie precedenta" pana la Sezonul 1 real
        var firstUrl = url
        var firstDoc = initialDoc
        val visitedBack = mutableSetOf(url.trimEnd('/'))
        var stepsBack = 0

        while (stepsBack < 10) {
            val prevEl = findInfoValueElement(firstDoc, "Serie anterioar")
                ?: findInfoValueElement(firstDoc, "Serie precedent")
            val prevHref = prevEl?.selectFirst("a[href]")?.attr("href")?.let { fixUrl(it) } ?: break
            val normalized = prevHref.trimEnd('/')
            if (normalized in visitedBack) break
            visitedBack.add(normalized)

            try {
                firstDoc = getSeriesDoc(prevHref, cookies)
                firstUrl = prevHref
                stepsBack++
            } catch (_: Exception) {
                break
            }
        }

        val title = firstDoc.selectFirst("div.page-title h1, h1")?.text()?.trim() ?: "Necunoscut"
        val poster = fixUrlNull(firstDoc.selectFirst("img.series-frame-image")?.attr("src"))
        val malId = extractMalId(firstDoc)

        val plot = findInfoValueElement(firstDoc, "Descriere")?.text()?.trim()

        val genres = findInfoValueElement(firstDoc, "Genuri")?.text()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val themes = findInfoValueElement(firstDoc, "Teme")?.text()
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val tags = (genres + themes).distinct()

        val year = findInfoValueElement(firstDoc, "Data lans")?.text()
            ?.let { Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull() }

        val duration = findInfoValueElement(firstDoc, "Durat")?.text()
            ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

        val recommendations = findInfoValueElement(firstDoc, "Serii alternative")
            ?.select("a[href]")
            ?.mapNotNull { a ->
                val recTitle = a.text().trim()
                val recHref = fixUrl(a.attr("href"))
                if (recTitle.isNotBlank() && recHref.contains("/nexus/")) {
                    newAnimeSearchResponse(recTitle, recHref, TvType.Anime)
                } else {
                    null
                }
            }
            .orEmpty()

        // Parcurge inainte de la Sezonul 1 real -> Sezonul 2, 3, 4...
        val allEpisodes = mutableListOf<Episode>()
        allEpisodes.addAll(parseEpisodes(firstDoc, seasonNumber = 1))

        val visitedForward = mutableSetOf(firstUrl.trimEnd('/'))
        var currentDoc = firstDoc
        var seasonNum = 2

        while (seasonNum <= 10) {
            val nextHref = findInfoValueElement(currentDoc, "Serie urm")
                ?.selectFirst("a[href]")
                ?.attr("href")
                ?.let { fixUrl(it) }
                ?: break

            val normalized = nextHref.trimEnd('/')
            if (normalized in visitedForward) break
            visitedForward.add(normalized)

            try {
                val nextDoc = getSeriesDoc(nextHref, cookies)
                val nextEps = parseEpisodes(nextDoc, seasonNumber = seasonNum)
                if (nextEps.isNotEmpty()) {
                    allEpisodes.addAll(nextEps)
                    seasonNum++
                }
                currentDoc = nextDoc
            } catch (_: Exception) {
                break
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.year = year
            this.duration = duration
            this.recommendations = recommendations
            addMalId(malId)
            addEpisodes(DubStatus.Subbed, allEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cookies = getSessionCookies()
        val doc = app.get(data, cookies = cookies).document

        val iframeUrls = doc.select("div.iframe_source iframe, iframe")
            .mapNotNull { iframe ->
                val raw = iframe.attr("data-src").ifBlank { iframe.attr("src") }.trim()
                if (raw.isNotBlank()) fixUrl(raw) else null
            }
            .distinct()

        iframeUrls.amap { url ->
            try {
                loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
            } catch (_: Exception) {
            }
        }
        return true
    }
}
