package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B10 · «Оглавление» — список глав поверх страницы (R3.2). Числа — `design/v5/B10.json`.
 *
 *   шапка      «Назад» блик Ø102.4 в 41.1,112.9 (иконка 73.1,144.9 43.9×45.7); заголовок 175.5,105
 *              60/700; подзаголовок 175.5,181 32/600 G4 — «Анна Каренина · Лев Толстой»
 *   вкладки    желоб 43.9,248.6 1316.2×95.1 (вдавленный, дно G11 r 44.8); активный сегмент
 *              48.4,253.2 438.4×90.5 (выпуклый); подписи 34 в 51.2 / 485.1 / 918.9, ширина 433.9
 *   панель     41.1,384.8 1320.8×1180.1 r 51.2 (тень G10, блик G15, поверхность G14)
 *   строка     первая 457.5, шаг 163.1 — 7 на страницу; текст 44 Literata от 153.6, номер 38/700
 *              справа до 1301.7, разделитель G11 1.8 в 153.6 шириной 1147.8; текущая глава —
 *              вдавленный желоб 65.8,−22.6 1272.4×153.6 с дном G12, 700 G1 и «Вы здесь» G4
 *   «часть»    верхний уровень: подпись 32/800 КАПСОМ G4 в 109.7 и иконка `check` 36.6×38.4
 *   пагинация  «1 / 6» 44/600 по центру y 1738.3; клавиши блик Ø117 в 41.1 и 1240.4, y 1708.3
 *
 * Вкладки «Закладки» и «Заметки» нарисованы по макету, но не нажимаются: их списки — R4 (B27),
 * показывать пока нечего (вопрос в `docs/DESIGN_QUESTIONS.md`).
 *
 * Экран встаёт поверх B3 внутри [ReaderScreen], а не отдельным экраном стека: при detach читалка
 * закрывает документ движка (`ReaderScreen.onDetachedFromWindow`), и книга открывалась бы заново
 * на каждой двери в оглавление. Список берётся из `Engine.toc()` один раз при открытии.
 *
 * Состояние (страница списка) держит экран, сюда передаётся в [draw].
 */
class TocScreen(private val ink: Ink, private val icons: Icons) {

    /** Пункт, по которому коснулись в последнем [hit] (−1 — никто). */
    var chosen = -1
        private set

    /** Нарисовать оглавление. [here] — индекс текущего пункта (−1 — нет). */
    fun draw(c: Canvas, title: String, sub: String, items: List<TocItem>, page: Int, here: Int) {
        header(c, title, sub)
        tabs(c)
        panel(c, items, page, here)
        pager(c, page, pageCount(items))
    }

    // ------------------------------------------------------------------ шапка

    private fun header(c: Canvas, title: String, sub: String) {
        circle(c, 41.1f, 112.9f, T.G14, T.G15, T.G10, 102.4f)
        icons.draw(c, "back@43.9x45.7", 73.1f, 144.9f, 43.9f, 45.7f)
        ink.text(c, ink.ellipsize(title, 900f, T.TS_TITLE, 700), 175.5f,
            T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, ink.ellipsize(sub, 1130f, T.TS_BODY_S, 600), 175.5f,
            T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Вкладки: вдавленный желоб и выпуклый активный сегмент. «Закладки» и «Заметки» — R4. */
    private fun tabs(c: Canvas) {
        ink.inset(c, 43.9f, 248.6f, 1316.2f, 95.1f, 47.5312f)
        ink.convex(c, 48.4f, 253.2f, 438.4f, 90.5f, 40.2188f)
        for (i in TAB_LABEL.indices) {
            ink.textCenter(c, TAB_LABEL[i], TAB_X + TAB_W * i, TAB_W,
                T.base(274.3f, T.TS_BODY, 1.25f), T.TS_BODY, if (i == 0) 700 else 600,
                if (i == 0) T.G1 else T.G4)
        }
    }

    // ------------------------------------------------------------------ список

    private fun panel(c: Canvas, items: List<TocItem>, page: Int, here: Int) {
        ink.convex(c, 41.1f, 384.8f, 1320.8f, 1180.1f, T.R_TILE)
        val first = page * ROWS
        val last = minOf(items.size, first + ROWS)
        for (i in first until last) row(c, items[i], i, ROW_TOP + (i - first) * ROW_STEP, here)
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
            val s = ink.ellipsize(it.title.uppercase(java.util.Locale.ROOT), PART_W, T.TS_BODY_S, 800)
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

    // ------------------------------------------------------------------ пагинация

    private fun pager(c: Canvas, page: Int, count: Int) {
        circle(c, 41.1f, 1708.3f, T.G14, T.G15, T.G10, 117f)
        icons.draw(c, "back@54.8x57.1", 75f, 1743.2f, 54.8f, 57.1f)
        circle(c, 1240.4f, 1708.3f, T.G3, T.G6, T.G8, 117f)
        icons.draw(c, "next@53x53", 1275.1f, 1743f, 53f, 53f)
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
     * Вкладки «Закладки» и «Заметки» не нажимаются — их списков ещё нет (R4).
     */
    fun hit(x: Float, y: Float, page: Int, items: List<TocItem>): Int {
        chosen = -1
        if (y in 112.9f..225.5f && x >= 41.1f && x <= 41.1f + 102.4f + T.SHADOW_DX) return ACT_CLOSE
        if (y in 1708.3f..1825.4f) {
            if (x <= 170f) return if (page > 0) ACT_PREV else ACT_NONE
            if (x >= 1240.4f) return if ((page + 1) * ROWS < items.size) ACT_NEXT else ACT_NONE
            return ACT_NONE
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
        /** Выбран пункт: читалка переходит по нему и закрывает список. */
        const val ACT_OPEN = 4

        /** Строк на страницу: панель 1180.1 высотой, первая строка 457.5, шаг 163.1. */
        const val ROWS = 7

        /** Сколько страниц займёт список пунктов. */
        fun pageCount(items: List<TocItem>): Int =
            if (items.isEmpty()) 1 else (items.size + ROWS - 1) / ROWS

        private const val D_W = 1404f
        private const val ROW_TOP = 457.5f
        private const val ROW_STEP = 163.1f
        /** Разделитель: 1.8 в 568.5 при строке в 457.5 — то есть +111 от верха строки. */
        private const val SEPARATOR_DY = 111f
        /** Текст пункта: от 153.6 до номера страницы (1104.3 − 153.6 − 30). */
        private const val TITLE_W = 920f
        /** Название части: от 109.7 до номера страницы (1104.3 − 109.7 − 20). */
        private const val PART_W = 975f

        // Вкладки: подписи 34/700 и 34/600, ширина 433.9 от 51.2 с шагом 433.9.
        private const val TAB_X = 51.2f
        private const val TAB_W = 433.9f
        private val TAB_LABEL = arrayOf("Оглавление", "Закладки", "Заметки")

        private const val HERE_TEXT = "Вы здесь"
    }
}
