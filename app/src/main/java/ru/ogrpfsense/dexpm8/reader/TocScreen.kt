package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.LibraryDb
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.T
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * B10 «Оглавление» (R3.2) и B27 «Закладки и заметки» (R4.2) — один экран с тремя вкладками.
 * Числа — `design/v5/B10.json` и `design/v5/B27.json`.
 *
 *   шапка      «Назад» блик Ø102.4 в 41.1,112.9 (иконка 73.1,144.9 43.9×45.7); заголовок 175.5,105
 *              60/700 — имя вкладки; подзаголовок 175.5,181 32/600 G4 — «Анна Каренина · Лев Толстой»
 *   вкладки    желоб 43.9,248.6 1316.2×95.1 (вдавленный, дно G11 r 44.8); активный сегмент
 *              48.4,253.2 438.4×90.5 (выпуклый) — под открытой вкладкой; подписи 34 в 51.2 / 485.1 /
 *              918.9, ширина 433.9, активная 700 G1, остальные 600 G5
 *   панель     B10 — 41.1,384.8 1320.8×1180.1; B27 — 1320.8×1280 (на 100 выше: под цитатой
 *              должна влезать ещё и строка заметки), r 51.2 (тень G10, блик G15, поверхность G14)
 *   оглавление первая строка 457.5, шаг 163.1 — 7 на страницу; текст 44 Literata от 153.6,
 *              номер 38/700 справа до 1301.7, разделитель G11 1.8 в 153.6 шириной 1147.8;
 *              текущая глава — вдавленный желоб 65.8,−22.6 1272.4×153.6 с дном G12, 700 G1
 *              и «Вы здесь» G4; «часть» — подпись 32/800 КАПСОМ G4 в 109.7 и иконка `check`
 *   закладки   первая строка 428.3, шаг 232.4 — 5 на страницу; `highlight` 31.5×28 в 100.3,+5.5;
 *              «Стр. N · Глава» 30/600 G5 в 155; дата 30/600 G5 справа до 1309; цитата Literata
 *              38/400 lh 1.4 в 125,+63 шириной 1180, не больше двух строк; черта выделения
 *              G7 8×высота цитаты в 95,+69; разделитель G11 1214×2 в 95,+199.1
 *   пагинация  «1 / 6» 44/600 по центру y 1738.3; клавиши блик Ø117 в 41.1 и 1240.4, y 1708.3
 *
 * Экран встаёт поверх B3 внутри [ReaderScreen], а не отдельным экраном стека: при detach читалка
 * закрывает документ движка (`ReaderScreen.onDetachedFromWindow`), и книга открывалась бы заново
 * на каждой двери в оглавление. Оглавление берётся из `Engine.toc()` при открытии книги, закладки —
 * из базы при каждом открытии экрана и после возврата с вкладки.
 *
 * Вкладка «Заметки» нарисована по макету, но не нажимается: заметок в базе нет до B50–B52.
 *
 * Состояние (вкладка и страница списка) держит экран, сюда передаётся в [draw]; что нажали,
 * сюда возвращается кодом [ACT_*], а выбранное экран забирает через [chosen] и [chosenMark].
 *
 * Подзаголовок шапки — [sub] от читалки: как и в меню B9, это «автор · текущая глава»,
 * а не «название · автор» из снимка (название крупно стоит на странице B3 и в меню).
 */
class TocScreen(private val ink: Ink, private val icons: Icons) {

    /** Пункт оглавления, по которому коснулись в последнем [hit] (−1 — никто). */
    var chosen = -1
        private set

    /** Закладка, по которой коснулись в последнем [hit] (−1 — никто). */
    var chosenMark = -1
        private set

    /**
     * Нарисовать экран. [tab] — открытая вкладка ([TAB_TOC] / [TAB_MARKS] / [TAB_NOTE]),
     * [marks] — закладки книги свежими сверху, [notes] — заметки и цитаты, [markCount] и
     * [noteCount] — их числа для подписей вкладок. Заголовок шапки — имя вкладки, как в снимке
     * B27 («Заметки» на третьей вкладке).
     */
    fun draw(c: Canvas, sub: String, items: List<TocItem>, page: Int, here: Int,
             tab: Int, marks: List<LibraryDb.Mark>, markCount: Int,
             notes: List<LibraryDb.Mark>, noteCount: Int) {
        val labels = tabLabels(markCount, noteCount)
        header(c, TAB_NAMES[tab], sub)
        tabBar(c, labels, tab)
        when (tab) {
            TAB_MARKS -> marksPanel(c, marks, page)
            TAB_NOTE -> notesPanel(c, notes, page)
            else -> tocPanel(c, items, page, here)
        }
        pager(c, page, pageCount(tab, items.size, marks.size, notes.size))
    }

    // ------------------------------------------------------------------ шапка

    private fun header(c: Canvas, tabName: String, sub: String) {
        circle(c, 41.1f, 112.9f, T.G14, T.G15, T.G10, 102.4f)
        icons.draw(c, "back@43.9x45.7", 73.1f, 144.9f, 43.9f, 45.7f)
        ink.text(c, ink.ellipsize(tabName, 900f, T.TS_TITLE, 700), 175.5f,
            T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 1130f, T.TS_BODY_S, 600), 175.5f,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /**
     * Вкладки: вдавленный желоб и выпуклый активный сегмент под открытой вкладкой.
     * Все три нажимаются: «Заметки» открывают список цитат и заметок (R4.3).
     */
    private fun tabBar(c: Canvas, labels: Array<String>, tab: Int) {
        ink.inset(c, 43.9f, 248.6f, 1316.2f, 95.1f, 47.5312f)
        // Сегмент — на 2.8 левее своей подписи и на 4.5 шире: так он в B10, а в B27 (третья
        // вкладка) снимок даёт 920 при подписи 920 — расхождение в 2.8 не видно на E-Ink.
        ink.convex(c, SEG_X + TAB_W * tab, 253.2f, SEG_W, 90.5f, 40.2188f)
        for (i in labels.indices) {
            ink.textCenter(c, labels[i], TAB_X + TAB_W * i, TAB_W,
                T.base(274.3f, T.TS_BODY, 1.25f), T.TS_BODY, if (i == tab) 700 else 600,
                if (i == tab) T.G1 else T.G5)
        }
    }

    // -------------------------------------------------------------- оглавление

    private fun tocPanel(c: Canvas, items: List<TocItem>, page: Int, here: Int) {
        ink.convex(c, PANEL_X, PANEL_Y, PANEL_W, PANEL_H_TOC, T.R_TILE)
        val first = page * ROWS
        val last = minOf(items.size, first + ROWS)
        for (i in first until last) row(c, items[i], i, ROW_TOP + (i - first) * ROW_STEP, here)
        if (items.isEmpty()) empty(c, EMPTY_TOC, EMPTY_TOC_HINT)
    }

    /**
     * Строка оглавления. Верхний уровень (level 1) — подпись КАПСОМ и галочка, как «ЧАСТЬ ПЕРВАЯ»
     * в макете; остальные — текст Literata с номером страницы. Текущая глава в макете вдавлена
     * и подсвечена: дно G12, текст 700 G1, «Вы здесь» G4 слева от номера.
     */
    private fun row(c: Canvas, it: TocItem, index: Int, top: Float, here: Int) {
        // Части (верхний уровень) не подсвечиваются: в макете «Вы здесь» стоит у главы.
        val cur = index == here && it.level >= 2
        if (cur) ink.inset(c, 65.8f, top - 22.6f, 1272.4f, 153.6f, T.R_SEGMENT, T.G12)
        if (it.level <= 1) {
            // Длинное название части не должно вылезать за панель (проверено на книге 2026-11-15).
            val s = ink.ellipsize(it.title.uppercase(Locale.ROOT), PART_W, T.TS_BODY_S, 800)
            ink.text(c, s, 109.7f, T.base(top + 5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 800, T.G4)
        } else {
            val f = T.literata(ink.res, if (cur) 600 else 500)
            val w = if (cur) 600 else 500
            ink.text(c, ink.ellipsize(it.title, TITLE_W, T.TS_PAGE, w, f), 153.6f,
                T.base(top, T.TS_PAGE, 1.25f, true), T.TS_PAGE, w, if (cur) T.G1 else T.G7, f)
            if (cur) {
                ink.text(c, HERE_TEXT, 775.3f, T.base(top + 20.8f, T.TS_META, 1.25f),
                    T.TS_META, 700, T.G4)
            }
        }
        if (it.page >= 0 && it.level >= 2) {
            val n = (it.page + 1).toString()
            val w = ink.measure(n, T.TS_LABEL_L, 700)
            ink.text(c, n, 1301.7f - w, T.base(top + 5f, T.TS_LABEL_L, 1.25f),
                T.TS_LABEL_L, 700, if (cur) T.G1 else T.G7)
        }
        ink.rect(c, 153.6f, top + SEPARATOR_DY, 1147.8f, 1.8f, T.G11)
    }

    // ---------------------------------------------------------------- закладки

    /**
     * Вкладка «Заметки»: те же строки, что у закладок, но вид записи подписан —
     * «Цитата» или «Заметка», а под ней текст заметки (у цитаты — её выделенный текст).
     * Так человек видит, что именно он сохранил, не открывая книгу.
     */
    private fun notesPanel(c: Canvas, notes: List<LibraryDb.Mark>, page: Int) {
        ink.convex(c, PANEL_X, PANEL_Y, PANEL_W, PANEL_H_MARKS, T.R_TILE)
        val first = page * MARK_ROWS
        val last = minOf(notes.size, first + MARK_ROWS)
        for (i in first until last) note(c, notes[i], MARK_TOP + (i - first) * MARK_STEP)
        if (notes.isEmpty()) empty(c, EMPTY_NOTES, EMPTY_NOTES_HINT)
    }

    /** Строка заметки: место, вид записи и её текст (у заметки — заметка, у цитаты — выделение). */
    private fun note(c: Canvas, m: LibraryDb.Mark, top: Float) {
        val kind = if (m.kind == LibraryDb.MARK_NOTE) "Заметка" else "Цитата"
        icons.draw(c, "highlight@31.5x28", MARK_ICON_X, top + MARK_ICON_DY, 31.5f, 28f)
        ink.text(c, kind, MARK_TEXT_X, T.base(top, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
        // Место — сразу за подписью вида, а не в колонке даты: у закладок там дата, а у заметки
        // дата не нужна — важнее, где она стоит. Ширина ограничена до крестика.
        val where = ink.ellipsize(place(m), NOTE_PLACE_W, T.TS_META, 600)
        ink.text(c, where, MARK_TEXT_X + NOTE_KIND_W, T.base(top, T.TS_META, 1.25f),
            T.TS_META, 600, T.G5)
        circle(c, DEL_X, top + DEL_DY, T.G14, T.G15, T.G10, DEL_D)
        val ix = DEL_X + T.SURFACE_DX + (DEL_D - DEL_ICON_W) / 2f
        val iy = top + DEL_DY + T.SURFACE_DY + (DEL_D - DEL_ICON_H) / 2f
        icons.draw(c, "close", ix, iy, DEL_ICON_W, DEL_ICON_H)
        val body = (m.note ?: m.text.orEmpty()).replace('\u00A0', ' ')
            .replace(NEWLINE, ". ").replace(WS, " ").trim()
        if (body.isEmpty()) {
            ink.rect(c, MARK_BAR_X, top + MARK_SEP_DY, MARK_SEP_W, 2f, T.G11)
            return
        }
        val lit = T.literata(ink.res, 400)
        ink.paragraph(c, body, QUOTE_X, top + QUOTE_DY, QUOTE_W, T.TS_LABEL_L, 400,
            T.G1, 0f, lit, QUOTE_LINES)
        ink.rect(c, MARK_BAR_X, top + MARK_SEP_DY, MARK_SEP_W, 2f, T.G11)
    }

    private fun marksPanel(c: Canvas, marks: List<LibraryDb.Mark>, page: Int) {
        ink.convex(c, PANEL_X, PANEL_Y, PANEL_W, PANEL_H_MARKS, T.R_TILE)
        val first = page * MARK_ROWS
        val last = minOf(marks.size, first + MARK_ROWS)
        for (i in first until last) mark(c, marks[i], MARK_TOP + (i - first) * MARK_STEP)
        if (marks.isEmpty()) empty(c, EMPTY_MARKS, EMPTY_MARKS_HINT)
    }

    /**
     * Строка закладки: где она (страница на момент постановки и глава), когда поставлена,
     * и первые слова страницы — снимок из R4.1, чтобы список читался без открытия книги.
     *
     * Номер страницы может разойтись с фактическим, если кегль меняли: переход идёт по `pos`,
     * а не по номеру, поэтому «Стр. N» здесь — справка, а не адрес.
     */
    private fun mark(c: Canvas, m: LibraryDb.Mark, top: Float) {
        icons.draw(c, "highlight@31.5x28", MARK_ICON_X, top + MARK_ICON_DY, 31.5f, 28f)
        ink.text(c, ink.ellipsize(place(m), PLACE_W, T.TS_META, 600), MARK_TEXT_X,
            T.base(top, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
        val d = date(m.created)
        ink.text(c, d, DATE_RIGHT - ink.measure(d, T.TS_META, 600),
            T.base(top, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
        // Крестик справа от даты. Корзину пользователь забраковал 2026-11-16: «очень мелкая,
        // не читается» — у неё тонкие линии и много деталей. Взята иконка `close` (штрих 3.4,
        // масштабируется вместе с рамкой) и клавиша Ø68 вместо Ø64, как и принято для круглых
        // клавиш экрана. Вида в макете нет (вопрос дизайну).
        // Иконка центрируется по ПОВЕРХНОСТИ кружка (+2.8,+2.8), а не по блику: в макете у всех
        // круглых клавиш иконка сдвинута вместе с поверхностью (B9: блик 41.1, иконка 73.1 =
        // 41.1 + 2.8 + (102.4 − 43.9)/2). По блику она уезжает влево-вверх — поймано на книге.
        // Тап по всей правой полосе строки — зона касания крупнее клавиши (E-Ink).
        circle(c, DEL_X, top + DEL_DY, T.G14, T.G15, T.G10, DEL_D)
        val ix = DEL_X + T.SURFACE_DX + (DEL_D - DEL_ICON_W) / 2f
        val iy = top + DEL_DY + T.SURFACE_DY + (DEL_D - DEL_ICON_H) / 2f
        icons.draw(c, "close", ix, iy, DEL_ICON_W, DEL_ICON_H)

        // Цитата — не больше двух строк: в макете строка занимает 201.1 без заметки, а под цитатой
        // ещё идёт строка заметки (B50–B52), ради которой панель B27 выше, чем на B10.
        //
        // `pageText()` отдаёт страницу целиком, а она начинается с заголовка главы: в базе текст
        // закладки — «I\n– Что, Петр, не видать еще?…». Перевод строки превращаем в точку с пробелом
        // (иначе «I» слипается с первой фразой), кратные пробелы схлопываем: в списке первая
        // строка иначе — одна буква заголовка, а цитата уезжает на вторую.
        val quote = m.text.orEmpty().replace('\u00A0', ' ')
            .replace(NEWLINE, ". ").replace(WS, " ").trim()
        if (quote.isEmpty()) {
            ink.rect(c, MARK_BAR_X, top + MARK_SEP_DY, MARK_SEP_W, 2f, T.G11)
            return
        }
        val lit = T.literata(ink.res, 400)
        val h = ink.paragraph(c, quote, QUOTE_X, top + QUOTE_DY, QUOTE_W, T.TS_LABEL_L, 400, T.G1,
            0f, lit, QUOTE_LINES)
        // Черта выделения: 8 шириной в 95, на 6 ниже верха цитаты и на 10 короче её (снимок B27).
        ink.rect(c, MARK_BAR_X, top + MARK_BAR_DY, 8f, h - 10f, T.G7)
        ink.rect(c, MARK_BAR_X, top + MARK_SEP_DY, MARK_SEP_W, 2f, T.G11)
    }

    /** «Стр. 3 · Часть 1, глава I»; без главы (DjVu, часть без заголовка) — только страница. */
    private fun place(m: LibraryDb.Mark): String {
        val ch = m.chapter.orEmpty().trim()
        return if (ch.isEmpty()) "Стр. ${m.page + 1}" else "Стр. ${m.page + 1} · $ch"
    }

    /** Дата закладки, как в макете: «28 окт». Год не выводится — список короткий и свежий. */
    private fun date(created: Long): String =
        DATE_FMT.format(Date(created)).trimEnd('.')

    /** Пустой список: в макете такого вида нет, поэтому по центру панели — заголовок и подсказка. */
    private fun empty(c: Canvas, title: String, hint: String) {
        ink.textCenter(c, title, 0f, D_W, T.base(EMPTY_Y, T.TS_PAGE, 1.25f), T.TS_PAGE, 700, T.G4)
        ink.textCenter(c, hint, 0f, D_W,
            T.base(EMPTY_Y + 70f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G5)
    }

    // ------------------------------------------------------------------ пагинация

    private fun pager(c: Canvas, page: Int, count: Int) {
        circle(c, 41.1f, 1708.3f, T.G14, T.G15, T.G10, 117f)
        icons.drawRound(c, "back@54.8x57.1", 41.1f, 1708.3f, 117f + T.SHADOW_DX,
            117f + T.SHADOW_DY, 54.8f, 57.1f)
        circle(c, 1240.4f, 1708.3f, T.G3, T.G6, T.G8, 117f)
        icons.drawRound(c, "next@53x53", 1240.4f, 1708.3f, 117f + T.SHADOW_DX,
            117f + T.SHADOW_DY, 53f, 53f)
        ink.textCenter(c, "${page + 1} / $count", 0f, D_W,
            T.base(1738.3f, T.TS_PAGE, 1.25f), T.TS_PAGE, 600, T.G1)
    }

    /**
     * Круглая клавиша: блик Ø[d] в ([x], [y]); цвета поверхностей как у `Ink.convex` по умолчанию,
     * у тёмной клавиши — G3/G6/G8 (`Ink.convexDark`).
     */
    private fun circle(c: Canvas, x: Float, y: Float, surface: Int, hi: Int, shadow: Int, d: Float) =
        ink.convex(c, x, y, d + T.SHADOW_DX, d + T.SHADOW_DY, d / 2f, surface, hi, shadow)

    // ------------------------------------------------------------------ касания

    /**
     * Касание в дизайн-px. Возвращает `ACT_*`; [ACT_NONE] — мимо, экран остаётся открытым.
     *
     * [tab] — открытая сейчас вкладка, [page] — её страница: по ним считается, куда попал палец.
     * Смена вкладки и её номер страницы — забота читалки: она же перечитывает закладки из базы.
     */
    fun hit(x: Float, y: Float, tab: Int, page: Int, items: List<TocItem>,
            marks: List<LibraryDb.Mark>, notes: List<LibraryDb.Mark>): Int {
        chosen = -1
        chosenMark = -1
        if (y in 112.9f..225.5f && x >= 41.1f && x <= 41.1f + 102.4f + T.SHADOW_DX) return ACT_CLOSE
        // Вкладки: желоб 43.9,248.6 1316.2×95.1, три равных сегмента — все нажимаются.
        if (y in TAB_HIT_Y0..TAB_HIT_Y1) {
            val i = ((x - TAB_X) / TAB_W).toInt()
            if (x < TAB_X || i !in 0 until TAB_NAMES.size) return ACT_NONE
            return if (i == tab) ACT_NONE else ACT_TAB + i
        }
        val list = if (tab == TAB_MARKS) marks else if (tab == TAB_NOTE) notes else emptyList()
        if (y in 1708.3f..1825.4f) {
            val rows = if (tab == TAB_TOC) ROWS else MARK_ROWS
            val count = if (tab == TAB_TOC) items.size else list.size
            if (x <= 170f) return if (page > 0) ACT_PREV else ACT_NONE
            if (x >= 1240.4f) return if ((page + 1) * rows < count) ACT_NEXT else ACT_NONE
            return ACT_NONE
        }
        if (tab != TAB_TOC) {
            if (y < MARK_TOP - MARK_STEP / 2f || y > MARK_TOP + MARK_STEP * MARK_ROWS) return ACT_NONE
            val i = page * MARK_ROWS + ((y - MARK_TOP + MARK_STEP / 2f) / MARK_STEP).toInt()
            if (i < 0 || i >= list.size) return ACT_NONE
            chosenMark = i
            // Правая полоса строки — корзина: удалить запись. Остальное — переход по ней.
            return if (x >= DEL_HIT_X0) ACT_DEL else ACT_MARK
        }
        if (y < ROW_TOP - ROW_STEP / 2f || y > ROW_TOP + ROW_STEP * ROWS) return ACT_NONE
        val i = page * ROWS + ((y - ROW_TOP + ROW_STEP / 2f) / ROW_STEP).toInt()
        if (i < 0 || i >= items.size) return ACT_NONE
        chosen = i
        return ACT_OPEN
    }

    companion object {
        const val ACT_NONE = 0
        const val ACT_CLOSE = 1
        const val ACT_PREV = 2
        const val ACT_NEXT = 3
        /** Выбран пункт оглавления: читалка переходит по нему и закрывает список. */
        const val ACT_OPEN = 4
        /** Выбрана закладка: читалка переходит по её `pos` и закрывает список. */
        const val ACT_MARK = 5
        /** Нажата корзина строки: читалка удаляет закладку и остаётся в списке. */
        const val ACT_DEL = 6
        /** Смена вкладки: к коду прибавляется её номер ([TAB_TOC] / [TAB_MARKS]). */
        const val ACT_TAB = 10

        /** Вкладки. «Заметки» ([TAB_NOTE]) рисуются, но не нажимаются до B50–B52. */
        const val TAB_TOC = 0
        const val TAB_MARKS = 1
        const val TAB_NOTE = 2

        /** Строк оглавления на страницу: панель 1180.1 высотой, первая строка 457.5, шаг 163.1. */
        const val ROWS = 7

        /** Закладок на страницу: первая строка 428.3, шаг 232.4 (в B27 панель выше на 100). */
        const val MARK_ROWS = 5

        /** Сколько страниц займёт список вкладки [tab]: [items] пунктов или [marks] закладок. */
        fun pageCount(tab: Int, items: Int, marks: Int, notes: Int): Int {
            val n = when (tab) {
                TAB_MARKS -> marks
                TAB_NOTE -> notes
                else -> items
            }
            val rows = if (tab == TAB_TOC) ROWS else MARK_ROWS
            return if (n <= 0) 1 else (n + rows - 1) / rows
        }

        /**
         * Подписи вкладок: число выводится только у закладок и заметок — сколько их у книги.
         * Заголовок шапки числа не берёт: в снимке B27 он «Заметки», а «Заметки · 4» стоят
         * под вкладкой.
         */
        fun tabLabels(markCount: Int, noteCount: Int): Array<String> =
            arrayOf("Оглавление", "Закладки · $markCount", "Заметки · $noteCount")

        /** Имена вкладок для заголовка шапки — без числа; индекс совпадает с [tabLabels]. */
        val TAB_NAMES = arrayOf("Оглавление", "Закладки", "Заметки")

        private const val D_W = 1404f
        private const val ROW_TOP = 457.5f
        private const val ROW_STEP = 163.1f
        /** Разделитель: 1.8 в 568.5 при строке в 457.5 — то есть +111 от верха строки. */
        private const val SEPARATOR_DY = 111f
        /** Текст пункта: от 153.6 до номера страницы (1104.3 − 153.6 − 30). */
        private const val TITLE_W = 920f
        /** Название части: от 109.7 до номера страницы (1104.3 − 109.7 − 20). */
        private const val PART_W = 975f

        // Панель: у B10 1180.1 высотой, у B27 — 1280 (место под строку заметки).
        private const val PANEL_X = 41.1f
        private const val PANEL_Y = 384.8f
        private const val PANEL_W = 1320.8f
        private const val PANEL_H_TOC = 1180.1f
        private const val PANEL_H_MARKS = 1280f

        // Строка закладки: рамка «Выделение / Стр. 3 · Часть 1, глава I» — 95,428.3 1214×201.1.
        private const val MARK_TOP = 428.3f
        private const val MARK_STEP = 232.4f
        private const val MARK_ICON_X = 100.3f
        /** Иконка ниже подписи на 5.5: она 28 высотой, а строка подписи 38. */
        private const val MARK_ICON_DY = 5.5f
        private const val MARK_TEXT_X = 155f
        /** «Стр. N · Глава» не залезает под дату (1009 − 155 − 12). */
        private const val PLACE_W = 720f
        /** Подпись вида записи на вкладке «Заметки» и место за ней (до крестика 1222). */
        private const val NOTE_KIND_W = 150f
        private const val NOTE_PLACE_W = 880f
        /** Правый край даты — 1197: правее стоит корзина (в макете дата доходила до 1309). */
        private const val DATE_RIGHT = 1197f
        /**
         * Крестик удаления строки: круглая клавиша Ø68 в 1222, на 1.4 ниже верха строки.
         * Иконка `close` — 22.1×24.3 в собственной системе, то есть штрих 3.4: на Ø68 это
         * читаемо на E-Ink (мелкая корзина 28.8×35.1 с тонкими линиями не читалась).
         */
        private const val DEL_X = 1222f
        private const val DEL_DY = 1.4f
        private const val DEL_D = 68f
        private const val DEL_ICON_W = 26f
        private const val DEL_ICON_H = 28.6f
        /** Касание по этой полосе в строке — удаление: палец на E-Ink крупнее клавиши. */
        private const val DEL_HIT_X0 = 1180f
        private const val QUOTE_X = 125f
        private const val QUOTE_W = 1180f
        /** Цитата ниже подписи на 63 (491 против 428.3); черта — на 69, то есть +6 к тексту. */
        private const val QUOTE_DY = 63f
        private const val MARK_BAR_X = 95f
        private const val MARK_BAR_DY = 69f
        private const val QUOTE_LINES = 2
        /** Разделитель строк: 627.4 против 428.3 — +199.1 от верха строки. */
        private const val MARK_SEP_DY = 199.1f
        private const val MARK_SEP_W = 1214f

        // Вкладки: подписи 34/700 и 34/600, ширина 433.9 от 51.2 с шагом 433.9.
        private const val TAB_X = 51.2f
        private const val TAB_W = 433.9f
        /** Выпуклый сегмент по вкладке [tab]: 48.4,253.2 438.4×90.5 у первой (снимок B10). */
        private const val SEG_X = 48.4f
        private const val SEG_W = 438.4f
        /** Зона касания вкладок — весь желоб с запасом по 6.6 сверху и снизу. */
        private const val TAB_HIT_Y0 = 242f
        private const val TAB_HIT_Y1 = 350f

        // Пустой список: в макете такого вида нет — заголовок и подсказка по центру панели.
        private const val EMPTY_Y = 990f
        private const val EMPTY_TOC = "Оглавления в книге нет"
        private const val EMPTY_TOC_HINT = "Переход по книге — трети экрана и листание"
        private const val EMPTY_MARKS = "Закладок пока нет"
        private const val EMPTY_MARKS_HINT = "Закладка ставится кнопкой в меню чтения"
        private const val EMPTY_NOTES = "Заметок пока нет"
        private const val EMPTY_NOTES_HINT = "Заметка и цитата — долгим нажатием на слово"

        private val DATE_FMT = SimpleDateFormat("d MMM", Locale("ru"))

        /** Переводы строк и кратные пробелы в цитате: в базе текст страницы с `\n` заголовка главы. */
        private val NEWLINE = Regex("\\s*\\n\\s*")
        private val WS = Regex("\\s+")

        private const val HERE_TEXT = "Вы здесь"
    }
}
