package ru.ogrpfsense.dexpm8.core

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Очередь загрузок каталогов (п. 10, экраны B18 и счётчик на B14). Один рабочий поток:
 * книга скачивается целиком, попадает в библиотеку через [Scanner.addFile], только потом
 * берётся следующая. Так загрузка не отнимает у читалки ни одного лишнего мегабайта —
 * на 1 ГБ ОЗУ параллельные загрузки бессмысленны.
 *
 * Состояние живёт в обычном списке под [lock]; экраны читают [items] копией и рисуют себя
 * сами. Ничего не извещает — экран сам решает, когда перерисоваться (E-Ink).
 */
class Downloads(private val ctx: Context, private val db: LibraryDb) {

    /** Одна загрузка. [total] == 0 — размер неизвестен (сервер не сказал). */
    class Item(
        val url: String,
        val title: String,
        val format: String,
        var done: Long,
        var total: Long,
        var state: Int,
        var why: String?,
    ) {
        val pct: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
    }

    private val lock = Any()
    private val list = ArrayList<Item>(8)

    /**
     * Номер изменения очереди. Растёт при любом видимом изменении (добавили, сдвинули прогресс,
     * закончили, ошиблись). Экраны сравнивают его со своим запомненным и перерисовываются,
     * только если он другой: сравнение по полям строк раньше не срабатывало (п. 10, B16).
     */
    private var gen = 0

    fun generation(): Int = synchronized(lock) { gen }
    private val working = AtomicInteger(0)
    private val scanner = Scanner(ctx.applicationContext, db)
    private val opds = Opds(ctx.applicationContext)

    /** Каталог загрузки — тот же, что обходит [Scanner]. */
    val dir: File get() = WifiShare.Books.dirOf(ctx)

    /** Копия очереди: экран рисует по ней, не держа замок. */
    fun items(): ArrayList<Item> = synchronized(lock) { ArrayList(list) }

    /** Сколько загрузок ждёт или идёт — для счётчика на B14. */
    fun active(): Int = synchronized(lock) { list.count { it.state == S_WAIT || it.state == S_RUN } }

    fun hasErrors(): Boolean = synchronized(lock) { list.any { it.state == S_FAIL } }

    /**
     * Поставить книгу в очередь. Адрес файла и его имя передаёт экран каталога — сам
     * [Downloads] не разбирает OPDS. Повтор того же адреса игнорируется.
     */
    fun add(url: String, title: String, format: String) {
        synchronized(lock) {
            if (list.any { it.url == url && (it.state == S_WAIT || it.state == S_RUN) }) return
            list.add(Item(url, title, format, 0, 0, S_WAIT, null))
            gen++
        }
        start()
    }

    /** Повторить неудачную загрузку [url]. */
    fun retry(url: String) {
        synchronized(lock) {
            val it = list.firstOrNull { it.url == url && it.state == S_FAIL } ?: return
            it.done = 0
            it.total = 0
            it.state = S_WAIT
            it.why = null
            gen++
        }
        start()
    }

    /** Убрать строку: и незавершённую (отменится), и с ошибкой. */
    fun remove(url: String) {
        synchronized(lock) {
            if (list.removeAll { it.url == url }) gen++
        }
    }

    fun clearDone() {
        synchronized(lock) {
            if (list.removeAll { it.state == S_DONE }) gen++
        }
    }

    /** Рабочий поток занят? false — очередь не идёт. */
    fun running(): Boolean = working.get() > 0

    private fun start() {
        if (working.incrementAndGet() > 1) {
            working.decrementAndGet()
            return
        }
        Thread({ work() }, "opds-downloads").start()
    }

    private fun work() {
        try {
            while (true) {
                val item = synchronized(lock) {
                    list.firstOrNull { it.state == S_WAIT }?.also { it.state = S_RUN }
                } ?: break
                run(item)
            }
        } finally {
            working.decrementAndGet()
        }
    }

    private fun run(item: Item) {
        val link = Opds.Link(item.url, "http://opds-spec.org/acquisition", mimeOf(item.format), item.title)
        val f = opds.download(link, dir, null, item.title) { done, total ->
            synchronized(lock) {
                item.done = done
                if (total > 0) item.total = total
                gen++
            }
        }
        if (f == null) {
            synchronized(lock) {
                item.state = S_FAIL
                item.why = opds.why ?: "Сервер не ответил"
                gen++
            }
            return
        }
        val ok = try {
            scanner.addFile(f)
        } catch (t: Throwable) {
            false
        }
        synchronized(lock) {
            item.done = f.length()
            item.total = f.length()
            item.state = if (ok) S_DONE else S_FAIL
            item.why = if (ok) null else "В библиотеку не попал"
            gen++
        }
    }

    private fun mimeOf(format: String): String = when (format.uppercase()) {
        "FB2" -> "application/fb2+zip"
        "EPUB" -> "application/epub+zip"
        "MOBI" -> "application/x-mobipocket-ebook"
        "TXT" -> "text/plain"
        "RTF" -> "application/rtf"
        "DJVU", "DJV" -> "image/vnd.djvu"
        else -> "application/octet-stream"
    }

    companion object {
        /** Состояния строки B18. */
        const val S_WAIT = 0
        const val S_RUN = 1
        const val S_DONE = 2
        const val S_FAIL = 3
    }
}
