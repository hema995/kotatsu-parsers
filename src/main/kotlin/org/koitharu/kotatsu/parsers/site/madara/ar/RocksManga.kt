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

	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
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

}
