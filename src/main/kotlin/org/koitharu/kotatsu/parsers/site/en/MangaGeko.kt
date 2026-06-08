package org.koitharu.kotatsu.parsers.site.en

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaSourceParser
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.PagedMangaParser
import okhttp3.Headers
import org.koitharu.kotatsu.parsers.model.*
import org.koitharu.kotatsu.parsers.util.*
import java.text.SimpleDateFormat
import java.util.*

@MangaSourceParser("MANGAGEKO", "MangaGeko", "en")
internal class MangaGeko(context: MangaLoaderContext) :
	PagedMangaParser(context, MangaParserSource.MANGAGEKO, 30) {

	override val availableSortOrders: Set<SortOrder> =
		EnumSet.of(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST)

	override val configKeyDomain = ConfigKey.Domain("www.mgeko.cc", "www.mgeko.com", "www.mangageko.com")

	override val filterCapabilities: MangaListFilterCapabilities
		get() = MangaListFilterCapabilities(
			isSearchSupported = true,
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
		)

	override suspend fun getFilterOptions() = MangaListFilterOptions(
		availableTags = fetchAvailableTags(),
	)

	override fun onCreateConfig(keys: MutableCollection<ConfigKey<*>>) {
		super.onCreateConfig(keys)
		keys.add(userAgentKey)
	}

	override suspend fun getListPage(page: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
		if (!filter.query.isNullOrEmpty()) {
			if (page > 1) return emptyList()
			val url = "https://$domain/search/?search=${filter.query.urlEncoded()}"
			val doc = webClient.httpGet(url).parseHtml()
			return doc.select("li.novel-item").map { div ->
				val href = div.selectFirstOrThrow("a").attrAsRelativeUrl("href")
				Manga(
					id = generateUid(href),
					title = div.selectFirstOrThrow("h4").text(),
					altTitles = emptySet(),
					url = href,
					publicUrl = href.toAbsoluteUrl(domain),
					rating = RATING_UNKNOWN,
					contentRating = null,
					coverUrl = div.selectFirstOrThrow("img").src(),
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					source = source,
				)
			}
		}
		val ordering = when (order) {
			SortOrder.POPULARITY -> "-views"
			SortOrder.UPDATED -> "-last_upload"
			SortOrder.NEWEST -> "-created_at"
			else -> "-views"
		}
		val url = "https://$domain/browse-comics/data/?page=$page&ordering=$ordering"
		val headers = Headers.Builder()
			.add("X-Requested-With", "XMLHttpRequest")
			.add("Accept", "application/json, text/plain, */*")
			.build()
		val json = webClient.httpGet(url, headers).parseJson()
		val html = json.getString("results_html")
		val doc = org.jsoup.Jsoup.parse(html, "https://$domain")
		return doc.select("article.comic-card").mapNotNull { el ->
			val titleEl = el.selectFirst(".comic-card__title a") ?: return@mapNotNull null
			val href = titleEl.attrAsRelativeUrl("href")
			val title = titleEl.text().trim()
			if (title.isBlank()) return@mapNotNull null
			val cover = el.selectFirst(".comic-card__cover img")?.src()
			Manga(
				id = generateUid(href),
				title = title,
				altTitles = emptySet(),
				url = href,
				publicUrl = href.toAbsoluteUrl(domain),
				rating = RATING_UNKNOWN,
				contentRating = null,
				coverUrl = cover,
				tags = emptySet(),
				state = null,
				authors = emptySet(),
				source = source,
			)
		}
	}

	private suspend fun fetchAvailableTags(): Set<MangaTag> {
		val doc = webClient.httpGet("https://$domain/browse-comics/").parseHtml()
		return doc.selectFirstOrThrow("div.genre-select-i").select("label").mapToSet { label ->
			MangaTag(
				key = label.selectFirstOrThrow("input").attr("value"),
				title = label.text(),
				source = source,
			)
		}
	}

	override suspend fun getDetails(manga: Manga): Manga = coroutineScope {
		val doc = webClient.httpGet(manga.url.toAbsoluteUrl(domain)).parseHtml()
		val chaptersDeferred = async { loadChapters(manga.url) }
		val author = doc.selectFirstOrThrow(".author").textOrNull()
		manga.copy(
			altTitles = setOfNotNull(doc.selectFirstOrThrow(".alternative-title").textOrNull()),
			state = when (doc.selectFirstOrThrow(".header-stats span:contains(Status) strong").text()) {
				"Ongoing" -> MangaState.ONGOING
				"Completed" -> MangaState.FINISHED
				else -> null
			},
			tags = doc.select(".categories ul li a").mapToSet { a ->
				MangaTag(
					key = a.attr("href").substringAfterLast('='),
					title = a.text(),
					source = source,
				)
			},
			authors = setOfNotNull(author),
			description = doc.selectFirstOrThrow(".description").html(),
			chapters = chaptersDeferred.await(),
		)
	}

	private suspend fun loadChapters(mangaUrl: String): List<MangaChapter> {
		val urlChapter = mangaUrl + "all-chapters/"
		val doc = webClient.httpGet(urlChapter.toAbsoluteUrl(domain)).parseHtml()
		val dateFormat = SimpleDateFormat("MMM dd, yyyy", sourceLocale)
		return doc.requireElementById("chapters").select("ul.chapter-list li")
			.mapChapters(reversed = true) { i, li ->
				val a = li.selectFirstOrThrow("a")
				val url = a.attrAsRelativeUrl("href")
				val name = li.selectFirstOrThrow(".chapter-title").text()
				val dateText = li.select(".chapter-update").attr("datetime").substringBeforeLast(',')
					.replace(".", "").replace("Sept", "Sep")
				MangaChapter(
					id = generateUid(url),
					title = name,
					number = i + 1f,
					volume = 0,
					url = url,
					scanlator = null,
					uploadDate = dateFormat.parseSafe(dateText),
					branch = null,
					source = source,
				)
			}
	}

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        val fullUrl = chapter.url.toAbsoluteUrl(domain)
        val doc = webClient.httpGet(fullUrl).parseHtml()
        return doc.select("center img")
            .mapNotNull { it.attr("src").takeIf { src -> src.isNotBlank() } }
            // remove all invaild images + credits
            .filterNot { it.startsWith("data:image") || it.contains("credits-mgeko.png") }
            .distinct().map { url ->
                val finalUrl = url.toRelativeUrl(domain)
                MangaPage(
                    id = generateUid(finalUrl),
                    url = finalUrl,
                    preview = null,
                    source = source,
                )
            }
    }
}
