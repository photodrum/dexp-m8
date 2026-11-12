package ru.ogrpfsense.dexpm8.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale

/**
 * База библиотеки: одна таблица `books`, `SQLiteOpenHelper` без Room.
 *
 * Для сканера (3.5): [stamps] — что уже известно, [upsert] — добавить или обновить
 * метаданные (прогресс, статус, даты не трогает), [remove] — убрать пропавшие файлы,
 * [inTransaction] — обернуть весь проход одной транзакцией (иначе fsync на каждую книгу).
 * Для экранов (3.7): [list] страницами, [count], [find], [lastOpened], [setProgress],
 * [markOpened]. Все методы — на любом потоке; чтение не ждёт запись (WAL).
 *
 * Сортировка: `title_sort` и `author_sort` — ключи [sortKey] (нижний регистр, ё как е),
 * так как `ORDER BY` в SQLite сравнивает байты и кириллицу с заглавными не упорядочит.
 * `author_sort` — «Фамилия Имя» первого автора, строит сканер. Пустые поля — NULL.
 */
class LibraryDb(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    /** Что сканер узнал о файле. [authorSort] — «Фамилия Имя» первого автора или null. */
    class Info(
        val path: String,
        val size: Long,
        val mtime: Long,
        val format: String,
        val title: String,
        val author: String?,
        val authorSort: String?,
        val series: String?,
        val seriesNum: Int,
        val lang: String?,
        val coverId: String?,
    )

    /** Строка таблицы. [progress] 0..100, [status] — `STATUS_*`, [added]/[opened] — мс, 0 — не открывалась. */
    class Entry(
        val id: Long,
        val path: String,
        val size: Long,
        val mtime: Long,
        val format: String,
        val title: String,
        val author: String?,
        val series: String?,
        val seriesNum: Int,
        val lang: String?,
        val coverId: String?,
        val progress: Int,
        val status: Int,
        val added: Long,
        val opened: Long,
    )

    class Stamp(val size: Long, val mtime: Long)

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE books (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "path TEXT NOT NULL UNIQUE," +
                "size INTEGER NOT NULL," +
                "mtime INTEGER NOT NULL," +
                "format TEXT NOT NULL," +
                "title TEXT NOT NULL," +
                "title_sort TEXT NOT NULL," +
                "author TEXT," +
                "author_sort TEXT," +
                "series TEXT," +
                "series_sort TEXT," +
                "series_num INTEGER NOT NULL DEFAULT 0," +
                "lang TEXT," +
                "cover_id TEXT," +
                "progress INTEGER NOT NULL DEFAULT 0," +
                "status INTEGER NOT NULL DEFAULT 0," +
                "added INTEGER NOT NULL," +
                "opened INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX books_title ON books (title_sort)")
        db.execSQL("CREATE INDEX books_author ON books (author_sort, title_sort)")
        db.execSQL("CREATE INDEX books_opened ON books (opened)")
    }

    // Версия 2: автор и название из имени файла — сбрасываем mtime, и сканер переразберёт все файлы
    // (статус, прогресс и «добавлена» при этом не меняются: upsert обновляет только метаданные).
    // Версия 3: `series_sort` — ключ серии для поиска (B21); заполняем из `series`.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("UPDATE books SET mtime = 0")
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE books ADD COLUMN series_sort TEXT")
            db.rawQuery("SELECT _id, series FROM books WHERE series IS NOT NULL", null).use { c ->
                while (c.moveToNext()) {
                    val cv = ContentValues(1)
                    cv.put("series_sort", sortKey(c.getString(1)))
                    db.update("books", cv, "_id = ?", arrayOf(c.getLong(0).toString()))
                }
            }
        }
    }

    /** `adb install -d` ставит старый APK поверх нового: схему не трогаем, а не падаем. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    /** Выполнить [block] одной транзакцией; вложенные [upsert]/[remove] присоединяются к ней. */
    fun <T> inTransaction(block: () -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    /** Путь → размер и mtime для всех известных книг: сканер сверяет файл и решает, разбирать ли его. */
    fun stamps(): HashMap<String, Stamp> {
        val m = HashMap<String, Stamp>()
        readableDatabase.rawQuery("SELECT path, size, mtime FROM books", null).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = Stamp(c.getLong(1), c.getLong(2))
        }
        return m
    }

    /** Новая книга: статус «новая», добавлена сейчас. Известная — только метаданные и stamp. */
    fun upsert(i: Info) {
        val cv = ContentValues(12)
        cv.put("size", i.size)
        cv.put("mtime", i.mtime)
        cv.put("format", i.format)
        cv.put("title", i.title)
        cv.put("title_sort", sortKey(i.title))
        cv.put("author", i.author)
        cv.put("author_sort", i.authorSort?.let { sortKey(it) })
        cv.put("series", i.series)
        cv.put("series_sort", i.series?.let { sortKey(it) })
        cv.put("series_num", i.seriesNum)
        cv.put("lang", i.lang)
        cv.put("cover_id", i.coverId)
        inTransaction {
            val db = writableDatabase
            if (db.update("books", cv, "path = ?", arrayOf(i.path)) == 0) {
                cv.put("path", i.path)
                cv.put("added", System.currentTimeMillis())
                db.insertOrThrow("books", null, cv)
            }
        }
    }

    /** Удалить из базы книги по путям (файлы пропали); возвращает, сколько строк удалено. */
    fun remove(paths: Collection<String>): Int = inTransaction {
        val db = writableDatabase
        var n = 0
        for (p in paths) n += db.delete("books", "path = ?", arrayOf(p))
        n
    }

    /**
     * Фильтр списка (B22): [formats] — маска по [FORMATS] (0 — любые), [states] — маска
     * `1 shl STATUS_*` (0 — любые), [hideRead] — без «прочитанных».
     */
    class Filter(val formats: Int = 0, val states: Int = 0, val hideRead: Boolean = false) {
        val isEmpty: Boolean get() = formats == 0 && states == 0 && !hideRead
    }

    /** `WHERE ...` (с пробелом впереди) или пустая строка. */
    private fun filterSql(f: Filter?): String {
        if (f == null || f.isEmpty) return ""
        val parts = ArrayList<String>(3)
        if (f.formats != 0) {
            val names = ArrayList<String>()
            for (i in FORMATS.indices) {
                if (f.formats and (1 shl i) != 0) for (n in FORMATS[i]) names.add("'$n'")
            }
            parts.add("format IN (" + names.joinToString(",") + ")")
        }
        if (f.states != 0) {
            val st = ArrayList<Int>(3)
            for (i in 0..2) if (f.states and (1 shl i) != 0) st.add(i)
            parts.add("status IN (" + st.joinToString(",") + ")")
        }
        if (f.hideRead) parts.add("status <> $STATUS_READ")
        return " WHERE " + parts.joinToString(" AND ")
    }

    /** Число книг под фильтром [f]. */
    fun count(f: Filter? = null): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM books" + filterSql(f), null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Суммарный размер файлов всех книг, байт. */
    fun totalSize(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM books", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /**
     * Страница списка: [offset] с нуля, [limit] строк; [sort] — `SORT_*`; [reverse] — обратный
     * порядок («Сначала старые»); [f] — фильтр.
     */
    fun list(sort: Int, reverse: Boolean, offset: Int, limit: Int, f: Filter? = null): ArrayList<Entry> {
        val order = when (sort) {
            SORT_AUTHOR ->
                if (reverse) "author_sort IS NULL, author_sort DESC, title_sort DESC, _id DESC"
                else "author_sort IS NULL, author_sort, title_sort, _id"
            SORT_RECENT -> if (reverse) "opened, added, _id" else "opened DESC, added DESC, _id DESC"
            SORT_ADDED -> if (reverse) "added, _id" else "added DESC, _id DESC"
            SORT_PROGRESS ->
                if (reverse) "progress, title_sort, _id" else "progress DESC, title_sort, _id"
            else -> if (reverse) "title_sort DESC, _id DESC" else "title_sort, _id"
        }
        val out = ArrayList<Entry>(limit)
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM books" + filterSql(f) + " ORDER BY $order LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c -> while (c.moveToNext()) out.add(entry(c)) }
        return out
    }

    /** Автор в результатах поиска: [books] — его книг в библиотеке, [started] — из них начатых. */
    class AuthorHit(val name: String, val books: Int, val started: Int)

    /** Серия в результатах поиска. */
    class SeriesHit(val name: String, val books: Int)

    /**
     * Книги по запросу [q] (B21): слова через пробел, каждое должно встретиться в названии,
     * авторе или серии (подстрока по ключам [sortKey], регистр и «ё» не важны). Порядок —
     * по названию. Пустой запрос — пустой список.
     */
    fun searchBooks(q: String, limit: Int): ArrayList<Entry> {
        val out = ArrayList<Entry>()
        val args = ArrayList<String>()
        val where = where(q, arrayOf("title_sort", "author_sort", "series_sort"), args) ?: return out
        args.add(limit.toString())
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM books WHERE $where ORDER BY title_sort, _id LIMIT ?",
            args.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(entry(c)) }
        return out
    }

    /** Авторы, чьё имя подходит под [q]; «начатые» — любой статус, кроме «новая». */
    fun searchAuthors(q: String, limit: Int): ArrayList<AuthorHit> {
        val out = ArrayList<AuthorHit>()
        val args = ArrayList<String>()
        val where = where(q, arrayOf("author_sort"), args) ?: return out
        args.add(limit.toString())
        readableDatabase.rawQuery(
            "SELECT MIN(author), COUNT(*), SUM(CASE WHEN status <> $STATUS_NEW THEN 1 ELSE 0 END) " +
                "FROM books WHERE author_sort IS NOT NULL AND $where " +
                "GROUP BY author_sort ORDER BY author_sort LIMIT ?",
            args.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(AuthorHit(c.getString(0), c.getInt(1), c.getInt(2))) }
        return out
    }

    /** Серии, чьё название подходит под [q]. */
    fun searchSeries(q: String, limit: Int): ArrayList<SeriesHit> {
        val out = ArrayList<SeriesHit>()
        val args = ArrayList<String>()
        val where = where(q, arrayOf("series_sort"), args) ?: return out
        args.add(limit.toString())
        readableDatabase.rawQuery(
            "SELECT MIN(series), COUNT(*) FROM books WHERE series_sort IS NOT NULL AND $where " +
                "GROUP BY series_sort ORDER BY series_sort LIMIT ?",
            args.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(SeriesHit(c.getString(0), c.getInt(1))) }
        return out
    }

    /** `WHERE` по словам [q]; [args] получает параметры. null — в запросе нет слов. */
    private fun where(q: String, cols: Array<String>, args: ArrayList<String>): String? {
        val words = sortKey(q).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val sb = StringBuilder()
        for ((n, w) in words.withIndex()) {
            if (n > 0) sb.append(" AND ")
            sb.append('(')
            for ((k, col) in cols.withIndex()) {
                if (k > 0) sb.append(" OR ")
                sb.append(col).append(" LIKE ? ESCAPE '\\'")
                args.add("%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
            }
            sb.append(')')
        }
        return sb.toString()
    }

    fun find(path: String): Entry? =
        readableDatabase.rawQuery("SELECT $COLUMNS FROM books WHERE path = ?", arrayOf(path)).use { c ->
            if (c.moveToFirst()) entry(c) else null
        }

    fun get(id: Long): Entry? =
        readableDatabase.rawQuery("SELECT $COLUMNS FROM books WHERE _id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) entry(c) else null
        }

    /** Последняя открытая книга (для выбранной строки B2/B11); null — ни одну не открывали. */
    fun lastOpened(): Entry? =
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM books WHERE opened > 0 ORDER BY opened DESC LIMIT 1", null,
        ).use { c -> if (c.moveToFirst()) entry(c) else null }

    /** Книга открыта сейчас: запоминаем для [lastOpened]. */
    fun markOpened(id: Long) {
        val cv = ContentValues(1)
        cv.put("opened", System.currentTimeMillis())
        writableDatabase.update("books", cv, "_id = ?", arrayOf(id.toString()))
    }

    /**
     * Прогресс чтения [pct] 0..100 (вне диапазона обрезается); статус по нему:
     * 100 — «прочитана», больше 0 — «читается», 0 — «новая».
     */
    fun setProgress(id: Long, pct: Int) {
        val p = pct.coerceIn(0, 100)
        val cv = ContentValues(2)
        cv.put("progress", p)
        cv.put("status", if (p >= 100) STATUS_READ else if (p > 0) STATUS_READING else STATUS_NEW)
        writableDatabase.update("books", cv, "_id = ?", arrayOf(id.toString()))
    }

    private fun entry(c: Cursor) = Entry(
        id = c.getLong(0), path = c.getString(1), size = c.getLong(2), mtime = c.getLong(3),
        format = c.getString(4), title = c.getString(5),
        author = if (c.isNull(6)) null else c.getString(6),
        series = if (c.isNull(7)) null else c.getString(7),
        seriesNum = c.getInt(8),
        lang = if (c.isNull(9)) null else c.getString(9),
        coverId = if (c.isNull(10)) null else c.getString(10),
        progress = c.getInt(11), status = c.getInt(12), added = c.getLong(13), opened = c.getLong(14),
    )

    companion object {
        const val NAME = "library.db"
        const val VERSION = 3

        const val STATUS_NEW = 0
        const val STATUS_READING = 1
        const val STATUS_READ = 2

        const val SORT_TITLE = 0
        const val SORT_AUTHOR = 1
        const val SORT_RECENT = 2
        const val SORT_ADDED = 3
        const val SORT_PROGRESS = 4

        /** Маски форматов фильтра B22: бит → имена в колонке `format`. */
        val FORMATS = arrayOf(
            arrayOf("fb2", "fb2.zip"), arrayOf("epub"), arrayOf("djvu", "djv"),
            arrayOf("mobi"), arrayOf("txt"),
        )
        val FORMAT_LABELS = arrayOf("FB2", "EPUB", "DjVu", "MOBI", "TXT")

        private const val COLUMNS = "_id, path, size, mtime, format, title, author, series, " +
            "series_num, lang, cover_id, progress, status, added, opened"

        /** Ключ сортировки: нижний регистр, «ё» как «е», без пробелов по краям. */
        fun sortKey(s: String): String =
            s.trim().lowercase(Locale.ROOT).replace('ё', 'е')
    }
}
