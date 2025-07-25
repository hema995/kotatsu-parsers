package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ROCKSMANGA, "rockscans.org") {

	override val datePattern = "d MMMM yyyy"
	override val selectDesc = "div.description, .summary__content, .manga-excerpt"
	override val selectGenre = "div.genres a, .manga-genre a"
	override val selectTag = selectGenre
	override val selectAlt = "div.manga-alt"
	override val selectArtist = "div.artist-content"
	override val selectAuthor = "div.author-content"
	override val selectState = "div.post-status div.post-content_item:contains(الحالة) div.summary-content"
	override val selectYear = "div.post-content_item:contains(السنة) div.summary-content"
	
	// تحديث selectors للفصول
	override val selectChapter = "li.wp-manga-chapter, .chapter-item, .listing-chapters_wrap li"
	override val selectDate = ".chapter-release-date, .release-date"
	
	// selectors لصفحات القراءة
	override val selectBodyPage = "div.reading-content, .read-container"
	override val selectPage = "img.wp-manga-page, .read-content img"

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		val chapters = mutableListOf<MangaChapter>()
		
		// جرب عدة selectors للفصول
		var chapterElements = document.select("li.wp-manga-chapter")
		if (chapterElements.isEmpty()) {
			chapterElements = document.select(".chapter-item")
		}
		if (chapterElements.isEmpty()) {
			chapterElements = document.select(".listing-chapters_wrap li")
		}
		if (chapterElements.isEmpty()) {
			chapterElements = document.select("ul.main li")
		}
		
		chapterElements.forEachIndexed { index, element ->
			try {
				val link = element.selectFirst("a")
				val href = link?.attrAsRelativeUrlOrNull("href") 
					?: element.parseFailed("Chapter link not found")
				
				val title = link?.text()?.trim() 
					?: element.selectFirst(".chapter-name, .wp-manga-chapter-name")?.text()?.trim()
					?: "Chapter ${index + 1}"
				
				// محاولة الحصول على تاريخ النشر
				val dateText = element.selectFirst(".chapter-release-date, .release-date")?.text()
					?: element.selectFirst("span.date, .chapter-date")?.text()
				
				chapters.add(
					MangaChapter(
						id = generateUid(href),
						url = href,
						title = title,
						number = (index + 1).toFloat(),
						volume = 0,
						branch = null,
						uploadDate = parseChapterDate(dateFormat, dateText),
						scanlator = null,
						source = source,
					)
				)
			} catch (e: Exception) {
				// تجاهل الفصول التي لا يمكن تحليلها
			}
		}
		
		return chapters.reversed() // عكس الترتيب ليكون الأحدث أولاً
	}

	override suspend fun loadPages(chapterUrl: String): List<MangaPage> {
		val fullUrl = chapterUrl.toAbsoluteUrl(domain)
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
		
		return images.mapNotNull { img ->
			val url = img.attrAsAbsoluteUrlOrNull("src") 
				?: img.attrAsAbsoluteUrlOrNull("data-src")
				?: return@mapNotNull null
			
			MangaPage(
				id = generateUid(url),
				url = url,
				preview = null,
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val root = doc.body().selectFirstOrThrow(selectInfo)
		
		return manga.copy(
			altTitle = root.selectFirst(selectAlt)?.text().orEmpty(),
			rating = root.selectFirst(selectRating)?.attr("value")?.toFloatOrNull()?.div(5f) ?: RATING_UNKNOWN,
			tags = root.select(selectTag).mapNotNullToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('/'),
					title = a.text().ifEmpty { return@mapNotNullToSet null },
					source = source,
				)
			},
			author = root.selectFirst(selectAuthor)?.text(),
			artist = root.selectFirst(selectArtist)?.text(),
			description = root.selectFirst(selectDesc)?.html()?.parseHtml()?.text(),
			chapters = loadChapters(manga.url, doc),
			state = when (root.selectFirst(selectState)?.text()?.lowercase()) {
				"ongoing", "مستمر", "مستمرة" -> MangaState.ONGOING
				"completed", "مكتمل", "مكتملة" -> MangaState.FINISHED
				else -> null
			},
			source = source,
			isNsfw = manga.isNsfw,
		)
	}
}
