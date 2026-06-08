package org.koitharu.kotatsu.parsers.site.galleryadults.all

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.ErrorMessages
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.site.galleryadults.GalleryAdultsParser
import org.koitharu.kotatsu.parsers.util.*
import java.util.*

@MangaSourceParser("NHENTAI_TO", "NHentai.to", type = ContentType.HENTAI)
internal class NHentaiToParser(context: MangaLoaderContext) :
	GalleryAdultsParser(context, MangaParserSource.NHENTAI_TO, "nhentai.to", 25) {
	override val selectGallery = "div.index-container:not(.index-popular) .gallery, #related-container .gallery"
	override val selectGalleryLink = "a"
	override val selectGalleryTitle = ".caption"
	override val pathTagUrl = "/tags?page="
	override val selectTitle = "h1"
	override val selectTags = "#tag-container"
	override val selectTag = ".tag-container:contains(Tags) span.tags"
	override val selectAuthor = "#tags div.tag-container:contains(Artists) span.name"
	override val selectLanguageChapter =
		".tag-container:contains(Languages) span.tags a:not(.tag-17) span.name" // tag-17 = translated
	override val idImg = "image-container"

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.UPDATED, SortOrder.POPULARITY, SortOrder.POPULARITY_TODAY, SortOrder.POPULARITY_WEEK)

	override val filterCapabilities: MangaListFilterCapabilities
		get() = super.filterCapabilities.copy(
			isMultipleTagsSupported = false,
		)

	override suspend fun getFilterOptions() = super.getFilterOptions().copy(
		availableLocales = setOf(Locale.ENGLISH, Locale.JAPANESE, Locale.CHINESE),
	)

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		val url = buildString {
			append("https://")
			append(domain)
			when {
				!filter.query.isNullOrEmpty() -> {
					append("/search?q=")
					append(filter.query.urlEncoded())
					append("&")
				}
				else -> {
					val tag = filter.tags.oneOrThrowIfMany()
					val lang = filter.locale
					if (tag != null && lang != null) {
						throw IllegalArgumentException(ErrorMessages.FILTER_BOTH_LOCALE_GENRES_NOT_SUPPORTED)
					}
					when {
						tag != null -> {
							append("/tag/${tag.key}/")
							when (order) {
								SortOrder.POPULARITY -> append("?sort=popular&")
								SortOrder.POPULARITY_TODAY -> append("?sort=popular-today&")
								SortOrder.POPULARITY_WEEK -> append("?sort=popular-week&")
								else -> append("?")
							}
						}
						lang != null -> {
							append("/language/${lang.toLanguagePath()}/")
							when (order) {
								SortOrder.POPULARITY -> append("?sort=popular&")
								SortOrder.POPULARITY_TODAY -> append("?sort=popular-today&")
								SortOrder.POPULARITY_WEEK -> append("?sort=popular-week&")
								else -> append("?")
							}
						}
						else -> {
							// /go is the real gallery listing; / is just a marketing landing page
							when (order) {
								SortOrder.POPULARITY -> append("/go?sort=popular&")
								SortOrder.POPULARITY_TODAY -> append("/go?sort=popular-today&")
								SortOrder.POPULARITY_WEEK -> append("/go?sort=popular-week&")
								else -> append("/go?")
							}
						}
					}
				}
			}
			append("page=")
			append(page)
		}

		return parseMangaList(webClient.httpGet(url).parseHtml())
	}

	override suspend fun getDetails(manga: Manga): Manga {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val urlChapters = doc.selectFirstOrThrow(selectUrlChapter).attr("href")
		val tag = doc.selectFirst(selectTag)?.parseTags()
		val branch = doc.select(selectLanguageChapter).joinToString(separator = " / ") { it.text() }
		val author = doc.selectFirst(selectAuthor)?.text()?.trim()?.ifEmpty { null }
		return manga.copy(
			tags = tag.orEmpty(),
			title = doc.selectFirst(selectTitle)?.textOrNull()?.cleanupTitle() ?: manga.title,
			authors = setOfNotNull(author),
			chapters = listOf(
				MangaChapter(
					id = manga.id,
					title = manga.title,
					number = 1f,
					volume = 0,
					url = urlChapters,
					scanlator = null,
					uploadDate = 0,
					branch = branch,
					source = source,
				),
			),
		)
	}

	override fun parseMangaList(doc: Document): List<Manga> {
		return doc.select(selectGallery).mapNotNull { div ->
			val href = div.selectFirst(selectGalleryLink)?.attrAsRelativeUrl("href") ?: return@mapNotNull null
			Manga(
				id = generateUid(href),
				title = div.select(selectGalleryTitle).text().cleanupTitle(),
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = ContentRating.ADULT,
				coverUrl = div.selectFirst(selectGalleryImg)?.src().orEmpty(),
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				largeCoverUrl = null,
				description = null,
				chapters = null,
				source = source,
			)
		}
	}

	override suspend fun getPageUrl(page: MangaPage): String {
		val doc = webClient.httpGet(page.url.toAbsoluteUrl(domain)).parseHtml()
		val root = doc.body()
		return root.requireElementById(idImg).selectFirstOrThrow("img").requireSrc()
	}

	override fun Element.parseTags() = select("a").mapToSet {
		val key = it.attr("href").removeSuffix('/').substringAfterLast('/')
		val name = it.selectFirst(".name")?.text() ?: it.text()
		MangaTag(
			key = key,
			title = name.toTitleCase(sourceLocale),
			source = source,
		)
	}
}
