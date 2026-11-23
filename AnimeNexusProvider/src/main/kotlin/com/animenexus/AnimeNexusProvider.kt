package com.animenexus

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AnimeNexusProvider(private val prefs: SharedPreferences) : MainAPI() {
    override var mainUrl = "https://anime-nexus.io"
    override var name = "AnimeNexus"
    override val hasMainPage = true
    override var lang = "ro"
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private val watchScheduler = Executors.newSingleThreadScheduledExecutor()
        @Volatile
        private var pendingWatchTask: ScheduledFuture<*>? = null

        @Volatile
        private var sessionCookies: Map<String, String> = emptyMap()
        @Volatile
        private var cachedSeries: List<NexusSeries>? = null
        private val cachedDocs = ConcurrentHashMap<String, Document>()
        private val cachedJikanFlags = ConcurrentHashMap<Int, Pair<Set<Int>, Set<Int>>>()

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

    private fun loadSavedCookies(): Map<String, String> {
        val raw = prefs.getString("saved_cookies", null) ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "")
                if (v.isNotBlank() && v != "deleted") {
                    map[k] = v
                }
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveCookies(cookies: Map<String, String>) {
        val clean = cookies.filterValues { it.isNotBlank() && it != "deleted" }
        prefs.edit().putString("saved_cookies", JSONObject(clean).toString()).apply()
    }

    private suspend fun getSessionCookies(forceRefresh: Boolean = false): Map<String, String> {
        if (!forceRefresh) {
            val current = sessionCookies
            if (current.isNotEmpty()) return current
            val saved = loadSavedCookies()
            if (saved.isNotEmpty()) {
                sessionCookies = saved
                return saved
            }
        }

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
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            cookies = initCookies,
            allowRedirects = false
        )

        val merged = (initCookies + loginRes.cookies)
            .filterValues { it.isNotBlank() && it != "deleted" }

        val portalRes = app.get(
            "$mainUrl/nexus/portal-serii/anime",
            headers = mapOf("Referer" to "$mainUrl/"),
            cookies = merged
        )

        val finalCookies = (merged + portalRes.cookies)
            .filterValues { it.isNotBlank() && it != "deleted" }

        sessionCookies = finalCookies
        saveCookies(finalCookies)
        return finalCookies
    }

    private suspend fun getSeriesDoc(url: String, cookies: Map<String, String>): Document {
        val key = url.trimEnd('/')
        cachedDocs[key]?.let { return it }
        val doc = app.get(url, cookies = cookies).document
        cachedDocs[key] = doc
        return doc
    }

    private suspend fun fetchSeriesJson(cookies: Map<String, String>): String {
        val headers = mapOf(
            "Origin" to mainUrl,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$mainUrl/nexus/portal-serii/anime"
        )
        return app.post(
            "$mainUrl/nexus/series_portal/full_data",
            data = mapOf("type" to "anime"),
            headers = headers,
            cookies = cookies
        ).text.trim()
    }

    private suspend fun getAllSeries(): List<NexusSeries> {
        cachedSeries?.let { if (it.isNotEmpty()) return it }

        var responseText = ""
        for (attempt in 1..3) {
            val cookies = getSessionCookies(forceRefresh = attempt > 1)
            responseText = fetchSeriesJson(cookies)
            if (responseText.startsWith("{")) break
        }

        if (!responseText.startsWith("{")) {
            clearSession()
            prefs.edit().remove("saved_cookies").apply()
            throw ErrorLoadingException("Răspuns non-JSON: ${responseText.take(150)}")
        }

        val json = JSONObject(responseText)
        val arr = json.optJSONArray("response")
        if (arr == null || arr.length() == 0) {
            clearSession()
            prefs.edit().remove("saved_cookies").apply()
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

    private suspend fun getJikanEpisodeFlags(malId: Int?): Pair<Set<Int>, Set<Int>> {
        if (malId == null) return emptySet<Int>() to emptySet()
        cachedJikanFlags[malId]?.let { return it }

        val fillers = mutableSetOf<Int>()
        val recaps = mutableSetOf<Int>()

        try {
            var page = 1
            while (page <= 15) {
                val resText = app.get("https://api.jikan.moe/v4/anime/$malId/episodes?page=$page").text
                if (!resText.startsWith("{")) break

                val json = JSONObject(resText)
                val data = json.optJSONArray("data") ?: break
                for (i in 0 until data.length()) {
                    val ep = data.optJSONObject(i) ?: continue
                    val num = ep.optInt("mal_id", -1)
                    if (num <= 0) continue
                    if (ep.optBoolean("filler", false)) fillers.add(num)
                    if (ep.optBoolean("recap", false)) recaps.add(num)
                }

                val hasNext = json.optJSONObject("pagination")?.optBoolean("has_next_page", false) ?: false
                if (!hasNext) break
                page++
            }
        } catch (_: Exception) {
        }

        val result = fillers.toSet() to recaps.toSet()
        cachedJikanFlags[malId] = result
        return result
    }

    private fun parseEpisodes(
        doc: Document,
        seasonNumber: Int,
        jikanFillers: Set<Int> = emptySet(),
        jikanRecaps: Set<Int> = emptySet()
    ): List<Episode> {
        return doc.select("div.episode-list a.btn.text-primary").mapIndexed { index, el ->
            val epHref = fixUrl(el.attr("href"))
            val epText = el.text().trim()
            val epNum = epText.substringBefore(" -").trim().toIntOrNull() ?: (index + 1)

            val rawHtml = el.outerHtml()
            val isFiller = rawHtml.contains("filler", ignoreCase = true) || epNum in jikanFillers
            val isRecap = rawHtml.contains("recap", ignoreCase = true) || epNum in jikanRecaps

            val cleanName = epText
                .replace(Regex("""^\s*\[(FILLER|RECAP)]\s*""", RegexOption.IGNORE_CASE), "")
                .trim()

            val prefix = when {
                isFiller -> "[FILLER] "
                isRecap -> "[RECAP] "
                else -> ""
            }

            newEpisode(epHref) {
                this.name = "$prefix$cleanName"
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
        val (firstFillers, firstRecaps) = getJikanEpisodeFlags(malId)
        allEpisodes.addAll(parseEpisodes(firstDoc, seasonNumber = 1, firstFillers, firstRecaps))

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
                val nextMalId = extractMalId(nextDoc)
                val (nextFillers, nextRecaps) = getJikanEpisodeFlags(nextMalId)
                val nextEps = parseEpisodes(nextDoc, seasonNumber = seasonNum, nextFillers, nextRecaps)
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

    private fun scheduleMarkEpisodeWatched(
        doc: Document,
        episodeUrl: String,
        cookies: Map<String, String>,
        delayMinutes: Long = 10L
    ) {
        try {
            val html = doc.html()

            fun extractParam(vararg names: String): String? {
                for (n in names) {
                    Regex("""$n\s*[:=]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.getOrNull(1)?.let {
                        if (it.isNotBlank()) return it.trim()
                    }
                    Regex("""$n\s*[:=]\s*(\d+)""").find(html)?.groupValues?.getOrNull(1)?.let {
                        if (it.isNotBlank()) return it.trim()
                    }
                    doc.selectFirst("input[name=$n], #$n")?.attr("value")?.trim()?.let {
                        if (it.isNotBlank()) return it
                    }
                }
                return null
            }

            val seriesId = extractParam("watchedSeriesId", "seriesId", "series_id") ?: return
            val seriesLink = extractParam("watchedLink", "seriesLink", "series_link")
                ?: Regex("""/nexus/anime/([^/]+)""").find(episodeUrl)?.groupValues?.getOrNull(1)
                ?: return
            val epNumber = extractParam("watchedNumber", "episodeNumber", "ep_number")
                ?: Regex("""(\d+)(?:/?$|\?)""").find(episodeUrl)?.groupValues?.getOrNull(1)
                ?: return
            val seriesName = extractParam("watchedSeriesName", "seriesName", "series_name")
                ?: doc.selectFirst("div.page-title h1, h1")?.text()?.substringBefore(" -")?.trim()
                ?: ""

            // Anuleaza timer-ul anterior daca ai schimbat episodul intre timp
            pendingWatchTask?.cancel(false)

            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val formBody = listOf(
                "watchedSeriesId" to seriesId,
                "watchedLink" to seriesLink,
                "watchedNumber" to epNumber,
                "watchedSeriesName" to seriesName,
                "manual" to "0"
            ).joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

            pendingWatchTask = watchScheduler.schedule({
                try {
                    val conn = URL("$mainUrl/nexus/ep_seen/watched_episode").openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    conn.setRequestProperty("Origin", mainUrl)
                    conn.setRequestProperty("Referer", episodeUrl)
                    conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                    if (cookieHeader.isNotBlank()) {
                        conn.setRequestProperty("Cookie", cookieHeader)
                    }
                    conn.outputStream.use { it.write(formBody.toByteArray(Charsets.UTF_8)) }
                    conn.responseCode
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }, delayMinutes, TimeUnit.MINUTES)
        } catch (_: Exception) {
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

        scheduleMarkEpisodeWatched(doc, data, cookies, delayMinutes = 10L)

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
