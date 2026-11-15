package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B29 · «Переход на страницу» — нижний лист с номером, ползунком позиции и цифровой
 * клавиатурой (R3.3). Числа — `design/v5/B29.json`.
 *
 *   лист      выпуклый −3,500 1409×1502 r 66 (тень G10 +5,+10, блик G15, поверхность G14);
 *             захват 644,525 117×11 G11 r 5; заголовок «Переход на страницу» 59,560 60/700 G1
 *   поле      вдавленное 56,670 600×160 (дно G12 r 45); номер 110,691.4 96/700 G1,
 *             «из 350» 38/600 G4 следом за номером (в макете 287,747.2 — там «124»)
 *   глава     710,700 42/700 G1 («Часть 2, глава V»); позиция 710,756 32/600 G4
 *             («35% книги · вы были на стр. 98»)
 *   ползунок  желоб вдавленный 56,898 1292×37 r 18 (дно G12 1287×29), заполнение G3 450.1×28
 *             от 59,904, ручка Ø88 (блик 463.1,870) на правом краю заполнения — как шкала B9
 *   клавиши   419.7×110 в 56 / 494.7 / 933.3, ряды 1010 / 1136 / 1262 / 1388; цифры 48/700,
 *             «Начало главы» 34/700, «Стереть» — иконка `back` 11.5×24.9 в 259.6,1429.5
 *   кнопки    «Отмена» выпуклая 56,1708 485×112 (подпись 38/700); «Перейти» тёмная
 *             576,1693 777×142 (подпись 42/700 G15) — как главная кнопка B45
 *
 * Страница под листом приглушена: в макете её текст и колонтитул G10 вместо G0/G4. Затемнения
 * нет — экран рисует страницу светлее ([ReaderScreen.pagePaintDim]), как B31 без фильтра.
 *
 * Ползунок **не перетаскивается** (правила E-Ink: `ACTION_MOVE` отменяет нажатие): касание
 * по шкале ставит номер в поле, а переход — кнопкой «Перейти». «Начало главы» тоже только
 * заполняет поле — лист остаётся «набрал номер → перейти» (вопрос в `docs/DESIGN_QUESTIONS.md`).
 *
 * Состояние ввода (набранные цифры) живёт здесь, как раскладка в `Keyboard`: экран берёт
 * готовую страницу через [page] и сам решает, когда переходить. Число страниц, страницу
 * «были на», название главы и начало главы экран передаёт в [draw] и [hit] — своих копий
 * у листа нет.
 */
class GotoSheet(private val ink: Ink, private val icons: Icons) {

    /** Набранные цифры как есть; пусто — номера нет. */
    private var input = ""

    /** Поле только открыли: первая цифра заменяет номер целиком, а не дописывается к нему. */
    private var fresh = true

    /** Открыть лист на странице [page] (1-based): поле показывает её номер. */
    fun open(page: Int) {
        input = if (page <= 0) "" else page.toString()
        fresh = true
    }

    /**
     * Страница по вводу (0-based). −1 — поле пустое или в книге нет страниц; номер больше
     * последней страницы ограничивается последней.
     */
    fun page(pages: Int): Int {
        if (pages <= 0) return -1
        val n = input.toIntOrNull() ?: return -1
        return (n - 1).coerceIn(0, pages - 1)
    }

    /**
     * Нарисовать лист. [page] — страница по вводу (0-based, −1 — поле пустое), [prev] —
     * с какой страницы открыли (1-based). Номер в поле рисуется как набран, без округления:
     * клавиши должны давать ровно то, что человек нажал.
     */
    fun draw(c: Canvas, pages: Int, page: Int, prev: Int, chapter: String, pct: Int) {
        sheet(c)
        field(c, pages)
        place(c, page, prev, chapter, pct)
        slider(c, page, pages)
        keypad(c)
        buttons(c)
    }

    // ------------------------------------------------------------------ лист

    private fun sheet(c: Canvas) {
        ink.convex(c, SHEET_X, SHEET_Y, SHEET_W, SHEET_H, R_SHEET)
        ink.roundRect(c, GRIP_X, GRIP_Y, GRIP_W, GRIP_H, R_GRIP, T.G11)
        ink.text(c, ink.ellipsize(TITLE, 1200f, T.TS_TITLE, 700), TITLE_X,
            T.base(TITLE_TOP, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
    }

    /** Поле номера: вдавленное гнездо, номер слева и «из N» сразу за ним. */
    private fun field(c: Canvas, pages: Int) {
        ink.inset(c, FIELD_X, FIELD_Y, FIELD_W, FIELD_H, R_FIELD, T.G12)
        var x = NUM_X
        if (input.isNotEmpty()) {
            ink.text(c, input, NUM_X, T.base(NUM_TOP, TS_NUM, 1.25f), TS_NUM, 700, T.G1)
            x += ink.measure(input, TS_NUM, 700) + NUM_GAP
        }
        if (pages > 0) {
            ink.text(c, "из $pages", Math.max(TOTAL_X, x),
                T.base(TOTAL_TOP, TS_TOTAL, 1.25f), TS_TOTAL, 600, T.G4)
        }
    }

    /** Глава и позиция справа от поля; макета пустого поля нет — тогда строки не рисуем. */
    private fun place(c: Canvas, page: Int, prev: Int, chapter: String, pct: Int) {
        if (page < 0) return
        if (chapter.isNotEmpty()) {
            ink.text(c, ink.ellipsize(chapter, PLACE_W, TS_CHAPTER, 700), PLACE_X,
                T.base(CHAPTER_TOP, TS_CHAPTER, 1.25f), TS_CHAPTER, 700, T.G1)
        }
        val hint = "$pct% книги · вы были на стр. $prev"
        ink.text(c, ink.ellipsize(hint, PLACE_W, T.TS_BODY_S, 600), PLACE_X,
            T.base(HINT_TOP, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    // ------------------------------------------------------------------ ползунок

    /** Желоб позиции: вдавленный трек, заполнение G3 и ручка на правом краю заполнения. */
    private fun slider(c: Canvas, page: Int, pages: Int) {
        ink.inset(c, TRACK_X, TRACK_Y, TRACK_W, TRACK_H, R_TRACK, T.G12)
        val pct = if (pages <= 0 || page < 0) 0f else (page + 1).toFloat() / pages
        val fill = (INNER_W * pct).coerceIn(0f, INNER_W)
        if (fill > 0f) {
            ink.roundRect(c, INNER_X, INNER_Y, fill, INNER_H, R_FILL, T.G3)
        }
        val hx = (INNER_X + fill - HANDLE_D / 2f).coerceIn(INNER_X, TRACK_X + TRACK_W - HANDLE_D)
        ink.convex(c, hx, HANDLE_Y, HANDLE_D + T.SHADOW_DX, HANDLE_D + T.SHADOW_DY,
            HANDLE_D / 2f)
    }

    // ------------------------------------------------------------------ клавиши

    /** Сетка 3×4: 1…9, «Стереть», 0, «Начало главы». */
    private fun keypad(c: Canvas) {
        for (i in 0 until KEYS) {
            val x = KEY_X[i % 3]
            val y = KEY_Y[i / 3]
            ink.convex(c, x, y, KEY_W, KEY_H, R_KEY)
            when {
                i < 9 -> label(c, x, y, (i + 1).toString(), TS_DIGIT, 700)
                i == 9 -> icons.draw(c, ERASE_ICON, x + ERASE_DX, y + ERASE_DY,
                    ERASE_W, ERASE_H)
                i == 10 -> label(c, x, y, "0", TS_DIGIT, 700)
                else -> label(c, x, y, CHAPTER_KEY, TS_CHAPTER_KEY, 700)
            }
        }
    }

    /** Подпись клавиши по центру поверхности — как в `Keyboard` (не по рамке макета). */
    private fun label(c: Canvas, x: Float, y: Float, s: String, size: Float, weight: Int) {
        ink.textCenter(c, s, x + T.SURFACE_DX, KEY_W - T.SHADOW_DX,
            ink.baselineIn(y + T.SURFACE_DY, KEY_H - T.SHADOW_DY, size, weight),
            size, weight, T.G1)
    }

    private fun buttons(c: Canvas) {
        ink.convex(c, CANCEL_X, CANCEL_Y, CANCEL_W, CANCEL_H, R_CANCEL)
        ink.textCenter(c, CANCEL, CANCEL_X + T.SURFACE_DX, CANCEL_W - T.SHADOW_DX,
            T.base(CANCEL_TOP, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        ink.convexDark(c, GO_X, GO_Y, GO_W, GO_H, T.R_BUTTON)
        ink.textCenter(c, GO, GO_X + T.SURFACE_DX, GO_W - T.SHADOW_DX,
            T.base(GO_TOP, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G15)
    }

    // ------------------------------------------------------------------ касания

    /**
     * Касание в дизайн-px. [pages] — сколько страниц в книге, [chapterStart] — первая
     * страница текущей главы (−1 — главы нет, клавиша не работает).
     * Возвращает `ACT_*`; [ACT_NONE] — мимо, лист остаётся открытым.
     */
    fun hit(x: Float, y: Float, pages: Int, chapterStart: Int): Int {
        if (y < SHEET_Y) return ACT_CLOSE          // выше листа — как касание мимо меню
        if (inRect(x, y, CANCEL_X, CANCEL_Y, CANCEL_W, CANCEL_H)) return ACT_CLOSE
        if (inRect(x, y, GO_X, GO_Y, GO_W, GO_H)) return ACT_GO
        // Шкала: касание ставит номер в поле (перетаскивания нет — правила E-Ink).
        if (y >= TRACK_PAD_Y0 && y <= TRACK_PAD_Y1 && x >= TRACK_X && x <= TRACK_X + TRACK_W) {
            setByX(x, pages)
            return ACT_REPAINT
        }
        for (i in 0 until KEYS) {
            val kx = KEY_X[i % 3]
            val ky = KEY_Y[i / 3]
            if (inRect(x, y, kx - HIT_PAD, ky - HIT_PAD,
                    KEY_W + 2f * HIT_PAD, KEY_H + 2f * HIT_PAD)) {
                return key(i, pages, chapterStart)
            }
        }
        return ACT_NONE
    }

    /** Нажата клавиша [i]: 1…9 и 0 добавляют цифру, «Стереть» убирает, «Начало главы» — номер. */
    private fun key(i: Int, pages: Int, chapterStart: Int): Int = when {
        i < 9 -> append((i + 1).toString(), pages)
        i == 9 -> if (input.isEmpty()) ACT_NONE else {
            input = input.substring(0, input.length - 1)
            fresh = false
            ACT_REPAINT
        }
        i == 10 -> append("0", pages)
        chapterStart < 0 || chapterStart >= pages -> ACT_NONE
        else -> {
            input = (chapterStart + 1).toString()
            fresh = false
            ACT_REPAINT
        }
    }

    /** Добавить цифру, если номер не превысил число страниц; ведущие нули убираются. */
    private fun append(d: String, pages: Int): Int {
        if (fresh) {
            // Первая цифра после открытия листа начинает номер заново; больше книги не бывает.
            fresh = false
            input = if (pages > 0 && d.toInt() > pages) pages.toString() else d
            return ACT_REPAINT
        }
        val max = if (pages <= 0) 1 else pages.toString().length
        if (input.length >= max) return ACT_NONE
        val s = (input + d).trimStart('0')
        val n = if (s.isEmpty()) 0 else s.toIntOrNull() ?: return ACT_NONE
        // Номера больше последней страницы нет: клавиша просто не добавляет цифру.
        if (pages > 0 && n > pages) return ACT_NONE
        input = if (s.isEmpty()) "0" else s
        return ACT_REPAINT
    }

    /** Номер по касанию шкалы: доля ширины желоба → страница. */
    private fun setByX(x: Float, pages: Int) {
        if (pages <= 0) return
        val pct = ((x - INNER_X) / INNER_W).coerceIn(0f, 1f)
        input = Math.round(pct * pages).coerceIn(1, pages).toString()
        fresh = false
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    companion object {
        const val ACT_NONE = 0
        /** Закрыть лист: «Отмена» или касание выше него. */
        const val ACT_CLOSE = 1
        /** Ввод изменился (цифра, «Стереть», «Начало главы», шкала) — перерисовать. */
        const val ACT_REPAINT = 2
        /** «Перейти»: экран берёт [page] и листает книгу. */
        const val ACT_GO = 3

        private const val TITLE = "Переход на страницу"
        private const val CANCEL = "Отмена"
        private const val GO = "Перейти"
        private const val CHAPTER_KEY = "Начало главы"

        // Лист и заголовок: группа −3,500 1409×1502, блик 1404×1492 r 66.
        private const val SHEET_X = -3f
        private const val SHEET_Y = 500f
        private const val SHEET_W = 1409f
        private const val SHEET_H = 1502f
        private const val R_SHEET = 66f
        private const val GRIP_X = 644f
        private const val GRIP_Y = 525f
        private const val GRIP_W = 117f
        private const val GRIP_H = 11f
        private const val R_GRIP = 5f
        private const val TITLE_X = 59f
        private const val TITLE_TOP = 560f

        // Поле номера: гнездо 56,670 600×160 (дно 595×152 r 45); номер 110,691.4 96/700.
        private const val FIELD_X = 56f
        private const val FIELD_Y = 670f
        private const val FIELD_W = 600f
        private const val FIELD_H = 160f
        private const val R_FIELD = 48f
        private const val NUM_X = 110f
        private const val NUM_TOP = 691.4f
        private const val TS_NUM = 96f
        /** «из 350» — 38/600; в макете 287 (после «124» шириной 157), дальше — за номером. */
        private const val TOTAL_X = 287f
        private const val NUM_GAP = 20f
        private const val TOTAL_TOP = 747.2f
        private const val TS_TOTAL = 38f

        // Глава и позиция справа от поля: 710 от 700 и 756.
        private const val PLACE_X = 710f
        /** До правого края контента 1360 (поле экрана 44). */
        private const val PLACE_W = 650f
        private const val CHAPTER_TOP = 700f
        private const val TS_CHAPTER = 42f
        private const val HINT_TOP = 756f

        // Шкала: желоб 56,898 1292×37 (дно 1287×29 от 59,903), заполнение 450.1×28 G3.
        private const val TRACK_X = 56f
        private const val TRACK_Y = 898f
        private const val TRACK_W = 1292f
        private const val TRACK_H = 37f
        private const val R_TRACK = 18f
        private const val INNER_X = TRACK_X + T.INSET_DX
        private const val INNER_Y = TRACK_Y + T.INSET_DY
        private const val INNER_W = 1287f
        private const val INNER_H = 29f
        private const val R_FILL = 14f
        private const val HANDLE_Y = 870f
        private const val HANDLE_D = 88f
        /** Зона касания шкалы по высоте: ручка Ø88 выходит за желоб (870…958). */
        private const val TRACK_PAD_Y0 = 866f
        private const val TRACK_PAD_Y1 = 960f

        // Клавиши: 419.7×110 в 56 / 494.7 / 933.3, ряды 1010 / 1136 / 1262 / 1388.
        private val KEY_X = floatArrayOf(56f, 494.7f, 933.3f)
        private val KEY_Y = floatArrayOf(1010f, 1136f, 1262f, 1388f)
        private const val KEYS = 12
        private const val KEY_W = 419.7f
        private const val KEY_H = 110f
        private const val R_KEY = 50f
        private const val TS_DIGIT = 48f
        private const val TS_CHAPTER_KEY = 34f
        /** «Стереть» — иконка `back` 11.5×24.9 в 259.6,1429.5 (клавиша 56,1388). */
        private const val ERASE_ICON = "back@11.5x24.9"
        private const val ERASE_DX = 203.6f
        private const val ERASE_DY = 41.5f
        private const val ERASE_W = 11.5f
        private const val ERASE_H = 24.9f
        /** Запас к зоне касания клавиши: палец на E-Ink крупнее (как B9, B31). Зазор между
         *  клавишами — 19 px, поэтому запас со всех сторон одинаковый и зоны не перекрываются. */
        private const val HIT_PAD = 8f

        // Кнопки: «Отмена» 56,1708 485×112 (r 51, подпись 38/700 в 1738.2),
        // «Перейти» 576,1693 777×142 (r 66, подпись 42/700 G15 в 1735.7).
        private const val CANCEL_X = 56f
        private const val CANCEL_Y = 1708f
        private const val CANCEL_W = 485f
        private const val CANCEL_H = 112f
        private const val R_CANCEL = 51f
        private const val CANCEL_TOP = 1738.2f
        private const val GO_X = 576f
        private const val GO_Y = 1693f
        private const val GO_W = 777f
        private const val GO_H = 142f
        private const val GO_TOP = 1735.7f
    }
}

