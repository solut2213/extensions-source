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
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

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
class Comix3 :
    keiyoushi.source.KeiSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    // The daemon host is configurable; the default points to localhost.
    // On Android the user must set it to the PC's LAN IP, e.g. http://192.168.1.10:8766
    private val daemonHost: String
        get() = preferences.getString(PREF_DAEMON_HOST, PREF_DAEMON_HOST_DEFAULT)
            ?.trimEnd('/')
            ?: PREF_DAEMON_HOST_DEFAULT

    override val name: String = "Comix3"
    override val lang: String = "en"

    // baseUrl is the daemon; no direct site traffic from the extension
    override val baseUrl: String
        get() = daemonHost

    override val client: OkHttpClient = network.cloudflareClient
        .newBuilder()
        .build()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    // ── Popular ─────────────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request =
        GET("$daemonHost/v1/popular?page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<BrowseResponse>()
        return MangasPage(data.items.map { it.toSManga() }, data.has_next)
    }

    // ── Latest ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request =
        GET("$daemonHost/v1/latest?page=$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val data = response.parseAs<BrowseResponse>()
        return MangasPage(data.items.map { it.toSManga() }, data.has_next)
    }

    // ── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request =
        GET("$daemonHost/v1/search?q=${query.encodeUrl()}&page=$page", headers)

    override fun searchMangaParse(response: Response): MangasPage {
        val data = response.parseAs<BrowseResponse>()
        return MangasPage(data.items.map { it.toSManga() }, data.has_next)
    }

    // ── Manga detail ────────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request {
        // manga.url is the site path, e.g. /title/abc123-name
        // The daemon's /v1/manga endpoint takes that path
        val path = manga.url.ifBlank { "/" }
        return GET("$daemonHost/v1/manga?path=${path.encodeUrl()}", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val d = response.parseAs<MangaDetail>()
        return SManga.create().apply {
            url         = d.url.ifBlank { d.hid }
            title       = d.title
            description = buildDescription(d)
            thumbnail_url = d.cover
            author      = d.author
            artist      = d.artist
            genre       = buildGenre(d)
            status      = when (d.status) {
                "ongoing"   -> SManga.ONGOING
                "hiatus"    -> SManga.ON_HIATUS
                "completed" -> SManga.COMPLETED
                "cancelled" -> SManga.CANCELLED
                else        -> SManga.UNKNOWN
            }
            initialized = true
        }
    }

    // ── Chapter list ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val path = manga.url.ifBlank { "/" }
        return GET("$daemonHost/v1/chapters?path=${path.encodeUrl()}", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val data = response.parseAs<ChaptersResponse>()
        return data.chapters.map { it.toSChapter() }
    }

    // ── Page list ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        val path = chapter.url.ifBlank { "/" }
        return GET("$daemonHost/v1/pages?path=${path.encodeUrl()}", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val data = response.parseAs<PagesResponse>()
        return data.pages.map { p ->
            // image_url already points to the daemon's /v1/img proxy
            Page(p.index, imageUrl = p.image_url)
        }
    }

    // ── Image ───────────────────────────────────────────────────────────────
    // image_url from pageListParse already points to /v1/img on the daemon.
    // imageUrlParse is never called for Page objects that already have imageUrl set.
    override fun imageUrlParse(response: Response): String =
        throw UnsupportedOperationException("imageUrlParse not used — imageUrl set directly in pageListParse")

    // The daemon handles descrambling; the extension just fetches raw bytes.
    override fun getImageRequest(page: Page): Request =
        GET(page.imageUrl!!, headers)

    // ── Filters ─────────────────────────────────────────────────────────────
    // No filters — searches go directly through the daemon
    override fun getFilterList(): FilterList = FilterList()

    // ── Preferences ─────────────────────────────────────────────────────────

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key     = PREF_DAEMON_HOST
            title   = "Daemon host"
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
        url           = this@toSManga.url.ifBlank { "/title/$hid" }
        title         = this@toSManga.title
        thumbnail_url = this@toSManga.cover
    }

    private fun ChapterItem.toSChapter(): SChapter = SChapter.create().apply {
        // url is the site path returned by the daemon, e.g. title/.../12345-chapter-N
        url = this@toSChapter.url.let {
            if (it.startsWith("/")) it else "/$it"
        }
        name            = this@toSChapter.name
        chapter_number  = this@toSChapter.number.toFloat()
        date_upload     = this@toSChapter.date_ms
        scanlator       = this@toSChapter.scanlator.ifBlank { "Unknown" }
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
        when (d.type) {
            "manhwa" -> parts.add("Manhwa")
            "manhua" -> parts.add("Manhua")
            "manga"  -> parts.add("Manga")
            else     -> if (d.type.isNotBlank()) parts.add(d.type.replaceFirstChar { it.uppercase() })
        }
        parts.addAll(d.genres)
        if (d.content_rating == "erotica" || d.content_rating == "pornographic") {
            parts.add("NSFW")
        }
        return parts.distinct().joinToString()
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8")
}
