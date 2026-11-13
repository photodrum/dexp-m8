package ru.ogrpfsense.dexpm8.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.ui.D
import ru.ogrpfsense.dexpm8.ui.EinkActivity
import ru.ogrpfsense.dexpm8.ui.Epd
import ru.ogrpfsense.dexpm8.ui.ErrorDialog
import ru.ogrpfsense.dexpm8.ui.Screen
import ru.ogrpfsense.dexpm8.ui.ShadeScreen
import ru.ogrpfsense.dexpm8.ui.T
import ru.ogrpfsense.dexpm8.ui.Trace
import java.nio.ByteBuffer

/**
 * B3 · Чтение (R1.3). Числа — из `design/v5/B03.json`.
 *
 *   книга      колонтитул слева 73.1,37.9, Manrope 30/600 G4
 *   глава      колонтитул справа, правый край 1330.8, Manrope 30/600 G4
 *   страница   область текста 102.4,117 1199.3×1540 — в неё рисуется буфер движка
 *   прогресс   трек 73.1,1764.8 1023.7×21.9 (вдавленный), заливка G3 75.9,1770.3 высотой 13.7
 *   счётчик    «124 / 350» Manrope 36/700 G1, правый край 1330.8, верх 1752.6
 *   подпись    «35% · … до конца главы» Manrope 30/600 G4, 73.1,1803.8
 *
 * Статус-бара на макете нет: на странице книги его роль играют колонтитулы.
 *
 * Листание — трети экрана (левая назад, правая вперёд; центр отдан шторке B5 сверху,
 * шторке B4 и меню B9 — R3, `ShadeScreen.ZONE_READER`) и физические кнопки книги:
 * `gpio-keys` отдаёт `KEY_PAGEUP` / `KEY_PAGEDOWN`, `adc-keys` — `KEY_VOLUMEUP` /
 * `KEY_VOLUMEDOWN` (разведка на книге 2026-11-13). Клавиши приходят из
 * `EinkActivity.dispatchKeyEvent` в [key].
 *
 * Память: два `ALPHA_8` (по 1,8 МБ) и один `ByteArray` — страница на экране и заранее
 * отрисованная соседняя (READER_SPEC, раздел 4). Соседняя рисуется в фоне в непоказанный
 * буфер, экран переключает готовый.
 *
 * Состояние страниц (`cur`, `pages`, буферы) меняется только в `Engine.worker` — движок
 * не потокобезопасен. UI-поток читает готовые поля в [render]; [show] и [key] с UI-потока
 * только ставят задачу в очередь движка.
 */
class ReaderScreen(act: EinkActivity) : Screen(act) {

    private val pagePaint = Paint().apply {
        color = T.G0          // альфа из буфера: 0 → G15 фона, 255 → G0
        isFilterBitmap = false
        isDither = false
    }
    private val dst = RectF(TX, TY, TX + TW, TY + TH)

    // --- Данные книги (меняются в worker)
    private var title = ""
    @Volatile private var pages = 0
    @Volatile private var cur = 0

    // --- Оглавление (`toc()`, R1.2): глава на экране и её последняя страница
    @Volatile private var chapterTitle = ""
    @Volatile private var chapterEnd = -1

    // --- Буферы: один показывается, второй пишется в фоне; nextPage — что в нём лежит
    private var bmpA: Bitmap? = null
    private var bmpB: Bitmap? = null
    @Volatile private var shownB = false
    private var nextPage = -1
    /** Какая страница лежит в буфере экрана; −1 — экрана ещё нет. */
    @Volatile private var shownPage = -1
    private var buf: ByteArray? = null
    private var pw = 0
    private var ph = 0
    private var toc: List<TocItem> = emptyList()

    private var gen = 0
    /** Меняется при каждом листании: подсчёт кадров в очереди движка по нему отсекается. */
    private var epoch = 0
    private var attached = false
    /** Страниц перевёрнуто: каждое [FULL_EVERY]-е — полная перерисовка (B37). */
    private var flips = 0

    private val dialog = ErrorDialog(ink, icons)
    private var dialogOpen = false

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        dst.set(TX, TY, TX + TW, TY + TH)
    }

    /** Читалка: шторка B5 открывается касанием центральной трети в верхней части страницы. */
    override val shadeZone: Float get() = ShadeScreen.ZONE_READER

    // ------------------------------------------------------------------ кадр

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG_READ)
        header(c)
        val b = if (shownB) bmpB else bmpA
        if (b != null) c.drawBitmap(b, null, dst, pagePaint)
        if (pages > 0) footer(c)
        if (dialogOpen) dialog.draw(c)
    }

    private fun header(c: Canvas) {
        if (title.isNotEmpty()) {
            val name = ink.ellipsize(title, 780f, T.TS_META, 600)
            ink.text(c, name, 73.1f, T.base(37.9f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
        }
        val ch = chapterTitle
        if (ch.isEmpty()) return
        val s = ink.ellipsize(ch, 780f, T.TS_META, 600)
        ink.text(c, s, 1330.8f - ink.measure(s, T.TS_META, 600),
            T.base(37.9f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
    }

    private fun footer(c: Canvas) {
        val pct = pctOf(cur)
        // Трек вдавленный: G15 / G10 / G12, дно 1018.3×13.7 в 75.9,1770.3 (B03.json).
        ink.inset(c, TRACK_X, TRACK_Y, TRACK_W, TRACK_H, T.R_COVER)
        if (pct > 0) {
            ink.roundRect(c, TRACK_X + T.INSET_DX, TRACK_Y + T.INSET_DY,
                TRACK_INNER_W * pct / 100f, TRACK_INNER_H, 6.85546875f, T.G3)
        }
        val page = "${cur + 1} / $pages"
        ink.text(c, page, 1330.8f - ink.measure(page, T.TS_LABEL, 700),
            T.base(1752.6f, T.TS_LABEL, 1.25f), T.TS_LABEL, 700, T.G1)
        ink.text(c, caption(pct), 73.1f, T.base(1803.8f, T.TS_META, 1.25f), T.TS_META, 600, T.G4)
    }

    private fun pctOf(page: Int): Int =
        if (pages <= 0) 0 else ((page + 1).toLong() * 100 / pages).toInt().coerceIn(0, 100)

    /**
     * Подпись под треком. В макете «35% · 4 мин до конца главы»; скорость чтения не
     * измеряется, поэтому вместо минут — страницы (записано в `docs/DESIGN_QUESTIONS.md`).
     * Главы нет (в книге нет оглавления) — только проценты.
     */
    private fun caption(pct: Int): String {
        val end = chapterEnd
        if (end <= cur) return "$pct%"
        return "$pct% · ${end - cur} стр. до конца главы"
    }

    // ------------------------------------------------------------------ открытие

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        start()
    }

    override fun onDetachedFromWindow() {
        attached = false
        gen++                 // результат уже идущей отрисовки выбрасываем
        // Документ закрываем: он держит DOM и разметку (PSS). Место чтения сохранит R1.4.
        Engine.worker.execute { Engine.close() }
        super.onDetachedFromWindow()
    }

    private fun start() {
        // Debug (R0): `--es probe <путь>` открывает файл напрямую, без базы.
        val dbg = ProbeScreen.debugPath
        ProbeScreen.debugPath = null
        if (dbg != null) {
            title = dbg.substringAfterLast('/')
            open(ru.ogrpfsense.dexpm8.core.LibraryDb.Entry(
                id = -1L, path = dbg, size = 0L, mtime = 0L,
                format = dbg.substringAfterLast('.', "?").lowercase(java.util.Locale.ROOT),
                title = title, author = null, series = null, seriesNum = 0, lang = null,
                coverId = null, progress = 0, status = 0, added = 0L, opened = 0L,
            ))
            return
        }
        val e = try {
            act.db.get(act.bookId) ?: act.db.lastOpened()
        } catch (t: Throwable) {
            Trace.step("b3: db FAIL ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (e == null) {
            fail(Engine.EOPEN, "Книга не найдена")
            return
        }
        title = e.title
        open(e)
    }

    private fun open(e: LibraryDb.Entry) {
        val w = D.px(TW)
        val h = D.px(TH)
        if (w <= 0 || h <= 0) return
        pw = w
        ph = h
        bmpA = Engine.newPageBitmap(w, h)
        bmpB = Engine.newPageBitmap(w, h)
        buf = ByteArray(w * h)
        val my = ++gen
        Engine.worker.execute {
            val t0 = SystemClock.uptimeMillis()
            val rc = Engine.open(e.path, w, h)
            if (rc != Engine.OK) {
                Trace.step("b3: open rc=$rc ${Engine.codeText(rc)} ${SystemClock.uptimeMillis() - t0}ms")
                post { if (attached && my == gen) fail(rc, Engine.codeText(rc)) }
                return@execute
            }
            pages = Engine.pageCount()
            toc = Engine.toc()
            val ms = SystemClock.uptimeMillis() - t0
            Trace.step("b3: open ok ${ms}ms pages=$pages toc=${toc.size}")
            post {
                if (!attached || my != gen) return@post
                chapter(cur)
                show(cur, 0)
            }
        }
    }

    /** Ошибка открытия: диалог B45 поверх пустой страницы. */
    private fun fail(rc: Int, text: String) {
        Trace.step("b3: FAIL $rc $text")
        dialog.open(
            title = "Книга не открылась",
            sub = if (title.isEmpty()) "Файл" else title,
            note = "Файл повреждён или формат не поддерживается. Остальные книги открываются как обычно.",
            label = "КОД ОШИБКИ",
            code = text,
            canDelete = false,
        )
        dialogOpen = true
        redraw()
    }

    // ------------------------------------------------------------------ листание

    /**
     * Показать страницу [page]: если соседняя уже отрисована в фоне — мгновенное
     * переключение буфера, иначе отрисовка в потоке движка. [dir] — куда листали
     * (+1 вперёд, −1 назад, 0 — открытие): соседняя в этом же направлении готовится заранее.
     */
    private fun show(page: Int, dir: Int) {
        if (page < 0 || page >= pages) return
        if (page == shownPage && nextPage == -1) return    // уже на экране
        val my = ++epoch
        Engine.worker.execute { showLocked(page, dir, my) }
    }

    /** Всё это — только в `Engine.worker`. */
    private fun showLocked(page: Int, dir: Int, my: Int) {
        if (my != epoch) return          // поверх встало другое листание
        if (page < 0 || page >= pages) return
        if (nextPage == page) {
            // Готовый буфер: переключаем без обращения к движку (≤ 300 мс от касания).
            shownB = !shownB
            nextPage = -1
            shownPage = page
            cur = page
            chapter(page)
            flips++
            val full = flips % FULL_EVERY == 0
            post {
                if (!attached) return@post
                if (full && Epd.ok) {
                    Epd.request(this, Epd.CLEAN, false)
                    Trace.step("b3: full frame after $flips")
                }
                redraw()
            }
            prefetch(dir)
            return
        }
        val w = pw
        val h = ph
        val out = buf ?: return
        val t0 = SystemClock.uptimeMillis()
        val rc = Engine.page(page, w, h, out)
        val ms = SystemClock.uptimeMillis() - t0
        if (rc != Engine.OK) {
            Trace.step("b3: page $page rc=$rc ${Engine.codeText(rc)}")
            post { if (attached) fail(rc, Engine.codeText(rc)) }
            return
        }
        copyToHidden(page)
        cur = page
        chapter(page)
        flips++
        val full = flips % FULL_EVERY == 0
        Trace.step("b3: page $page ${ms}ms")
        post {
            if (!attached) return@post
            if (full && Epd.ok) {
                Epd.request(this, Epd.CLEAN, false)
                Trace.step("b3: full frame after $flips")
            }
            redraw()
        }
        prefetch(dir)
    }

    /** Буфер движка → непоказанный `Bitmap`, показ переключается на него. */
    private fun copyToHidden(page: Int) {
        val out = buf ?: return
        val b = if (shownB) bmpA else bmpB
        if (b == null) return
        b.copyPixelsFromBuffer(ByteBuffer.wrap(out, 0, pw * ph))
        shownB = !shownB
        shownPage = page
        nextPage = -1
    }

    /**
     * Соседняя страница в фоне — листание тогда мгновенное. Считается устаревшей, если
     * [epoch] сменился: значит, поверх встало другое листание (проверка до и после отрисовки).
     */
    private fun prefetch(dir: Int) {
        if (dir == 0) return
        val p = cur + dir
        if (p < 0 || p >= pages) return
        val w = pw
        val h = ph
        val out = buf ?: return
        val my = epoch
        Engine.worker.execute {
            if (my != epoch) return@execute
            val t0 = SystemClock.uptimeMillis()
            val rc = Engine.page(p, w, h, out)
            if (rc != Engine.OK || my != epoch) return@execute
            // В непоказанный буфер, но экран на него не переключаем: это заготовка.
            val b = (if (shownB) bmpA else bmpB) ?: return@execute
            b.copyPixelsFromBuffer(ByteBuffer.wrap(out, 0, pw * ph))
            nextPage = p
            Trace.step("b3: prefetch $p ${SystemClock.uptimeMillis() - t0}ms")
        }
    }

    override fun onUp(x: Float, y: Float) {
        if (dialogOpen) {
            if (dialog.hit(x, y) != ErrorDialog.ACT_NONE) {
                dialogOpen = false
                redraw()
            }
            return
        }
        when {
            x < W / 3f -> back()
            x > W * 2f / 3f -> forward()
        }
    }

    private fun forward() = show(cur + 1, 1)

    private fun back() = show(cur - 1, -1)

    /**
     * Физические клавиши книги (см. шапку класса). Возвращает true, если клавиша наша.
     * Громкость временно листает: настройка B39 «кнопки громкости» — R3.
     */
    fun key(code: Int): Boolean {
        if (dialogOpen) return false
        val next = code == KEY_PAGEDOWN || code == KEY_VOLUME_DOWN
        val prev = code == KEY_PAGEUP || code == KEY_VOLUME_UP
        if (!next && !prev) return false
        if (next) forward() else back()
        return true
    }

    /** Глава для страницы [page]: последний пункт оглавления, начавшийся не позже неё. */
    private fun chapter(page: Int) {
        if (toc.isEmpty()) {
            chapterTitle = ""
            chapterEnd = -1
            return
        }
        var idx = -1
        for (i in toc.indices) {
            val p = toc[i].page
            if (p in 0..page) idx = i else if (p > page) break
        }
        if (idx < 0) {
            chapterTitle = ""
            chapterEnd = -1
            return
        }
        val level = toc[idx].level
        var end = pages
        for (i in idx + 1 until toc.size) {
            val p = toc[i].page
            if (toc[i].level <= level && p >= 0) {
                end = p
                break
            }
        }
        chapterTitle = toc[idx].title
        chapterEnd = end
    }

    companion object {
        /** Область текста страницы из B03.json: x 102.4, y 117, 1199.3×1540. */
        private const val TX = 102.4f
        private const val TY = 117f
        private const val TW = 1199.3f
        private const val TH = 1540f

        /** Трек прогресса и заливка внутри него (B03.json). */
        private const val TRACK_X = 73.1f
        private const val TRACK_Y = 1764.8f
        private const val TRACK_W = 1023.7f
        private const val TRACK_H = 21.9f
        private const val TRACK_INNER_W = 1018.3f
        private const val TRACK_INNER_H = 13.7f

        /** Полная перерисовка каждые N страниц (READER_SPEC, раздел 6; в B37 пока нет). */
        private const val FULL_EVERY = 6

        private const val KEY_PAGEUP = android.view.KeyEvent.KEYCODE_PAGE_UP
        private const val KEY_PAGEDOWN = android.view.KeyEvent.KEYCODE_PAGE_DOWN
        private const val KEY_VOLUME_UP = android.view.KeyEvent.KEYCODE_VOLUME_UP
        private const val KEY_VOLUME_DOWN = android.view.KeyEvent.KEYCODE_VOLUME_DOWN
    }
}
