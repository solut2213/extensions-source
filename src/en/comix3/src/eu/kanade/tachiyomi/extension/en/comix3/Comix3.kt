package eu.kanade.tachiyomi.extension.en.comix3

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.net.URLEncoder

// ── Preference Keys ────────────────────────────────────────────────────────
private const val PREF_DAEMON_HOST = "daemon_host"
private const val PREF_DAEMON_HOST_DEFAULT = "http://127.0.0.1:8766"

private const val PREF_CONTENT_RATING = "pref_content_rating"
private const val DEFAULT_CONTENT_RATING = "suggestive"

private const val PREF_DEFAULT_TYPES = "pref_default_types"
private const val PREF_DEFAULT_DEMOGRAPHICS = "pref_default_demographics"
private const val PREF_BLOCKED_GENRES = "pref_blocked_genres"

private const val PREF_DEDUPLICATE_CHAPTERS = "pref_deduplicate_chapters"
private const val PREF_SCANLATOR_BLACKLIST = "pref_scanlator_blacklist"
private const val PREF_ALTERNATIVE_NAMES = "pref_alternative_names_in_description"
private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"

// ── Response DTOs ──────────────────────────────────────────────────────────

@Serializable
private data class BrowseItem(
    val hid: String = "",
    val url: String = "",
    val title: String = "",
    val cover: String? = null,
)

@Serializable
private data class BrowseResponse(
    val items: List<BrowseItem> = emptyList(),
    val has_next: Boolean = false,
)

@Serializable
private data class MangaDetail(
    val hid: String = "",
    val title: String = "",
    val description: String = "",
    val cover: String = "",
    val status: String = "unknown",
    val author: String = "",
    val artist: String = "",
    val genres: List<String> = emptyList(),
    val type: String = "manga",
    val content_rating: String = "safe",
    val year: Int? = null,
    val url: String = "",
)

@Serializable
private data class ChapterItem(
    val hid: String = "",
    val url: String = "",
    val number: String = "",
    val title: String = "",
    val date: String = "",
    val scanlator: String = "",
    val lang: String = "en",
)

@Serializable
private data class ChapterListResponse(
    val chapters: List<ChapterItem> = emptyList(),
)

@Serializable
private data class PageItem(
    val index: Int = 0,
    val image_url: String = "",
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
private data class PageListResponse(
    val pages: List<PageItem> = emptyList(),
)

// ── Source Implementation ──────────────────────────────────────────────────

@Source("comix3")
class Comix3 : KeiSource(), ConfigurableSource {

    override val name: String = "Comix3"
    override val lang: String = "en"
    override val supportsLatest: Boolean = true

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val daemonHost: String
        get() = preferences.getString(PREF_DAEMON_HOST, PREF_DAEMON_HOST_DEFAULT)
            ?.trim()?.removeSuffix("/") ?: PREF_DAEMON_HOST_DEFAULT

    override val baseUrl: String
        get() = daemonHost

    // ── Filters & Preferences Resolution ───────────────────────────────────

    private fun SharedPreferences.contentRating(): String =
        getString(PREF_CONTENT_RATING, DEFAULT_CONTENT_RATING) ?: DEFAULT_CONTENT_RATING

    private fun SharedPreferences.defaultTypes(): Set<String> {
        val all = Filters.getTypes().map { it.second }.toSet()
        return getStringSet(PREF_DEFAULT_TYPES, all) ?: all
    }

    private fun SharedPreferences.defaultDemographics(): Set<String> {
        val all = Filters.getDemographics().map { it.second }.toSet()
        return getStringSet(PREF_DEFAULT_DEMOGRAPHICS, all)?.ifEmpty { all } ?: all
    }

    private fun SharedPreferences.blockedGenres(): Set<String> =
        getStringSet(PREF_BLOCKED_GENRES, emptySet()) ?: emptySet()

    private fun SharedPreferences.deduplicateChapters(): Boolean =
        getBoolean(PREF_DEDUPLICATE_CHAPTERS, false)

    private fun SharedPreferences.scanlatorBlacklist(): Set<String> =
        getString(PREF_SCANLATOR_BLACKLIST, "")
            ?.split(",")
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet() ?: emptySet()

    private fun SharedPreferences.showExtraInfo(): Boolean =
        getBoolean(PREF_SHOW_EXTRA_INFO, true)

    private fun sourceFilters(): Filters = Filters(
        contentRating = preferences.contentRating(),
        selectedTypes = preferences.defaultTypes(),
        selectedDemographics = preferences.defaultDemographics(),
        blockedGenres = preferences.blockedGenres(),
    )

    override fun getFilterList(): FilterList = sourceFilters().getFilterList()

    // ── Catalog Browsing ───────────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage {
        val uriBuilder = "https://comix.to/browse".toHttpUrl().newBuilder()
        uriBuilder.addQueryParameter("order[views_7d]", "desc")
        uriBuilder.addQueryParameter("page", page.toString())

        val defaultFilters = sourceFilters().getFilterList()
        defaultFilters.filterIsInstance<Filters.PreferenceFilter>().forEach {
            it.addToUri(uriBuilder)
        }

        val url = "$daemonHost/v1/browse?${uriBuilder.build().query}"
        val req = GET(url, headers)
        val res = client.newCall(req).await().parseAs<BrowseResponse>()

        return MangasPage(
            mangas = res.items.map { item ->
                SManga.create().apply {
                    this.url = item.url
                    this.title = item.title
                    this.thumbnail_url = item.cover
                }
            },
            hasNextPage = res.has_next,
        )
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val uriBuilder = "https://comix.to/browse".toHttpUrl().newBuilder()
        uriBuilder.addQueryParameter("order[chapter_updated_at]", "desc")
        uriBuilder.addQueryParameter("page", page.toString())

        val defaultFilters = sourceFilters().getFilterList()
        defaultFilters.filterIsInstance<Filters.PreferenceFilter>().forEach {
            it.addToUri(uriBuilder)
        }

        val url = "$daemonHost/v1/browse?${uriBuilder.build().query}"
        val req = GET(url, headers)
        val res = client.newCall(req).await().parseAs<BrowseResponse>()

        return MangasPage(
            mangas = res.items.map { item ->
                SManga.create().apply {
                    this.url = item.url
                    this.title = item.title
                    this.thumbnail_url = item.cover
                }
            },
            hasNextPage = res.has_next,
        )
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val hasTermSelection = filters.filterIsInstance<Filters.TermFilter>()
            .any { it.hasSelection }
        val uriBuilder = "https://comix.to/browse".toHttpUrl().newBuilder()

        filters.filterIsInstance<Filters.UriFilter>()
            .filterNot { it is Filters.RequiresTermSelection && !hasTermSelection }
            .forEach {
                if (it is Filters.QueryAwareFilter) {
                    it.addToUri(uriBuilder, query)
                } else {
                    it.addToUri(uriBuilder)
                }
            }

        val q = query.trim()
        if (q.isNotBlank()) {
            uriBuilder.addQueryParameter("keyword", q)
        }
        uriBuilder.addQueryParameter("page", page.toString())

        val targetQuery = uriBuilder.build().query ?: ""
        val url = "$daemonHost/v1/browse?$targetQuery"

        val req = GET(url, headers)
        val res = client.newCall(req).await().parseAs<BrowseResponse>()

        return MangasPage(
            mangas = res.items.map { item ->
                SManga.create().apply {
                    this.url = item.url
                    this.title = item.title
                    this.thumbnail_url = item.cover
                }
            },
            hasNextPage = res.has_next,
        )
    }

    // ── Manga Details & Chapters ───────────────────────────────────────────

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        var updatedManga = manga
        var updatedChapters = chapters

        if (fetchDetails) {
            val encodedPath = URLEncoder.encode(manga.url, "UTF-8")
            val url = "$daemonHost/v1/manga?path=$encodedPath"
            val req = GET(url, headers)
            val d = client.newCall(req).await().parseAs<MangaDetail>()

            updatedManga = SManga.create().apply {
                this.url = manga.url
                this.title = d.title.ifEmpty { manga.title }
                this.thumbnail_url = d.cover.ifEmpty { manga.thumbnail_url }
                this.author = d.author
                this.artist = d.artist
                this.genre = d.genres.joinToString(", ")
                this.description = buildString {
                    append(d.description)
                    if (preferences.showExtraInfo()) {
                        val extra = mutableListOf<String>()
                        if (d.type.isNotEmpty()) extra.add("Type: ${d.type.replaceFirstChar { it.uppercase() }}")
                        if (d.year != null && d.year > 0) extra.add("Year: ${d.year}")
                        if (d.content_rating.isNotEmpty()) extra.add("Rating: ${d.content_rating.replaceFirstChar { it.uppercase() }}")
                        if (extra.isNotEmpty()) {
                            if (isNotEmpty()) append("\n\n")
                            append("── Information ──\n")
                            append(extra.joinToString("\n"))
                        }
                    }
                }
                this.status = when (d.status.lowercase()) {
                    "ongoing", "releasing" -> SManga.ONGOING
                    "completed", "finished" -> SManga.COMPLETED
                    "hiatus", "on_hiatus" -> SManga.ON_HIATUS
                    "cancelled", "discontinued" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
                this.initialized = true
            }
        }

        if (fetchChapters) {
            val encodedPath = URLEncoder.encode(manga.url, "UTF-8")
            val url = "$daemonHost/v1/chapters?path=$encodedPath"
            val req = GET(url, headers)
            val resp = client.newCall(req).await().parseAs<ChapterListResponse>()

            val blacklist = preferences.scanlatorBlacklist()
            val deduplicate = preferences.deduplicateChapters()

            var chaps = resp.chapters.filter { item ->
                if (blacklist.isEmpty()) true
                else item.scanlator.lowercase().trim() !in blacklist
            }

            if (deduplicate) {
                chaps = chaps.distinctBy { it.number }
            }

            updatedChapters = chaps.map { item ->
                SChapter.create().apply {
                    this.url = item.url
                    this.name = if (item.title.isNotBlank() && item.title != item.number) {
                        if (item.number.isNotBlank()) "Ch. ${item.number} - ${item.title}" else item.title
                    } else if (item.number.isNotBlank()) {
                        "Ch. ${item.number}"
                    } else {
                        "Chapter"
                    }
                    this.chapter_number = item.number.toFloatOrNull() ?: -1f
                    this.scanlator = item.scanlator.ifEmpty { null }
                    this.date_upload = 0L
                }
            }
        }

        return SMangaUpdate(updatedManga, updatedChapters)
    }

    // ── Page List & Streaming ──────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val encodedPath = URLEncoder.encode(chapter.url, "UTF-8")
        val url = "$daemonHost/v1/pages?path=$encodedPath"
        val req = GET(url, headers)
        val resp = client.newCall(req).await().parseAs<PageListResponse>()

        return resp.pages.map { p ->
            Page(p.index, "", p.image_url)
        }
    }

    override fun getImageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers)
    }

    // ── Extension Preferences Screen ────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DAEMON_HOST
            title = "Daemon Host URL"
            summary = "Local scraper daemon address. Default: $PREF_DAEMON_HOST_DEFAULT"
            setDefaultValue(PREF_DAEMON_HOST_DEFAULT)
        }.let(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_CONTENT_RATING
            title = "Content rating"
            summary = "Maximum content rating shown in popular, latest, and search results. Current: %s."
            entries = arrayOf("Show all", "Safe only", "Up to Suggestive", "Up to Erotica", "Up to Pornographic")
            entryValues = arrayOf("", "safe", "suggestive", "erotica", "pornographic")
            setDefaultValue(DEFAULT_CONTENT_RATING)
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_TYPES
            title = "Default types"
            summary = "Types to include in popular, latest, and search results. The Type filter in search overrides this."
            entries = Filters.getTypes().map { it.first }.toTypedArray()
            entryValues = Filters.getTypes().map { it.second }.toTypedArray()
            setDefaultValue(Filters.getTypes().map { it.second }.toSet())
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_DEFAULT_DEMOGRAPHICS
            title = "Default demographics"
            summary = "Unticked demographics are hidden from popular, latest, and search results. The Demographic filter in search overrides this."
            entries = Filters.getDemographics().map { it.first }.toTypedArray()
            entryValues = Filters.getDemographics().map { it.second }.toTypedArray()
            setDefaultValue(Filters.getDemographics().map { it.second }.toSet())
        }.let(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_BLOCKED_GENRES
            title = "Blocked genres"
            summary = "Genres always excluded from results. The search filter can still include a blocked genre as a one-off override."
            entries = Filters.getGenres().map { it.first }.toTypedArray()
            entryValues = Filters.getGenres().map { it.second }.toTypedArray()
            setDefaultValue(emptySet<String>())
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_DEDUPLICATE_CHAPTERS
            title = "Deduplicate Chapters"
            summary = "Remove duplicate chapters from the chapter list."
            setDefaultValue(false)
        }.let(screen::addPreference)

        EditTextPreference(screen.context).apply {
            key = PREF_SCANLATOR_BLACKLIST
            title = "Scanlator Blacklist"
            summary = "Filter out chapters from specific groups. Comma-separated list of group names."
            dialogTitle = "Exclude groups"
            setDefaultValue("")
        }.let(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Append publication year, language, content rating, and stats to the manga description."
            setDefaultValue(true)
        }.let(screen::addPreference)
    }
}
