package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Сканер аудиокниг (п. 12, B33–B35): папки `Audio` и `Books` на памяти книги.
 *
 * **Что считается книгой.** Одна папка — одна книга. У MP3-книги главы лежат отдельными
 * файлами (`01 - Глава 1.mp3`, `02 - …`), поэтому книгу нельзя привязать к файлу. Одиночный
 * `.m4b` — отдельная книга, ключ у неё тоже «папка»: путь самого файла, иначе два `.m4b`
 * в одной папке слились бы.
 *
 * **Порядок файлов** — по имени, числа сравниваются как числа: `2` раньше `10` (иначе книга
 * играла бы вперемешку).
 *
 * **Длительность** — `MediaMetadataRetriever` на каждый файл: книга из 60 глав означает
 * 60 открытий файла. Поэтому проход идёт в фоне с `MIN_PRIORITY`, а результат пишется в базу
 * одной транзакцией ([LibraryDb.upsertAudio]). Книга, у которой ни один файл не изменился
 * (размер и mtime), не переразбирается — место прослушивания и `opened` целы.
 *
 * **Главы.** Если у M4B есть встроенная разметка (`chpl`), берём её: названия в файле
 * человеческие, а имена mp3 бывают нумерованными. Разбор свой, байтовый — без сторонних
 * библиотек ([Mp4Chapters], [Id3Chapters]).
 */
class AudioBooks(private val ctx: Context, private val db: LibraryDb) {

    /** [skipped] — почему прохода не было (null — был); счётчики — по книгам. */
    class Result(
        val skipped: String?,
        val found: Int,
        val added: Int,
        val updated: Int,
        val removed: Int,
        val failed: Int,
        val ms: Long,
    )

    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** Проход в фоне; [onDone] придёт на потоке сканера (экран переносит в UI сам). */
    fun start(onDone: ((Result) -> Unit)? = null): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val t = Thread({
            val r = try {
                scan()
            } catch (e: Throwable) {
                Log.e(TAG, "audio scan failed", e)
                Result("ошибка: ${e.message ?: e.javaClass.simpleName}", 0, 0, 0, 0, 0, 0L)
            } finally {
                running.set(false)
            }
            onDone?.invoke(r)
        }, "audio-scan")
        t.priority = Thread.MIN_PRIORITY
        t.isDaemon = true
        t.start()
        return true
    }

    /** Синхронный проход — не с UI-потока (вызывает [start]). */
    @Synchronized
    fun scan(): Result {
        val t0 = SystemClock.elapsedRealtime()
        if (!hasStorage()) return Result("нет разрешения на чтение памяти", 0, 0, 0, 0, 0, 0L)
        val roots = roots()
        if (roots.isEmpty()) return Result("нет папок Audio и Books", 0, 0, 0, 0, 0, 0L)

        val found = LinkedHashMap<String, Folder>()
        for (root in roots) collect(root, found, 0)
        // Известные файлы: путь → размер и mtime. По ним видно, изменилась ли книга.
        val known = db.audioStamps()

        var added = 0
        var updated = 0
        var failed = 0
        for ((key, folder) in found) {
            val knownBook = db.audioBookAt(key)
            if (knownBook != null && !isChanged(folder, known, knownBook)) continue
            val info = try {
                build(key, folder)
            } catch (e: Exception) {
                Log.w(TAG, "аудиокнига не разобрана: $key", e)
                null
            }
            if (info == null) {
                failed++
                continue
            }
            db.upsertAudio(info)
            if (knownBook != null) updated++ else added++
        }
        val removed = db.removeAudioStale(found.keys)
        val ms = SystemClock.elapsedRealtime() - t0
        Log.i(TAG, "audio: книг ${found.size}, новых $added, обновлено $updated, " +
            "убрано $removed, ошибок $failed, $ms мс")
        return Result(null, found.size, added, updated, removed, failed, ms)
    }

    // --------------------------------------------------------------- поиск файлов

    /** Папка-книга (или один `.m4b`): файлы в том порядке, в каком нашли. */
    private class Folder(val single: Boolean) {
        val files = ArrayList<File>(8)
    }

    private fun roots(): List<File> {
        val base = android.os.Environment.getExternalStorageDirectory()
        return listOf(File(base, "Audio"), File(base, "Books")).filter { it.isDirectory }
    }

    private fun hasStorage(): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Собрать книги под [dir]: mp3/m4a этой папки — одна книга; каждый `.m4b` — своя;
     * вложенные папки разбираются отдельно.
     */
    private fun collect(dir: File, out: MutableMap<String, Folder>, depth: Int) {
        val list = dir.listFiles() ?: return
        val tracks = ArrayList<File>(8)
        for (f in list) {
            val n = f.name
            if (n.startsWith(".")) continue
            if (f.isDirectory) {
                if (depth < MAX_DEPTH) collect(f, out, depth + 1)
                continue
            }
            when (extOf(n)) {
                "mp3", "m4a" -> tracks.add(f)
                "m4b" -> out.getOrPut(f.path) { Folder(true) }.files.add(f)
            }
        }
        if (tracks.isEmpty()) return
        out.getOrPut(dir.path) { Folder(false) }.files.addAll(tracks)
    }

    private fun extOf(name: String): String? {
        val dot = name.lastIndexOf('.')
        if (dot < 0) return null
        return name.substring(dot + 1).lowercase(Locale.ROOT)
    }

    /**
     * Изменилась ли книга: число файлов другое или у какого-то файла другой размер либо mtime
     * (по [known] — снимок `audio_files`). Не изменилась — не открываем ни одного файла.
     */
    private fun isChanged(f: Folder, known: HashMap<String, LibraryDb.Stamp>,
                          book: LibraryDb.Audio): Boolean {
        if (book.files != f.files.size) return true
        for (file in f.files) {
            val st = known[file.path]
            if (st == null || st.size != file.length() || st.mtime != file.lastModified()) return true
        }
        return false
    }

    // --------------------------------------------------------------- разбор книги

    /**
     * Разбор книги: длительности файлов, название, автор и главы. null — ни один файл не
     * читается (тогда книгу в базу не пишем: пустая строка только мешала бы списку).
     *
     * Название — из тега первого файла, иначе имя папки (у одиночного `.m4b` — имя файла):
     * папка `Мастер и Маргарита` и без тегов читается как надо.
     */
    private fun build(key: String, f: Folder): LibraryDb.AudioInfo? {
        val r = MediaMetadataRetriever()
        val ordered = f.files.sortedWith { a, b -> natural(a.name, b.name) }
        val files = ArrayList<LibraryDb.AudioFile>(ordered.size)
        var title: String? = null
        var artist: String? = null
        try {
            for ((i, file) in ordered.withIndex()) {
                val dur: Long
                try {
                    r.setDataSource(file.path)
                    dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: "")
                        .toLongOrNull() ?: 0L
                    if (title == null) {
                        title = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                    }
                    if (artist == null) {
                        artist = clean(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "файл не читается: ${file.path}", e)
                    continue
                }
                if (dur <= 0L) continue
                files.add(LibraryDb.AudioFile(
                    path = file.path, ord = i, dur = dur, size = file.length(),
                    mtime = file.lastModified(), name = file.nameWithoutExtension,
                ))
            }
        } finally {
            try {
                r.release()
            } catch (ignored: Throwable) {
            }
        }
        if (files.isEmpty()) return null
        val folderName = File(key).let { if (f.single) it.nameWithoutExtension else it.name }
        return LibraryDb.AudioInfo(
            folder = key,
            title = title ?: folderName,
            author = artist,
            files = files,
            chapters = chapters(ordered, files),
        )
    }

    /**
     * Главы книги: встроенные (`chpl` у M4B, `CHAP` у MP3) или null — тогда главы строятся
     * по файлам (одна глава на файл). Смещения встроенных глав — от начала своего файла,
     * к ним прибавляется смещение файла в книге.
     */
    private fun chapters(ordered: List<File>,
                         files: List<LibraryDb.AudioFile>): List<LibraryDb.Chapter>? {
        val offset = HashMap<String, Long>(files.size * 2)
        var acc = 0L
        for (f in files) {
            offset[f.path] = acc
            acc += f.dur
        }
        val out = ArrayList<LibraryDb.Chapter>(16)
        for (file in ordered) {
            val start = offset[file.path] ?: continue
            val list = if (extOf(file.name) == "m4b") Mp4Chapters.read(file)
            else Id3Chapters.read(file)
            if (list.isEmpty()) continue
            for (c in list) {
                // Длительность встроенной главы 0 означает «до конца файла»: её добирает
                // следующий файл/глава, а последняя — остаток книги.
                val dur = if (c.third > 0L) c.third else maxOf(0L, files.first { it.path == file.path }.dur - c.first)
                out.add(LibraryDb.Chapter(out.size, c.second, start + c.first, dur))
            }
        }
        if (out.isEmpty()) return null
        // У последней главы длительность — до конца книги: `chpl`/`CHAP` её иногда не пишут.
        val last = out[out.size - 1]
        if (last.dur <= 0L && files.isNotEmpty()) {
            out[out.size - 1] = LibraryDb.Chapter(last.ord, last.title, last.start,
                maxOf(0L, acc - last.start))
        }
        return out
    }

    // --------------------------------------------------------------- натуральный порядок

    /**
     * Сравнение имён «как человек»: `2` раньше `10`, `Глава 9` раньше `Глава 10`.
     * Регистр и «ё» не важны: `глава` и `Глава` — одно и то же.
     */
    fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ni = i
                var nj = j
                while (ni < a.length && a[ni].isDigit()) ni++
                while (nj < b.length && b[nj].isDigit()) nj++
                val na = a.substring(i, ni).trimStart('0')
                val nb = b.substring(j, nj).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ni
                j = nj
                continue
            }
            val la = if (ca == 'ё' || ca == 'Ё') 'е' else ca.lowercaseChar()
            val lb = if (cb == 'ё' || cb == 'Ё') 'е' else cb.lowercaseChar()
            if (la != lb) return la.compareTo(lb)
            i++
            j++
        }
        return (a.length - i) - (b.length - j)
    }

    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val TAG = "DexpM8"
        private const val MAX_DEPTH = 6
    }
}
