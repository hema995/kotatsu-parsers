package org.koitharu.kotatsu.parsers.site.madara.tr

import org.koitharu.kotatsu.parsers.Broken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@Broken
@MangaSourceParser("LICHSUBS", "LichSubs", "tr")
internal class LichSubs(context: MangaLoaderContext) :
	MadaraParser(context, MangaParserSource.LICHSUBS, "www.kuroimanga.com") {
	override val datePattern = "dd/MM/yyyy"
}
