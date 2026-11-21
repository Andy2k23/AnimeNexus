package com.animenexus

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class AnimeNexusProvider(private val prefs: SharedPreferences) : MainAPI() {
    override var mainUrl = "https://anime-nexus.io"
    override var name = "AnimeNexus"
    override val hasMainPage = true
    override var lang = "ro"
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private var sessionCookies: Map<String, String> = emptyMap()
        private var cachedSeries: List<NexusSeries>? = null

        fun clearSession() {
            sessionCookies = emptyMap()
            cachedSeries = null
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
            headers = mapOf("Referer" to "$mainUrl/"),
            cookies = initCookies
        )

        val merged = initCookies + loginRes.cookies
        sessionCookies = merged
        return merged
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
            headers = headers,
            cookies = cookies
        ).text.trim()

        if (!responseText.startsWith("{")) {
            clearSession()
            throw ErrorLoadingException("Autentificare eșuată! Verifică email-ul și parola în setările extensiei.")
        }

        val json = JSONObject(responseText)
        val arr = json.optJSONArray("response") ?: return emptyList()
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

    override suspend fun load(url: String): LoadResponse {
        val cookies = getSessionCookies()
        val doc = app.get(url, cookies = cookies).document
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
        val cookies = getSessionCookies()
        val doc = app.get(data, cookies = cookies).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(fixUrl(src), data, subtitleCallback, callback)
            }
        }
        return true
    }
}
