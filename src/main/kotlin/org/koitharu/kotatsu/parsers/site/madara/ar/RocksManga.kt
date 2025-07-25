package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ROCKSMANGA, "rockscans.org") {

	override val listUrl = "manga/"
	override val tagPrefix = "manga-genre/"
	
	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.UPDATED,
		SortOrder.POPULARITY,
		SortOrder.NEWEST,
		SortOrder.ALPHABETICAL,
	)

	override suspend fun getListPage(page: Int, order: SortOrder, tag: MangaTag?): List<Manga> {
		val url = buildString {
			append("https://").append(domain)
			when {
				!tag?.key.isNullOrEmpty() -> {
					append("/manga-genre/").append(tag!!.key).append("/")
				}
				else -> append("/")
			}
			if (page > 1) {
				append("?page=").append(page)
			}
		}

		val doc = webClient.httpGet(url).parseHtml()
		
		return doc.select("div.manga-item, .post, .wp-block-post").mapNotNull { div ->
			val a = div.selectFirst("a") ?: return@mapNotNull null
			val href = a.attrAsRelativeUrlOrNull("href") ?: return@mapNotNull null
			val img = div.selectFirst("img") ?: return@mapNotNull null
			
			Manga(
				id = generateUid(href),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				coverUrl = img.src()?.toAbsoluteUrl(domain),
				title = img.attr("alt").ifEmpty { 
					a.selectFirst(".manga-title, .post-title, .wp-block-post-title")?.text() 
						?: a.ownText() 
				},
				altTitle = null,
				rating = RATING_UNKNOWN,
				tags = emptySet(),
				author = null,
				state = null,
				source = source,
				isNsfw = false,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		
		val description = doc.selectFirst("div.description, .summary__content, .entry-content")?.text()
		
		// محاولة العثور على قائمة الفصول في نفس الصفحة أو صفحة منفصلة
		val chapters = loadChapters(manga.url, doc)
		
		return manga.copy(
			description = description,
			chapters = chapters,
		)
	}

	private suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val chapters = mutableListOf<MangaChapter>()
		
		// جرب البحث عن الفصول في الصفحة الحالية
		var chapterElements = document.select("li.wp-manga-chapter")
		if (chapterElements.isEmpty()) {
			chapterElements = document.select(".chapter-item")
		}
		if (chapterElements.isEmpty()) {
			chapterElements = document.select("ul.main li")
		}
		if (chapterElements.isEmpty()) {
			chapterElements = document.select("a[href*='chapter'], a[href*='الفصل']")
		}
		
		// إذا لم نجد فصول، جرب البحث في الصفحة الرئيسية للمانجا
		if (chapterElements.isEmpty()) {
			val mangaDoc = if (document.location().contains(mangaUrl)) {
				document
			} else {
				webClient.httpGet(mangaUrl.toAbsoluteUrl(domain)).parseHtml()
			}
			
			chapterElements = mangaDoc.select("a[href*='/chapter/'], a[href*='/الفصل/']")
			if (chapterElements.isEmpty()) {
				val filteredElements = mangaDoc.select("a").filter { 
					it.attr("href").contains("chapter", ignoreCase = true) ||
					it.text().contains("فصل", ignoreCase = true) ||
					it.text().contains("chapter", ignoreCase = true)
				}
				chapterElements = org.jsoup.select.Elements(filteredElements)
			}
		}
		
		chapterElements.forEachIndexed { index, element ->
			try {
				val href = if (element.tagName() == "a") {
					element.attrAsRelativeUrlOrNull("href")
				} else {
					element.selectFirst("a")?.attrAsRelativeUrlOrNull("href")
				} ?: return@forEachIndexed
				
				val title = if (element.tagName() == "a") {
					element.text()
				} else {
					element.selectFirst("a")?.text()
				} ?: "Chapter ${index + 1}"
				
				chapters.add(
					MangaChapter(
						id = generateUid(href),
						url = href,
						title = title.trim(),
						number = (index + 1).toFloat(),
						volume = 0,
						branch = null,
						uploadDate = 0L,
						scanlator = null,
						source = source,
					)
				)
			} catch (e: Exception) {
				// تجاهل الفصول التي لا يمكن معالجتها
			}
		}
		
		return chapters.reversed() // أحدث فصل أولاً
	}

	override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
		val fullUrl = chapter.url.toAbsoluteUrl(domain)
		val doc = webClient.httpGet(fullUrl).parseHtml()
		
		// جرب عدة selectors للصور
		var images = doc.select("div.reading-content img")
		if (images.isEmpty()) {
			images = doc.select(".read-container img")
		}
		if (images.isEmpty()) {
			images = doc.select("img.wp-manga-page")
		}
		if (images.isEmpty()) {
			images = doc.select(".entry-content img")
		}
		if (images.isEmpty()) {
			images = doc.select("img[src*='wp-content']")
		}
		
		return images.mapNotNull { img ->
			val url = img.src()?.toAbsoluteUrl(domain) ?: return@mapNotNull null
			
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/").parseHtml()
		return doc.select("a[href*='genre'], a[href*='tag']").mapNotNullToSet { a ->
			val href = a.attr("href") ?: return@mapNotNullToSet null
			val key = href.substringAfterLast('/')
			if (key.isBlank()) return@mapNotNullToSet null
			
			MangaTag(
				key = key,
				title = a.text(),
				source = source,
			)
		}
	}
}
