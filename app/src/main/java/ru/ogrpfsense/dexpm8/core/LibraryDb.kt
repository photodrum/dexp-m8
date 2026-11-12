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
        createShelves(db)
        createCatalogs(db)
    }

    private fun createShelves(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE shelves (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL," +
                "name_sort TEXT NOT NULL," +
                "created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE shelf_books (" +
                "shelf_id INTEGER NOT NULL," +
                "book_id INTEGER NOT NULL," +
                "put_at INTEGER NOT NULL," +
                "PRIMARY KEY (shelf_id, book_id))"
        )
        db.execSQL("CREATE INDEX shelf_books_book ON shelf_books (book_id)")
    }

    /**
     * Версия 6: сетевые каталоги (п. 10, B14). Хранится только то, что человек ввёл сам:
     * адрес, название и логин с паролем. Последний фид и его `ETag` — в кэше на диске
     * ([Opds]), в базе их нет: кэш можно стереть в любой момент без потери настроек.
     *
     * `title` пустой — название ещё не получено (каталог добавлен до первой проверки);
     * `seen` — когда последний раз каталог открывали (`System.currentTimeMillis`), 0 — никогда.
     */
    private fun createCatalogs(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE catalogs (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "url TEXT NOT NULL UNIQUE," +
                "title TEXT NOT NULL DEFAULT ''," +
                "user TEXT NOT NULL DEFAULT ''," +
                "pass TEXT NOT NULL DEFAULT ''," +
                "added INTEGER NOT NULL," +
                "seen INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX catalogs_seen ON catalogs (seen)")
    }

    // Версия 2: автор и название из имени файла — сбрасываем mtime, и сканер переразберёт все файлы
    // (статус, прогресс и «добавлена» при этом не меняются: upsert обновляет только метаданные).
    // Версия 3: `series_sort` — ключ серии для поиска (B21); заполняем из `series`.
    // Версия 4: полки — таблицы `shelves` и `shelf_books` (B23, B24).
    // Версия 5: `pdf` и `cbz` читалка не открывает (решение 2026-11-11) — строки убираем сами:
    // файлы на диске остаются, сканер их больше не находит, значит удалить их из базы ему нечем.
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
        if (oldVersion < 4) createShelves(db)
        if (oldVersion < 5) {
            db.execSQL("DELETE FROM shelf_books WHERE book_id IN (SELECT _id FROM books WHERE format IN ('pdf', 'cbz'))")
            db.execSQL("DELETE FROM books WHERE format IN ('pdf', 'cbz')")
        }
        if (oldVersion < 6) createCatalogs(db)
    }

    /** `adb install -d` ставит старый APK поверх нового: схему не трогаем, а не падаем. */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    // ------------------------------------------------------- сетевые каталоги

    /** Каталог B14: адрес, название, вход и когда открывали. */
    class Catalog(
        val id: Long,
        val url: String,
        val title: String,
        val user: String,
        val pass: String,
        val added: Long,
        val seen: Long,
    ) {
        /** Заголовок для строки B14: пустое название — адрес без схемы. */
        fun label(): String = title.ifEmpty {
            url.removePrefix("http://").removePrefix("https://").trimEnd('/')
        }

        fun auth(): Opds.Auth = Opds.Auth(user, pass)
    }

    /** Каталоги по «последнему открытому» вперёд, затем по имени (B14). */
    fun catalogs(): ArrayList<Catalog> {
        val out = ArrayList<Catalog>(4)
        readableDatabase.rawQuery(
            "SELECT _id, url, title, user, pass, added, seen FROM catalogs " +
                "ORDER BY seen DESC, title COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) {
                out.add(Catalog(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getString(4), c.getLong(5), c.getLong(6)))
            }
        }
        return out
    }

    fun catalog(id: Long): Catalog? =
        readableDatabase.rawQuery(
            "SELECT _id, url, title, user, pass, added, seen FROM catalogs WHERE _id = ?",
            arrayOf(id.toString())).use { c ->
            if (c.moveToNext()) Catalog(c.getLong(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), c.getLong(5), c.getLong(6)) else null
        }

    /** Каталог уже есть с таким адресом (адрес уникален). */
    fun catalogExists(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM catalogs WHERE url = ?", arrayOf(url)).use { it.moveToNext() }

    /**
     * Добавить каталог (B15). Адрес нормализуется: без пробелов по краям, со схемой `http://`,
     * если её забыли. Возвращает `_id` или -1, если адрес уже есть.
     */
    fun addCatalog(url: String, title: String, user: String, pass: String): Long {
        val u = normalizeUrl(url)
        if (u.isEmpty() || catalogExists(u)) return -1L
        val cv = ContentValues(6)
        cv.put("url", u)
        cv.put("title", title.trim())
        cv.put("user", user)
        cv.put("pass", pass)
        cv.put("added", System.currentTimeMillis())
        return writableDatabase.insert("catalogs", null, cv)
    }

    /** Запомнить название, полученное из фида, если человек своё не ввёл. */
    fun catalogTitle(id: Long, title: String) {
        writableDatabase.execSQL(
            "UPDATE catalogs SET title = ? WHERE _id = ? AND title = ''",
            arrayOf(title, id.toString()))
    }

    fun renameCatalog(id: Long, title: String) {
        writableDatabase.execSQL("UPDATE catalogs SET title = ? WHERE _id = ?",
            arrayOf(title.trim(), id.toString()))
    }

    fun catalogAuth(id: Long, user: String, pass: String) {
        writableDatabase.execSQL("UPDATE catalogs SET user = ?, pass = ? WHERE _id = ?",
            arrayOf(user, pass, id.toString()))
    }

    fun deleteCatalog(id: Long) {
        writableDatabase.delete("catalogs", "_id = ?", arrayOf(id.toString()))
    }

    /** Отметка «открывали сейчас» — по ней B14 сортирует список. */
    fun touchCatalog(id: Long) {
        writableDatabase.execSQL("UPDATE catalogs SET seen = ? WHERE _id = ?",
            arrayOf(System.currentTimeMillis(), id.toString()))
    }

    /** Есть ли хоть один каталог: B14 показывает пустое состояние B44, если нет. */
    fun catalogCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM catalogs", null).use { if (it.moveToNext()) it.getInt(0) else 0 }

    /**
     * Открыть каталог «наполовину» — по нажатию на строку B14 без выбора страницы:
     * ставит `act.catalogId` и отметку открытия.
     */
    fun openCatalog(id: Long): Catalog? {
        val c = catalog(id) ?: return null
        touchCatalog(id)
        return c
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
        if (n > 0) db.execSQL("DELETE FROM shelf_books WHERE book_id NOT IN (SELECT _id FROM books)")
        n
    }

    /** Удалить книгу из базы (B53): строку `books` и её места на полках. Файл удаляет вызывающий. */
    fun deleteBook(id: Long) {
        inTransaction {
     val db = writableDatabase
     db.delete("shelf_books", "book_id = ?", arrayOf(id.toString()))
     db.delete("books", "_id = ?", arrayOf(id.toString()))
 }
    }

    // ------------------------------------------------------------------ полки (B23, B24)

    /** Своя полка; [count] — книг на ней. */
    class Shelf(val id: Long, val name: String, val count: Int)

    private fun shelfRow(c: Cursor) = Shelf(c.getLong(0), c.getString(1), c.getInt(2))

    private val shelfSelect = "SELECT s._id, s.name, (SELECT COUNT(*) FROM shelf_books sb " +
        "JOIN books b ON b._id = sb.book_id WHERE sb.shelf_id = s._id) FROM shelves s"

    /** Свои полки по названию. */
    fun shelves(): ArrayList<Shelf> {
        val out = ArrayList<Shelf>()
        readableDatabase.rawQuery("$shelfSelect ORDER BY s.name_sort, s._id", null).use { c ->
            while (c.moveToNext()) out.add(shelfRow(c))
        }
        return out
    }

    fun shelf(id: Long): Shelf? =
        readableDatabase.rawQuery("$shelfSelect WHERE s._id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) shelfRow(c) else null
        }

    /** Есть ли своя полка с таким названием (без учёта регистра), кроме [exceptId]. */
    fun shelfExists(name: String, exceptId: Long = 0L): Boolean {
        if (AUTO_SHELF_NAMES.any { sortKey(it) == sortKey(name) }) return true
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM shelves WHERE name_sort = ? AND _id <> ?",
            arrayOf(sortKey(name), exceptId.toString()),
        ).use { c -> c.moveToFirst() && c.getInt(0) > 0 }
    }

    /** Новая полка; возвращает её `_id`. */
    fun createShelf(name: String): Long {
        val cv = ContentValues(3)
        cv.put("name", name.trim())
        cv.put("name_sort", sortKey(name))
        cv.put("created", System.currentTimeMillis())
        return writableDatabase.insertOrThrow("shelves", null, cv)
    }

    fun renameShelf(id: Long, name: String) {
        val cv = ContentValues(2)
        cv.put("name", name.trim())
        cv.put("name_sort", sortKey(name))
        writableDatabase.update("shelves", cv, "_id = ?", arrayOf(id.toString()))
    }

    /** Удалить полку; книги остаются в библиотеке. */
    fun deleteShelf(id: Long) {
        inTransaction {
            val db = writableDatabase
            db.delete("shelf_books", "shelf_id = ?", arrayOf(id.toString()))
            db.delete("shelves", "_id = ?", arrayOf(id.toString()))
        }
    }

    /** `_id` своих полок, на которых лежит книга [bookId]. */
    fun shelvesOfBook(bookId: Long): HashSet<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery(
            "SELECT shelf_id FROM shelf_books WHERE book_id = ?", arrayOf(bookId.toString()),
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    /** Положить книгу на полку или снять; true — теперь лежит. */
    fun toggleOnShelf(shelfId: Long, bookId: Long): Boolean = inTransaction {
        val db = writableDatabase
        val args = arrayOf(shelfId.toString(), bookId.toString())
        if (db.delete("shelf_books", "shelf_id = ? AND book_id = ?", args) > 0) {
            false
        } else {
            val cv = ContentValues(3)
            cv.put("shelf_id", shelfId)
            cv.put("book_id", bookId)
            cv.put("put_at", System.currentTimeMillis())
            db.insertOrThrow("shelf_books", null, cv)
            true
        }
    }

    /** `FROM … WHERE …` для полки: свои — по `shelf_books`, автоматические — по статусу. */
    private fun shelfFrom(id: Long): String = when (id) {
        SHELF_READING -> "books WHERE status = $STATUS_READING"
        SHELF_READ -> "books WHERE status = $STATUS_READ"
        else -> "books JOIN shelf_books sb ON sb.book_id = books._id WHERE sb.shelf_id = $id"
    }

    /** Книг на полке [id] (своей или автоматической). */
    fun shelfBookCount(id: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM " + shelfFrom(id), null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /**
     * Страница книг полки [id]; [order] — `SHELF_ORDER_*`. «Порядок полки»: у своей — как клали
     * (раньше положенные выше), у автоматических — недавно открытые выше.
     */
    fun shelfBooks(id: Long, order: Int, offset: Int, limit: Int): ArrayList<Entry> {
        val by = when (order) {
            SHELF_ORDER_AUTHOR -> "author_sort IS NULL, author_sort, title_sort, books._id"
            SHELF_ORDER_PROGRESS -> "progress DESC, title_sort, books._id"
            else -> if (id > 0) "sb.put_at, books._id" else "opened DESC, added DESC, books._id DESC"
        }
        val out = ArrayList<Entry>(limit)
        readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM " + shelfFrom(id) + " ORDER BY $by LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString()),
        ).use { c -> while (c.moveToNext()) out.add(entry(c)) }
        return out
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
        /** Версия 6: сетевые каталоги (п. 10, таблица `catalogs`). */
        const val VERSION = 6

        /** Автоматические полки: `_id` отрицательные, в таблице `shelves` их нет. */
        const val SHELF_READING = -1L
        const val SHELF_READ = -2L
        const val NAME_READING = "Читаю сейчас"
        const val NAME_READ = "Прочитанные"

        /** Названия автоматических полок: свои полки так назвать нельзя. */
        val AUTO_SHELF_NAMES = arrayOf(NAME_READING, NAME_READ)

        const val SHELF_ORDER_SHELF = 0
        const val SHELF_ORDER_AUTHOR = 1
        const val SHELF_ORDER_PROGRESS = 2

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

        /**
         * Адрес каталога в том виде, в каком он лежит в базе: без пробелов по краям,
         * со схемой (человек часто пишет «gutenberg.org/feeds»), без хвостового «/».
         * Схема `https://` сохраняется — часть каталогов живёт только по ней.
         */
        fun normalizeUrl(raw: String): String {
            val s = raw.trim()
            if (s.isEmpty()) return ""
            val withScheme = when {
                s.startsWith("http://", true) || s.startsWith("https://", true) -> s
                s.startsWith("//") -> "http:$s"
                else -> "http://$s"
            }
            // Хвостовой «/» без пути ничего не меняет, а в базе даёт два разных адреса.
            val cut = withScheme.indexOf("://") + 3
            val tail = withScheme.substring(cut)
            return if (tail.endsWith("/") && tail.isNotEmpty()) withScheme.dropLast(1) else withScheme
        }
    }
}
