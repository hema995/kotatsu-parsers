    package org.koitharu.kotatsu.parsers.site.liliana

    import kotlinx.coroutines.async
    import kotlinx.coroutines.coroutineScope
    import org.json.JSONObject
    import org.jsoup.nodes.Document
    import org.jsoup.nodes.Element
    import org.koitharu.kotatsu.parsers.MangaLoaderContext
    import org.koitharu.kotatsu.parsers.PagedMangaParser
    import org.koitharu.kotatsu.parsers.config.ConfigKey
    import org.koitharu.kotatsu.parsers.model.*
    import org.koitharu.kotatsu.parsers.util.*
    import java.text.SimpleDateFormat
    import java.util.*

    internal abstract class LilianaParser(
        context: MangaLoaderContext,
        source: MangaParserSource,
        domain: String,
        pageSize: Int = 24
    ) : PagedMangaParser(context, source, pageSize) {

        override val configKeyDomain = ConfigKey.Domain(domain)

        override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
            super.onCreateConfig(keys)
            keys.add(userAgentKey)
        }

        override val availableSortOrders: Set<SortOrder> = EnumSet.of(
            SortOrder.UPDATED,
            SortOrder.POPULARITY,
            SortOrder.ALPHABETICAL
        )

        override val filterCapabilities: MangaListFilterCapabilities
            get() = MangaListFilterCapabilities(
                isMultipleTagsSupported = true,
                isTagsExclusionSupported = false,
                isSearchSupported = true,
                isSearchWithFiltersSupported = true,
                isYearSupported = false,
                isYearRangeSupported = false,
                isOriginalLocaleSupported = false
            )

        override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
            val url = buildString {
                append("https://")
                append(domain)
                when {
                    !filter.query.isNullOrEmpty() -> {
                        append("/search")
                        append("?keyword=")
                        append(filter.query.urlEncoded())
                    }
                    else -> {
                        append("/filter")
                    }
                }
                append("/")
                append(page)
                append("/")
                
                when (order) {
                    SortOrder.UPDATED -> append("?sort=last_update")
                    SortOrder.POPULARITY -> append("?sort=views")
                    SortOrder.ALPHABETICAL -> append("?sort=name")
                    else -> append("?sort=last_update")
                }
                
                filter.tags.forEach { tag ->
                    append("&genres=")
                    append(tag.key)
                }
                
                if (filter.states.isNotEmpty()) {
                    append("&status=")
                    append(when (filter.states.first()) {
                        MangaState.ONGOING -> "ongoing"
                        MangaState.FINISHED -> "completed"
                        else -> "all"
                    })
                }
            }

            val doc = webClient.httpGet(url).parseHtml()
            return doc.select("div#main div.grid > div").map { parseSearchManga(it) }
        }

        private fun parseSearchManga(element: Element): Manga {
            val href = element.selectFirst("a")?.attrAsRelativeUrl("href") ?: element.parseFailed("Manga link not found")
            return Manga(
                id = generateUid(href),
                url = href,
                publicUrl = href.toAbsoluteUrl(domain),
                coverUrl = element.selectFirst("img")?.src().orEmpty(),
                title = element.selectFirst(".text-center a")?.text().orEmpty(),
                altTitle = null,
                rating = RATING_UNKNOWN,
                tags = emptySet(),
                author = null,
                state = null,
                source = source,
                isNsfw = false,
            )
        }

        override suspend fun getDetails(manga: Manga): Manga {
            val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
            return manga.copy(
                description = doc.selectFirst("div#syn-target")?.text(),
                largeCoverUrl = doc.selectFirst(".a1 > figure img")?.src(),
                tags = doc.select(".a2 div > a[rel='tag'].label").mapNotNullToSet { a ->
                    MangaTag(
                        key = a.attr("href").substringAfterLast("/"),
                        title = a.text().trim(),
                        source = source,
                    )
                },
                author = doc.selectFirst("div.y6x11p i.fas.fa-user + span.dt")?.text()?.takeUnless {
                    it.equals("updating", true)
                },
                state = when (doc.selectFirst("div.y6x11p i.fas.fa-rss + span.dt")?.text()?.lowercase()) {
                    "ongoing", "đang tiến hành", "進行中" -> MangaState.ONGOING
                    "completed", "hoàn thành", "完了" -> MangaState.FINISHED
                    else -> null
                },
                chapters = doc.select("ul > li.chapter").mapChapters { i, element ->
                    val href = element.selectFirst("a")?.attrAsRelativeUrl("href") ?: element.parseFailed("Chapter link not found")
                    MangaChapter(
                        id = generateUid(href),
                        name = element.selectFirst("a")?.text().orEmpty(),
                        number = i + 1f,
                        url = href,
                        scanlator = null,
                        uploadDate = element.selectFirst("time[datetime]")?.attr("datetime")?.toLongOrNull()?.times(1000) ?: 0L,
                        branch = null,
                        source = source,
                        volume = 0
                    )
                }
            )
        }

        override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
            val fullUrl = chapter.url.toAbsoluteUrl(domain)
            val doc = webClient.httpGet(fullUrl).parseHtml()
            val script = doc.selectFirst("script:containsData(const CHAPTER_ID)")?.data()
                ?: throw Exception("Failed to get chapter id")

            val chapterId = script.substringAfter("const CHAPTER_ID = ").substringBefore(";")

            val ajaxUrl = buildString {
                append("https://")
                append(domain)
                append("/ajax/image/list/chap/")
                append(chapterId)
            }

            val ajaxResponse = webClient.httpGet(ajaxUrl)
            val responseJson = JSONObject(ajaxResponse.requireBody().string())

            if (!responseJson.optBoolean("status", false)) {
                throw Exception(responseJson.optString("msg"))
            }

            val pageListHtml = responseJson.getString("html")
            val pageListDoc = org.jsoup.Jsoup.parse(pageListHtml)

            return pageListDoc.select("div.separator[data-index]").map { page ->
                val index = page.attr("data-index").toInt()
                val url = page.selectFirst("a")?.attr("abs:href") ?: page.parseFailed("Image url not found")
                MangaPage(
                    id = generateUid(url),
                    url = url,
                    preview = null,
                    source = source,
                )
            }.sortedBy { it.id.toInt() }
        }

        protected open suspend fun getAvailableTags(): Set<MangaTag> = coroutineScope {
            val doc = webClient.httpGet("https://$domain/filter").parseHtml()
            doc.select("div.advanced-genres > div > .advance-item").mapNotNullToSet { element ->
                MangaTag(
                    key = element.selectFirst("span")?.attr("data-genre") ?: return@mapNotNullToSet null,
                    title = element.text().trim(),
                    source = source,
                )
            }
        }

        override suspend fun getFilterOptions(): MangaListFilterOptions = MangaListFilterOptions(
            availableTags = getAvailableTags(),
            availableStates = setOf(MangaState.ONGOING, MangaState.FINISHED)
        )
    }
