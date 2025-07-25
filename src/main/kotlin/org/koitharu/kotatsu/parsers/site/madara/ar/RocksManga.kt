package org.koitharu.kotatsu.parsers.site.madara.ar

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser
import org.koitharu.kotatsu.parsers.util.attrAsRelativeUrlOrNull
import org.koitharu.kotatsu.parsers.util.generateUid
import org.koitharu.kotatsu.parsers.util.mapChapters
import org.koitharu.kotatsu.parsers.util.parseFailed
import java.text.SimpleDateFormat

@MangaSourceParser("ROCKSMANGA", "RocksManga", "ar")
internal class RocksManga(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.ROCKSMANGA, "rockscans.org") {
	
	override val selectChapter = "li.wp-manga-chapter, .chapter-item, .listing-chapters_wrap li"
	override val datePattern = "d MMMM yyyy"
	override val selectDate = ".chapter-release-date, .release-date"
	override val selectBodyPage = "div.reading-content, .read-container"
	override val selectPage = "img.wp-manga-page, .read-content img"
	override val selectDesc = "div.description, .summary__content, .manga-excerpt"
	
	override suspend fun loadChapters(mangaUrl: String, document: Document): List<MangaChapter> {
		val dateFormat = SimpleDateFormat(datePattern, sourceLocale)
		
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
		if (chapterElements.isEmpty()) {
			chapterElements = document.select(selectChapter)
		}
		
		return chapterElements.mapChapters(reversed = true) { i, li ->
			val a = li.selectFirst("a")
			val href = a?.attrAsRelativeUrlOrNull("href") ?: li.parseFailed("Link is missing")
			val link = href + stylePage
			
			// محاولة الحصول على التاريخ من عدة مصادر
			val dateText = li.selectFirst("a.c-new-tag")?.attr("title") 
				?: li.selectFirst(selectDate)?.text()
				?: li.selectFirst(".chapter-release-date")?.text()
				?: li.selectFirst(".release-date")?.text()
				?: li.selectFirst("span.date")?.text()
				?: li.selectFirst(".chapter-date")?.text()
			
			// محاولة الحصول على اسم الفصل من عدة مصادر
			val name = a?.selectFirst(".ch-title")?.text()
				?: a?.selectFirst(".chapter-name")?.text()
				?: a?.selectFirst(".wp-manga-chapter-name")?.text()
				?: a?.ownText()
				?: a?.text()
				?: "Chapter ${i + 1}"
			
			MangaChapter(
				id = generateUid(href),
				url = link,
				title = name,
				number = i + 1f,
				volume = 0,
				branch = null,
				uploadDate = parseChapterDate(
					dateFormat,
					dateText,
				),
				scanlator = null,
				source = source,
			)
		}
	}
}
