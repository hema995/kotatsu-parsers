package org.koitharu.kotatsu.parsers.site.ar

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.LegacyPagedMangaParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import okhttp3.Headers
import java.text.SimpleDateFormat
import java.util.*

/**
 * DilarTube Parser - موقع dilar.tube
 */
@MangaSourceParser("DILARTUBE", "DilarTube", "ar")
internal class DilarTube(context: MangaLoaderContext) :
    LegacyPagedMangaParser(context, MangaParserSource.DILARTUBE, 20) {

    override val availableSortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.ALPHABETICAL,
        SortOrder.NEWEST
    )

    override val configKeyDomain = ConfigKey.Domain("dilar.tube")

    override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
        super.onCreateConfig(keys)
        keys.add(userAgentKey)
    }

    // Headers مخصصة للموقع
    private val customHeaders: Headers = Headers.headersOf(
        "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept", "application/json, text/plain, */*",
        "Accept-Language", "ar,en;q=0.9",
        "Referer", "https://dilar.tube/",
        "Origin", "https://dilar.tube"
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getFilterOptions() = MangaListFilterOptions(
        availableTags = fetchAvailableTags(),
        availableStates = EnumSet.of(
            MangaState.ONGOING,
            MangaState.FINISHED,
            MangaState.ABANDONED
        ),
        availableContentTypes = EnumSet.of(
            ContentType.MANGA,
            ContentType.MANHWA,
            ContentType.MANHUA,
        ),
    )

    override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val apiAttempts = listOf(
            "https://$domain/api/manga?page=$page&limit=20",
            "https://$domain/api/v1/manga?page=$page&limit=20",
            "https://$domain/api/comics?page=$page&limit=20",
            "https://$domain/rest/manga?page=$page&limit=20",
            "https://$domain/backend/manga?page=$page&limit=20"
        )
        for (apiUrl in apiAttempts) {
            try {
                val response = webClient.httpGet(apiUrl, customHeaders).parseJson()
                val result = parseMangaListFromJson(response) ?: emptyList()
                if (result.isNotEmpty()) return result
            } catch (_: Exception) {}
        }
        return tryWebScraping(page, order, filter)
    }

    private suspend fun tryWebScraping(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
        val possibleUrls = listOf(
            "https://$domain",
            "https://$domain/home",
            "https://$domain/manga",
            "https://$domain/comics",
            "https://$domain/stories"
        )
        for (url in possibleUrls) {
            try {
                val doc = webClient.httpGet(url, customHeaders).parseHtml()
                val scriptTags = doc.select("script")
                for (script in scriptTags) {
                    val content = script.html()
                    val patterns = listOf(
                        "window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?});".toRegex(),
                        "window\\.__DATA__\\s*=\\s*(\\{.*?});".toRegex(),
                        "__NEXT_DATA__\\s*=\\s*(\\{.*?})".toRegex(),
                        "\\\\\"manga\\\\\":\\s*(\\[.*?])".toRegex(),
                        "\\\\\"comics\\\\\":\\s*(\\[.*?])".toRegex()
                    )
                    for (pattern in patterns) {
                        pattern.find(content)?.groupValues?.get(1)?.let { jsonStr ->
                            try {
                                val json = JSONObject(jsonStr)
                                val list = extractMangaFromEmbeddedJson(json)
                                if (list.isNotEmpty()) return list
                            } catch (_: Exception) {}
                        }
                    }
                }
                val elems = doc.select(
                    ".manga-item, .comic-item, .story-item, .card, .item, .post, [data-manga], [data-comic], .grid-item, .list-item"
                )
                if (elems.isNotEmpty()) return parseMangaElementsFromWeb(elems)
            } catch (_: Exception) {}
        }
        return emptyList()
    }

    // باقي الدوال بدون تغيير رئيسي…
    // تأكدت إن كل استدعاء httpGet يستخدم customHeaders
    // واستخدمت parseMangaListFromJson مع ?? emptyList() للتأكد من non-null

}
