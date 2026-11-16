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
 * шторке B4 и меню B9 — R3, `ShadeScreen.ZONE_READER`), свайп по настройке B39 «Листать
 * свайпом» ([onSwipe]: влево — вперёд, вправо — назад) и физические кнопки книги:
 * `gpio-keys` отдаёт `KEY_PAGEUP` / `KEY_PAGEDOWN`, `adc-keys` — `KEY_VOLUMEUP` /
 * `KEY_VOLUMEDOWN` (разведка на книге 2026-11-13). Клавиши страниц листают всегда, громкость —
 * по настройке B39 «Кнопки громкости» (`Prefs.volumeKeys`, B39.2). Клавиши приходят из
 * `EinkActivity.dispatchKeyEvent` в [key].
 *
 * Память: два `ALPHA_8` (по 1,8 МБ) и один `ByteArray` — страница на экране и заранее
 * отрисованная соседняя (READER_SPEC, раздел 4). Соседняя рисуется в фоне в непоказанный
 * буфер, экран переключает готовый.
 *
 * Состояние страниц (`cur`, `pages`, буферы) меняется только в `Engine.worker` — движок
 * не потокобезопасен. UI-поток читает готовые поля в [render]; [show] и [key] с UI-потока
 * только ставят задачу в очередь движка.
 *
 * Место чтения (R1.4): в базе лежит позиция движка (xpointer, колонка `pos`), а не номер
 * страницы — она переживает смену кегля и повторную разметку. При открытии [Engine.gotoPos]
 * ставит движок на сохранённую строку; при листании, уходе в фон и закрытии экрана позиция
 * пишется обратно вместе с прогрессом ([LibraryDb.setPlace]).
 *
 * Поверх страницы живут: лист B31 (DjVu), меню B9, список B10, лист B29 «Переход на страницу»
 * (R3.3) — он открывается касанием шкалы меню B9 (и подписи «Стр. N из M» под ней) — и лист
 * B4 «Типографика» (R3.5): кнопка «Текст» меню B9 у crengine (у DjVu там «Масштаб» → B31).
 * Настройки B4 хранятся в `Prefs` (общие с экраном B39) и применяются при каждом открытии книги.
 */

class ReaderScreen(act: EinkActivity) : Screen(act) {

    private val pagePaint = Paint().apply {
        color = T.G0          // альфа из буфера: 0 → G15 фона, 255 → G0
        isFilterBitmap = false
        isDither = false
    }
    /**
     * B29: под листом страница «приглушена» — в макете её текст и колонтитул G10, а не G0/G4.
     * Это не фильтр и не прозрачность: цвет точки меняется на одну ступень палитры.
     */
    private val pagePaintDim = Paint().apply {
        color = T.G10
        isFilterBitmap = false
        isDither = false
    }
    private val dst = RectF(TX, TY, TX + TW, TY + TH)

    // --- Данные книги (меняются в worker)
    private var title = ""
    /** Строка базы открытой книги: из неё берётся место чтения и в неё оно пишется. */
    @Volatile private var entry: LibraryDb.Entry? = null
    @Volatile private var pages = 0
    @Volatile private var cur = 0

    // --- Оглавление (`toc()`, R1.2): глава на экране и её последняя страница
    @Volatile private var chapterTitle = ""
    @Volatile private var chapterEnd = -1
    /** Первая страница текущей главы (для клавиши «Начало главы» на B29); −1 — главы нет. */
    @Volatile private var chapterStart = -1

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
    /** B59: какая страница не декодировалась (−1 — диалог B45 или его нет). */
    private var badPage = -1

    // --- B31 «Масштаб и поля» (R2.5): настройки DjVu живут в экране, лист — только рисует
    private val sheet = DjvuViewSheet(ink, icons)
    /** Открыт лист B31 поверх страницы. */
    private var sheetOpen = false
    // --- B9 «Меню чтения» (R3.1): две панели поверх страницы, состояние — здесь
    private val menu = ReaderMenu(ink, icons)
    /** Открыто меню B9. */
    private var menuOpen = false
    // --- B10 «Оглавление» (R3.2): полноэкранный список поверх страницы
    private val tocView = TocScreen(ink, icons)
    /** Открыт список B10. */
    private var tocOpen = false
    /** Страница списка B10 (по 7 пунктов). */
    private var tocPage = 0
    // --- B29 «Переход на страницу» (R3.3): нижний лист с клавиатурой поверх страницы
    private val goto = GotoSheet(ink, icons)
    /** Открыт лист B29. */
    private var gotoOpen = false
    // --- B4 «Типографика» (R3.5): нижний лист настроек текста поверх приглушённой страницы
    private val typo = TypographySheet(ink, icons)
    /** Открыт лист B4. */
    private var typoOpen = false
    /**
     * Типографика B4 на листе: значения из `Prefs`, пока лист открыт — его
     * [TypographySheet.result]. Пишется и из потока движка (применение), читается в [render] —
     * поэтому `volatile`, как `pages` и `cur`.
     */
    @Volatile private var typing = TypographySheet.State()
    /** Перевёрстка после смены настроек идёт: касания листа ждут (нельзя менять на ходу). */
    @Volatile private var relayouting = false
    /** Настройки страницы DjVu этой книги; для crengine не используется. */
    @Volatile private var view = DjvuView.DEFAULT
    /** Предел сдвига последнего отрисованного кадра `[maxX, maxY]` — для третей (пишет worker). */
    @Volatile private var panLast: IntArray? = null
    /** Предел сдвига от движка по странице `[maxX, maxY]`: трети сдвигают или листают. */
    private val pan = HashMap<Int, IntArray>(CACHE_MAX)
    /** Промилле авто-обрезки: страница → промилле (пустой массив = полей нет). Только worker. */
    private val auto = HashMap<Int, IntArray>(CACHE_MAX)
    /** Превью для листа: кадр 600×820 без обрезки и его страница. Только worker. */
    private val prevBuf = ByteArray(PREVIEW_W * PREVIEW_H)
    /** Два `Bitmap` превью: worker пишет в непоказанный, экран переключает по `post`. */
    private var prevA: Bitmap? = null
    private var prevB: Bitmap? = null
    @Volatile private var prevShown: Bitmap? = null
    private var prevPage = -1

    // --- B58 «Открываем книгу»: первое открытие длиннее SHOW_AFTER_MS
    private val openDialog = OpenBookDialog(ink, icons)
    /** Диалог на экране: загрузка идёт и порог ожидания уже прошёл. */
    private var opening = false
    /** Процент, который пришёл от движка, когда диалога ещё не было. */
    private var openPct = 0
    /** Открытие идёт (от постановки задачи в `Engine.worker` до первой страницы). */
    private var loading = false
    /** Нажата «Отмена»: результат открытия выбрасывается, документ закрывается. */
    @Volatile private var cancelled = false
    private val showOpen = Runnable {
        if (attached && loading && !cancelled) {
            opening = true
            openDialog.open(openSub(), openPct)
            redraw()
        }
    }

    /** Прогресс открытия с потока движка (переслан на UI). */
    private fun onOpenProgress(p: Int) {
        if (!attached) return
        openPct = p
        if (opening && openDialog.progress(p)) redraw()
    }

    /** Подзаголовок B58: «книга · автор · FB2» (в снимке «Война и мир · Л. Толстой · FB2»). */
    private fun openSub(): String {
        val e = entry ?: return title
        val parts = ArrayList<String>(3)
        if (e.title.isNotEmpty()) parts.add(e.title)
        if (!e.author.isNullOrEmpty()) parts.add(e.author)
        if (e.format.isNotEmpty()) parts.add(e.format.uppercase(java.util.Locale.ROOT))
        return parts.joinToString(" · ")
    }

    private fun stopOpenDialog() {
        loading = false
        removeCallbacks(showOpen)
        if (opening) {
            opening = false
            redraw()
        }
    }

    override fun layout(w: Float, h: Float) {
        super.layout(w, h)
        dst.set(TX, TY, TX + TW, TY + TH)
    }

    /** Читалка: шторка B5 открывается касанием центральной трети в верхней части страницы. */
    override val shadeZone: Float
        get() = if (sheetOpen || menuOpen || tocOpen || gotoOpen || typoOpen) 0f
        else ShadeScreen.ZONE_READER


    // ------------------------------------------------------------------ кадр

    override fun render(c: Canvas) {
        // B31: на макете под листом только фон и превью — страницу и колонтитулы книги не рисуем
        // (иначе они просвечивают за превью и колонтитул листа). Затемнения нет, как решено.
        if (sheetOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            sheet.draw(c, view)
            if (dialogOpen) dialog.draw(c)
            return
        }
        // B10 «Оглавление»: полноэкранный список поверх страницы, страница под ним не рисуется.
        if (tocOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG)
            tocView.draw(c, "Оглавление", menuSub(), toc, tocPage, hereOf())
            return
        }
        // B29 «Переход на страницу»: та же страница, но приглушённая (макет: текст G10),
        // сверху нижний лист с клавиатурой. Затемнения нет — как B31.
        if (gotoOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            header(c, T.G10)
            val gb = if (shownB) bmpB else bmpA
            if (gb != null) c.drawBitmap(gb, null, dst, pagePaintDim)
            val target = goto.page(pages)
            goto.draw(c, pages, target, cur + 1, chapterOf(target), pctOf(target))
            return
        }
        // B4 «Типографика» (R3.5): страница тоже приглушена (в макете текст и колонтитул G10),
        // сверху нижний лист с настройками. Затемнения нет — как B29 и B31.
        if (typoOpen) {
            ink.rect(c, 0f, 0f, W, H, T.BG_READ)
            header(c, T.G10)
            val tb = if (shownB) bmpB else bmpA
            if (tb != null) c.drawBitmap(tb, null, dst, pagePaintDim)
            typo.draw(c, typing)
            return
        }

        val dim = if (opening) dimBegin(c) else -1
        ink.rect(c, 0f, 0f, W, H, T.BG_READ)
        header(c)
        val b = if (shownB) bmpB else bmpA
        if (b != null) c.drawBitmap(b, null, dst, pagePaint)
        if (pages > 0) footer(c)
        if (menuOpen) menu.draw(c, title, menuSub(), menuPlace(), pages, cur, Engine.djvu)
        if (dim >= 0) dimEnd(c, dim)
        if (opening) openDialog.draw(c)
        if (dialogOpen) dialog.draw(c)
    }

    /**
     * Подзаголовок меню B9: «Лев Толстой · Часть 1, глава I» в макете. У нас это автор и
     * текущая глава из оглавления; чего нет — не выводим.
     */
    private fun menuSub(): String {
        val a = entry?.author.orEmpty()
        val ch = chapterTitle
        return when {
            a.isNotEmpty() && ch.isNotEmpty() -> "$a · $ch"
            a.isNotEmpty() -> a
            else -> ch
        }
    }

    /** Положение в книге для нижней панели B9: «Стр. 124 из 350 · Глава I · 35%». */
    private fun menuPlace(): String {
        val pct = pctOf(cur)
        val base = "Стр. ${cur + 1} из $pages"
        return if (chapterTitle.isEmpty()) "$base · $pct%" else "$base · $chapterTitle · $pct%"
    }

    /** Открыть меню B9 (касание середины центральной колонки, решение 2026-11-12). */
    private fun openMenu() {
        if (menuOpen || loading || pages <= 0) return
        menuOpen = true
        redraw()
        Trace.step("b3: menu open")
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        redraw()
        Trace.step("b3: menu close")
    }

    /** Открыть B10 «Оглавление»: список уже в [toc] (`Engine.toc()` при открытии книги). */
    private fun openToc() {
        if (tocOpen) return
        if (toc.isEmpty()) {
            // Оглавления нет (часть DjVu без навигации) — открывать нечего, меню просто закрылось.
            Trace.step("b3: toc пусто")
            return
        }
        tocOpen = true
        // Открываем на странице со текущей главой: иначе искать своё место в длинной книге.
        val here = hereOf()
        tocPage = if (here >= 0) here / TocScreen.ROWS else 0
        redraw()
        Trace.step("b3: toc open items=${toc.size} page=$tocPage here=$here")
    }

    private fun closeToc() {
        if (!tocOpen) return
        tocOpen = false
        redraw()
        Trace.step("b3: toc close")
    }

    /** Открыть B29 «Переход на страницу» (шкала меню B9): поле сразу с номером текущей страницы. */
    private fun openGoto() {
        if (gotoOpen || loading || pages <= 0) return
        gotoOpen = true
        goto.open(cur + 1)
        redraw()
        Trace.step("b3: goto open page=${cur + 1}/$pages chapter=$chapterStart")
    }

    private fun closeGoto() {
        if (!gotoOpen) return
        gotoOpen = false
        redraw()
        Trace.step("b3: goto close")
    }

    /**
     * «Перейти» на B29: страница берётся из поля листа и листается как обычная ([show]).
     * Номер в книге ограничивает сам лист (`GotoSheet.page`); пустое поле — просто закрыть.
     */
    private fun gotoPage() {
        val target = goto.page(pages)
        closeGoto()
        if (target < 0) return
        Trace.step("b3: goto page ${target + 1}/$pages")
        show(target, if (target > cur) 1 else if (target < cur) -1 else 0)
    }

    // ------------------------------------------------------------------ B4 «Типографика» (R3.5)

    /**
     * Открыть лист B4 «Типографика» (кнопка «Текст» меню B9). Значения — из `Prefs`:
     * лист и экран настроек B39 пишут в одни и те же ключи (READER_SPEC, «жесты касания»).
     * У DjVu листа нет: у скана нет кегля — там «Масштаб» (B31, решение 2026-11-15).
     */
    private fun openTypo() {
        if (typoOpen || Engine.djvu || loading || pages <= 0) return
        val p = act.prefs
        typing = TypographySheet.State(
            face = p.textFace, size = p.textSize, spacing = p.textSpacing,
            margins = p.textMargins, hyphens = p.hyphens,
        )
        typo.open(typing)
        typoOpen = true
        redraw()
        Trace.step("b3: B4 open ${typing}")
    }

    private fun closeTypo() {
        if (!typoOpen) return
        typoOpen = false
        redraw()
        Trace.step("b3: B4 close")
    }

    /**
     * Значения листа изменились: записать в `Prefs` и переверстать книгу с удержанием места.
     * Перевёрстка на 5-МБ книге — 4,2–4,4 с (R3.4), поэтому идёт в `Engine.worker`, а лист
     * на это время не принимает касаний ([relayouting]) и показывает новые числа сразу.
     */
    private fun applyTypo(s: TypographySheet.State) {
        val before = typing
        typing = s
        val p = act.prefs
        p.textFace = s.face
        p.textSize = s.size
        p.textSpacing = s.spacing
        p.textMargins = s.margins
        p.hyphens = s.hyphens
        redraw()
        if (relayouting) return                  // применяется — по завершении возьмёт [typing]
        relayouting = true
        val my = ++epoch
        Engine.worker.execute {
            val pos = Engine.pos(cur)
            val np = Engine.setTypography(s.face, s.size, TypographySheet.INTERLINE[s.spacing],
                TypographySheet.MARGIN[s.margins], if (s.hyphens) 1 else 0, pos)
            Trace.step("b3: setTypography ${before} → $s pages=$np " +
                "${if (pos == null) "позиции нет" else "позиция есть"}")
            if (np <= 0) {
                relayouting = false
                post { if (attached) fail(if (np == 0) Engine.EPAGE else np, Engine.codeText(np)) }
                return@execute
            }
            pages = np
            // Место чтения — по xpointer: номер страницы после перевёрстки другой.
            val page = if (pos != null) Engine.pageOf(pos) else -1
            val target = if (page >= 0) page else cur.coerceIn(0, np - 1)
            cur = target
            nextPage = -1                    // заготовка нарисована старым кеглем
            shownPage = -1                   // кадр на экране тоже
            toc = Engine.toc()
            chapter(target)
            relayouting = false
            post {
                if (!attached || my != epoch) return@post
                // Пока верстали, на листе могли поменять ещё раз — сначала доводим до
                // последнего значения, иначе страница осталась бы от промежуточного.
                if (typing != s) {
                    applyTypo(typing)
                    return@post
                }
                Trace.step("b3: после типографики стр.${target + 1}/$np")
                show(target, 0)
                redraw()
            }
        }
    }

    /** Индекс пункта оглавления для текущей страницы — тот же, что даёт главу в колонтитуле. */
    private fun hereOf(): Int = tocIndex(cur)

    /** Индекс пункта оглавления, который начинается на странице [page] или раньше её. */
    private fun tocIndex(page: Int): Int {
        if (toc.isEmpty()) return -1
        var idx = -1
        for (i in toc.indices) {
            val p = toc[i].page
            if (p in 0..page) idx = i else if (p > page) break
        }
        return idx
    }

    /**
     * Название главы для страницы [page] — строка справа от поля B29 («Часть 2, глава V»).
     * У листа она про **целевую** страницу, а не про текущую: человек видит, куда попадает.
     */
    private fun chapterOf(page: Int): String {
        val i = tocIndex(page)
        return if (i >= 0) toc[i].title else ""
    }

    /** Перейти по пункту оглавления: позиция движка, а не номер страницы (переживает смену кегля). */
    private fun gotoItem(i: Int) {
        if (i < 0 || i >= toc.size) return
        val pos = toc[i].pos
        val page = toc[i].page
        if (pos == null && page < 0) return
        closeToc()
        val my = ++epoch
        Engine.worker.execute {
            val target = if (pos != null) Engine.gotoPos(pos) else page
            if (my != epoch) return@execute
            if (target < 0 || target >= pages) return@execute
            post {
                if (!attached) return@post
                show(target, 0)
            }
        }
        Trace.step("b3: toc goto $i «${toc[i].title}» pos=${if (pos == null) "нет" else "ok"} page=$page")
    }

    /**
     * Колонтитулы B3: книга 73.1,37.9 и глава справа (правый край 1330.8), Manrope 30/600.
     * [color] — G4 обычно и G10 под листом B29, где в макете страница приглушена.
     * Полосы не пересекаются: у каждой половина ширины строки (73.1…1330.8) минус зазор,
     * иначе длинная глава налезает на название книги.
     */
    private fun header(c: Canvas, color: Int = T.G4) {
        if (title.isNotEmpty()) {
            val name = ink.ellipsize(title, HEAD_W, T.TS_META, 600)
            ink.text(c, name, 73.1f, T.base(37.9f, T.TS_META, 1.25f), T.TS_META, 600, color)
        }
        val ch = chapterTitle
        if (ch.isEmpty()) return
        val s = ink.ellipsize(ch, HEAD_W, T.TS_META, 600)
        ink.text(c, s, 1330.8f - ink.measure(s, T.TS_META, 600),
            T.base(37.9f, T.TS_META, 1.25f), T.TS_META, 600, color)
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
        // Экран живёт в кэше и отвязывается: подписка на прогресс — на каждый показ.
        Engine.setProgressListener { p -> post { onOpenProgress(p) } }
        start()
    }

    override fun onDetachedFromWindow() {
        attached = false
        gen++                 // результат уже идущей отрисовки выбрасываем
        stopOpenDialog()
        sheetOpen = false
        prevShown = null
        prevA = null
        prevB = null
        Engine.setProgressListener(null)
        // Очередь движка: сначала сохранить место чтения (позиция берётся у движка),
        // потом закрыть документ — он держит DOM и разметку (PSS).
        Engine.worker.execute {
            savePlace()
            Engine.close()
        }
        super.onDetachedFromWindow()
    }

    private fun start() {
        // Debug (R0): `--es probe <путь>` открывает файл напрямую, без базы.
        val dbg = ProbeScreen.debugPath
        ProbeScreen.debugPath = null
        if (dbg != null) {
            open(ru.ogrpfsense.dexpm8.core.LibraryDb.Entry(
                id = -1L, path = dbg, size = 0L, mtime = 0L,
                format = dbg.substringAfterLast('.', "?").lowercase(java.util.Locale.ROOT),
                title = dbg.substringAfterLast('/'), author = null, series = null, seriesNum = 0, lang = null,
                coverId = null, progress = 0, status = 0, added = 0L, opened = 0L,
            ))
            return
        }
        val e = try {
            // Книга задана явно (B12, B1, отладка) — берём её; не нашли строку — ошибка, а не
            // «последняя прочитанная»: иначе откроется не та книга (R1.4).
            if (act.bookId > 0) act.db.get(act.bookId) else act.db.lastOpened()
        } catch (t: Throwable) {
            Trace.step("b3: db FAIL ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (e == null) {
            fail(Engine.EOPEN, "Книга не найдена")
            return
        }
        // Книга открыта сейчас: B1 «Сейчас читаю» берёт последнюю по `opened`. Пишем здесь,
        // а не на кнопке «Читать» — вход в читалку есть и с главного экрана.
        try {
            act.db.markOpened(e.id)
        } catch (t: Throwable) {
            Trace.step("b3: markOpened FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
        title = e.title
        open(e)
    }

    private fun open(e: LibraryDb.Entry) {
        val w = D.px(TW)
        val h = D.px(TH)
        if (w <= 0 || h <= 0) return
        // Типографика B4 из `Prefs` (R3.5): читаем здесь, на UI-потоке, — `SharedPreferences`
        // в поток движка не тащим. Движок держит свои настройки на процесс, но `Prefs` могли
        // сбросить или изменить на экране B39 — тогда перевёрстка (см. `Engine.applyTypography`).
        val pr = act.prefs
        val tp = TypographySheet.State(pr.textFace, pr.textSize, pr.textSpacing,
            pr.textMargins, pr.hyphens)
        typing = tp
        // Экран один на всё приложение и живёт в кэше: при новой книге старые поля
        // (номер страницы, буферы, глава) не должны попасть в кадр до первой отрисовки.
        reset()
        title = e.title
        entry = e
        // B31: настройки страницы DjVu — на книгу, база v9. Для crengine не используются.
        view = DjvuView.parse(e.view)
        pw = w
        ph = h
        bmpA = Engine.newPageBitmap(w, h)
        bmpB = Engine.newPageBitmap(w, h)
        prevA = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ALPHA_8)
        prevB = Bitmap.createBitmap(PREVIEW_W, PREVIEW_H, Bitmap.Config.ALPHA_8)
        prevShown = null
        buf = ByteArray(w * h)
        val my = ++gen
        cancelled = false
        openPct = 0
        loading = true
        removeCallbacks(showOpen)
        postDelayed(showOpen, OpenBookDialog.SHOW_AFTER_MS)
        Engine.worker.execute {
            val t0 = SystemClock.uptimeMillis()
            val rc = Engine.open(e.path, w, h)
            if (rc != Engine.OK) {
                Trace.step("b3: open rc=$rc ${Engine.codeText(rc)} ${SystemClock.uptimeMillis() - t0}ms")
                post {
                    if (!attached || my != gen) return@post
                    stopOpenDialog()
                    fail(rc, Engine.codeText(rc))
                }
                return@execute
            }
            // «Отмена» на B58: разметка в crengine не прерывается (см. `cre_session.cpp`),
            // поэтому открытие доводится до конца и тут же закрывается — экран уже ушёл.
            if (cancelled || my != gen) {
                Trace.step("b3: open отменён ${SystemClock.uptimeMillis() - t0}ms")
                Engine.close()
                return@execute
            }
            // Типографика B4 — сразу после открытия: движок применил свои настройки на
            // `LoadDocument`, а [tp] могла измениться, пока книга открывалась. Место чтения
            // передаётся позицией: номер страницы после перевёрстки другой.
            val trc = Engine.applyTypography(tp.face, tp.size,
                TypographySheet.INTERLINE[tp.spacing],
                TypographySheet.MARGIN[tp.margins], tp.hyphens, e.pos)
            if (trc < 0) {
                // Книга открыта — читать можно: типографика не применилась, страницы как есть.
                Trace.step("b3: applyTypography FAIL $trc ${Engine.codeText(trc)}")
            }
            pages = Engine.pageCount()
            toc = Engine.toc()

            // Место чтения — позицией движка, а не номером страницы (READER_SPEC, раздел 4).
            // Не нашлась (книга переверстана, файл заменён) — начинаем с начала.
            val saved = Engine.gotoPos(e.pos)
            cur = if (saved in 0 until pages) saved else 0
            val ms = SystemClock.uptimeMillis() - t0
            Trace.step("b3: open ok ${ms}ms pages=$pages toc=${toc.size} " +
                "pos=${if (e.pos.isNullOrEmpty()) "нет" else "есть -> $saved"}")
            post {
                if (!attached || my != gen) return@post
                stopOpenDialog()
                chapter(cur)
                show(cur, 0)
            }
        }
    }

    /** Забыть прошлую книгу: номер страницы, глава, готовые буферы и открытый диалог. */
    private fun reset() {
        title = ""
        pages = 0
        cur = 0
        chapterTitle = ""
        chapterEnd = -1
        chapterStart = -1
        shownB = false
        nextPage = -1
        shownPage = -1
        flips = 0
        toc = emptyList()
        dialogOpen = false
        badPage = -1
        stopOpenDialog()
        cancelled = false
        // B31: лист и всё, что живёт вокруг него, — от прошлой книги.
        sheetOpen = false
        menuOpen = false
        tocOpen = false
        gotoOpen = false
        typoOpen = false
        relayouting = false

        tocPage = 0
        prevPage = -1
        prevShown = null
        panLast = null
        auto.clear()
        pan.clear()
    }

    /**
     * Сохранить место чтения: позиция текущей страницы и прогресс. Только в `Engine.worker` —
     * берёт `cur` и сам движок. Книга без строки в базе (отладочный `--es probe`) не пишется.
     */
    private fun savePlace() {
        val e = entry ?: return
        if (e.id <= 0 || pages <= 0) return
        val pos = Engine.pos(cur)
        val pct = (cur + 1).toLong().times(100).div(pages).toInt().coerceIn(0, 100)
        try {
            act.db.setPlace(e.id, pos, pct)
            Trace.step("b3: save page=${cur + 1}/$pages $pct% pos=${if (pos == null) "нет" else "ok"}")
        } catch (t: Throwable) {
            Trace.step("b3: save FAIL ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Сохранить место сейчас же — вызов с UI-потока (уход приложения в фон, [EinkActivity.onPause]).
     * Задача ставится в очередь движка: `Engine.pos` требует его потока.
     */
    fun saveNow() {
        if (!attached) return
        Engine.worker.execute { savePlace() }
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
        badPage = -1
        dialogOpen = true
        redraw()
    }

    /**
     * B59 «Страница повреждена» (R2.3): книга открыта, не декодируется одна страница
     * (`EPAGE`, например обрезанный DjVu). Остальные страницы листаются; «Следующая стр.»
     * сразу просит [page] + 1. Тексты предложены чату дизайна — `docs/DESIGN_QUESTIONS.md`.
     */
    private fun pageFail(page: Int) {
        Trace.step("b3: B59 page $page")
        badPage = page
        dialog.open(
            title = "Страница не открылась",
            sub = if (title.isEmpty()) "Файл" else title,
            note = "Эта страница в файле повреждена. Остальные страницы открываются — можно листать дальше.",
            label = "КОД ОШИБКИ",
            code = "Стр. ${page + 1} из $pages · ${Engine.codeText(Engine.EPAGE)}",
            canDelete = false,
            next = page + 1 < pages,
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
        if (loading) return             // книга ещё открывается, листать нечего
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
            if (flips % SAVE_EVERY == 0) savePlace()
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
        val rc = draw(page, w, h, out)
        val ms = SystemClock.uptimeMillis() - t0
        if (rc != Engine.OK) {
            Trace.step("b3: page $page rc=$rc ${Engine.codeText(rc)}")
            // Страница не декодировалась, а книга открыта — B59; остальное — B45.
            if (rc == Engine.EPAGE && pages > 0) post { if (attached) pageFail(page) }
            else post { if (attached) fail(rc, Engine.codeText(rc)) }
            return
        }
        copyToHidden(page)
        cur = page
        chapter(page)
        flips++
        if (flips % SAVE_EVERY == 0) savePlace()
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

    /**
     * Страница [page] в буфер [out]: DjVu — с настройками листа B31 (`Engine.pageView`),
     * crengine — обычным `Engine.page`. Только в `Engine.worker`.
     *
     * Обрезка берётся по режиму листа: «Вручную» — промилле из настроек, «Авто» — найденные
     * по превью этой страницы ([autoOf]), «Без обрезки» — никакой. Предел сдвига движок
     * возвращает в `panOut` — он запоминается для страницы, чтобы трети знали, сколько
     * ещё можно сдвинуть.
     */
    private fun draw(page: Int, w: Int, h: Int, out: ByteArray): Int {
        if (!Engine.djvu) return Engine.page(page, w, h, out)
        val v = view
        val crop = when (v.cropMode) {
            DjvuView.CROP_NONE -> null
            DjvuView.CROP_AUTO -> autoOf(page)
            else -> v.crop()
        }
        val ve = if (crop == null) v.copy(cropL = 0, cropT = 0, cropR = 0, cropB = 0)
        else v.copy(cropL = crop[0], cropT = crop[1], cropR = crop[2], cropB = crop[3])
        val panOut = PAN_OUT
        val rc = Engine.pageView(page, w, h, out, ve, panOut)
        if (rc == Engine.OK) {
            val lim = intArrayOf(panOut[2], panOut[3])
            panLast = lim
            if (pan.size >= CACHE_MAX) pan.clear()
            pan[page] = lim
        }
        return rc
    }

    // ------------------------------------------------------------------ авто-обрезка (R2.5)

    /**
     * Промилле авто-обрезки для страницы [page]; null — не режем. Считается по превью
     * 600×820 ([ensurePreview]) и запоминается: страница листается туда-сюда, а рендер
     * превью — лишний кадр движка. Кэш маленький ([AUTO_MAX]) — память 1 ГБ.
     */
    private fun autoOf(page: Int): IntArray? {
        val known = auto[page] ?: computeAuto(page)
        return if (known.isEmpty()) null else known
    }

    /** Посчитать авто-обрезку страницы [page] и запомнить; пустой массив — полей нет. */
    private fun computeAuto(page: Int): IntArray {
        val c = if (ensurePreview(page)) DjvuAuto.measure(prevBuf, PREVIEW_W, PREVIEW_H) else null
        val v = c ?: NO_CROP
        if (auto.size >= CACHE_MAX) auto.clear()     // страниц много, держим только свежие
        auto[page] = v
        return v
    }

    /**
     * Отрисовать превью страницы [page] (600×820, без обрезки и сдвига) в [prevBuf] —
     * тот самый кадр, что видит человек на листе B31. Только в `Engine.worker`.
     * false — движок не отдал кадр (тогда и резать не по чему).
     */
    private fun ensurePreview(page: Int): Boolean {
        if (prevPage == page) return true
        val rc = Engine.pageView(page, PREVIEW_W, PREVIEW_H, prevBuf, DjvuView(), null)
        if (rc != Engine.OK) {
            Trace.step("b3: preview $page rc=$rc ${Engine.codeText(rc)}")
            return false
        }
        prevPage = page
        showPreview()
        return true
    }

    /** Превью из рабочего буфера в `Bitmap` листа — по правилу «писать в worker, показывать по post». */
    private fun showPreview() {
        val shown = prevShown
        val b = if (shown === prevA) prevB else prevA
        if (b == null) return
        b.copyPixelsFromBuffer(ByteBuffer.wrap(prevBuf, 0, PREVIEW_W * PREVIEW_H))
        val page = prevPage
        post {
            if (!attached || prevPage != page) return@post    // уже отрисовано другое
            prevShown = b
            sheet.setPreview(b)
            if (sheetOpen) redraw()
        }
    }

    /** Лист B31 ждёт превью текущей страницы: дверь — касание колонтитула (R2.5). */
    private fun openSheet() {
        if (!Engine.djvu || pages <= 0) return
        sheetOpen = true
        sheet.open(sheetHeader())
        // Кадр текущей страницы уже мог быть отрисован ради авто-обрезки — показываем его,
        // иначе лист сначала пустой и превью приходит из worker.
        sheet.setPreview(if (prevPage == cur) prevShown else null)
        redraw()
        val page = cur
        Engine.worker.execute {
            ensurePreview(page)
            post { if (attached && sheetOpen) redraw() }
        }
    }

    /** Колонтитул листа: «Атлас мира.djvu · стр. 12 из 240» (в макете B31). */
    private fun sheetHeader(): String {
        val name = entry?.path?.substringAfterLast('/') ?: title
        return "$name · стр. ${cur + 1} из $pages"
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        sheet.setPreview(null)
        redraw()
    }

    /**
     * Настройки листа изменились: страницу перерисовать и записать строку в базу.
     * Перерисовка идёт через [epoch]: ранний выход `page == shownPage && nextPage == -1`
     * в [showLocked] не дал бы нарисовать ту же страницу заново.
     */
    private fun applyView(v: DjvuView) {
        view = v
        sheet.setPreview(sheetPreviewOrNull())
        val e = entry
        if (e != null && e.id > 0) {
            try {
                act.db.setView(e.id, v.encode())
            } catch (t: Throwable) {
                Trace.step("b3: setView FAIL ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        redraw()
        if (!Engine.djvu || pages <= 0) return
        val page = cur
        val my = ++epoch
        Engine.worker.execute {
            nextPage = -1                     // заготовка нарисована старыми настройками
            showLocked(page, 0, my)
        }
        Trace.step("b3: view ${v.encode()}")
    }

    private fun sheetPreviewOrNull(): Bitmap? = if (prevPage >= 0) prevShown else null

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
            val rc = draw(p, w, h, out)      // те же настройки B31, что и у показанной страницы
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
            val a = dialog.hit(x, y)
            if (a != ErrorDialog.ACT_NONE) {
                dialogOpen = false
                redraw()
                // B59: «Следующая стр.» — сразу за повреждённой, мимо неё.
                if (a == ErrorDialog.ACT_NEXT && badPage >= 0) show(badPage + 1, 1)
                badPage = -1
            }
            return
        }
        // B31 поверх страницы: касания только его (и «Готово» — закрыть).
        if (sheetOpen) {
            when (sheet.hit(x, y, view)) {
                DjvuViewSheet.ACT_CHANGED -> applyView(sheet.result)
                DjvuViewSheet.ACT_REPAINT -> redraw()
                DjvuViewSheet.ACT_CLOSE -> closeSheet()
            }
            return
        }
        // B10 «Оглавление»: касания только списка (страницы, листание, кнопки).
        if (tocOpen) {
            when (tocView.hit(x, y, tocPage, toc)) {
                TocScreen.ACT_CLOSE -> closeToc()
                TocScreen.ACT_PREV -> { tocPage--; redraw() }
                TocScreen.ACT_NEXT -> { tocPage++; redraw() }
                TocScreen.ACT_OPEN -> gotoItem(tocView.chosen)
            }
            return
        }
        // B4 «Типографика»: касания только листа (кегль, гарнитура, интервал, поля, переносы).
        // Пока идёт перевёрстка, лист касаний не принимает — иначе она встанет в очередь
        // движка и человек решит, что клавиша не работает.
        if (typoOpen) {
            if (relayouting) return
            when (typo.hit(x, y, typing)) {
                TypographySheet.ACT_CLOSE -> closeTypo()
                TypographySheet.ACT_CHANGED -> applyTypo(typo.result)
            }
            return
        }
        // B29 «Переход на страницу»: касания только листа (клавиатура, шкала, кнопки).

        if (gotoOpen) {
            when (goto.hit(x, y, pages, chapterStart)) {
                GotoSheet.ACT_CLOSE -> closeGoto()
                GotoSheet.ACT_GO -> gotoPage()
                GotoSheet.ACT_REPAINT -> redraw()
            }
            return
        }
        // B9 «Меню чтения»: пока открыто, касания только его.
        if (menuOpen) {
            when (menu.hit(x, y, Engine.djvu)) {
                ReaderMenu.ACT_CLOSE -> closeMenu()
                ReaderMenu.ACT_PREV -> { closeMenu(); back() }
                ReaderMenu.ACT_NEXT -> { closeMenu(); forward() }
                ReaderMenu.ACT_TOC -> { closeMenu(); openToc() }
                ReaderMenu.ACT_SCALE -> { closeMenu(); openSheet() }
                // «Текст» у crengine — лист B4 «Типографика» (R3.5); у DjVu это «Масштаб».
                ReaderMenu.ACT_TEXT -> { closeMenu(); openTypo() }

                // Шкала и подпись «Стр. N из M» на нижней панели — дверь в B29 (R3.3).
                ReaderMenu.ACT_GOTO -> { closeMenu(); openGoto() }
                // Мимо меню — закрыть. «Текст» у crengine (B4), «Контраст», «Вслух» и «Ещё»
                // ждут своих этапов: кнопки нарисованы, касание пока только закрывает меню.
                else -> closeMenu()
            }
            return
        }
        // B58: пока книга открывается, касания только на «Отмену» — страниц ещё нет.
        if (opening) {
            if (openDialog.hitCancel(x, y)) cancelOpen()
            return
        }
        // Середина центральной колонки — «Меню чтения» B9 (решение 2026-11-12:
        // верх ≈27% — шторка B5, низ ≈27% — шторка B4, между ними — меню).
        if (x >= ShadeScreen.ZONE_X0 && x <= ShadeScreen.ZONE_X1 &&
            y > ShadeScreen.ZONE_READER && y < MENU_ZONE_Y1) {
            openMenu()
            return
        }
        // Трети: сначала сдвиг увеличенной страницы, листание — когда сдвигать нечего.
        val dir = when {
            x < W / 3f -> -1
            x > W * 2f / 3f -> 1
            else -> 0
        }
        if (dir != 0 && panPage(dir)) return
        if (dir < 0) back() else if (dir > 0) forward()
    }

    /**
     * Сдвиг страницы третями (решение дизайна по B31, `B31б`): касание левой/правой трети
     * сдвигает кадр на треть окна, пока есть что сдвигать. false — сдвигать некуда,
     * экран листает страницу как обычно.
     *
     * Условие — настройки DjVu и запас сдвига у страницы, который вернул движок
     * (`panOut[2]`/`panOut[3]`). Сначала идёт горизонталь, потом вертикаль: у скана
     * обычно крупнее только одна сторона.
     */
    private fun panPage(dir: Int): Boolean {
        if (!Engine.djvu) return false
        val v = view
        // Предел — от последнего кадра: страница на экране, настройки текущие.
        val lim = panLast ?: return false
        val mx = lim[0]
        val my = lim[1]
        if (mx <= 0 && my <= 0) return false
        val stepX = (pw / 3).coerceAtLeast(1)
        val stepY = (ph / 3).coerceAtLeast(1)
        // Крупнее по вертикали (свой масштаб на длинную страницу) — вертикаль первой.
        val vFirst = my > mx
        var dx = 0
        var dy = 0
        if (vFirst) {
            dy = if (dir > 0) Math.min(v.panY + stepY, my) else v.panY - stepY
            if (dy == v.panY) dx = if (dir > 0) Math.min(v.panX + stepX, mx) else v.panX - stepX
        } else {
            dx = if (dir > 0) Math.min(v.panX + stepX, mx) else v.panX - stepX
            if (dx == v.panX) dy = if (dir > 0) Math.min(v.panY + stepY, my) else v.panY - stepY
        }
        val nx = dx.coerceIn(0, mx)
        val ny = dy.coerceIn(0, my)
        if (nx == v.panX && ny == v.panY) return false     // двигать некуда — листаем
        applyView(v.pan(nx, ny))
        Trace.step("b3: pan ${nx},${ny} max ${mx},${my}")
        return true
    }

    /**
     * Свайп листает страницу (настройка B39 «Листать свайпом», `Prefs.swipe`). Правило E-Ink
     * — «касание — это нажал и отпустил» — здесь расширено единственным жестом и только по
     * настройке: палец уезжает по экрану на 120 дизайн-px и больше (≈9 % ширины), при этом
     * горизонталь перевешивает вертикаль вдвое — иначе случайное «уползание» пальца
     * при листании третями стало бы листанием. Влево — вперёд, вправо — назад, как страницу
     * в бумажной книге.
     *
     * Открытые листы, меню и диалоги жест не принимают — как у клавиш в [key]: пока поверх
     * страницы что-то лежит, листать нечего.
     */
    override fun onSwipe(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        if (!act.prefs.swipe) return false
        if (dialogOpen || sheetOpen || menuOpen || tocOpen || gotoOpen || typoOpen) return false
        if (opening || loading || pages <= 0) return false
        val dx = x1 - x0
        val dy = y1 - y0
        if (Math.abs(dx) < SWIPE_MIN || Math.abs(dx) < Math.abs(dy) * SWIPE_ASPECT) return false
        // Увеличенный скан (B31) сдвигается третями: жест листает только когда сдвигать нечего.
        val dir = if (dx < 0) 1 else -1
        if (!panPage(dir)) {
            if (dir > 0) forward() else back()
        }
        Trace.step("b3: swipe ${dx.toInt()},${dy.toInt()} dir=$dir")
        return true
    }

    /** «Назад»: сначала B10 и меню B9, листы B4 и B31, диалоги, потом уход из книги. */
    override fun backPressed(): Boolean {
        if (typoOpen) {
            closeTypo()
            return true
        }
        if (gotoOpen) {
            closeGoto()
            return true
        }
        if (tocOpen) {
            closeToc()
            return true
        }
        if (menuOpen) {
            closeMenu()
            return true
        }
        if (sheetOpen) {
            closeSheet()
            return true
        }
        if (dialogOpen) {
            dialogOpen = false
            badPage = -1
            redraw()
            return true
        }
        return false
    }

    /**
     * «Отмена» на B58. Разметку crengine не прерывает (флага остановки в движке нет),
     * поэтому: экран уходит в библиотеку сразу, результат открытия выбрасывается,
     * документ закрывается по возврату `Engine.open` (см. `open`).
     */
    private fun cancelOpen() {
        Trace.step("b3: отмена открытия на ${openPct}%")
        cancelled = true
        gen++            // результат открытия уже не нужен
        loading = false
        opening = false
        removeCallbacks(showOpen)
        act.onBackPressed()
    }

    private fun forward() = show(cur + 1, 1)

    private fun back() = show(cur - 1, -1)

    /**
     * Физические клавиши книги (см. шапку класса). Возвращает true, если клавиша наша.
     *
     * Клавиши страниц (`gpio-keys`) листают всегда. Клавиши громкости (`adc-keys`) листают,
     * только если на B39 выбрано «Листание» (`Prefs.volumeKeys`); при «Громкость» они уходят
     * системе — тогда закрытый лист не должен съедать их, это проверяет [handlesKey].
     */
    fun key(code: Int): Boolean {
        if (dialogOpen) return false
        if (sheetOpen) return false    // B31: пока лист открыт, страница не листается
        if (menuOpen) return false     // B9: меню закрывается касанием или «Назад»
        if (tocOpen) return false      // B10: список листается своими кнопками
        if (gotoOpen) return false     // B29: лист с клавиатурой, страница не листается
        if (opening) return false     // B58: страниц ещё нет, клавиши листания не наши
        val next = code == KEY_PAGEDOWN || (volumePaging() && code == KEY_VOLUME_DOWN)
        val prev = code == KEY_PAGEUP || (volumePaging() && code == KEY_VOLUME_UP)
        if (!next && !prev) return false
        if (next) forward() else back()
        return true
    }

    /**
     * Берёт ли читалка эту клавишу себе (для `ACTION_UP` в `EinkActivity`). false — клавиша
     * наша по типу, но отдана системе: громкость при настройке «Громкость».
     */
    fun handlesKey(code: Int): Boolean = when (code) {
        KEY_PAGEUP, KEY_PAGEDOWN -> true
        KEY_VOLUME_UP, KEY_VOLUME_DOWN -> volumePaging()
        else -> false
    }

    /** Листают ли клавиши громкости (B39). */
    private fun volumePaging() = act.prefs.volumeKeys == ru.ogrpfsense.dexpm8.core.Prefs.VOLUME_PAGING

    /** Глава для страницы [page]: последний пункт оглавления, начавшийся не позже неё. */
    private fun chapter(page: Int) {
        if (toc.isEmpty()) {
            chapterTitle = ""
            chapterEnd = -1
            chapterStart = -1
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
            chapterStart = -1
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
        // Клавиша «Начало главы» (B29): если номер пункта не пришёл от движка — берём эту страницу.
        chapterStart = if (toc[idx].page >= 0) toc[idx].page else page
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

        /**
         * Ширина половины колонтитула B3: строка 73.1…1330.8 (1257.7) делится пополам с зазором 40.
         * Раньше обе половины были по 780 и налезали друг на друга у длинных глав.
         */
        private const val HEAD_W = 600f

        /**
         * Нижняя граница зоны «Меню чтения» B9 (решение 2026-11-12): ниже начинается шторка
         * B4 «Типографика» (y ≈1358). Границы взяты с картинки пользователя на глаз и
         * подтверждаются на книге.
         */
        private const val MENU_ZONE_Y1 = 1358f

        /** Свайп листания (B39 «Листать свайпом»): порог хода и перевес горизонтали. */
        private const val SWIPE_MIN = 120f
        private const val SWIPE_ASPECT = 2f

        // --- B31 «Масштаб и поля» (R2.5)
        /** Размер превью листа: 600×820 — слой «Страница DjVu» макета, без обрезки. */
        private const val PREVIEW_W = 600
        private const val PREVIEW_H = 820
        /** Сколько страниц помнить в кэше авто-обрезки и по каким настроить трети. */
        private const val CACHE_MAX = 64
        /** Пометка «полей нет» в кэше авто-обрезки (пустой массив короче любого результата). */
        private val NO_CROP = IntArray(0)
        /** Выход движка `[panX, panY, maxX, maxY]` — свой на каждый запрос, чтобы не делиться. */
        private val PAN_OUT = IntArray(4)

        /**
         * Место чтения в базу каждые N перевёрнутых страниц: если книгу выключить на середине,
         * позиция всё равно останется (выход из читалки сохраняет её отдельно).
         */
        private const val SAVE_EVERY = 10

        private const val KEY_PAGEUP = android.view.KeyEvent.KEYCODE_PAGE_UP
        private const val KEY_PAGEDOWN = android.view.KeyEvent.KEYCODE_PAGE_DOWN
        private const val KEY_VOLUME_UP = android.view.KeyEvent.KEYCODE_VOLUME_UP
        private const val KEY_VOLUME_DOWN = android.view.KeyEvent.KEYCODE_VOLUME_DOWN
    }
}
