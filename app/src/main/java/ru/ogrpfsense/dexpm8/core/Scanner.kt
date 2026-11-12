package ru.ogrpfsense.dexpm8.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сканер книг: `<хранилище>/Books` (рекурсивно) + `MediaStore.Files` для книг вне `Books`.
 * Результат пишется в [LibraryDb] одной транзакцией.
 *
 * Файл с тем же размером и mtime, что в базе, не разбирается. Метаданные читает только
 * FB2 ([NativeBook.metaFb2], один буфер на весь проход); у остальных форматов и у `.fb2.zip`
 * название — имя файла без расширения, автора нет.
 *
 * Без разрешения `READ_EXTERNAL_STORAGE` или без смонтированного хранилища проход
 * пропускается и ничего не удаляет: иначе пустой обход стёр бы всю библиотеку.
 * Из базы убираются только книги, которых нет среди найденных и нет на диске.
 *
 * Запуск — [start] (фоновый поток, один проход за раз); [scan] блокирует поток
 * и вызывается только не из UI.
 */
class Scanner(private val ctx: Context, private val db: LibraryDb) {

    /** [skipped] — причина, по которой проход не выполнялся (null — выполнялся). */
    class Result(
        val skipped: String?,
        val seen: Int,
        val added: Int,
        val updated: Int,
        val removed: Int,
        val failed: Int,
        val ms: Long,
    )

    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** Конец последнего выполненного прохода (`elapsedRealtime`), 0 — проходов не было. */
    @Volatile private var lastDone = 0L

    /** mtime папки `Books` на момент последнего прохода. */
    @Volatile private var lastBooksMtime = 0L

    /**
     * Проход не чаще раза в [PAUSE_MS]; раньше — только если изменился mtime папки `Books`.
     * Вложенные папки mtime родителя не меняют, поэтому основная защита — пауза.
     * false — пауза не вышла или проход уже идёт.
     */
    fun startIfDue(onDone: ((Result) -> Unit)? = null): Boolean {
        val done = lastDone
        if (done != 0L && SystemClock.elapsedRealtime() - done < PAUSE_MS &&
            booksMtime() == lastBooksMtime
        ) return false
        return start(onDone)
    }

    private fun booksMtime(): Long =
        File(Environment.getExternalStorageDirectory(), "Books").lastModified()

    /**
     * Проход в фоновом потоке. [onDone] вызывается на этом же потоке (экран переносит
     * результат в UI сам). false — предыдущий проход ещё идёт.
     */
    fun start(onDone: ((Result) -> Unit)? = null): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val t = Thread({
            val r = try {
                scan()
            } catch (e: Throwable) {
                Log.e(TAG, "scan failed", e)
                skipped("ошибка: ${e.message ?: e.javaClass.simpleName}", 0L)
            } finally {
                running.set(false)
            }
            onDone?.invoke(r)
        }, "library-scan")
        t.priority = Thread.MIN_PRIORITY
        t.isDaemon = true
        t.start()
        return true
    }

    @Synchronized
    fun scan(): Result {
        val t0 = SystemClock.elapsedRealtime()
        if (ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return skipped("нет разрешения на чтение памяти", t0)
        }
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            return skipped("хранилище недоступно", t0)
        }

        val base = Environment.getExternalStorageDirectory()
        val found = HashSet<String>()
        walk(File(base, "Books"), 0, found)
        fromMediaStore(base.path, found)

        val known = db.stamps()
        val buf = ByteArray(NativeBook.META_HEAD)
        var added = 0
        var updated = 0
        var failed = 0
        var removed = 0

        db.inTransaction {
            for (p in found) {
                val f = File(p)
                val format = formatOf(p)
                val size = f.length()
                if (format == null || !f.isFile || size <= 0L) continue
                val mtime = f.lastModified()
                val st = known[p]
                if (st != null && st.size == size && st.mtime == mtime) continue
                // Ошибку разбора одного файла гасим здесь; ошибки базы не гасим:
                // исключение из транзакции откатывает весь проход.
                val info = try {
                    info(f, format, size, mtime, buf)
                } catch (e: Exception) {
                    Log.w(TAG, "не разобран: $p", e)
                    failed++
                    continue
                }
                db.upsert(info)
                if (st == null) added++ else updated++
            }
            val gone = ArrayList<String>()
            for (p in known.keys) if (p !in found && !File(p).exists()) gone.add(p)
            if (gone.isNotEmpty()) removed = db.remove(gone)
        }

        lastBooksMtime = booksMtime()
        lastDone = SystemClock.elapsedRealtime()
        val r = Result(null, found.size, added, updated, removed, failed, lastDone - t0)
        Log.i(TAG, "scan: найдено ${r.seen}, новых ${r.added}, обновлено ${r.updated}, " +
            "удалено ${r.removed}, ошибок ${r.failed}, ${r.ms} мс")
        return r
    }

    private fun skipped(why: String, t0: Long): Result {
        Log.i(TAG, "scan пропущен: $why")
        return Result(why, 0, 0, 0, 0, 0, if (t0 == 0L) 0L else SystemClock.elapsedRealtime() - t0)
    }

    private fun walk(dir: File, depth: Int, out: MutableSet<String>) {
        val list = dir.listFiles() ?: return
        for (f in list) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < MAX_DEPTH) walk(f, depth + 1, out)
            } else if (formatOf(n) != null) {
                out.add(f.path)
            }
        }
    }

    /** Книги вне `Books`: только однозначные форматы (без txt/rtf/doc), без скрытых и `Android/`. */
    private fun fromMediaStore(base: String, out: MutableSet<String>) {
        val patterns = ArrayList<String>()
        for (e in EXTS) if (e !in WEAK) patterns.add("%.$e")
        patterns.add("%.fb2.zip")
        val sel = patterns.joinToString(" OR ") { "_data LIKE ?" }
        try {
            ctx.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA),
                sel, patterns.toTypedArray(), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val p = c.getString(0) ?: continue
                    if (!p.startsWith("$base/")) continue
                    if (p.contains("/.") || p.startsWith("$base/Android/")) continue
                    val format = formatOf(p) ?: continue
                    if (format in WEAK) continue
                    out.add(p)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore недоступен", e)
        }
    }

    private fun info(f: File, format: String, size: Long, mtime: Long, buf: ByteArray): LibraryDb.Info {
        val m = if (format == "fb2") NativeBook.metaFb2(f, buf) else null
        val name = f.name
        val base = name.substring(0, name.length - format.length - 1).trim()
        // Нет метаданных (EPUB, PDF… до движков читалки) — «Автор. Название» из имени файла.
        val parsed = splitName(base)
        val t = m?.title?.trim()
        val title = if (!t.isNullOrEmpty()) t else parsed?.second ?: base.ifEmpty { name }
        val author = clean(m?.authors) ?: parsed?.first
        return LibraryDb.Info(
            path = f.path, size = size, mtime = mtime, format = format, title = title,
            author = author, authorSort = authorSort(m) ?: parsed?.first?.let { sortFromName(it) },
            series = clean(m?.series), seriesNum = m?.seriesNum ?: 0,
            lang = clean(m?.lang), coverId = clean(m?.coverId),
        )
    }

    /**
     * «Автор. Название» → (автор, название); null — не похоже на такое имя.
     * Инициалы не считаем концом автора: «Иэн М. Бэнкс. Культура» и «Чехов А. Доктор».
     * Без «. » — «Автор, Название» (два-три слова слева).
     */
    private fun splitName(base: String): Pair<String, String>? {
        var from = 0
        while (true) {
            val i = base.indexOf(". ", from)
            if (i < 0) break
            val left = base.substring(0, i)
            val right = base.substring(i + 2).trim()
            if (right.isEmpty()) return null
            val word = left.substringAfterLast(' ')
            if (word.length <= 2 && word[0].isUpperCase()) {
                val next = right.indexOf(". ")
                val seg = if (next < 0) null else right.substring(0, next)
                if (seg != null && seg.count { it == ' ' } <= 1 && ',' !in seg) {
                    from = i + 2
                    continue
                }
                return authorOrNull(left + ".", right)
            }
            return authorOrNull(left, right)
        }
        val c = base.indexOf(", ")
        if (c <= 0) return null
        val left = base.substring(0, c)
        val words = left.count { it == ' ' } + 1
        return if (words in 2..3) authorOrNull(left, base.substring(c + 2).trim()) else null
    }

    private fun authorOrNull(author: String, title: String): Pair<String, String>? {
        val a = author.trim()
        if (a.isEmpty() || title.isEmpty() || a.length > 120) return null
        if (a.any { it.isDigit() }) return null
        if (a == a.uppercase(Locale.ROOT)) return null
        if (a.lowercase(Locale.ROOT) in NOT_AUTHORS) return null
        return Pair(a, title)
    }

    /** «Имя Фамилия» → «Фамилия Имя» для первого автора; «Чехов А.» и одно слово — как есть. */
    private fun sortFromName(author: String): String {
        val first = author.substringBefore(',').trim()
        val w = first.split(' ')
        if (w.size < 2 || w.last().endsWith(".")) return first
        return w.last() + " " + w.dropLast(1).joinToString(" ")
    }

    /** «Фамилия Имя» первого автора; без них — ник; null — автора нет. */
    private fun authorSort(m: NativeBook.Meta?): String? {
        if (m == null) return null
        val s = listOfNotNull(m.lastName, m.firstName)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return if (s.isNotEmpty()) s else clean(m.nickname)
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    /** Формат по имени или пути: `fb2`, `epub`, … или `fb2.zip`; null — не книга. */
    private fun formatOf(name: String): String? {
        val l = name.lowercase(Locale.ROOT)
        if (l.endsWith(".fb2.zip")) return "fb2.zip"
        val dot = l.lastIndexOf('.')
        if (dot < 0) return null
        val e = l.substring(dot + 1)
        return if (e in EXTS) e else null
    }

    companion object {
        private const val TAG = "DexpM8"
        private const val MAX_DEPTH = 12

        /** Слова слева от «. », которые не автор. */
        private val NOT_AUTHORS = setOf("сборник", "антология", "разное", "сборники", "журнал")
        /** Минимальный промежуток между проходами из [startIfDue]. */
        private const val PAUSE_MS = 10L * 60L * 1000L

        /**
         * Форматы из `READER_SPEC`: CREngine, DjVuLibre. `pdf` и `cbz` читалка не открывает
         * (решение 2026-11-11); что показывать для них в библиотеке — вопрос чату дизайна.
         */
        private val EXTS = setOf(
            "fb2", "epub", "mobi", "txt", "rtf", "doc", "pdf", "cbz", "djvu", "djv",
        )

        /** Вне `Books` не берём: слишком много посторонних файлов (логи, заметки). */
        private val WEAK = setOf("txt", "rtf", "doc")
    }
}
