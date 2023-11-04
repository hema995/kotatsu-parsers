package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.*

@MangaSourceParser("DOUJINDESUUK", "DoujinDesu.uk", type = ContentType.HENTAI)
internal class DoujinDesuUk(context: MangaLoaderContext) :
	GalleryAdultsParser(context, MangaSource.DOUJINDESUUK, "doujindesu.uk", 50) {
	override val selectGallery = ".gallery"
	override val selectGalleryLink = "a"
	override val selectGalleryTitle = ".caption"
	override val pathTagUrl = "/tags?page="
	override val selectTags = "#tag-container"
	override val selectTag = "div.tag-container:contains(Tags) span.tags"
	override val selectAuthor = "div.tag-container:contains(Artists) a"
	override val selectLanguageChapter = "div.tag-container:contains(Languages) a"
	override val idImg = "image-container"
	override val listLanguage = arrayOf(
		"/english",
		"/japanese",
		"/chinese",
	)

	override fun parseMangaList(doc: Document): List<Manga> {
		val regexBrackets = Regex("\\[[^]]+]|\\([^)]+\\)")
		val regexSpaces = Regex("\\s+")
		return doc.select(selectGallery).map { div ->
			val href = div.selectFirstOrThrow(selectGalleryLink).attrAsRelativeUrl("href")
			Manga(
				id = generateUid(href),
				title = div.select(selectGalleryTitle).text().replace(regexBrackets, "")
					.replace(regexSpaces, " ")
					.trim(),
				altTitle = null,
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				isNsfw = isNsfwSource,
				coverUrl = div.selectLastOrThrow(selectGalleryImg).src().orEmpty(),
				tags = emptySet(),
				state = null,
				author = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
		val root = doc.body()
		return root.requireElementById(idImg).selectFirstOrThrow("img").src() ?: root.parseFailed("Image src not found")
	}

}
