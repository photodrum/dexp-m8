package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B9 · «Меню чтения» — две панели поверх страницы книги (R3.1). Числа — `design/v5/B09.json`.
 *
 *   верх    полоса −10.1,−10.1 1423.2×222.1: тень G10, блик G15, поверхность G14
 *           клавиши Ø102.4 в 41.1 / 1138 / 1255, y 44.8; иконки 49.4×51.4
 *           название 175.5,45.7 Literata 44/600 G1; автор и глава 175.5,100.5 Manrope 32/600 G4
 *   низ     полоса −10.1,1423.2 1423.2×463.4 (те же слои, сдвиг +1433.3)
 *           клавиши Ø87.7 в 41.1 и 1269.6, y 1474.4; стрелки 42×43.9
 *           шкала 160.9,1506.4 1082.3×29.2, заполнение G3 381.2×21, ручка Ø73.1
 *           «Стр. 124 из 350 · Глава I · 35%» 34/600 G4 по центру, y 1588.2
 *           ряд 234×169 от 43.9 шагом 263.2: круг Ø117 в y 1664.5, подпись 32/600 в 1793.5
 *
 * Панели выпуклые, как остальной пластик: слои макета — это ровно `Ink.convex` (тень G10
 * +4.6,+10.1, блик G15, поверхность G14 +2.8,+2.8). Радиуса у полос нет — они уходят за край
 * экрана на 10.1 с каждой стороны, и тень не обрывается на границе.
 *
 * Затемнения под меню нет: страница остаётся как есть, панели просто лежат поверх — так же,
 * как лист B31 и шторка B4 (`docs/READER_SPEC.md`, «листы и диалоги без затемнения»).
 *
 * Кнопки, чьих экранов ещё нет, нарисованы по макету, но касание не ловят —
 * вопрос в `DESIGN_QUESTIONS.md`. Мёртвых кнопок в меню больше нет: «Ещё» (⋮) в шапке и
 * «Контраст» в нижнем ряду убраны (решение пользователя 2026-11-21) — под ними не было ни
 * экрана, ни действия, а контраст экрана давно живёт в шторке B5 и на B37.
 * Живые: «Назад» (закрыть книгу и вернуться туда, откуда её открыли — замечание 2026-11-16),
 * «Закладка» (поставить закладку на текущей странице, R4.1; решение 2026-11-16 — только ставит,
 * снимается крестиком в списке B27), листание (меню при этом НЕ закрывается — замечание
 * 2026-11-16: им листают несколько страниц подряд), Оглавление
 * (B10) и «Текст» — у crengine лист B4 «Типографика», у DjVu «Масштаб» (лист B31, решение
 * пользователя 2026-11-15): у скана нет кегля. «Вслух» — чтение системным TTS (R6): кнопка
 * вдавлена, пока идёт чтение; у DjVu она мертва — текстового слоя у скана нет.
 *
 * Состояние держит экран ([ReaderScreen]): меню получает надписи и значения в [draw], что нажали —
 * возвращает кодом действия. Своих копий у него нет.
 */
class ReaderMenu(private val ink: Ink, private val icons: Icons) {

    /**
     * Нарисовать обе панели поверх страницы. [marked] — на текущей странице есть закладка
     * (R4.1): клавиша «Закладка» в шапке тогда вдавлена, как выбранный вариант на B39.
     */
    fun draw(c: Canvas, title: String, sub: String, place: String,
             pages: Int, cur: Int, djvu: Boolean, marked: Boolean, speaking: Boolean) {
        top(c, title, sub, marked)
        bottom(c, place, pages, cur, djvu, speaking)
    }

    // ------------------------------------------------------------------ верх

    private fun top(c: Canvas, title: String, sub: String, marked: Boolean) {
        ink.convex(c, PANEL_X, PANEL_Y, PANEL_W, TOP_H, 0f)
        for (i in 0 until KEY_TOP_X.size) {
            // Вида «закладка поставлена» в макете нет: показываем вдавленной клавишей, как
            // выбранный вариант зон касаний на B39. Иконка одна и та же (`bookmark`). Кнопка —
            // переключатель (решение 2026-11-21): вдавленность значит «на этой странице закладка
            // есть», повторное нажатие её снимает.
            if (i == KEY_MARK && marked) {
                ink.inset(c, KEY_TOP_X[i], KEY_TOP_Y, TOP_KEY_D, TOP_KEY_D, TOP_KEY_D / 2f)
                icons.drawInset(c, ICON_TOP[i], KEY_TOP_X[i], KEY_TOP_Y, TOP_KEY_D, TOP_KEY_D,
                    TOP_ICON_W, TOP_ICON_H)
            } else {
                circle(c, KEY_TOP_X[i], KEY_TOP_Y, TOP_KEY_D)
                icons.drawRound(c, ICON_TOP[i], KEY_TOP_X[i], KEY_TOP_Y,
                    TOP_KEY_D + T.SHADOW_DX, TOP_KEY_D + T.SHADOW_DY, TOP_ICON_W, TOP_ICON_H)
            }
        }
        val lf = T.literata(ink.res, 600)
        val t = ink.ellipsize(title, TITLE_W, T.TS_PAGE, 600, lf)
        ink.text(c, t, TITLE_X, T.base(45.7f, T.TS_PAGE, 1.25f, true), T.TS_PAGE, 600, T.G1, lf)
        val s = ink.ellipsize(sub, SUB_W, T.TS_BODY_S, 600)
        ink.text(c, s, TITLE_X, T.base(100.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    // ------------------------------------------------------------------ низ

    private fun bottom(c: Canvas, place: String, pages: Int, cur: Int, djvu: Boolean,
                       speaking: Boolean) {
        ink.convex(c, PANEL_X, BOT_Y, PANEL_W, BOT_H, 0f)
        circle(c, 41.1f, 1474.4f, 87.7f)
        icons.draw(c, "prev", 66.7f, 1500.9f, 42f, 43.9f)
        circle(c, 1269.6f, 1474.4f, 87.7f)
        icons.draw(c, "nxt", 1295.2f, 1500.9f, 42f, 43.9f)
        scale(c, pages, cur)
        val p = ink.ellipsize(place, PLACE_W, T.TS_BODY, 600)
        ink.textCenter(c, p, PLACE_X, PLACE_W, T.base(1588.2f, T.TS_BODY, 1.25f),
            T.TS_BODY, 600, T.G4)
        for (i in 0 until BTN_COUNT) button(c, i, djvu, speaking)
    }

    /**
     * Шкала прогресса: желоб вдавленный (G15 / G10 / дно G12 r 14.6), заполнение G3 высотой 21
     * и ручка Ø73.1 на его правом краю. Значение берётся по странице на экране, как трек B3, —
     * ручка показывает, а не перетаскивается: касание шкалы (и подписи под ней) открывает
     * B29 «Переход на страницу», где номер набирается клавишами (R3.3).
     */
    private fun scale(c: Canvas, pages: Int, cur: Int) {
        ink.inset(c, SCALE_X, SCALE_Y, SCALE_W, SCALE_H, T.R_TRACK, T.G12)
        val pct = if (pages <= 0) 0f else (cur + 1).toFloat() / pages
        val fill = (SCALE_INNER_W * pct).coerceIn(0f, SCALE_INNER_W)
        if (fill > 0f) {
            ink.roundRect(c, SCALE_X + T.INSET_DX, SCALE_Y + T.INSET_DY, fill, SCALE_INNER_H,
                10.51171875f, T.G3)
        }
        val hx = (SCALE_X + T.INSET_DX + fill - HANDLE_D / 2f)
            .coerceIn(SCALE_X + T.INSET_DX, SCALE_X + SCALE_W - HANDLE_D)
        circle(c, hx, HANDLE_Y, HANDLE_D)
    }

    /**
     * Кнопка нижнего ряда [i]: круг Ø117 и подпись под ним; у DjVu «Текст» — «Масштаб».
     *
     * «Вслух» ([BTN_SPEAK]) во время чтения рисуется вдавленной, как «Закладка» в шапке:
     * вида «читает» в макете нет, а на E-Ink вдавленность — уже знакомый признак включённого
     * состояния (B39, B27). Иконка и подпись те же.
     */
    private fun button(c: Canvas, i: Int, djvu: Boolean, speaking: Boolean) {
        val zoom = djvu && i == BTN_TEXT
        val on = speaking && i == BTN_SPEAK
        if (on) {
            ink.inset(c, BTN_SEAT_X[i], BTN_SEAT_Y, 117f, 117f, 117f / 2f)
            icons.drawInset(c, BTN_ICON[i], BTN_SEAT_X[i], BTN_SEAT_Y, 117f, 117f,
                BTN_ICON_W, BTN_ICON_H)
        } else {
            circle(c, BTN_SEAT_X[i], BTN_SEAT_Y, 117f)
            icons.drawCentered(c, if (zoom) ICON_ZOOM else BTN_ICON[i],
                BTN_SEAT_X[i] + (117f + T.SURFACE_DX) / 2f,
                BTN_SEAT_Y + (117f + T.SURFACE_DY) / 2f, BTN_ICON_W, BTN_ICON_H)
        }
        val label = if (zoom) BTN_ZOOM else BTN_LABEL[i]
        ink.textCenter(c, label, BTN_X + BTN_STEP * i, BTN_W,
            T.base(1793.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G1)
    }

    /**
     * Круглая клавиша: блик Ø[d] в ([x], [y]) — это слой «Блик» макета; тень уходит на
     * +4.6/+10.1, поверхность ложится на +2.8, поэтому `Ink.convex` получает габарит
     * Ø[d]+тень и те же слои, что в снимке.
     */
    private fun circle(c: Canvas, x: Float, y: Float, d: Float) =
        ink.convex(c, x, y, d + T.SHADOW_DX, d + T.SHADOW_DY, d / 2f)

    // ------------------------------------------------------------------ касания

    /**
     * Касание в дизайн-px. [djvu] — книга открыта движком DjVu (там «Текст» зовётся «Масштаб»).
     * Возвращает `ACT_*`; [ACT_NONE] — мимо, и экран закрывает меню.
     */
    fun hit(x: Float, y: Float, djvu: Boolean): Int {
        if (y <= TOP_H) {
            if (y < KEY_TOP_Y || y > KEY_TOP_Y + TOP_KEY_D) return ACT_NONE
            for (i in 0 until KEY_TOP_X.size) {
                if (x >= KEY_TOP_X[i] - HIT_PAD && x <= KEY_TOP_X[i] + TOP_KEY_D + HIT_PAD) {
                    // Три клавиши шапки: «Назад» закрывает книгу (возврат туда, откуда её
                    // открыли), «Поиск» открывает B28, «Закладка» ставит закладку на текущей
                    // странице (R4.1, B27). Мёртвой «Ещё» (⋮) в шапке больше нет — решение
                    // пользователя 2026-11-21.
                    return when (i) {
                        0 -> ACT_CLOSE
                        1 -> ACT_SEARCH
                        2 -> ACT_MARK
                        else -> ACT_NONE
                    }
                }
            }
            return ACT_NONE
        }
        if (y < BOT_Y) return ACT_NONE
        if (y <= PLACE_Y0) {
            if (x >= 20f && x <= 160f) return ACT_PREV
            if (x >= 1240f && x <= 1384f) return ACT_NEXT
            // Шкала и подпись «Стр. N из M» — дверь в B29 «Переход на страницу» (R3.3):
            // ручка не перетаскивается, набирать номер надо на листе.
            return ACT_GOTO
        }
        if (y < BTN_HIT_Y0) return ACT_NONE       // подпись «Стр. N из M» — касание закрывает
        for (i in 0 until BTN_COUNT) {
            // Зона — слот до середины промежутка с соседями, а не только подпись 234: ряд шире
            // макета (четыре кнопки вместо пяти), и касание между ними не должно просто закрывать
            // меню — палец на E-Ink крупный.
            val cx = BTN_X + BTN_STEP * i + BTN_W / 2f
            if (x >= cx - BTN_STEP / 2f && x <= cx + BTN_STEP / 2f) {
                return when (i) {
                    BTN_TOC -> ACT_TOC
                    BTN_TEXT -> if (djvu) ACT_SCALE else ACT_TEXT
                    BTN_SPEAK -> ACT_TTS
                    // «Ещё» в нижнем ряду — B32 «Статистика чтения» (п. 22): своего борда
                    // у кнопки нет, дверь открывает единственный экран, который за ней стоит.
                    // Это последняя кнопка ряда ([BTN_COUNT] − 1) — при смене числа кнопок
                    // проверь, что она осталась последней.
                    BTN_COUNT - 1 -> ACT_MORE
                    else -> ACT_NONE
                }
            }
        }
        return ACT_NONE
    }

    companion object {
        /** Мимо меню: экран закрывает его, ничего не делая. */
        const val ACT_NONE = 0
        /** «Назад» в шапке — закрыть меню. */
        const val ACT_CLOSE = 1
        const val ACT_PREV = 2
        const val ACT_NEXT = 3
        /** B10 «Оглавление». */
        const val ACT_TOC = 4
        /** B4 «Типографика» (crengine; в R3 его ещё нет — кнопка ждёт). */
        const val ACT_TEXT = 5
        /** B31 «Масштаб и поля» (DjVu). */
        const val ACT_SCALE = 6
        /** B29 «Переход на страницу» — шкала и подпись «Стр. N из M» нижней панели. */
        const val ACT_GOTO = 7
        /** «Закладка» в шапке — поставить или снять закладку на текущей странице (R4.1). */
        const val ACT_MARK = 8
        /** «Поиск» в шапке — экран B28 «Поиск по книге» (R4.3). */
        const val ACT_SEARCH = 9
        /**
         * «Вслух» в нижнем ряду — чтение системным TTS (п. 19, R6). Первое нажатие начинает
         * с текущей страницы, второе (пока читает) — останавливает.
         */
        const val ACT_TTS = 10

        /** «Ещё» в нижнем ряду — B32 «Статистика чтения» (п. 22). Своего борда «Ещё» нет. */
        const val ACT_MORE = 11

        // Полосы: макет −10.1,−10.1 1423.2×222.1 и −10.1,1423.2 1423.2×463.4 (габарит с тенью).
        private const val PANEL_X = -10.1f
        private const val PANEL_Y = -10.1f
        private const val PANEL_W = 1423.2f
        private const val TOP_H = 222.1f
        private const val BOT_Y = 1423.2f
        private const val BOT_H = 463.4f

        // Верхние клавиши: блик Ø102.4, y 44.8; иконки по макету. В макете их четыре
        // (41.1 / 1021 / 1138 / 1255), но правое «Ещё» (⋮) ничего не открывало — ни экрана,
        // ни действия; решение пользователя 2026-11-21: клавишу убрать, а «Поиск» и «Закладку»
        // сдвинуть на её место, сохранив шаг 117 и правый край полосы.
        private val KEY_TOP_X = floatArrayOf(41.1f, 1138f, 1255f)
        private const val KEY_TOP_Y = 44.8f
        private const val TOP_KEY_D = 102.4f
        /** Индекс клавиши «Закладка» в [KEY_TOP_X]: по нему меню показывает поставленную закладку. */
        private const val KEY_MARK = 2
        private const val TOP_ICON_W = 49.4f
        private const val TOP_ICON_H = 51.4f
        private val ICON_TOP = arrayOf("back@49.4x51.4", "search@49.4x51.4",
            "bookmark@49.4x51.4")

        private const val TITLE_X = 175.5f
        /** Название не залезает под клавишу «Поиск» (1138 − 175.5 − 12). */
        private const val TITLE_W = 950.5f
        private const val SUB_W = 950.5f

        // Шкала: слой «Трек» 160.9,1506.4 1082.3×29.2; дно 1076.8×21; ручка Ø73.1 в 1481.7.
        private const val SCALE_X = 160.9f
        private const val SCALE_Y = 1506.4f
        private const val SCALE_W = 1082.3f
        private const val SCALE_H = 29.2f
        private const val SCALE_INNER_W = 1076.8f
        private const val SCALE_INNER_H = 21f
        private const val HANDLE_D = 73.1f
        private const val HANDLE_Y = 1481.7f

        // Подпись положения: 43.9,1588.2 1316.3×43; ниже неё до ряда кнопок — пусто.
        private const val PLACE_X = 43.9f
        private const val PLACE_W = 1316.3f
        private const val PLACE_Y0 = 1660f
        private const val BTN_HIT_Y0 = 1664.5f

        // Ряд кнопок: 234×169 от 43.9; блик Ø117, иконка 54.8×57.1. В макете кнопок пять
        // (шаг 263.2), но «Контраст» ничего не открывал — своего экрана у него не было,
        // решение пользователя 2026-11-21: кнопку убрать, четыре оставшиеся разложить
        // равномерно между теми же краями (43.9 и 1330.7): шаг (1152.6 − 99.6) / 3 ≈ 351.
        private const val BTN_X = 43.9f
        private const val BTN_W = 234f
        private const val BTN_STEP = 351f
        private const val BTN_COUNT = 4
        private const val BTN_TOC = 0
        private const val BTN_TEXT = 1
        /** «Вслух» — чтение системным TTS (п. 19, R6). */
        private const val BTN_SPEAK = 2
        /** Круги ряда: блик Ø117 в 99.6 / 450.6 / 801.7 / 1152.6, y 1664.5. */
        private val BTN_SEAT_X = floatArrayOf(99.6f, 450.6f, 801.7f, 1152.6f)
        private const val BTN_SEAT_Y = 1664.5f
        private const val BTN_ICON_W = 54.8f
        private const val BTN_ICON_H = 57.1f
        private val BTN_LABEL = arrayOf("Оглавление", "Текст", "Вслух", "Ещё")
        private val BTN_ICON = arrayOf("chapters@54.8x57.1", "font@54.8x57.1",
            "tts", "more@54.8x57.1")
        /** У DjVu «Текст» — это лист B31 «Масштаб и поля» (решение пользователя 2026-11-15). */
        private const val BTN_ZOOM = "Масштаб"
        private const val ICON_ZOOM = "font@54.8x57.1"

        /** Запас к зоне касания: палец на E-Ink крупнее клавиши (ручки B31 — 96×96). */
        private const val HIT_PAD = 12f
    }
}
