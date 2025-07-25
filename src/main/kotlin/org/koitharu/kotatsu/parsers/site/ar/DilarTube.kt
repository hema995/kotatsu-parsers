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
import java.text.SimpleDateFormat
import java.util.*

/**
 * DilarTube Parser - موقع dilar.tube
 * 
 * ملاحظات هامة:
 * 1. الموقع يعتمد بشكل كامل على JavaScript و SPA (Single Page Application)
 * 2. يحتوي على تطبيق محمول منفصل
 * 3. يتطلب API calls للحصول على البيانات
 * 4. قد يحتاج authentication أو headers خاصة
 * 5. البنية قد تتغير بشكل متكرر لكونها تطبيق تفاعلي
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
    private val customHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "ar,en;q=0.9",
        "Referer" to "https://dilar.tube/",
        "Origin" to "https://dilar.tube"
    )

    // محاولة اكتشاف API endpoints المحتملة
    private val possibleApiEndpoints = listOf(
        "/api/v1/",
        "/api/",
        "/rest/",
        "/graphql",
        "/_next/static/",
        "/backend/"
    )

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
        )

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
        // نظراً لأن الموقع JavaScript-heavy، سنحتاج لمحاولة عدة طرق
        
        // الطريقة الأولى: محاولة API endpoints مختلفة
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
                val result = parseMangaListFromJson(response)
                if (result.isNotEmpty()) {
                    return result
                }
            } catch (e: Exception) {
                // تجاهل الخطأ وحاول التالي
                continue
            }
        }
        
        // الطريقة الثانية: محاولة scraping عادي
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
                
                // محاولة اكتشاف JavaScript state أو embedded data
                val scriptTags = doc.select("script")
                for (script in scriptTags) {
                    val scriptContent = script.html()
                    
                    // البحث عن JSON data في JavaScript
                    val jsonPatterns = listOf(
                        """window\.__INITIAL_STATE__\s*=\s*(\{.*?\});""".toRegex(),
                        """window\.__DATA__\s*=\s*(\{.*?\});""".toRegex(),
                        """__NEXT_DATA__\s*=\s*(\{.*?\})""".toRegex(),
                        """"manga":\s*(\[.*?\])""".toRegex(),
                        """"comics":\s*(\[.*?\])""".toRegex()
                    )
                    
                    for (pattern in jsonPatterns) {
                        val match = pattern.find(scriptContent)
                        if (match != null) {
                            try {
                                val jsonData = JSONObject(match.groupValues[1])
                                val result = extractMangaFromEmbeddedJson(jsonData)
                                if (result.isNotEmpty()) {
                                    return result
                                }
                            } catch (e: Exception) {
                                continue
                            }
                        }
                    }
                }
                
                // محاولة scraping تقليدي مع selectors مختلفة
                val mangaElements = doc.select(
                    ".manga-item, .comic-item, .story-item, " +
                    ".card, .item, .post, " +
                    "[data-manga], [data-comic], " +
                    ".grid-item, .list-item"
                )
                
                if (mangaElements.isNotEmpty()) {
                    return parseMangaElementsFromWeb(mangaElements)
                }
                
            } catch (e: Exception) {
                continue
            }
        }
        
        return emptyList()
    }

    private fun extractMangaFromEmbeddedJson(json: JSONObject): List<Manga> {
        val mangaList = mutableListOf<Manga>()
        
        // محاولة العثور على data المانجا في مختلف الأماكن المحتملة
        val possibleArrayKeys = listOf("manga", "comics", "stories", "data", "items", "results")
        
        for (key in possibleArrayKeys) {
            val array = json.optJSONArray(key)
            if (array != null && array.length() > 0) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        val manga = parseMangaFromJsonObject(item)
                        if (manga != null) {
                            mangaList.add(manga)
                        }
                    }
                }
                break
            }
        }
        
        // إذا لم نجد array، قد يكون object واحد
        if (mangaList.isEmpty()) {
            val manga = parseMangaFromJsonObject(json)
            if (manga != null) {
                mangaList.add(manga)
            }
        }
        
        return mangaList
    }
    
    private fun parseMangaFromJsonObject(obj: JSONObject): Manga? {
        val possibleIdKeys = listOf("id", "_id", "slug", "key", "mangaId")
        val possibleTitleKeys = listOf("title", "name", "manga_name", "comic_name", "story_name")
        val possibleImageKeys = listOf("image", "cover", "thumbnail", "poster", "coverUrl", "imageUrl")
        val possibleStatusKeys = listOf("status", "state", "manga_status", "comic_status")
        
        val id = possibleIdKeys.firstNotNullOfOrNull { obj.optString(it).takeIf { it.isNotEmpty() } }
        val title = possibleTitleKeys.firstNotNullOfOrNull { obj.optString(it).takeIf { it.isNotEmpty() } }
        
        return if (id != null && title != null) {
            val image = possibleImageKeys.firstNotNullOfOrNull { obj.optString(it).takeIf { it.isNotEmpty() } }
            val status = possibleStatusKeys.firstNotNullOfOrNull { obj.optString(it).takeIf { it.isNotEmpty() } }
            
            Manga(
                id = generateUid(id),
                title = title,
                altTitles = emptySet(),
                url = "/manga/$id",
                publicUrl = "https://$domain/manga/$id",
                rating = RATING_UNKNOWN,
                contentRating = null,
                coverUrl = image?.let { 
                    if (it.startsWith("http")) it else "https://$domain$it" 
                },
                tags = emptySet(),
                state = parseStatusToState(status),
                authors = emptySet(),
                source = source,
            )
        } else null
    }
    
    private fun parseMangaElementsFromWeb(elements: org.jsoup.select.Elements): List<Manga> {
        return elements.mapNotNull { element ->
            try {
                // محاولة العثور على الرابط
                val link = element.selectFirst("a") ?: element.selectFirst("[href]")
                val href = link?.attrAsRelativeUrl("href") ?: return@mapNotNull null
                
                // محاولة العثور على العنوان
                val titleSelectors = listOf(
                    ".title", ".name", "h1", "h2", "h3", "h4", 
                    ".manga-title", ".comic-title", ".story-title",
                    "[data-title]", ".card-title"
                )
                
                val title = titleSelectors.firstNotNullOfOrNull { selector ->
                    element.selectFirst(selector)?.text()?.takeIf { it.isNotEmpty() }
                } ?: link.attr("title").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                
                // محاولة العثور على الصورة
                val image = element.selectFirst("img")?.let { img ->
                    img.attr("src").ifEmpty { 
                        img.attr("data-src").ifEmpty { 
                            img.attr("data-lazy-src") 
                        } 
                    }
                }
                
                // محاولة العثور على الحالة
                val statusElement = element.selectFirst(".status, .state, .manga-status")
                val status = statusElement?.text()
                
                Manga(
                    id = generateUid(href),
                    title = title,
                    altTitles = emptySet(),
                    url = href,
                    publicUrl = href.toAbsoluteUrl(domain),
                    rating = RATING_UNKNOWN,
                    contentRating = null,
                    coverUrl = image?.takeIf { it.isNotEmpty() }?.let { 
                        if (it.startsWith("http")) it else "https://$domain$it" 
                    },
                    tags = emptySet(),
                    state = parseStatusToState(status),
                    authors = emptySet(),
                    source = source,
                )
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun parseStatusToState(status: String?): MangaState? {
        return when (status?.lowercase()) {
            "ongoing", "مستمرة", "مستمر", "continuing" -> MangaState.ONGOING
            "completed", "finished", "مكتمل", "مكتملة", "انتهى" -> MangaState.FINISHED
            "dropped", "cancelled", "متوقف", "متوقفة", "ملغي" -> MangaState.ABANDONED
            else -> null
        }
    }

    private suspend fun fetchAvailableTags(): Set<MangaTag> {
        return try {
            val response = webClient.httpGet("https://$domain/api/genres").parseJson()
            val genresArray = response.optJSONArray("data") ?: response.optJSONArray("genres") ?: JSONArray()
            
            val tags = mutableSetOf<MangaTag>()
            for (i in 0 until genresArray.length()) {
                val genre = genresArray.getJSONObject(i)
                val key = genre.optString("id") ?: genre.optString("slug")
                val title = genre.optString("name") ?: genre.optString("title")
                
                if (key.isNotEmpty() && title.isNotEmpty()) {
                    tags.add(
                        MangaTag(
                            key = key,
                            title = title,
                            source = source,
                        )
                    )
                }
            }
            tags
        } catch (e: Exception) {
            // Fallback to web scraping
            emptySet()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val fullUrl = manga.url.toAbsoluteUrl(domain)
        
        // محاولة الحصول على التفاصيل من API أولاً
        val mangaId = manga.url.substringAfterLast("/")
        val apiEndpoints = listOf(
            "https://$domain/api/manga/$mangaId",
            "https://$domain/api/v1/manga/$mangaId",
            "https://$domain/api/comic/$mangaId",
            "https://$domain/rest/manga/$mangaId"
        )
        
        for (apiUrl in apiEndpoints) {
            try {
                val response = webClient.httpGet(apiUrl, customHeaders).parseJson()
                val result = parseMangaDetailsFromJson(response, manga)
                if (result.chapters.isNotEmpty() || result.description.isNotEmpty()) {
                    return result
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        // Fallback إلى web scraping
        return parseMangaDetailsFromWeb(fullUrl, manga)
    }

    private suspend fun parseMangaDetailsFromJson(json: JSONObject, manga: Manga): Manga {
        val mangaData = json.optJSONObject("data") ?: json
        
        val description = mangaData.optString("description") ?: mangaData.optString("summary")
        val status = mangaData.optString("status")
        val author = mangaData.optString("author")
        val chaptersArray = mangaData.optJSONArray("chapters") ?: JSONArray()
        
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val chapterObj = chaptersArray.getJSONObject(i)
            val chapterId = chapterObj.optString("id")
            val chapterTitle = chapterObj.optString("title") ?: chapterObj.optString("name")
            val chapterNumber = chapterObj.optDouble("number", 0.0).toFloat()
            val publishedAt = chapterObj.optString("published_at") ?: chapterObj.optString("created_at")
            
            if (chapterId.isNotEmpty()) {
                chapters.add(
                    MangaChapter(
                        id = generateUid(chapterId),
                        title = chapterTitle,
                        number = chapterNumber,
                        volume = 0,
                        url = "/manga/${manga.url.substringAfterLast("/")}/chapter/$chapterId",
                        scanlator = null,
                        uploadDate = parseDate(publishedAt),
                        branch = null,
                        source = source,
                    )
                )
            }
        }
        
        return manga.copy(
            description = description,
            state = when (status) {
                "ongoing", "مستمرة" -> MangaState.ONGOING
                "completed", "مكتمل" -> MangaState.FINISHED
                "dropped", "متوقف" -> MangaState.ABANDONED
                else -> null
            },
            authors = if (author.isNotEmpty()) setOf(author) else emptySet(),
            chapters = chapters.reversed(),
        )
    }

    private suspend fun parseMangaDetailsFromWeb(url: String, manga: Manga): Manga {
        val doc = webClient.httpGet(url).parseHtml()
        
        val description = doc.selectFirst(".description, .summary, .manga-info")?.text() ?: ""
        val status = doc.selectFirst(".status")?.text()
        val author = doc.selectFirst(".author")?.text()
        
        // Parse chapters from web
        val chapters = doc.select(".chapter-item, .chapter, .episode").mapIndexed { index, element ->
            val link = element.selectFirst("a") ?: return@mapIndexed null
            val chapterUrl = link.attrAsRelativeUrl("href")
            val chapterTitle = element.text()
            val chapterNumber = extractChapterNumber(chapterTitle) ?: (index + 1).toFloat()
            
            MangaChapter(
                id = generateUid(chapterUrl),
                title = chapterTitle,
                number = chapterNumber,
                volume = 0,
                url = chapterUrl,
                scanlator = null,
                uploadDate = 0,
                branch = null,
                source = source,
            )
        }.filterNotNull()
        
        return manga.copy(
            description = description,
            state = when (status) {
                "مستمرة" -> MangaState.ONGOING
                "مكتمل" -> MangaState.FINISHED
                "متوقف" -> MangaState.ABANDONED
                else -> null
            },
            authors = if (!author.isNullOrEmpty()) setOf(author) else emptySet(),
            chapters = chapters.reversed(),
        )
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        
        // محاولة الحصول على الصفحات من API
        val chapterId = chapter.url.substringAfterLast("/")
        val mangaId = chapter.url.substringAfter("/manga/").substringBefore("/")
        
        val apiEndpoints = listOf(
            "https://$domain/api/chapter/$chapterId/pages",
            "https://$domain/api/manga/$mangaId/chapter/$chapterId/pages",
            "https://$domain/api/v1/chapter/$chapterId/images",
            "https://$domain/rest/chapter/$chapterId/pages"
        )
        
        for (apiUrl in apiEndpoints) {
            try {
                val response = webClient.httpGet(apiUrl, customHeaders).parseJson()
                val result = parsePagesFromJson(response)
                if (result.isNotEmpty()) {
                    return result
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        // Fallback إلى web scraping
        return parsePagesFromWeb(fullUrl)
    }

    private fun parsePagesFromJson(json: JSONObject): List<MangaPage> {
        val pagesArray = json.optJSONArray("data") ?: json.optJSONArray("pages") ?: JSONArray()
        val pages = mutableListOf<MangaPage>()
        
        for (i in 0 until pagesArray.length()) {
            val pageObj = pagesArray.getJSONObject(i)
            val imageUrl = pageObj.optString("image") ?: pageObj.optString("url")
            
            if (imageUrl.isNotEmpty()) {
                val fullImageUrl = if (imageUrl.startsWith("http")) {
                    imageUrl
                } else {
                    "https://$domain$imageUrl"
                }
                
                pages.add(
                    MangaPage(
                        id = generateUid(fullImageUrl),
                        url = fullImageUrl,
                        preview = null,
                        source = source,
                    )
                )
            }
        }
        
        return pages
    }

    private suspend fun parsePagesFromWeb(url: String): List<MangaPage> {
        val doc = webClient.httpGet(url).parseHtml()
        
        return doc.select(".page-image img, .manga-page img, .chapter-image img").mapNotNull { img ->
            val imageUrl = img.src() ?: img.attr("data-src")
            if (imageUrl.isNotEmpty()) {
                MangaPage(
                    id = generateUid(imageUrl),
                    url = imageUrl,
                    preview = null,
                    source = source,
                )
            } else null
        }
    }

    private fun extractChapterNumber(title: String): Float? {
        val regex = Regex("""(\d+(?:\.\d+)?)""")
        return regex.find(title)?.value?.toFloatOrNull()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH)
    
    private fun parseDate(dateString: String): Long {
        return try {
            dateFormat.parse(dateString)?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
