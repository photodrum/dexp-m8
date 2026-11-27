package ru.ogrpfsense.dexpm8.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Locale
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * База библиотеки: одна таблица `books`, `SQLiteOpenHelper` без Room.
 *
 * Для сканера (3.5): [stamps] — что уже известно, [upsert] — добавить или обновить
 * метаданные (прогресс, статус, даты не трогает), [remove] — убрать пропавшие файлы,
 * [inTransaction] — обернуть весь проход одной транзакцией (иначе fsync на каждую книгу).
 * Для экранов (3.7): [list] страницами, [count], [find], [lastOpened], [setProgress],
 * [markOpened], [setPlace] — место чтения и прогресс (R1.4).
 * Все методы — на любом потоке; чтение не ждёт запись (WAL).
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
        /** Аннотация FB2 или EPUB (абзацы через `\n`, ≤ 4 КБ), версия 14/15, B12; null — нет. */
        val annotation: String? = null,
    )

    /**
     * Строка таблицы. [progress] 0..100, [status] — `STATUS_*`, [added]/[opened] — мс, 0 — не открывалась.
     * [pos] — место чтения (позиция движка, версия 8): xpointer для CREngine; null — не читали.
     * [view] — настройки страницы DjVu (версия 9, лист B31): строка `DjvuView.encode()`; null — умолчания.
     */
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
        val pos: String? = null,
        val view: String? = null,
        /** Когда книгу дочитали (мс, 0 — не дочитывали): колонка `finished`, база v13 (B32). */
        val finished: Long = 0L,
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
                "opened INTEGER NOT NULL DEFAULT 0," +
                "pos TEXT," +
                "view TEXT," +
                "finished INTEGER NOT NULL DEFAULT 0," +
                "annotation TEXT)"
        )
        db.execSQL("CREATE INDEX books_title ON books (title_sort)")
        db.execSQL("CREATE INDEX books_author ON books (author_sort, title_sort)")
        db.execSQL("CREATE INDEX books_opened ON books (opened)")
        createShelves(db)
        createCatalogs(db)
        createBookmarks(db)
        createAudio(db)
        createReading(db)
    }

    /**
     * Версия 12: аудиокниги (п. 12, B7/B33–B35).
     *
     * Аудиокнига — это не файл, а **папка** (`folder`), поэтому ключ книги — путь папки:
     * у MP3-книги главы лежат отдельными файлами, и без папки они не собрались бы в одно
     * целое. Одиночный `.m4b` — тоже «папка» (папка, где он лежит), иначе две книги в одной
     * папке слились бы в одну.
     *
     * `pos` — место в книге в миллисекундах от её начала (не от начала файла): при переходе
     * от главы к главе экран сам переключает файл, а прогресс книги остаётся одним числом.
     * `files` и `duration` — снимок разбора: сколько файлов и сколько всего звука.
     *
     * `audio_files` — файлы книги по порядку `ord`; `dur` в мс, `name` — имя файла без
     * расширения (из него делается название главы, когда встроенной разметки нет).
     * `audio_chapters` — встроенные главы (`chpl` у M4B); пусто — главы берутся из файлов.
     */
    private fun createAudio(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE audio (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "folder TEXT NOT NULL UNIQUE," +
                "title TEXT NOT NULL," +
                "title_sort TEXT NOT NULL," +
                "author TEXT," +
                "author_sort TEXT," +
                "files INTEGER NOT NULL," +
                "duration INTEGER NOT NULL," +
                "size INTEGER NOT NULL," +
                "pos INTEGER NOT NULL DEFAULT 0," +
                "added INTEGER NOT NULL," +
                "opened INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX audio_opened ON audio (opened DESC, title_sort)")
        db.execSQL(
            "CREATE TABLE audio_files (" +
                "path TEXT PRIMARY KEY," +
                "book_id INTEGER NOT NULL," +
                "ord INTEGER NOT NULL," +
                "dur INTEGER NOT NULL," +
                "size INTEGER NOT NULL," +
                "mtime INTEGER NOT NULL," +
                "name TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX audio_files_book ON audio_files (book_id, ord)")
        db.execSQL(
            "CREATE TABLE audio_chapters (" +
                "book_id INTEGER NOT NULL," +
                "ord INTEGER NOT NULL," +
                "title TEXT NOT NULL," +
                "start INTEGER NOT NULL," +
                "dur INTEGER NOT NULL," +
                "PRIMARY KEY (book_id, ord))"
        )
    }

    /**
     * Время чтения (версия 13, п. 22, B32 «Статистика»).
     *
     * `reading` — одна строка на пару «день + книга»: `day` — местная дата в виде `ГГГГММДД`
     * (число, поэтому `ORDER BY` и сравнение диапазонов работают как строки и так же дают
     * календарный порядок), `sec` — сколько секунд читали, `pages` — сколько страниц
     * перелистнули. Ключ пары — `UNIQUE (day, book_id)`: писатель складывает ([addReading]).
     *
     * `book_id` намеренно **без внешнего ключа**: книгу из базы сканер может убрать (файл
     * удалили), а статистика за прошлые дни — история, её не стираем. Список «Дольше всего»
     * берёт только книги, которые ещё есть в библиотеке.
     */
    private fun createReading(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE reading (" +
                "day INTEGER NOT NULL," +
                "book_id INTEGER NOT NULL," +
                "sec INTEGER NOT NULL DEFAULT 0," +
                "pages INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE (day, book_id))"
        )
        db.execSQL("CREATE INDEX reading_day ON reading (day)")
        db.execSQL("CREATE INDEX reading_book ON reading (book_id)")
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

    /**
     * Версия 10: закладки (R4.1, B27). Позиция — движка (`pos`, xpointer у crengine), а не
     * номер страницы: после смены кегля закладка остаётся на своём тексте (требование R4).
     *
     * Версия 11 (R4.3, B50–B52) — та же таблица хранит три вида записей:
     *  - `kind` 0 — закладка страницы (R4.1): `pos` — позиция строки;
     *  - `kind` 1 — цитата: `pos` — начало выделения, `pos_end` — конец, `text` — сам текст;
     *  - `kind` 2 — заметка: как цитата плюс `note` — что человек написал (B51).
     * У цитаты и заметки `text` — выделенный текст (он же цитата в списке B27), у заметки
     * в `text` остаётся выделение: B52 рисует именно его. Старые строки получают 0 — закладку.
     *
     * [text] — первые слова строки, чтобы список читался без открытия книги; [chapter] —
     * название главы из оглавления на момент постановки. Оба поля — снимок на момент
     * постановки: если книга изменится, закладка всё равно останется узнаваемой.
     *
     * UNIQUE (book_id, pos) — одна закладка на позицию: повторное нажатие «Закладка» снимает
     * её, а не плодит дубли.
     */
    private fun createBookmarks(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE bookmarks (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "book_id INTEGER NOT NULL," +
                "pos TEXT NOT NULL," +
                "page INTEGER NOT NULL DEFAULT 0," +
                "chapter TEXT," +
                "text TEXT," +
                "created INTEGER NOT NULL," +
                "kind INTEGER NOT NULL DEFAULT 0," +
                "pos_end TEXT," +
                "note TEXT," +
                "UNIQUE (book_id, pos, kind))"
        )
        db.execSQL("CREATE INDEX bookmarks_book ON bookmarks (book_id, created)")
    }

    // Версия 2: автор и название из имени файла — сбрасываем mtime, и сканер переразберёт все файлы
    // (статус, прогресс и «добавлена» при этом не меняются: upsert обновляет только метаданные).
    // Версия 3: `series_sort` — ключ серии для поиска (B21); заполняем из `series`.
    // Версия 4: полки — таблицы `shelves` и `shelf_books` (B23, B24).
    // Версия 5: `pdf` и `cbz` читалка не открывает (решение 2026-11-11) — строки убираем сами:
    // файлы на диске остаются, сканер их больше не находит, значит удалить их из базы ему нечем.
    /**
     * `ALTER TABLE … ADD COLUMN`, который не падает, если колонка уже есть.
     *
     * Зачем (2026-11-26, релиз 1.4.1): `adb install -d` старого APK поверх новой базы оставляет
     * колонки, а [onDowngrade] ничего не делает — но `SQLiteOpenHelper` после него всё равно
     * записывает в `user_version` старое число. Новая сборка тогда заново шла по миграциям и на
     * `ADD COLUMN annotation` падала с «duplicate column name»; из-за этого не открывалась
     * база, и ни один экран с данными не работал. Колонка уже на месте — значит, делать нечего.
     */
    private fun addColumn(db: SQLiteDatabase, table: String, column: String, ddl: String) {
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) == column) return
        }
        db.execSQL("ALTER TABLE $table ADD COLUMN $column $ddl")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("UPDATE books SET mtime = 0")
        if (oldVersion < 3) {
            addColumn(db, "books", "series_sort", "TEXT")
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
        if (oldVersion < 7) nSealed = sealCatalogPasswords(db)
        // Версия 8: место чтения (R1.4). Пустая колонка = «начинать с начала»;
        // прогресс у уже читанных книг остаётся, экран откроет первую страницу.
        if (oldVersion < 8) addColumn(db, "books", "pos", "TEXT")
        // Версия 9: настройки страницы DjVu — лист B31 «Масштаб и поля» (R2.5). Строка
        // формата `DjvuView.encode()`; пусто = умолчания (по странице, авто-обрезка, без контраста).
        if (oldVersion < 9) addColumn(db, "books", "view", "TEXT")
        // Версия 10: закладки (R4.1, B27) — своя таблица, старые строки не трогаем.
        if (oldVersion < 10) createBookmarks(db)
        // Версия 11: цитаты и заметки (R4.3, B50–B52) — те же строки, три новых поля.
        // Таблицу перестраиваем, а не дополняем: `UNIQUE (book_id, pos)` не дал бы цитате
        // встать на ту же позицию, что и закладка страницы, а ALTER в SQLite не меняет
        // ограничения таблицы. Старые закладки переезжают как есть (kind 0).
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_old")
            // Индекс переезжает вместе с таблицей и сохраняет своё имя — без этого
            // createBookmarks упадёт на «index bookmarks_book already exists».
            db.execSQL("DROP INDEX IF EXISTS bookmarks_book")
            createBookmarks(db)
            db.execSQL("INSERT INTO bookmarks (_id, book_id, pos, page, chapter, text, created, kind) " +
                "SELECT _id, book_id, pos, page, chapter, text, created, 0 FROM bookmarks_old")
            db.execSQL("DROP TABLE bookmarks_old")
        }
        // Версия 12: аудиокниги (п. 12) — три новые таблицы, книги и полки не трогаем.
        if (oldVersion < 12) createAudio(db)
        // Версия 13 (п. 22, B32 «Статистика»): время чтения — таблица `reading` по дням и книгам,
        // плюс `finished` у книги: когда её дочитали. Старые дни не восстанавливаются — до v13
        // время не писалось вовсе, поэтому таблица начинается пустой.
        if (oldVersion < 13) {
            addColumn(db, "books", "finished", "INTEGER NOT NULL DEFAULT 0")
            createReading(db)
        }
        // Версия 14 (п. 27, B12 «Аннотация»): текст аннотации FB2. Сканер пропускает файл с тем же
        // размером и mtime, поэтому у FB2 mtime сбрасывается (как в v2) — следующий проход
        // перечитает `<title-info>` и заполнит колонку; статус, прогресс и полки не трогаются.
        if (oldVersion < 14) {
            addColumn(db, "books", "annotation", "TEXT")
            db.execSQL("UPDATE books SET mtime = 0 WHERE format = 'fb2'")
        }
        // Версия 15 (п. 27): аннотация EPUB (`dc:description`, [EpubMeta]) — то же, что в v14 для FB2:
        // колонка уже есть, у EPUB сбрасывается `mtime`, чтобы следующий проход прочитал OPF.
        if (oldVersion < 15) db.execSQL("UPDATE books SET mtime = 0 WHERE format = 'epub'")
        // Версия 16 (п. 18, перевод слова): язык EPUB (`dc:language`, [EpubMeta]) — колонка `lang`
        // уже есть, у EPUB снова сбрасывается `mtime`, чтобы следующий проход прочитал OPF.
        if (oldVersion < 16) db.execSQL("UPDATE books SET mtime = 0 WHERE format = 'epub'")
    }

    /**
     * Открыть базу и, если при этом шло обновление до версии 7, убрать из файла следы
     * открытых паролей ([scrub]). Чтение схемы и есть открытие: `SQLiteOpenHelper`
     * вызывает `onUpgrade` лениво, при первом обращении к `getWritableDatabase`, поэтому
     * уборка не может произойти раньше — только здесь.
     */
    fun open() {
        val db = writableDatabase
        if (nSealed > 0) {
            nSealed = 0
            // Один раз за жизнь базы: после обновления схемы.
            Trace.step("db v7: открыта, версия ${db.version}")
            scrub()
        }
    }

    /** Сколько паролей перешифровано при обновлении до v7: по нему [scrub] решает, нужен ли VACUUM. */
    private var nSealed = 0

    /**
     * Версия 7 (п. 10.12): пароли каталогов, лежавшие открытым текстом, — в шифртекст
     * ([Pass]). Работает внутри транзакции `onUpgrade`, поэтому ни `VACUUM`, ни `checkpoint`
     * здесь нельзя (`cannot VACUUM from within a transaction`) — уборку делает [scrub]
     * после открытия базы.
     *
     * Ошибки глушим: не собрался ключ — база должна открыться, пароль человек введёт заново.
     */
    private fun sealCatalogPasswords(db: SQLiteDatabase): Int {
        val rows = ArrayList<Pair<Long, String>>(4)
        try {
            db.rawQuery("SELECT _id, pass FROM catalogs WHERE pass <> ''", null).use { c ->
                while (c.moveToNext()) rows.add(c.getLong(0) to c.getString(1))
            }
            return Pass.migrate({ rows }) { id, sealed ->
                val cv = ContentValues(1)
                cv.put("pass", sealed)
                db.update("catalogs", cv, "_id = ?", arrayOf(id.toString()))
            }
        } catch (t: Throwable) {
            Trace.step("db v7: перешифрование FAIL: ${t.javaClass.simpleName}: ${t.message}")
            return 0
        }
    }

    /**
     * Убрать следы старых значений из файла базы после перешифрования паролей (п. 10.12).
     *
     * Зачем: `UPDATE` не стирает прежнее значение — оно остаётся в освободившемся месте
     * страницы, и открытый пароль по-прежнему находится `grep`'ом по `library.db`.
     * `VACUUM` пересобирает файл заново, старые страницы не переносятся. Затем `checkpoint`
     * переносит содержимое `-wal` в основной файл и усекает журнал: в нём тоже лежат копии
     * старых страниц.
     *
     * Вызывается один раз — сразу после обновления схемы, вне транзакции. Сбой не мешает
     * работе (пароль всё равно уже зашифрован), поэтому только в трассу.
     */
    private fun scrub() {
        try {
            writableDatabase.execSQL("VACUUM")
        } catch (t: Throwable) {
            Trace.step("db v7: VACUUM FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        try {
            writableDatabase.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } catch (t: Throwable) {
            Trace.step("db v7: checkpoint FAIL: ${t.javaClass.simpleName}: ${t.message}")
        }
        Trace.step("db v7: уборка после перешифрования готова")
    }

    /**
     * `adb install -d` ставит старый APK поверх нового: схему не трогаем, а не падаем.
     * Колонки при этом остаются, а `user_version` `SQLiteOpenHelper` всё равно понижает, поэтому
     * миграции обязаны переживать повторный проход (см. [addColumn]).
     */
    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    // ------------------------------------------------------- сетевые каталоги

    /** Каталог B14: адрес, название, вход и когда открывали. [pass] — уже расшифрованный. */
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

        /** Вход каталога; `null` — каталог открытый (пустые логин и пароль не шлём). */
        fun auth(): Opds.Auth? =
            if (user.isEmpty() && pass.isEmpty()) null else Opds.Auth(user, pass)
    }

    /** Каталоги по «последнему открытому» вперёд, затем по имени (B14). */
    fun catalogs(): ArrayList<Catalog> {
        val out = ArrayList<Catalog>(4)
        readableDatabase.rawQuery(
            "SELECT _id, url, title, user, pass, added, seen FROM catalogs " +
                "ORDER BY seen DESC, title COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out.add(row(c))
        }
        return out
    }

    fun catalog(id: Long): Catalog? =
        readableDatabase.rawQuery(
            "SELECT _id, url, title, user, pass, added, seen FROM catalogs WHERE _id = ?",
            arrayOf(id.toString())).use { c -> if (c.moveToNext()) row(c) else null }

    /** Строка `catalogs` → [Catalog]. Пароль в базе — шифртекст ([Pass]), наружу отдаём открытый. */
    private fun row(c: Cursor) = Catalog(
        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
        Pass.open(c.getString(4)), c.getLong(5), c.getLong(6))

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
        cv.put("pass", Pass.seal(pass))
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
            arrayOf(user, Pass.seal(pass), id.toString()))
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
        val cv = ContentValues(13)
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
        cv.put("annotation", i.annotation)
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

    /**
     * Аннотация книги для B12 (версия 14); null — нет. Не в [Entry] и не в [COLUMNS]: до 4 КБ
     * на книгу в списках библиотеки на 1 ГБ ОЗУ не нужны — читается одним запросом при показе B12.
     */
    fun annotation(id: Long): String? =
        readableDatabase.rawQuery("SELECT annotation FROM books WHERE _id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
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

    /**
     * Место чтения (R1.4): позиция движка [pos] и прогресс [pct] 0..100 одним `UPDATE`.
     * [pos] null — позицию взять не удалось, тогда сохраняется только прогресс (старая позиция
     * остаётся: она лучше, чем «с начала»). Статус — как у [setProgress].
     *
     * Пишется и при выходе из книги, и раз в несколько страниц: если книгу выключить
     * на середине, место не потеряется.
     */
    fun setPlace(id: Long, pos: String?, pct: Int) {
        val p = pct.coerceIn(0, 100)
        val cv = ContentValues(3)
        if (!pos.isNullOrEmpty()) cv.put("pos", pos)
        cv.put("progress", p)
        cv.put("status", if (p >= 100) STATUS_READ else if (p > 0) STATUS_READING else STATUS_NEW)
        writableDatabase.update("books", cv, "_id = ?", arrayOf(id.toString()))
    }

    /**
     * Настройки страницы DjVu (R2.5, база v9): строка `DjvuView.encode()` — масштаб, обрезка,
     * сдвиг и контраст книги. Пишется с UI-потока по [id]: в отличие от места чтения движок
     * для этого не нужен. Пустая строка — вернуть умолчания (пишется как NULL).
     */
    fun setView(id: Long, view: String) {
        val cv = ContentValues(1)
        if (view.isEmpty()) cv.putNull("view") else cv.put("view", view)
        writableDatabase.update("books", cv, "_id = ?", arrayOf(id.toString()))
    }

    // ------------------------------------------- статистика чтения (п. 22, B32)

    /**
     * Добавить время чтения (база v13): [day] — местная дата `ГГГГММДД` ([Stat.day]), [sec] —
     * секунды, [pages] — перелистнутые страницы. Строка пары «день + книга» одна, поэтому
     * значения складываются, а не перезаписываются: за один день книгу открывают много раз.
     *
     * Пишется с фонового потока (у читалки это `Engine.worker`), поэтому обе операции —
     * вставка и прибавка — идут одной транзакцией.
     */
    fun addReading(bookId: Long, day: Int, sec: Long, pages: Int) {
        if (bookId <= 0) return
        if (sec <= 0 && pages <= 0) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val cv = ContentValues(4)
            cv.put("day", day)
            cv.put("book_id", bookId)
            cv.put("sec", sec)
            cv.put("pages", pages)
            db.insertWithOnConflict("reading", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL(
                "UPDATE reading SET sec = sec + ?, pages = pages + ? WHERE day = ? AND book_id = ?",
                arrayOf(sec, pages, day, bookId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Сводка за дни периода для B32. [sec]/[pages] — по дням (`day` → значение), дни без
     * чтения в них не попадают: график рисует их нулями сам. [finished] — сколько книг
     * дочитано в тех же днях, [bestDay]/[bestSec] — лучший день, [totalSec]/[totalPages] — итог.
     */
    class Stats(
        val sec: HashMap<Int, Long> = HashMap(),
        val pages: HashMap<Int, Long> = HashMap(),
        var finished: Int = 0,
        var bestDay: Int = 0,
        var bestSec: Long = 0L,
        var totalSec: Long = 0L,
        var totalPages: Long = 0L,
    )

    /** Секунды и страницы по дням `from`…`to` (включительно, местные даты `ГГГГММДД`). */
    fun stats(from: Int, to: Int): Stats {
        val s = Stats()
        readableDatabase.rawQuery(
            "SELECT day, sec, pages FROM reading WHERE day >= ? AND day <= ?",
            arrayOf(from.toString(), to.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val day = c.getInt(0)
                val sec = c.getLong(1)
                val pg = c.getLong(2)
                s.sec[day] = (s.sec[day] ?: 0L) + sec
                s.pages[day] = (s.pages[day] ?: 0L) + pg
                s.totalSec += sec
                s.totalPages += pg
                if (sec > s.bestSec) {
                    s.bestSec = sec
                    s.bestDay = day
                }
            }
        }
        s.finished = finishedBetween(from, to)
        return s
    }

    /**
     * Сколько книг дочитано с дня [from] (0 — без нижней границы) по день [to] включительно.
     * Дни — местные `ГГГГММДД`, а `books.finished` — миллисекунды, поэтому границы переводятся
     * в полночь мс по местному времени ([dayStart], [nextDay]).
     *
     * `finished > 0` обязателен: ноль — это «книгу не дочитывали» (колонка `NOT NULL DEFAULT 0`),
     * а не полночь 1970-го. Без него «Всё время» (`lo = 0`) считало весь фонд: 534 книги при
     * нулевом чтении (замечание 2026-11-21).
     */
    fun finishedBetween(from: Int, to: Int): Int {
        val lo = if (from <= 0) 0L else dayStart(from)
        val hi = dayStart(nextDay(to))
        return try {
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM books WHERE finished > 0 AND finished >= ? AND finished < ?",
                arrayOf(lo.toString(), hi.toString()),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        } catch (t: Throwable) {
            0
        }
    }

    /**
     * Книга в списке «Дольше всего» B32: [sec] — сколько её читали за период, [progress] —
     * прогресс из `books`, чтобы нарисовать дорожку и проценты, как в макете.
     */
    class TimeBook(
        val id: Long,
        val title: String,
        val author: String?,
        val coverId: String?,
        val progress: Int,
        val sec: Long,
    )

    /**
     * Книги периода по убыванию времени чтения, не больше [limit]. Только те, что ещё есть
     * в библиотеке: название и обложка берутся из `books`, а время — из `reading`.
     */
    fun topBooks(from: Int, to: Int, limit: Int): ArrayList<TimeBook> {
        val out = ArrayList<TimeBook>(limit)
        readableDatabase.rawQuery(
            "SELECT r.book_id, SUM(r.sec), b.title, b.author, b.cover_id, b.progress " +
                "FROM reading r JOIN books b ON b._id = r.book_id " +
                "WHERE r.day >= ? AND r.day <= ? GROUP BY r.book_id " +
                "ORDER BY SUM(r.sec) DESC LIMIT ?",
            arrayOf(from.toString(), to.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    TimeBook(
                        id = c.getLong(0),
                        sec = c.getLong(1),
                        title = c.getString(2),
                        author = if (c.isNull(3)) null else c.getString(3),
                        coverId = if (c.isNull(4)) null else c.getString(4),
                        progress = c.getInt(5),
                    )
                )
            }
        }
        return out
    }

    /**
     * Запомнить, что книгу дочитали (прогресс дошёл до 100). Метка ставится один раз: хранится
     * первая дата дочитывания — перечитывание книги не делает её «новой» в статистике.
     */
    fun markFinished(id: Long) {
        writableDatabase.execSQL(
            "UPDATE books SET finished = ? WHERE _id = ? AND finished = 0",
            arrayOf(System.currentTimeMillis(), id),
        )
    }

    /** Первый день, за который есть чтение (`ГГГГММДД`); `null` — статистики ещё нет. */
    fun readingStart(): Int? =
        readableDatabase.rawQuery("SELECT MIN(day) FROM reading", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else null
        }

    /**
     * Сколько дней подряд читали, считая от [today] назад: «14 дн. подряд без пропусков» на B32.
     * Сегодня ещё не читали — серия не рвётся, она считается от вчера: день ведь не кончился.
     * Пропуск в середине — конец серии.
     */
    fun streak(today: Int): Int {
        val days = HashSet<Int>(64)
        readableDatabase.rawQuery(
            "SELECT DISTINCT day FROM reading WHERE sec > 0 ORDER BY day DESC LIMIT 400", null,
        ).use { c -> while (c.moveToNext()) days.add(c.getInt(0)) }
        if (days.isEmpty()) return 0
        var d = if (days.contains(today)) today else dayMinus(today, 1)
        var n = 0
        while (days.contains(d)) {
            n++
            d = dayMinus(d, 1)
        }
        return n
    }

    // ------------------------------------------- закладки, цитаты и заметки (R4.1, R4.3)

    /**
     * Запись книги: закладка страницы (B27), цитата или заметка (B50–B52) — позиция движка и то,
     * что видно в списке B27 без открытия книги.
     *
     * [pos] у закладки — позиция строки, у цитаты и заметки — начало выделения; [posEnd] — его
     * конец (только цитата и заметка); [note] — текст заметки (только заметка).
     */
    class Mark(
        val id: Long,
        val pos: String,
        val page: Int,
        val chapter: String?,
        val text: String?,
        val created: Long,
        val kind: Int = MARK_BOOKMARK,
        val posEnd: String? = null,
        val note: String? = null,
    )

    /**
     * Поставить закладку на позицию [pos] (R4.1). Повторная постановка на ту же позицию
     * ничего не меняет (`UNIQUE` с `IGNORE`). Кнопка «Закладка» в меню B9 — переключатель
     * (решение 2026-11-21): на странице с закладкой она зовёт [removeMark], а не `addMark`.
     *
     * [page] — номер страницы на момент постановки: по нему список B27 показывает «Стр. N»,
     * если книгу нельзя открыть прямо сейчас. Сама навигация идёт по [pos], поэтому после
     * смены кегля номер в списке может отличаться от фактического — это ожидаемо.
     */
    fun addMark(bookId: Long, pos: String, page: Int, chapter: String?, text: String?): Long =
        add(bookId, MARK_BOOKMARK, pos, null, page, chapter, text, null)

    /**
     * Записать цитату (B50 «Цитата»): [start]/[end] — начало и конец выделения (xpointer),
     * [text] — выделенный текст. Та же строка потом рисуется в B52 подчёркиванием G3 и в B27
     * цитатой под закладкой.
     *
     * Возвращает `_id` строки; `< 0` — такая цитата у книги уже есть (`UNIQUE`).
     */
    fun addQuote(bookId: Long, start: String, end: String, page: Int, chapter: String?,
                 text: String?): Long =
        add(bookId, MARK_QUOTE, start, end, page, chapter, text, null)

    /**
     * Записать заметку (B51): выделение [start]…[end] и то, что человек написал, — [note].
     * Выделенный текст остаётся в `text`: B52 заливает его G13, а номер заметки рисует на полях.
     */
    fun addNote(bookId: Long, start: String, end: String, page: Int, chapter: String?,
                text: String?, note: String): Long =
        add(bookId, MARK_NOTE, start, end, page, chapter, text, note)

    /**
     * Общая запись строки `bookmarks`. Вид [kind] входит в `UNIQUE` вместе с позицией:
     * цитата может начинаться там же, где стоит закладка страницы, — это разные записи.
     */
    private fun add(bookId: Long, kind: Int, pos: String, posEnd: String?, page: Int,
                    chapter: String?, text: String?, note: String?): Long {
        val cv = ContentValues(9)
        cv.put("book_id", bookId)
        cv.put("kind", kind)
        cv.put("pos", pos)
        cv.put("pos_end", posEnd)
        cv.put("page", page)
        cv.put("chapter", chapter)
        cv.put("text", text)
        cv.put("note", note)
        cv.put("created", System.currentTimeMillis())
        return writableDatabase.insertWithOnConflict("bookmarks", null, cv,
            SQLiteDatabase.CONFLICT_IGNORE)
    }

    /**
     * Снять запись вида [kind] (по умолчанию закладку) с позиции [pos]. true — строка была и
     * удалена. Вид обязателен в условии: цитата или заметка может начинаться там же, где стоит
     * закладка страницы (`UNIQUE` включает вид), и удаление одной не должно трогать другую.
     */
    fun removeMark(bookId: Long, pos: String, kind: Int = MARK_BOOKMARK): Boolean =
        writableDatabase.delete("bookmarks", "book_id = ? AND pos = ? AND kind = ?",
            arrayOf(bookId.toString(), pos, kind.toString())) > 0

    /**
     * Есть ли закладка книги на позиции [pos]: по этому меню B9 рисует кнопку нажатой.
     * Цитаты и заметки не считаются — у них свой вид ([MARK_QUOTE], [MARK_NOTE]).
     */
    fun hasMark(bookId: Long, pos: String): Boolean =
        writableDatabase.rawQuery(
            "SELECT 1 FROM bookmarks WHERE book_id = ? AND pos = ? AND kind = 0 LIMIT 1",
            arrayOf(bookId.toString(), pos)).use { it.moveToFirst() }

    /**
     * Закладки книги, свежие сверху (список B27). Порядок — по `created`: человек ищет
     * последнюю закладку, а не первую по тексту.
     *
     * Только вид [MARK_BOOKMARK]: цитаты и заметки живут в той же таблице, но у них своя
     * вкладка «Заметки» ([notes]). Раньше отдавались все строки без фильтра, и сохранённая
     * заметка появлялась в списке закладок — замечание пользователя 2026-11-17.
     */
    fun marks(bookId: Long): List<Mark> {
        val out = ArrayList<Mark>()
        writableDatabase.rawQuery(
            "SELECT _id, pos, page, chapter, text, created, kind, pos_end, note FROM bookmarks " +
                "WHERE book_id = ? AND kind = 0 ORDER BY created DESC",
            arrayOf(bookId.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(Mark(c.getLong(0), c.getString(1), c.getInt(2),
                    c.getString(3), c.getString(4), c.getLong(5), c.getInt(6),
                    if (c.isNull(7)) null else c.getString(7),
                    if (c.isNull(8)) null else c.getString(8)))
            }
        }
        return out
    }

    /** Сколько закладок у книги — для подписи вкладки «Закладки · N» (B10/B27). */
    fun markCount(bookId: Long): Int =
        writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM bookmarks WHERE book_id = ? AND kind = 0",
            arrayOf(bookId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /**
     * Заметки и цитаты книги, свежие сверху — вкладка «Заметки» экрана B28 и метки B52.
     * [kind] — [MARK_QUOTE] или [MARK_NOTE]; −1 — обе.
     *
     * Второй ключ — `_id`: две записи могут лечь в одну миллисекунду (`created` —
     * `currentTimeMillis`), и без него порядок между ними был бы случайным. Номера
     * заметок на полях B52 и в списке B28 считаются по этому же порядку ([noteNumbers]),
     * так что метки времени врозь их развести уже не могут.
     */
    fun notes(bookId: Long, kind: Int = -1): List<Mark> {
        val out = ArrayList<Mark>()
        val where = if (kind < 0) "book_id = ? AND kind <> 0" else "book_id = ? AND kind = $kind"
        writableDatabase.rawQuery(
            "SELECT _id, pos, page, chapter, text, created, kind, pos_end, note FROM bookmarks " +
                "WHERE $where ORDER BY created DESC, _id DESC", arrayOf(bookId.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(Mark(c.getLong(0), c.getString(1), c.getInt(2),
                    c.getString(3), c.getString(4), c.getLong(5), c.getInt(6),
                    if (c.isNull(7)) null else c.getString(7),
                    if (c.isNull(8)) null else c.getString(8)))
            }
        }
        return out
    }

    /** Сколько заметок и цитат у книги (вкладка «Заметки» B28 и подпись вкладки B27). */
    fun noteCount(bookId: Long): Int =
        writableDatabase.rawQuery(
            "SELECT COUNT(*) FROM bookmarks WHERE book_id = ? AND kind <> 0",
            arrayOf(bookId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun entry(c: Cursor) = Entry(
        id = c.getLong(0), path = c.getString(1), size = c.getLong(2), mtime = c.getLong(3),
        format = c.getString(4), title = c.getString(5),
        author = if (c.isNull(6)) null else c.getString(6),
        series = if (c.isNull(7)) null else c.getString(7),
        seriesNum = c.getInt(8),
        lang = if (c.isNull(9)) null else c.getString(9),
        coverId = if (c.isNull(10)) null else c.getString(10),
        progress = c.getInt(11), status = c.getInt(12), added = c.getLong(13), opened = c.getLong(14),
        pos = if (c.isNull(15)) null else c.getString(15),
        view = if (c.isNull(16)) null else c.getString(16),
        finished = c.getLong(17),
    )

    // --------------------------------------------------------- аудиокниги (п. 12)

    /**
     * Аудиокнига для списков B33 и плеера B7.
     *
     * [pos] — место в книге в мс от её начала, 0 — не слушали. [opened] — когда последний раз
     * играла (мс, 0 — никогда). [percent] считается от [duration] и не хранится: сумма глав
     * может измениться, если файлы заменили.
     */
    class Audio(
        val id: Long,
        val folder: String,
        val title: String,
        val author: String?,
        val files: Int,
        val duration: Long,
        val size: Long,
        val pos: Long,
        val added: Long,
        val opened: Long,
    ) {
        /** Прогресс книги 0…100. Ноль длительности — 0 (книга ещё не разобрана). */
        val percent: Int
            get() = if (duration <= 0L) 0 else (pos * 100 / duration).toInt().coerceIn(0, 100)

        /** Прослушана целиком: последние 5 % книги считаются концом (как у читалки). */
        val finished: Boolean
            get() = duration > 0L && percent >= 95
    }

    /** Файл аудиокниги: [dur] и [size] — из разбора, [name] — имя без расширения. */
    class AudioFile(
        val path: String,
        val ord: Int,
        val dur: Long,
        val size: Long,
        val mtime: Long,
        val name: String,
    )

    /** Встроенная глава M4B: [start] и [dur] в мс от начала файла. */
    class Chapter(val ord: Int, val title: String, val start: Long, val dur: Long)

    /** Итог разбора папки: то, что сканер пишет в `audio`, `audio_files` и `audio_chapters`. */
    class AudioInfo(
        val folder: String,
        val title: String,
        val author: String?,
        val files: List<AudioFile>,
        /** Главы книги со смещением от её начала, мс; null — глав нет, считать по файлам. */
        val chapters: List<Chapter>?,
    ) {
        val duration: Long get() = files.sumOf { it.dur }
        val size: Long get() = files.sumOf { it.size }
    }

    /**
     * Аудиокниги для B33: сначала та, что играла последней ([Audio.opened]), затем остальные
     * по «последнему прослушиванию», затем новые по названию. Строк немного (папки `Audio`
     * и `Books`), поэтому без пагинации в SQL — экран режет сам.
     */
    fun audioBooks(): ArrayList<Audio> {
        val out = ArrayList<Audio>(8)
        readableDatabase.rawQuery(
            "SELECT _id, folder, title, author, files, duration, size, pos, added, opened " +
                "FROM audio ORDER BY opened DESC, added DESC, title_sort, _id", null,
        ).use { c -> while (c.moveToNext()) out.add(audioRow(c)) }
        return out
    }

    /**
     * Поиск по аудиокнигам (п. 12): каждое слово запроса встречается в названии или авторе
     * (ключи [sortKey], регистр и «ё» не важны). Пустой запрос — пустой список.
     */
    fun searchAudio(q: String, limit: Int): ArrayList<Audio> {
        val out = ArrayList<Audio>(limit)
        val args = ArrayList<String>()
        val where = where(q, arrayOf("title_sort", "author_sort"), args) ?: return out
        args.add(limit.toString())
        readableDatabase.rawQuery(
            "SELECT _id, folder, title, author, files, duration, size, pos, added, opened " +
                "FROM audio WHERE $where ORDER BY title_sort, _id LIMIT ?", args.toTypedArray(),
        ).use { c -> while (c.moveToNext()) out.add(audioRow(c)) }
        return out
    }

    /** Аудиокнига по `_id`; null — строки нет (база обновилась, пока экран держал номер). */
    fun audioBook(id: Long): Audio? =
        readableDatabase.rawQuery(
            "SELECT _id, folder, title, author, files, duration, size, pos, added, opened " +
                "FROM audio WHERE _id = ?", arrayOf(id.toString()),
        ).use { if (it.moveToFirst()) audioRow(it) else null }

    /** Аудиокнига по папке: так её находит сканер, не зная `_id`. */
    fun audioBookAt(folder: String): Audio? =
        readableDatabase.rawQuery(
            "SELECT _id, folder, title, author, files, duration, size, pos, added, opened " +
                "FROM audio WHERE folder = ?", arrayOf(folder),
        ).use { if (it.moveToFirst()) audioRow(it) else null }

    /**
     * Путь → размер и mtime известных аудиофайлов (`audio_files`) — сканер сверяет и решает,
     * разбирать ли книгу заново (как [stamps] для книг).
     */
    fun audioStamps(): HashMap<String, Stamp> {
        val m = HashMap<String, Stamp>(256)
        readableDatabase.rawQuery("SELECT path, size, mtime FROM audio_files", null).use { c ->
            while (c.moveToNext()) m[c.getString(0)] = Stamp(c.getLong(1), c.getLong(2))
        }
        return m
    }

    /** Файлы книги по порядку проигрывания. */
    fun audioFiles(bookId: Long): ArrayList<AudioFile> {
        val out = ArrayList<AudioFile>(8)
        readableDatabase.rawQuery(
            "SELECT path, ord, dur, size, mtime, name FROM audio_files " +
                "WHERE book_id = ? ORDER BY ord", arrayOf(bookId.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(AudioFile(
                c.getString(0), c.getInt(1), c.getLong(2), c.getLong(3), c.getLong(4), c.getString(5)))
        }
        return out
    }

    /** Встроенные главы M4B (`chpl`); пусто — главы строятся по файлам ([AudioFile]). */
    fun audioChapters(bookId: Long): ArrayList<Chapter> {
        val out = ArrayList<Chapter>(8)
        readableDatabase.rawQuery(
            "SELECT ord, title, start, dur FROM audio_chapters WHERE book_id = ? ORDER BY ord",
            arrayOf(bookId.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(Chapter(c.getInt(0), c.getString(1), c.getLong(2), c.getLong(3)))
        }
        return out
    }

    private fun audioRow(c: Cursor) = Audio(
        id = c.getLong(0), folder = c.getString(1), title = c.getString(2),
        author = if (c.isNull(3)) null else c.getString(3),
        files = c.getInt(4), duration = c.getLong(5), size = c.getLong(6),
        pos = c.getLong(7), added = c.getLong(8), opened = c.getLong(9),
    )

    /**
     * Добавить или обновить аудиокнигу целиком: строка `audio`, её файлы и главы.
     * Место прослушивания (`pos`) и `opened` не трогаются — они принадлежат человеку,
     * а не сканеру. Новая книга получает `pos` = 0 и `added` = сейчас.
     *
     * Возвращает `_id` — сканеру он нужен, чтобы отличить новую книгу от обновлённой.
     */
    fun upsertAudio(i: AudioInfo): Long = inTransaction {
        val db = writableDatabase
        val cur = db.rawQuery("SELECT _id FROM audio WHERE folder = ?", arrayOf(i.folder)).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
        val cv = ContentValues(8)
        cv.put("folder", i.folder)
        cv.put("title", i.title)
        cv.put("title_sort", sortKey(i.title))
        cv.put("author", i.author)
        cv.put("author_sort", i.author?.let { sortKey(it) })
        cv.put("files", i.files.size)
        cv.put("duration", i.duration)
        cv.put("size", i.size)
        val id: Long
        if (cur < 0L) {
            cv.put("added", System.currentTimeMillis())
            id = db.insertOrThrow("audio", null, cv)
        } else {
            db.update("audio", cv, "_id = ?", arrayOf(cur.toString()))
            id = cur
        }
        db.delete("audio_files", "book_id = ?", arrayOf(id.toString()))
        for (f in i.files) {
            val fc = ContentValues(7)
            fc.put("path", f.path)
            fc.put("book_id", id)
            fc.put("ord", f.ord)
            fc.put("dur", f.dur)
            fc.put("size", f.size)
            fc.put("mtime", f.mtime)
            fc.put("name", f.name)
            db.insertOrThrow("audio_files", null, fc)
        }
        db.delete("audio_chapters", "book_id = ?", arrayOf(id.toString()))
        for (ch in i.chapters.orEmpty()) {
            val cc = ContentValues(5)
            cc.put("book_id", id)
            cc.put("ord", ch.ord)
            cc.put("title", ch.title)
            cc.put("start", ch.start)
            cc.put("dur", ch.dur)
            db.insertOrThrow("audio_chapters", null, cc)
        }
        id
    }

    /**
     * Убрать аудиокниги, которых больше нет на диске: [folders] — найденные сканером папки.
     * Файлы и главы уходят вместе со строкой. Возвращает, сколько книг убрано.
     */
    fun removeAudioStale(folders: Set<String>): Int = inTransaction {
        val db = writableDatabase
        val gone = ArrayList<Long>(4)
        db.rawQuery("SELECT _id, folder FROM audio", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) !in folders) gone.add(c.getLong(0))
        }
        for (id in gone) {
            val a = arrayOf(id.toString())
            db.delete("audio_files", "book_id = ?", a)
            db.delete("audio_chapters", "book_id = ?", a)
            db.delete("audio", "_id = ?", a)
        }
        gone.size
    }

    /**
     * Сохранить место прослушивания [pos] (мс от начала книги) и поставить `opened` = сейчас.
     * Вызывается и при паузе, и по таймеру раз в 10–30 с, и при уходе экрана в фон.
     */
    fun setAudioPos(id: Long, pos: Long) {
        val cv = ContentValues(2)
        cv.put("pos", pos.coerceAtLeast(0L))
        cv.put("opened", System.currentTimeMillis())
        writableDatabase.update("audio", cv, "_id = ?", arrayOf(id.toString()))
    }

    /** Поставить место в книге, не трогая `opened` (B35 «Закладка», повтор с начала). */
    fun setAudioProgress(id: Long, pos: Long) {
        val cv = ContentValues(1)
        cv.put("pos", pos.coerceAtLeast(0L))
        writableDatabase.update("audio", cv, "_id = ?", arrayOf(id.toString()))
    }

    /** Книга, которая играла последней (карточка «сейчас слушаете» B33); null — не слушали. */
    fun lastAudio(): Audio? =
        readableDatabase.rawQuery(
            "SELECT _id, folder, title, author, files, duration, size, pos, added, opened " +
                "FROM audio WHERE opened > 0 ORDER BY opened DESC LIMIT 1", null,
        ).use { if (it.moveToFirst()) audioRow(it) else null }

    /** Сколько всего аудиокниг (подзаголовок B33 «5 книг · 41 ч»). */
    fun audioCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM audio", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** Сколько всего звука в аудиокнигах, мс. */
    fun audioTotalDuration(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(duration), 0) FROM audio", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Суммарный размер аудиофайлов, байт — строка «Аудиокниги» на B42. */
    fun audioTotalSize(): Long =
        readableDatabase.rawQuery("SELECT COALESCE(SUM(size), 0) FROM audio", null).use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        }

    /** Удалить аудиокнигу из базы (файлы на диске не трогаем — как у книг, B53). */
    fun deleteAudio(id: Long) {
        inTransaction {
            val db = writableDatabase
            val a = arrayOf(id.toString())
            db.delete("audio_files", "book_id = ?", a)
            db.delete("audio_chapters", "book_id = ?", a)
            db.delete("audio", "_id = ?", a)
        }
    }

    companion object {
        const val NAME = "library.db"
        /**
         * Версия 13 (п. 22, B32 «Статистика чтения»): таблица `reading` (минуты чтения по дням
         * и книгам) и колонка `books.finished`.
         * Версия 16 (п. 18): язык EPUB — у EPUB сброшен `mtime`, колонка `lang` та же.
         * Версия 15 (п. 27): аннотация EPUB — у EPUB сброшен `mtime`, колонка та же.
         * Версия 14 (п. 27, B12): колонка `books.annotation` (аннотация FB2); у FB2 сброшен `mtime`.
         * Версия 12 (п. 12): аудиокниги — таблицы `audio`, `audio_files`, `audio_chapters`.
         * Версия 11 (R4.3): цитаты и заметки — колонки `kind`, `pos_end`, `note` в `bookmarks`.
         * Версия 9 (R2.5): настройки страницы DjVu — колонка `view` (строка `DjvuView.encode()`).
         * Версия 8 (R1.4): место чтения — колонка `pos` (позиция движка, xpointer).
         * Версия 7: пароль каталога в базе шифруется ключом из `AndroidKeyStore`
         * ([Pass], п. 10.12). Старые открытые пароли перешифровываются при обновлении.
         */
        const val VERSION = 16

        /** Вид записи в таблице `bookmarks` (версия 11, R4.3): закладка, цитата, заметка. */
        const val MARK_BOOKMARK = 0
        const val MARK_QUOTE = 1
        const val MARK_NOTE = 2

        /**
         * Номера заметок книги от начала книги: `_id` заметки → её номер «1, 2, 3…».
         * Цитаты ([MARK_QUOTE]) номера не получают.
         *
         * Порядок — от старшей записи к младшей: `created`, при равных метках — `_id`
         * (в обратном порядке к [notes]. В списке B28 свежие сверху, значит номер заметки
         * там растёт снизу вверх — номер у записи один и тот же, на полях B52 и в списке.
         *
         * Это единственное место, где номер считается: `refreshPageMarks` и `noteRow` B28
         * берут его отсюда, иначе две формулы разъедутся.
         */
        fun noteNumbers(notes: List<Mark>): HashMap<Long, Int> {
            val ordered = notes.filter { it.kind == MARK_NOTE }
                .sortedWith(compareBy<Mark>({ it.created }, { it.id }))
            val map = HashMap<Long, Int>(ordered.size * 2)
            for (i in ordered.indices) map[ordered[i].id] = i + 1
            return map
        }

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
            "series_num, lang, cover_id, progress, status, added, opened, pos, view, finished"

        /** Ключ сортировки: нижний регистр, «ё» как «е», без пробелов по краям. */
        fun sortKey(s: String): String =
            s.trim().lowercase(Locale.ROOT).replace('ё', 'е')

        // ---------------------------------------------- даты статистики (п. 22, B32)
        // День в базе — местная дата числом `ГГГГММДД`: так `ORDER BY` и сравнение диапазонов
        // дают календарный порядок, а строка вида `2026-11-20` требовала бы разбора.

        /** Местная дата [time] (мс, по умолчанию сейчас) числом `ГГГГММДД`. */
        fun day(time: Long = System.currentTimeMillis()): Int {
            val c = java.util.Calendar.getInstance()
            c.timeInMillis = time
            return c.get(java.util.Calendar.YEAR) * 10000 +
                (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        }

        /** Год, месяц (1…12) и число из дня `ГГГГММДД`. */
        fun ymd(day: Int): IntArray = intArrayOf(day / 10000, day / 100 % 100, day % 100)

        /** Полночь дня `ГГГГММДД` по местному времени, мс. */
        fun dayStart(day: Int): Long {
            val c = java.util.Calendar.getInstance()
            c.clear()
            c.set(day / 10000, day / 100 % 100 - 1, day % 100)
            return c.timeInMillis
        }

        /** Следующий день за `ГГГГММДД` — с учётом месяцев, лет и високосных лет. */
        fun nextDay(day: Int): Int {
            val c = java.util.Calendar.getInstance()
            c.clear()
            c.set(day / 10000, day / 100 % 100 - 1, day % 100)
            c.add(java.util.Calendar.DAY_OF_MONTH, 1)
            return c.get(java.util.Calendar.YEAR) * 10000 +
                (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        }

        /** День `ГГГГММДД` за [n] дней до [from] (n > 0 — назад, n < 0 — вперёд). */
        fun dayMinus(day: Int, n: Int): Int {
            val c = java.util.Calendar.getInstance()
            c.clear()
            c.set(day / 10000, day / 100 % 100 - 1, day % 100)
            c.add(java.util.Calendar.DAY_OF_MONTH, -n)
            return c.get(java.util.Calendar.YEAR) * 10000 +
                (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        }

        /** Первый день месяца дня `ГГГГММДД`; [back] — сколько месяцев назад. */
        fun monthStart(day: Int, back: Int = 0): Int {
            val c = java.util.Calendar.getInstance()
            c.clear()
            c.set(day / 10000, day / 100 % 100 - 1, 1)
            c.add(java.util.Calendar.MONTH, -back)
            return c.get(java.util.Calendar.YEAR) * 10000 + (c.get(java.util.Calendar.MONTH) + 1) * 100 + 1
        }

        /** Последний день месяца дня `ГГГГММДД` (28…31). */
        fun monthEnd(day: Int): Int {
            val c = java.util.Calendar.getInstance()
            c.clear()
            c.set(day / 10000, day / 100 % 100 - 1, 1)
            c.add(java.util.Calendar.MONTH, 1)
            c.add(java.util.Calendar.DAY_OF_MONTH, -1)
            return c.get(java.util.Calendar.YEAR) * 10000 +
                (c.get(java.util.Calendar.MONTH) + 1) * 100 + c.get(java.util.Calendar.DAY_OF_MONTH)
        }

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
