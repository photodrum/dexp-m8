package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.net.ConnectivityManager
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Сетевые каталоги (п. 10): свой клиент OPDS 1.2 на `XmlPullParser`, без библиотек —
 * на 1 ГБ ОЗУ и API 27 хватает `java.net` и `org.xmlpull` из `android.jar`.
 *
 * Устройство (почему так, а не иначе):
 *
 *  - **разбор потоком.** Фид каталога у Calibre бывает на мегабайты; `DocumentBuilder`
 *    построил бы дерево в куче. `XmlPullParser` читает по событию и держит только
 *    текущую запись.
 *  - **предел ответа [MAX_BYTES]** — фид больше 4 МБ не наш случай: обрываем чтение,
 *    иначе 1 ГБ ОЗУ кончается на первом же «все книги каталога».
 *  - **кэш на диск** ([Cache]) — `ETag`/`Last-Modified` и тело ответа лежат файлом в
 *    `cacheDir/opds`. Сеть пропала — экран показывает последний удачный фид (B57
 *    говорит «скачанные книги читаются и без сети»).
 *  - **только GET и только чтение.** Никаких POST/PUT: каталог — источник книг,
 *    а не место, куда мы пишем. Авторизация — HTTP Basic (`Authorization`).
 *  - **всё в фоновом потоке.** Класс ничего не рисует и не знает про экраны;
 *    экран вызывает [fetch] из своего потока и переносит итог в UI сам.
 *
 * Типы различаются по `rel` ссылок (§4 OPDS 1.2, «Acquisition» и «Navigation»):
 *  - навигация — тип `application/atom+xml;profile=opds-catalog`;
 *  - книга — `http://opds-spec.org/acquisition` (и `/open-access`);
 *  - обложка — `.../image` и `.../thumbnail`;
 *  - страницы — `next`, `prev`;
 *  - поиск — `search` (шаблон OpenSearch с `{searchTerms}`).
 */
class Opds(private val ctx: Context) {

    /** Ссылка `<link>`: адрес уже абсолютный, [rel] и [type] — как в фиде. */
    class Link(val href: String, val rel: String, val type: String, val title: String)

    /** Запись фида: и книга, и раздел каталога — различаются наличием [acquisitions]. */
    class Entry {
        var id = ""
        var title = ""
        var author = ""
        var summary = ""
        var updated = ""
        var lang = ""
        val categories = ArrayList<String>(2)
        val links = ArrayList<Link>(6)

        /** Ссылки на файлы книги (FB2, EPUB…); пусто — это раздел, а не книга. */
        fun acquisitions(): ArrayList<Link> =
            ArrayList(links.filter { it.rel == REL_ACQUISITION || it.rel == REL_ACQUISITION_OA })

        /** Обложка: сначала полноразмерная, потом миниатюра. */
        fun cover(): String? =
            (links.firstOrNull { it.rel == REL_IMAGE } ?: links.firstOrNull { it.rel == REL_THUMB })?.href

        /** Первая навигационная ссылка записи (раздел каталога). */
        fun section(): Link? = links.firstOrNull { it.type.startsWith(TYPE_NAV) || it.rel == REL_SUBSECTION }
    }

    /** Разобранный фид. [next]/[prev] — абсолютные адреса страниц, null — страницы нет. */
    class Feed(
        val url: String,
        val title: String,
        val entries: ArrayList<Entry>,
        val next: String?,
        val prev: String?,
        val search: String?,
        val total: Int,
    ) {
        /** В фиде книги (есть acquisition), а не разделы каталога. */
        val books: Boolean get() = entries.any { it.acquisitions().isNotEmpty() }
    }

    /**
     * Итог запроса. [feed] == null — не получилось, причина в [why] (готовая строка для
     * экрана), [code] — HTTP-код или 0. [cached] — фид взят с диска, сети не было.
     */
    class Reply(val feed: Feed?, val code: Int, val why: String?, val cached: Boolean)

    /** Логин и пароль каталога; оба пустые — вход не нужен. */
    class Auth(val user: String, val pass: String) {
        val empty: Boolean get() = user.isEmpty() && pass.isEmpty()
    }

    // ------------------------------------------------------------------ сеть

    /** Есть ли сеть: подключён хотя бы один интерфейс. Экран B57 — если false. */
    fun online(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val ni = cm?.activeNetworkInfo
        ni != null && ni.isConnected
    } catch (t: Throwable) {
        Trace.step("opds online FAIL: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /**
     * Загрузить и разобрать фид. Сеть есть — идём в сеть (с `ETag`), сети нет — отдаём
     * кэш, если он был. [url] — адрес фида; относительные `href` считаются от него.
     */
    fun fetch(url: String, auth: Auth? = null): Reply {
        lastWhy = null
        val hit = cache.read(url)
        if (!online()) {
            return if (hit != null) Reply(parseCache(hit, url), 0, null, true)
            else Reply(null, 0, "Нет подключения", false).also { lastWhy = "Нет подключения" }
        }
        val r = request(url, auth, hit?.etag, hit?.lastModified)
        try {
            if (r.code == 304 && hit != null) return Reply(parseCache(hit, url), 200, null, true)
            if (r.code in 200..299 && r.stream != null) {
                val bytes = readBody(r.stream)
                val feed = parse(bytes, url)
                cache.write(url, bytes, r.etag, r.lastModified)
                Trace.step("opds: ${feed.entries.size} записей, книги=${feed.books} <- $url")
                return Reply(feed, r.code, null, false)
            }
            // Ошибки: если есть кэш — показываем его, иначе готовим причину для экрана.
            val why = when (r.code) {
                401, 403 -> if (auth == null || auth.empty) "Нужен логин и пароль"
                else "Неверный логин или пароль"
                404 -> "Каталог не найден по этому адресу"
                in 200..299 -> "Пустой ответ каталога"
                else -> "Сервер ответил ${r.code}"
            }
            if (hit != null && r.code != 401 && r.code != 403) return Reply(parseCache(hit, url), r.code, null, true)
            lastWhy = why
            return Reply(null, r.code, why, false)
        } catch (t: Throwable) {
            Trace.step("opds fetch FAIL $url: ${t.javaClass.simpleName}: ${t.message}")
            if (hit != null) return Reply(parseCache(hit, url), 0, null, true)
            val why = whyOf(t)
            lastWhy = why
            return Reply(null, 0, why, false)
        } finally {
            try {
                r.stream?.close()
            } catch (ignored: Throwable) {
            }
            r.conn?.disconnect()
        }
    }

    /** Причина последнего неудачного [fetch]/[probe] для экрана; null — всё хорошо. */
    var lastWhy: String? = null
        private set

    /**
     * Проверка адреса до сохранения (B15/B49, панель «Результат проверки»): название
     * каталога, сколько книг и какие форматы встречаются в первых записях.
     * null — каталог не ответил, причина в [lastWhy].
     */
    fun probe(url: String, auth: Auth? = null): Probe? {
        val f = fetch(url, auth).feed ?: return null
        lastWhy = null
        val exts = LinkedHashSet<String>(4)
        for (e in f.entries) {
            for (l in e.acquisitions()) {
                val x = formatOf(l.type)
                if (x != null) exts.add(x)
            }
            if (exts.size >= 3) break
        }
        val n = if (f.total > 0) f.total else f.entries.count { it.acquisitions().isNotEmpty() }
        return Probe(f.title.ifEmpty { hostOf(url) }, n, exts.joinToString(", "))
    }

    /** Итог [probe]: [formats] — «FB2, EPUB» (пусто — книг в фиде нет). */
    class Probe(val title: String, val books: Int, val formats: String)

    /** Запись кэша: тело фида и его метки для `If-None-Match`. */
    class Hit(val body: ByteArray, val etag: String?, val lastModified: String?)

    /** Подставить запрос в шаблон OpenSearch `...?q={searchTerms}`. */
    fun searchUrl(template: String, q: String): String {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        return template.replace("{searchTerms}", enc).replace("{searchTerms?}", enc)
    }

    // --------------------------------------------------- загрузка книги (B18)

    /**
     * Скачать файл книги в [dir] потоком через `*.part`, затем переименовать — тот же
     * приём, что у приёма по Wi-Fi ([WifiShare]): недокачанный файл в библиотеку не попадёт.
     * [onProgress] — (принято, всего) из потока загрузки, 0 — размер неизвестен.
     * Возвращает готовый файл или null; причина отказа — в [why].
     */
    fun download(link: Link, dir: File, auth: Auth?, name: String,
                 onProgress: ((Long, Long) -> Unit)? = null): File? {
        why = null
        if (!online()) return fail("Нет подключения")
        if (!dir.isDirectory && !dir.mkdirs()) return fail("Нет папки Books")
        val dest = unique(File(dir, safeName(name, link)))
        val part = File(dir, dest.name + ".part")
        val r = request(link.href, auth, null, null)
        try {
            if (r.code !in 200..299) return fail("Сервер ответил ${r.code}")
            val total = r.length
            if (total > 0 && dir.usableSpace in 1 until total) return fail("Не хватает места на книге")
            var done = 0L
            var next = STEP
            FileOutputStream(part).use { out ->
                val buf = ByteArray(BUF)
                while (true) {
                    val n = r.stream!!.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done >= next) {
                        next = done + STEP
                        onProgress?.invoke(done, total)
                    }
                }
            }
            if (total > 0 && done < total) throw IOException("обрыв на $done из $total")
            if (!part.renameTo(dest)) throw IOException("не переименован")
            onProgress?.invoke(done, total)
            Trace.step("opds: скачано «${dest.name}» ($done б)")
            return dest
        } catch (t: Throwable) {
            part.delete()
            Trace.step("opds download FAIL ${link.href}: ${t.javaClass.simpleName}: ${t.message}")
            return fail(whyOf(t))
        } finally {
            try {
                r.stream?.close()
            } catch (ignored: Throwable) {
            }
            r.conn?.disconnect()
        }
    }

    /** Причина последней неудачной [download]; null — получилось. */
    var why: String? = null
        private set

    private fun fail(reason: String): File? {
        why = reason
        return null
    }

    // ---------------------------------------------------------------- запрос

    private class Raw(
        val code: Int,
        val conn: HttpURLConnection?,
        val stream: InputStream?,
        val etag: String?,
        val lastModified: String?,
        val length: Long,
    )

    /**
     * GET с ручным разбором переадресаций: `HttpURLConnection` на API 27 сам не переходит
     * между http и https, а каталоги на это грешат. Тело распаковывается, если пришёл gzip.
     */
    private fun request(url: String, auth: Auth?, etag: String?, lastModified: String?): Raw {
        var target = url
        var redirects = 0
        while (true) {
            val conn = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS
                readTimeout = READ_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Accept", "application/atom+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", UA)
                if (etag != null) setRequestProperty("If-None-Match", etag)
                if (lastModified != null) setRequestProperty("If-Modified-Since", lastModified)
                if (auth != null && !auth.empty) setRequestProperty("Authorization", basic(auth))
            }
            val code = conn.responseCode
            if (code in 300..399 && redirects < REDIRECTS) {
                val to = conn.getHeaderField("Location")
                conn.disconnect()
                if (to == null) return Raw(code, null, null, null, null, 0)
                target = resolve(target, to)
                redirects++
                continue
            }
            val raw = if (code in 200..299) conn.inputStream else null
            val stream = if (raw == null) null
            else if ("gzip".equals(conn.contentEncoding, true)) GZIPInputStream(BufferedInputStream(raw, BUF))
            else BufferedInputStream(raw, BUF)
            return Raw(code, conn, stream,
                conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"),
                conn.contentLengthLong)
        }
    }

    /** Тело ответа с пределом [MAX_BYTES]: больше — не наш фид, читать незачем. */
    private fun readBody(ins: InputStream): ByteArray {
        val out = ByteArrayOutputStream(16 * 1024)
        val buf = ByteArray(BUF)
        var total = 0
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("ответ больше 4 МБ")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    // ----------------------------------------------------------------- разбор

    private fun parseCache(hit: Hit, url: String): Feed? = try {
        parse(hit.body, url)
    } catch (t: Throwable) {
        Trace.step("opds cache parse FAIL $url: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    // ------------------------------------------------------------- служебное

    private fun basic(a: Auth): String {
        val s = "${a.user}:${a.pass}"
        return "Basic " + android.util.Base64.encodeToString(
            s.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
    }

    /** Расширение книги по MIME. Неизвестный тип — null. */
    private fun formatOf(type: String): String? {
        val t = type.substringBefore(';').trim().lowercase(Locale.ROOT)
        return when {
            t.contains("fb2") -> "FB2"
            t.contains("epub") -> "EPUB"
            t.contains("mobipocket") -> "MOBI"
            t.contains("text/plain") -> "TXT"
            t.contains("rtf") -> "RTF"
            t.contains("wordprocessingml") || t == "application/msword" -> "DOC"
            t.contains("djvu") -> "DJVU"
            t.contains("pdf") -> "PDF"
            else -> null
        }
    }

    /** Имя файла из названия записи и типа ссылки: «Белая гвардия.fb2». */
    private fun safeName(name: String, link: Link): String {
        val ext = (formatOf(link.type) ?: "bin").lowercase(Locale.ROOT)
        val sb = StringBuilder(name.length)
        for (ch in name) sb.append(if (ch in BAD_NAME) ' ' else ch)
        val clean = sb.toString().trim().take(120).ifEmpty { "book" }
        return if (clean.lowercase(Locale.ROOT).endsWith(".$ext")) clean else "$clean.$ext"
    }

    /** Свободное имя в папке: «Книга (2).fb2». */
    private fun unique(f: File): File {
        if (!f.exists()) return f
        val base = f.nameWithoutExtension
        val ext = f.extension
        var i = 2
        while (true) {
            val c = File(f.parentFile, "$base ($i).$ext")
            if (!c.exists()) return c
            i++
        }
    }

    private fun hostOf(url: String): String = try {
        URL(url).host ?: url
    } catch (t: Throwable) {
        url
    }

    /** Ошибка сети человеческим языком — то, что экран покажет вместо стека. */
    private fun whyOf(t: Throwable): String {
        val m = (t.message ?: "").lowercase(Locale.ROOT)
        return when {
            t is java.net.SocketTimeoutException -> "Сервер не ответил"
            t is java.net.UnknownHostException -> "Каталог не найден по адресу"
            t is java.net.ConnectException -> "Не удалось подключиться"
            m.contains("cleartext") -> "Android запретил http для этого адреса"
            m.contains("4 мб") -> "Каталог слишком большой"
            else -> "Сервер не ответил"
        }
    }

    // ------------------------------------------------------------------- кэш

    /**
     * Кэш фидов на диске: тело ответа + `ETag`/`Last-Modified` рядом. Имя файла — хэш
     * адреса, поэтому кэш не зависит от длины URL. Читается, когда сеть не ответила
     * (офлайн-показ каталога, B57).
     */
    private inner class Cache {

        private val dir: File by lazy { File(ctx.cacheDir, "opds").apply { mkdirs() } }

        private fun file(url: String, ext: String): File =
            File(dir, "f" + Integer.toHexString(url.hashCode()) + ext)

        fun read(url: String): Hit? = try {
            val body = file(url, ".xml")
            if (body.isFile && body.length() > 0 && body.length() <= MAX_BYTES) {
                val meta = file(url, ".meta")
                val lines = if (meta.isFile) meta.readLines() else emptyList()
                Hit(body.readBytes(),
                    lines.getOrNull(0)?.takeIf { it.isNotEmpty() },
                    lines.getOrNull(1)?.takeIf { it.isNotEmpty() })
            } else null
        } catch (t: Throwable) {
            Trace.step("opds cache read FAIL: ${t.javaClass.simpleName}: ${t.message}")
            null
        }

        fun write(url: String, body: ByteArray, etag: String?, lastModified: String?) = try {
            dir.mkdirs()
            file(url, ".xml").writeBytes(body)
            file(url, ".meta").writeText((etag ?: "") + "\n" + (lastModified ?: "") + "\n")
        } catch (t: Throwable) {
            Trace.step("opds cache write FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private val cache = Cache()

    private companion object {

        /**
         * Разбор OPDS. Имена берём локальные (`FEATURE_PROCESS_NAMESPACES`), поэтому префиксы
         * `atom:`/`opds:`/`dc:` не важны — часть серверов пишет их, часть нет.
         *
         * `nextText()` читает текст элемента целиком и оставляет парсер на его закрывающем
         * теге, поэтому `author` помечается флагом: у `dc:creator` и `atom:name` разные имена,
         * а автор нужен один.
         *
         * Чистая функция без `Context` — проверяется на JVM отдельным запуском
         * (`docs/BUILD.md`, «Проверка разбора OPDS»).
         */
        fun parse(bytes: ByteArray, base: String): Feed {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(bytes.inputStream(), null)

            var feedTitle = ""
            var search: String? = null
            var total = 0
            val entries = ArrayList<Entry>(24)
            val feedLinks = ArrayList<Link>(8)
            var e: Entry? = null
            var inAuthor = false

            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "entry" -> {
                            e = Entry()
                            inAuthor = false
                        }
                        "author" -> inAuthor = true
                        "link" -> {
                            val href = p.getAttributeValue(null, "href")
                            if (href != null) {
                                val l = Link(
                                    resolve(base, href),
                                    p.getAttributeValue(null, "rel") ?: REL_OTHER,
                                    p.getAttributeValue(null, "type") ?: "",
                                    p.getAttributeValue(null, "title") ?: "",
                                )
                                val target = e
                                if (target != null) target.links.add(l)
                                else {
                                    feedLinks.add(l)
                                    if (l.rel == "search" && search == null) search = l.href
                                }
                            }
                        }
                        "title" -> {
                            val t = text(p)
                            val target = e
                            if (target != null) {
                                if (target.title.isEmpty()) target.title = t
                            } else if (feedTitle.isEmpty()) feedTitle = t
                        }
                        "name" -> if (inAuthor) {
                            val t = text(p)
                            val target = e
                            if (target != null && target.author.isEmpty()) target.author = t
                        }
                        "creator" -> {
                            // dc:creator у части каталогов — единственный автор.
                            val t = text(p)
                            val target = e
                            if (target != null && target.author.isEmpty()) target.author = t
                        }
                        "content", "summary" -> {
                            val t = text(p)
                            val target = e
                            if (target != null && target.summary.isEmpty()) target.summary = t
                        }
                        "updated" -> e?.updated = text(p)
                        "id" -> {
                            val t = text(p)
                            val target = e
                            if (target != null && target.id.isEmpty()) target.id = t
                        }
                        "language" -> e?.lang = text(p)
                        "category" -> {
                            val label = p.getAttributeValue(null, "label")
                                ?: p.getAttributeValue(null, "term")
                            if (label != null && label.isNotEmpty()) e?.categories?.add(label)
                        }
                        "totalResults" -> {
                            val t = text(p)
                            total = t.toIntOrNull() ?: total
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "entry" -> {
                            val done = e
                            if (done != null && done.title.isNotEmpty()) entries.add(done)
                            e = null
                        }
                        "author" -> inAuthor = false
                    }
                }
                ev = p.next()
            }

            var next: String? = null
            var prev: String? = null
            for (l in feedLinks) {
                when (l.rel) {
                    "next" -> if (next == null) next = l.href
                    "prev", "previous" -> if (prev == null) prev = l.href
                    "search" -> if (search == null) search = l.href
                }
            }
            return Feed(base, feedTitle, entries, next, prev, search, total)
        }

        /** Текст элемента целиком; пусто → "". Вложенные элементы в OPDS почти не встречаются. */
        private fun text(p: XmlPullParser): String = try {
            p.nextText()?.replace('\u00a0', ' ')?.trim() ?: ""
        } catch (t: XmlPullParserException) {
            ""
        }

        /** Относительный `href` → абсолютный. `URI.resolve` падает на не-ASCII, тогда руками. */
        private fun resolve(base: String, href: String): String {
            if (href.startsWith("http://") || href.startsWith("https://")) return href
            try {
                return java.net.URI(base).resolve(java.net.URI(href)).toString()
            } catch (t: Throwable) {
                return joinFallback(base, href)
            }
        }

        private fun joinFallback(base: String, href: String): String {
            if (href.startsWith("/")) {
                val scheme = base.indexOf("://")
                val hostEnd = if (scheme < 0) -1 else base.indexOf('/', scheme + 3)
                return if (hostEnd < 0) base + href else base.substring(0, hostEnd) + href
            }
            val cut = base.lastIndexOf('/')
            return if (cut < 0) "$base/$href" else base.substring(0, cut + 1) + href
        }

        const val CONNECT_MS = 15000
        const val READ_MS = 20000
        const val MAX_BYTES = 4 * 1024 * 1024
        const val BUF = 16 * 1024
        const val STEP = 64 * 1024L
        const val REDIRECTS = 5
        const val UA = "DexpM8/1.0 OPDS"

        /** Символы, недопустимые в имени файла на FAT. */
        val BAD_NAME = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

        const val REL_ACQUISITION = "http://opds-spec.org/acquisition"
        const val REL_ACQUISITION_OA = "http://opds-spec.org/acquisition/open-access"
        const val REL_IMAGE = "http://opds-spec.org/image"
        const val REL_THUMB = "http://opds-spec.org/image/thumbnail"
        const val REL_SUBSECTION = "subsection"
        const val REL_OTHER = "other"
        const val TYPE_NAV = "application/atom+xml;profile=opds-catalog"
    }
}

