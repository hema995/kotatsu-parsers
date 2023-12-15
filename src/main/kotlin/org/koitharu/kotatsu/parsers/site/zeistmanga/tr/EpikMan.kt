package org.koitharu.kotatsu.parsers.site.zeistmanga.tr

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.site.zeistmanga.ZeistMangaParser
import org.koitharu.kotatsu.parsers.util.domain
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.parseHtml
import org.koitharu.kotatsu.parsers.util.requireElementById

@MangaSourceParser("EPIKMAN", "EpikMan", "tr")
internal class EpikMan(context: MangaLoaderContext) :
	ZeistMangaParser(context, MangaSource.EPIKMAN, "www.epikman.ga") {
	override val sateOngoing = "Devam Ediyor"
	override val sateFinished = "Tamamlandı"
	override val mangaCategory = "Seri"

	override suspend fun getAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain").parseHtml()
		return doc.requireElementById("LinkList1").select("ul li a").mapNotNullToSet {
			MangaTag(
				key = it.attr("href").substringBefore("?").substringAfterLast('/'),
				title = it.text(),
				source = source,
			)
		}
	}
}
