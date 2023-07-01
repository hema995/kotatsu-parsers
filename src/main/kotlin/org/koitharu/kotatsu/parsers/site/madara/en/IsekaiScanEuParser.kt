package org.koitharu.kotatsu.parsers.site.madara.en


import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.site.madara.MadaraParser

@MangaSourceParser("ISEKAISCAN_EU", "IsekaiScan", "en")
internal class IsekaiScanEuParser(context: MangaLoaderContext) :
	MadaraParser(context, MangaSource.ISEKAISCAN_EU, "isekaiscan.to") {

	override val datePattern = "MM/dd/yyyy"

}
