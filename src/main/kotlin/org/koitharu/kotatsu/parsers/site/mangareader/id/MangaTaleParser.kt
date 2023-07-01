package org.koitharu.kotatsu.parsers.site.mangareader.id

import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.site.mangareader.MangaReaderParser
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGATALE", "MangaTale", "id")
internal class MangaTaleParser(context: MangaLoaderContext) :
	MangaReaderParser(context, MangaSource.MANGATALE, pageSize = 20, searchPageSize = 10) {
	override val configKeyDomain: ConfigKey.Domain
		get() = ConfigKey.Domain("mangatale.co")


	override val chapterDateFormat: SimpleDateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
}
