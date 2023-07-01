package org.koitharu.kotatsu.parsers.site.mangareader.en

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser

@MangaSourceParser("SURYASCANS", "Suryascans", "en")
internal class Suryascans(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaSource.SURYASCANS, pageSize = 5, searchPageSize = 5) {
	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("suryascans.com")

}
