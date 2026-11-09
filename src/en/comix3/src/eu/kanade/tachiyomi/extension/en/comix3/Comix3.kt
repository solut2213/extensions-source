package eu.kanade.tachiyomi.extension.en.comix3

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
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
import okhttp3.Request
import java.net.URLEncoder

// ── Preference key ─────────────────────────────────────────────────────────
private const val PREF_DAEMON_HOST = "daemon_host"
private const val PREF_DAEMON_HOST_DEFAULT = "http://127.0.0.1:8766"

// ── Serialisable response DTOs ──────────────────────────────────────────────

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
    val cover: String? = null,
    val status: String = "unknown",
    val author: String = "",
    val artist: String = "",
    val genres: List<String> = emptyList(),
    val type: String = "",
    val content_rating: String = "safe",
    val year: Int? = null,
    val url: String = "",
)

@Serializable
private data class ChapterItem(
    val id: Int = 0,
    val url: String = "",
    val name: String = "",
    val number: Double = 0.0,
    val scanlator: String = "Unknown",
    val group_id: Int? = null,
    val date_ms: Long = 0L,
    val votes: Int = 0,
)

@Serializable
private data class ChaptersResponse(
    val chapters: List<ChapterItem> = emptyList(),
    val complete: Boolean = false,
)

@Serializable
private data class PageItem(
    val index: Int = 0,
    val image_url: String = "",
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
private data class PagesResponse(
    val pages: List<PageItem> = emptyList(),
)

// ── Extension ───────────────────────────────────────────────────────────────

@Source
abstract class Comix3 :
    KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    override val supportsLatest = true

    private val daemonHost: String
        get() = preferences.getString(PREF_DAEMON_HOST, PREF_DAEMON_HOST_DEFAULT)
            ?.trimEnd('/')
            ?.ifBlank { PREF_DAEMON_HOST_DEFAULT }
            ?: PREF_DAEMON_HOST_DEFAULT

    // ── Popular ─────────────────────────────────────────────────────────────

    override suspend fun getPopularManga(page: Int): MangasPage {
        val response = client.newCall(GET("$daemonHost/v1/popular?page=$page", headers)).execute()
        val data = response.parseAs<BrowseResponse>()
        return MangasPage(data.items.map { it.toSManga() }, data.has_next)
    }

    // ── Latest ──────────────────────────────────────────────────────────────

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.newCall(GET("$daemonHost/v1/latest?page=$page", headers)).execute()
        val data = response.parseAs<BrowseResponse>()
        return MangasPage(data.items.map { it.toSManga() }, data.has_next)
    }

    // ── Search ──────────────────────────────────────────────────────────────

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val response = client.newCall(GET("$daemonHost/v1/search?q=${query.encodeUrl()}&page=$page", headers)).execute()
        val data = response.parseAs<BrowseResponse>()
        return MangasPage(data.items.map { it.toSManga() }, data.has_next)
    }

    // ── Manga Update (Details & Chapters) ────────────────────────────────────

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val path = manga.url.ifBlank { "/" }
        var details = manga
        var chapterList = chapters

        if (fetchDetails) {
            val response = client.newCall(GET("$daemonHost/v1/manga?path=${path.encodeUrl()}", headers)).execute()
            val d = response.parseAs<MangaDetail>()
            details = SManga.create().apply {
                url = d.url.ifBlank { d.hid }
                title = d.title
                description = buildDescription(d)
                thumbnail_url = d.cover
                author = d.author
                artist = d.artist
                genre = buildGenre(d)
                status = when (d.status.lowercase()) {
                    "ongoing" -> SManga.ONGOING
                    "hiatus" -> SManga.ON_HIATUS
                    "completed", "finished" -> SManga.COMPLETED
                    "cancelled" -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
                initialized = true
            }
        }

        if (fetchChapters) {
            val response = client.newCall(GET("$daemonHost/v1/chapters?path=${path.encodeUrl()}", headers)).execute()
            val data = response.parseAs<ChaptersResponse>()
            chapterList = data.chapters.map { it.toSChapter() }
        }

        return SMangaUpdate(details, chapterList)
    }

    // ── Page List ───────────────────────────────────────────────────────────

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val path = chapter.url.ifBlank { "/" }
        val response = client.newCall(GET("$daemonHost/v1/pages?path=${path.encodeUrl()}", headers)).execute()
        val data = response.parseAs<PagesResponse>()
        return data.pages.map { p ->
            Page(p.index, imageUrl = p.image_url)
        }
    }

    // ── Image ───────────────────────────────────────────────────────────────

    override fun getImageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    // ── Preferences ─────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_DAEMON_HOST
            title = "Daemon host"
            summary = "URL of the comix_service daemon.\n" +
                "• PC (Suwayomi): http://127.0.0.1:8766\n" +
                "• Phone (Mihon): http://<PC-LAN-IP>:8766\n" +
                "Current: ${daemonHost}"
            setDefaultValue(PREF_DAEMON_HOST_DEFAULT)
            dialogTitle = "Daemon host"
            dialogMessage = "Enter the full URL, e.g. http://192.168.1.10:8766"
        }.also(screen::addPreference)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun BrowseItem.toSManga(): SManga = SManga.create().apply {
        url = this@toSManga.url.ifBlank { "/title/$hid" }
        title = this@toSManga.title
        thumbnail_url = this@toSManga.cover
    }

    private fun ChapterItem.toSChapter(): SChapter = SChapter.create().apply {
        url = this@toSChapter.url.let {
            if (it.startsWith("/")) it else "/$it"
        }
        name = this@toSChapter.name
        chapter_number = this@toSChapter.number.toFloat()
        date_upload = this@toSChapter.date_ms
        scanlator = this@toSChapter.scanlator.ifBlank { "Unknown" }
    }

    private fun buildDescription(d: MangaDetail): String {
        val sb = StringBuilder(d.description)
        if (d.year != null && d.year > 0) sb.append("\n\nYear: ${d.year}")
        if (d.content_rating.isNotBlank() && d.content_rating != "safe") {
            sb.append("\nRating: ${d.content_rating.replaceFirstChar { it.uppercase() }}")
        }
        return sb.toString().trim()
    }

    private fun buildGenre(d: MangaDetail): String {
        val parts = mutableListOf<String>()
        when (d.type.lowercase()) {
            "manhwa" -> parts.add("Manhwa")
            "manhua" -> parts.add("Manhua")
            "manga" -> parts.add("Manga")
            else -> if (d.type.isNotBlank()) parts.add(d.type.replaceFirstChar { it.uppercase() })
        }
        parts.addAll(d.genres)
        if (d.content_rating == "erotica" || d.content_rating == "pornographic") {
            parts.add("NSFW")
        }
        return parts.distinct().joinToString()
    }

    private fun String.encodeUrl(): String =
        URLEncoder.encode(this, "UTF-8")
}
