package ru.ogrpfsense.dexpm8.core

import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Проигрывание аудиокниги (п. 12, B7) поверх `MediaPlayer`.
 *
 * **Почему `MediaPlayer`, а не свой путь `MediaCodec`.** Книга — это mp3/m4a/m4b с диска;
 * `MediaPlayer` играет их из коробки, умеет паузу, скорость (`PlaybackParams`, API 23 —
 * на книге 27). Свой конвейер (MediaExtractor + MediaCodec + AudioTrack) понадобился бы
 * только для вырезания тишины — это отдельная задача.
 *
 * **Место в книге** — одно число, мс от начала книги, а не от начала файла: книга из 60 mp3,
 * и переход между ними — смена `dataset` у одного и того же `MediaPlayer`. Позиция файла =
 * `позиция книги − смещение файла`.
 *
 * **Кто владеет.** Один экземпляр на всю оболочку (`EinkActivity.player`): уход с B7 на B33
 * или в библиотеку не останавливает звук. Останавливают пауза, конец книги и таймер сна.
 *
 * **Прогресс** пишется в базу раз в [TICK_MS] и при паузе — как у читалки
 * ([LibraryDb.setAudioPos]), чтобы место не потерялось при выключении книги.
 */
class AudioPlayer(private val db: LibraryDb) {

    /** Глава книги: смещение и длительность в мс от её начала. */
    class Chap(val title: String, val start: Long, val dur: Long) {
        val end: Long get() = start + dur
    }

    var book: LibraryDb.Audio? = null
        private set

    var chapters: List<Chap> = emptyList()
        private set

    var playing = false
        private set

    /** Позиция в книге, мс. */
    var pos = 0L
        private set

    /** Длительность книги, мс (сумма файлов). */
    var total = 0L
        private set

    /** Скорость в процентах (75, 100, 125, 150, 200). */
    var speed = 100
        private set

    /** Момент `elapsedRealtime`, когда ставить паузу; 0 — таймер не взведён. */
    var sleepAt = 0L
        private set

    /** Таймер «до конца главы»: пауза на границе следующей главы. */
    var sleepChapter = false
        private set

    /**
     * «Пропускать паузы» (B35): хвост файла не доигрывается до последней секунды —
     * переход к следующей главе раньше на [SKIP_TAIL_MS]. Полное вырезание тишины
     * (по громкости) требует своего конвейера MediaExtractor+MediaCodec, а не `MediaPlayer`;
     * это отдельная задача — записано в DEVLOG.
     */
    var skipPauses = false
        private set

    /** Экран перерисовывается (позиция, глава, конец книги). */
    var onTick: (() -> Unit)? = null

    /** Книга доиграла до конца. */
    var onEnd: (() -> Unit)? = null

    private var files = ArrayList<LibraryDb.AudioFile>()
    private var offsets = LongArray(0)
    private var index = -1
    private var mp: MediaPlayer? = null
    private val ui = Handler(Looper.getMainLooper())

    val hasBook: Boolean get() = book != null

    /** Длительность известна — прогресс и «осталось» можно считать. */
    val ready: Boolean get() = total > 0L

    // --------------------------------------------------------------- открытие

    /**
     * Открыть книгу и встать на сохранённое место (0 — начало), со скоростью [speedPct].
     * Играть не начинает: первый кадр B7 рисует паузу, звук включает человек.
     */
    fun open(id: Long, speedPct: Int = 100): Boolean {
        val b = db.audioBook(id) ?: return false
        book = b
        files = db.audioFiles(id)
        if (files.isEmpty()) return false
        offsets = LongArray(files.size)
        var acc = 0L
        for (i in files.indices) {
            offsets[i] = acc
            acc += files[i].dur
        }
        total = acc
        chapters = buildChapters(id)
        speed = speedPct.coerceIn(Prefs.Speed.MIN, Prefs.Speed.MAX)
        pos = b.pos.coerceIn(0L, maxOf(0L, total - 1L))
        release()
        index = -1
        applySeek(pos)
        return true
    }

    /** Главы: встроенные (`chpl`/`CHAP`), иначе одна глава на файл. */
    private fun buildChapters(id: Long): List<Chap> {
        val built = db.audioChapters(id)
        if (built.isNotEmpty()) return built.map { Chap(it.title, it.start, it.dur) }
        val out = ArrayList<Chap>(files.size)
        for (i in files.indices) out.add(Chap(files[i].name, offsets[i], files[i].dur))
        return out
    }

    fun close() {
        pause()
        release()
        book = null
        files = ArrayList()
        offsets = LongArray(0)
        chapters = emptyList()
        total = 0L
        pos = 0L
    }

    // --------------------------------------------------------------- управление

    fun toggle() {
        if (playing) pause() else play()
    }

    fun play() {
        if (book == null) return
        // Книга доиграла (место в конце) — кнопка play начинает её заново: в макете нет
        // отдельной «сначала», а жать play на конце — первое, что приходит в голову.
        if (total > 0L && pos >= total - END_MS) {
            restart()
            return
        }
        val p = mp ?: prepare(index.coerceAtLeast(0)) ?: return
        try {
            applySpeed(p)
            p.start()
            playing = true
            Trace.step("audio: play ${book?.title} @ $pos")
            schedule()
            onTick?.invoke()
        } catch (t: Throwable) {
            Trace.step("audio: play FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun pause() {
        if (!playing) return
        playing = false
        try {
            mp?.pause()
        } catch (t: Throwable) {
            Trace.step("audio: pause FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        syncPos()
        save()
        ui.removeCallbacks(tick)
        onTick?.invoke()
        Trace.step("audio: pause @ $pos")
    }

    /** Перейти на место [ms] в книге. Переключает файл, если место в другом файле. */
    fun seek(ms: Long) {
        syncPos()
        applySeek(ms)
        if (playing) {
            try {
                mp?.start()
            } catch (ignored: Throwable) {
            }
        }
        onTick?.invoke()
    }

    private fun applySeek(ms: Long) {
        val target = ms.coerceIn(0L, maxOf(0L, total - 1L))
        pos = target
        val i = fileOf(target)
        val inner = target - offsets.getOrElse(i) { 0L }
        val p = if (i == index && mp != null) mp else prepare(i)
        try {
            p?.seekTo(inner.toInt())
        } catch (t: Throwable) {
            Trace.step("audio: seek FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** ±10 с (клавиши на B7); на границе книги — упор. */
    fun skip(deltaMs: Long) = seek(pos + deltaMs)

    /** Следующая глава (кнопка `nxt` на B7, строка B34). */
    fun nextChapter() {
        val c = chapterOf(pos) ?: return
        seek(c.end)
    }

    /** Предыдущая глава: первые секунды главы — на её начало, иначе на предыдущую. */
    fun prevChapter() {
        val c = chapterOf(pos) ?: return
        if (pos - c.start > PREV_RESTART_MS) {
            seek(c.start)
            return
        }
        val i = chapters.indexOf(c)
        seek(if (i > 0) chapters[i - 1].start else 0L)
    }

    /** Играть главу [n] с начала (список B34). */
    fun playChapter(n: Int) {
        val c = chapters.getOrNull(n) ?: return
        seek(c.start)
        if (!playing) play()
    }

    /** Глава, в которой стоит место [ms]; null — глав нет. */
    fun chapterOf(ms: Long): Chap? {
        if (chapters.isEmpty()) return null
        var lo = 0
        var hi = chapters.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (chapters[mid].start <= ms) lo = mid else hi = mid - 1
        }
        return chapters[lo]
    }

    /** Номер текущей главы с единицы («Глава 13 из 32» на B7). */
    fun chapterNumber(): Int = (chapters.indexOf(chapterOf(pos)) + 1).coerceAtLeast(1)

    /** Прогресс книги 0…100. */
    fun percent(): Int = if (total <= 0L) 0 else (pos * 100 / total).toInt().coerceIn(0, 100)

    /**
     * Скорость в процентах. Действует сразу — `PlaybackParams` меняется на ходу; на паузе
     * только запоминается (иначе `setPlaybackParams` сам запустил бы звук).
     */
    fun setSpeed(pct: Int) {
        speed = pct.coerceIn(Prefs.Speed.MIN, Prefs.Speed.MAX)
        try {
            if (playing) mp?.let { applySpeed(it) }
        } catch (t: Throwable) {
            Trace.step("audio: speed FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        onTick?.invoke()
    }

    /** «Пропускать паузы»: значение живёт в `Prefs`, сюда его передаёт экран B35. */
    fun setSkipPauses(on: Boolean) {
        skipPauses = on
    }

    // --------------------------------------------------------------- таймер сна

    /** Пауза через [minutes] минут; [SLEEP_CHAPTER] — в конце текущей главы; 0 — выключить. */
    fun sleepIn(minutes: Int) {
        sleepChapter = minutes == SLEEP_CHAPTER
        sleepAt = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
        onTick?.invoke()
    }

    fun sleepOff() {
        sleepAt = 0L
        sleepChapter = false
        onTick?.invoke()
    }

    /** Сколько осталось до паузы, мс; 0 — таймер не взведён. */
    fun sleepLeft(): Long = if (sleepAt == 0L) 0L else maxOf(0L, sleepAt - SystemClock.elapsedRealtime())

    // --------------------------------------------------------------- место и файлы

    /**
     * Обновить [pos] по текущему положению проигрывателя. Вызывается перед сохранением,
     * переходом и на тике.
     */
    fun syncPos() {
        val p = mp ?: return
        val i = index
        if (i < 0) return
        try {
            pos = (offsets.getOrElse(i) { 0L } + p.currentPosition).coerceIn(0L, maxOf(0L, total))
        } catch (t: Throwable) {
            Trace.step("audio: pos FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Сохранить место в базе (место и «когда играла»). */
    fun save() {
        val b = book ?: return
        try {
            db.setAudioPos(b.id, pos)
        } catch (t: Throwable) {
            Trace.step("audio: save FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Начать книгу заново (B33 «Прослушана» → повтор, play на конце книги). */
    fun restart() {
        val b = book ?: return
        applySeek(0L)
        pos = 0L
        db.setAudioProgress(b.id, 0L)
        play()
        onTick?.invoke()
    }

    private fun fileOf(ms: Long): Int {
        if (files.isEmpty()) return -1
        var lo = 0
        var hi = files.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[mid] <= ms) lo = mid else hi = mid - 1
        }
        return lo
    }

    /**
     * Подготовить файл [i]: сменить `dataset` у проигрывателя. null — файл не открылся
     * (остаток книги играть нечем; экран покажет место как есть).
     */
    private fun prepare(i: Int): MediaPlayer? {
        if (i !in files.indices) return null
        val old = mp
        if (old != null) {
            try {
                if (old.isPlaying) old.stop()
            } catch (ignored: Throwable) {
            }
            try {
                old.release()
            } catch (ignored: Throwable) {
            }
        }
        mp = null
        val p = MediaPlayer()
        return try {
            p.setDataSource(files[i].path)
            p.setOnCompletionListener { onFileDone() }
            p.setOnErrorListener { _, what, extra ->
                Trace.step("audio: error $what/$extra ${files.getOrNull(i)?.path}")
                pause()
                true
            }
            p.prepare()
            if (speed != 100) applySpeed(p)
            mp = p
            index = i
            p
        } catch (t: Throwable) {
            Trace.step("audio: prepare FAIL ${t.javaClass.simpleName}: ${t.message}")
            try {
                p.release()
            } catch (ignored: Throwable) {
            }
            index = -1
            null
        }
    }

    /** Файл доиграл: следующая глава сразу, если это не конец книги. */
    private fun onFileDone() {
        if (sleepChapter) {
            // «До конца главы»: глава закончилась — пауза, дальше не идём.
            playing = false
            sleepChapter = false
            syncPos()
            save()
            ui.removeCallbacks(tick)
            onTick?.invoke()
            Trace.step("audio: sleep (конец главы)")
            return
        }
        val next = index + 1
        if (next >= files.size) {
            pos = total
            playing = false
            ui.removeCallbacks(tick)
            save()
            onTick?.invoke()
            onEnd?.invoke()
            Trace.step("audio: конец книги ${book?.title}")
            return
        }
        pos = offsets[next]
        val wasPlaying = playing
        val p = prepare(next) ?: return
        try {
            applySpeed(p)
            if (wasPlaying) p.start()
        } catch (t: Throwable) {
            Trace.step("audio: next FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        onTick?.invoke()
    }

    /** Скорость — `PlaybackParams`: меняем темп, не тон (иначе голос «пищит»). */
    private fun applySpeed(p: MediaPlayer) {
        try {
            p.playbackParams = PlaybackParams().setSpeed(speed / 100f).setPitch(1f)
        } catch (t: Throwable) {
            Trace.step("audio: params FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Тик: перерисовка, проверка таймера, место в базу — раз в [TICK_MS]. */
    private fun schedule() {
        ui.removeCallbacks(tick)
        ui.postDelayed(tick, TICK_MS)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!playing) return
            syncPos()
            // Таймер сна сработал: пауза здесь и сейчас (экран увидит это по onTick).
            if (sleepAt != 0L && SystemClock.elapsedRealtime() >= sleepAt) {
                sleepAt = 0L
                pause()
                return
            }
            // «Пропускать паузы»: хвост файла отрезаем заранее, не дожидаясь completion.
            if (skipPauses) skipTail()
            // Место в базу — на том же тике: п. 12 просит прогресс раз в 10–30 с.
            save()
            onTick?.invoke()
            ui.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Хвост файла короче [SKIP_TAIL_MS] до конца — переходим к следующему файлу сейчас.
     * Пишем позицию книги на начало следующего файла и продолжаем играть.
     */
    private fun skipTail() {
        val p = mp ?: return
        val i = index
        if (i < 0 || i >= files.size - 1) return
        try {
            val left = p.duration - p.currentPosition
            if (left in 1 until SKIP_TAIL_MS.toInt()) {
                pos = offsets[i + 1]
                val next = prepare(i + 1) ?: return
                applySpeed(next)
                next.start()
                playing = true
                Trace.step("audio: пауза пропущена (${left} мс хвоста)")
            }
        } catch (t: Throwable) {
            Trace.step("audio: skip tail FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun release() {
        ui.removeCallbacks(tick)
        playing = false
        try {
            mp?.release()
        } catch (ignored: Throwable) {
        }
        mp = null
    }

    companion object {
        /** «Конец главы» в списке таймера B35 — вместо числа минут. */
        const val SLEEP_CHAPTER = -1

        /** Раз в сколько перерисовывать время на B7 и проверять таймер сна. */
        private const val TICK_MS = 15_000L

        /** Тик — и перерисовка, и место в базу (п. 12: «прогресс раз в 10–30 с»). */

        /** Первые секунды главы: «назад» ведёт на её начало, а не к прошлой главе. */
        private const val PREV_RESTART_MS = 3_000L

        /** Хвост файла, который отсекает «Пропускать паузы» (B35), мс. */
        private const val SKIP_TAIL_MS = 2_000L

        /** Насколько близко к концу книги play считается «доиграла» — тогда начинаем заново. */
        private const val END_MS = 1_000L
    }
}
