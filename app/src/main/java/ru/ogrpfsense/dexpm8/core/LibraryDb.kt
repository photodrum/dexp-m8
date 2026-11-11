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

    // Версия 1 — первая; миграции появятся вместе со схемой версии 2.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
        val cv = ContentValues(11)
        cv.put("size", i.size)
        cv.put("mtime", i.mtime)
        cv.put("format", i.format)
        cv.put("title", i.title)
        cv.put("title_sort", sortKey(i.title))
        cv.put("author", i.author)
        cv.put("author_sort", i.authorSort?.let { sortKey(it) })
        cv.put("series", i.series)
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

    /** Число книг; [onlyNew] — только «новые» (`STATUS_NEW`). */
    fun count(onlyNew: Boolean = false): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM books" + if (onlyNew) " WHERE status = $STATUS_NEW" else "", null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Суммарный размер файлов всех книг, байт. */
    fun totalSize(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM books", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    /**
     * Страница списка: [offset] с нуля, [limit] строк; [sort] — `SORT_*`;
     * [onlyNew] — только «новые» (`STATUS_NEW`).
     */
    fun list(sort: Int, offset: Int, limit: Int, onlyNew: Boolean = false): ArrayList<Entry> {
        val order = when (sort) {
            SORT_AUTHOR -> "author_sort IS NULL, author_sort, title_sort, _id"
            SORT_RECENT -> "opened DESC, added DESC, _id DESC"
            SORT_ADDED -> "added DESC, _id DESC"
            else -> "title_sort, _id"
        }
        val out = ArrayList<Entry>(limit)
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM books" +
                (if (onlyNew) " WHERE status = $STATUS_NEW" else "") +
                " ORDER BY $order LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c -> while (c.moveToNext()) out.add(entry(c)) }
        return out
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
        const val VERSION = 1

        const val STATUS_NEW = 0
        const val STATUS_READING = 1
        const val STATUS_READ = 2

        const val SORT_TITLE = 0
        const val SORT_AUTHOR = 1
        const val SORT_RECENT = 2
        const val SORT_ADDED = 3

        private const val COLUMNS = "_id, path, size, mtime, format, title, author, series, " +
            "series_num, lang, cover_id, progress, status, added, opened"

        /** Ключ сортировки: нижний регистр, «ё» как «е», без пробелов по краям. */
        fun sortKey(s: String): String =
            s.trim().lowercase(Locale.ROOT).replace('ё', 'е')
    }
}
