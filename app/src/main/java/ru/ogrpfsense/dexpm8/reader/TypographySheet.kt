package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import android.graphics.Typeface
import ru.ogrpfsense.dexpm8.ui.Icons
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B4 · «Типографика» — нижний лист читалки (R3.5). Числа — `design/v5/B04.json`.
 *
 *   лист      группа −2.7,867.4 1408.6×1106.9: тень G10 +4.6,+10.1, блик G15, поверхность G14
 *             r 66; захват 643.5,892.1 117×11 G11 r 5.5
 *   шапка     «Типографика» 58.5,930 Manrope/52/700; «Готово» тёмная 1079.5,916.8 267.8×97.8
 *             (подпись 38/700 G15)
 *   кегль     метка «Размер шрифта» 58.5,1054.8 32/600 G4;
 *             «−» Ø117 в 55.8,1108.8 (иконка `minus`); индикатор 204.8,1111.5 175.5×117,
 *             число 66/300 по центру; ползунок 416.8,1159 753.2×21.9 (заполнение G3), ручка Ø65.8;
 *             «+» Ø117 в 1225.8,1108.8 — тёмная (G3), иконка `plus`
 *   гарнитура метка 58.5,1259.6; клавиши 409.5×138.9 в 58.5 / 494.5 / 933.3 (в макете — три);
 *             в коде их четыре (с PT Serif, B70) по 301.9 шириной, зазор 26.5;
 *             выбранная — вдавленная; «Аа» 66/500 своей гарнитурой, название 36/600
 *   интервал  метка 58.5,1478.9; желоб 58.5,1528.3 614.2×102.4, три подписи 28/600
 *   поля      метка 731.3,1478.9; желоб 731.3,1528.3 614.2×102.4
 *   переносы  «Переносы слов» 731.3,1688.8 34/600 + тумблер 138.9×73 в 1206.6,1674.6
 *
 * Чего в коде нет и почему (R3.4/R3.5, вопросы — `docs/DESIGN_QUESTIONS.md`):
 *  - «Выравнивание по ширине» — в crengine это `body { text-align: justify }` дефолтного
 *    стилевого листа, а `setStyleSheet` заменяет его целиком; без своей копии стилей тумблер
 *    не выключить, поэтому он **не рисуется**, пока дизайн не ответит;
 *  - «Моно» — в макете Martian Mono, файла в проекте нет: движок берёт системный
 *    `Droid Sans Mono`, в образце «Аа» — тоже системный моноширинный.
 *
 * Под листом страница приглушена (в макете её текст G10), затемнения нет — как у B29 и B31.
 *
 * Состояние живёт в экране ([ReaderScreen]): лист получает значения в [draw], меняет их
 * только через [result] — второй копии настроек нет. Каждое изменение применяется сразу:
 * кнопки «Применить» в макете нет.
 */
class TypographySheet(private val ink: Ink, private val icons: Icons) {

    /** Значения на листе: гарнитура, кегль, интервал, поля, переносы. */
    data class State(
        val face: Int = FACE_SERIF,
        val size: Int = 32,
        val spacing: Int = 1,
        val margins: Int = 0,
        val hyphens: Boolean = true,
    )

    /** Новое состояние после [hit]; до первого касания — то, что передали в [draw]. */
    var result = State()
        private set

    /** Открыть лист на значениях [s]. */
    fun open(s: State) {
        result = s
    }

    fun draw(c: Canvas, s: State) {
        result = s
        sheet(c)
        head(c)
        sizeRow(c, s.size)
        faces(c, s.face)
        spacing(c, s.spacing)
        margins(c, s.margins)
        hyphensRow(c, s.hyphens)
    }

    // ------------------------------------------------------------------ лист

    private fun sheet(c: Canvas) {
        ink.convex(c, SHEET_X, SHEET_Y, SHEET_W, SHEET_H, R_SHEET)
        ink.roundRect(c, GRIP_X, GRIP_Y, GRIP_W, GRIP_H, R_GRIP, T.G11)
    }

    private fun head(c: Canvas) {
        ink.text(c, TITLE, TITLE_X, T.base(TITLE_TOP, TS_TITLE, 1.25f), TS_TITLE, 700, T.G1)
        ink.convexDark(c, DONE_X, DONE_Y, DONE_W, DONE_H, R_DONE)
        ink.textCenter(c, DONE, DONE_X + T.SURFACE_DX, DONE_W - T.SHADOW_DX,
            T.base(DONE_TOP, TS_DONE, 1.25f), TS_DONE, 700, T.G15)
    }

    // ------------------------------------------------------------------ размер шрифта

    /** «−» / индикатор с числом / ползунок / «+»: одна строка, правая клавиша тёмная. */
    private fun sizeRow(c: Canvas, size: Int) {
        ink.text(c, LABEL_SIZE, LABEL_X, T.base(LABEL_SIZE_TOP, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        ink.convex(c, MINUS_X, MINUS_Y, KEY_D + T.SHADOW_DX, KEY_D + T.SHADOW_DY, KEY_D / 2f)
        icons.drawRound(c, "minus", MINUS_X, MINUS_Y, KEY_D + T.SHADOW_DX, KEY_D + T.SHADOW_DY,
            MINUS_W, MINUS_H)
        ink.inset(c, IND_X, IND_Y, IND_W, IND_H, R_IND, T.G12)
        ink.textCenter(c, size.toString(), IND_X + T.INSET_DX, IND_W - T.INSET_W,
            T.base(SIZE_TOP, TS_SIZE, 1.25f), TS_SIZE, 300, T.G1)
        slider(c, size)
        ink.convexDark(c, PLUS_X, PLUS_Y, KEY_D + T.SHADOW_DX, KEY_D + T.SHADOW_DY, KEY_D / 2f)
        icons.drawRound(c, "plus", PLUS_X, PLUS_Y, KEY_D + T.SHADOW_DX, KEY_D + T.SHADOW_DY,
            PLUS_W, PLUS_H)
    }

    /** Ползунок кегля: желоб вдавленный, заполнение G3 и ручка Ø65.8 на его правом краю. */
    private fun slider(c: Canvas, size: Int) {
        ink.inset(c, TRACK_X, TRACK_Y, TRACK_W, TRACK_H, R_TRACK, T.G12)
        val fill = (TRACK_INNER_W * pct(size)).coerceIn(0f, TRACK_INNER_W)
        if (fill > 0f) {
            ink.roundRect(c, TRACK_X + T.INSET_DX, TRACK_Y + T.INSET_DY, fill,
                TRACK_INNER_H, R_FILL, T.G3)
        }
        val hx = (TRACK_X + T.INSET_DX + fill - HANDLE_D / 2f)
            .coerceIn(TRACK_X + T.INSET_DX, TRACK_X + TRACK_W - HANDLE_D)
        ink.convex(c, hx, HANDLE_Y, HANDLE_D + T.SHADOW_DX, HANDLE_D + T.SHADOW_DY,
            HANDLE_D / 2f)
    }

    // ------------------------------------------------------------------ гарнитура

    /**
     * Четыре клавиши: «Аа» своей гарнитурой и название. Выбранная — вдавленная.
     * В макете B4 клавиш три (409.5 шириной); с PT Serif их четыре, поэтому ширина
     * делится поровну при тех же зазорах — 300.8. Порядок подписей совпадает с кодами
     * натива (0 Антиква · 1 Гротекс · 2 Моно · 3 PT Serif), так что индекс = код.
     */
    private fun faces(c: Canvas, face: Int) {
        ink.text(c, LABEL_FACE, LABEL_X, T.base(LABEL_FACE_TOP, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        for (i in FACE_LABELS.indices) {
            val x = FACE_X[i]
            if (i == face) ink.inset(c, x, FACE_Y, FACE_W, FACE_H, R_FACE, T.G12)
            else ink.convex(c, x, FACE_Y_CONVEX, FACE_W + T.SHADOW_DX,
                FACE_H + T.SHADOW_DY, R_FACE)
            // Базовая линия «Аа» — как в макете (рамка 66/1.2: у «Антиквы» верх 1337.3, у
            // остальных 1341.3, оба дают ≈1406), название стоит на той же линии: в макете
            // базовые линии «Аа» и названия совпадают.
            val base = if (i == FACE_SERIF) T.base(FACE_AA_TOP_SERIF, TS_AA, 1.2f, true)
            else T.base(FACE_AA_TOP, TS_AA, 1.2f)
            ink.text(c, "Аа", FACE_AA_X[i], base, TS_AA, 500, T.G1, faceFont(i))
            // Название обрезается по правому краю клавиши: «PT Serif» шире 301.9.
            ink.text(c, ink.ellipsize(FACE_LABELS[i], FACE_W - 124f, TS_FACE_NAME,
                if (i == face) 800 else 600), FACE_NAME_X[i], base, TS_FACE_NAME,
                if (i == face) 800 else 600, if (i == face) T.G1 else T.G4)
        }
    }

    // ------------------------------------------------------------------ интервал и поля

    /** Желоб «Плотный / Обычный / Свободный»: выбранный сегмент — выпуклая плашка. */
    private fun spacing(c: Canvas, active: Int) {
        ink.text(c, LABEL_SPACING, LABEL_X, T.base(LABEL_SPACING_TOP, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        groove(c, SPACING_X, SPACING_Y, SPACING_W, SPACING_LABELS, active)
    }

    /** Желоб «Узкие / Средние / Широкие». */
    private fun margins(c: Canvas, active: Int) {
        ink.text(c, LABEL_MARGIN, MARGIN_X, T.base(LABEL_SPACING_TOP, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        groove(c, MARGIN_X, SPACING_Y, MARGIN_W, MARGIN_LABELS, active)
    }

    /**
     * Желоб с тремя сегментами. Он шире, чем `SettingsKit.segments` (614.2×102.4 против
     * 1221×95), поэтому нарисован здесь. Числа макета B04: поверхность плашки и рамка
     * подписи 199.9 шириной, шаг 199.9 от +7.3; группа плашки с тенью 204.5×97.8 лежит
     * на −2.8 по x и +4.6 по y от поверхности (`Ink.convex` берёт габарит с тенью, поэтому
     * 87.7 слоя в него передавать нельзя — снизу оставался зазор больше, чем сверху).
     * Подпись 28/600 в рамке с верхом +32.8, у активной 700.
     */
    private fun groove(c: Canvas, x: Float, y: Float, w: Float, labels: Array<String>,
                       active: Int) {
        ink.inset(c, x, y, w, SPACING_H, R_GROOVE, T.G12)
        for (i in labels.indices) {
            val sx = x + SEG_PAD + SEG_W * i          // поверхность плашки и рамка подписи
            val on = i == active
            if (on) ink.convex(c, sx - T.SURFACE_DX, y + SEG_DY, SEG_W + T.SHADOW_DX, SEG_GROUP_H,
                R_SEG)
            ink.textCenter(c, labels[i], sx, SEG_W, T.base(y + SEG_TEXT_DY, TS_SEG, 1.25f),
                TS_SEG, if (on) 700 else 600, if (on) T.G1 else T.G4)
        }
    }

    // ------------------------------------------------------------------ переносы

    private fun hyphensRow(c: Canvas, on: Boolean) {
        ink.text(c, LABEL_HYPHENS, HYPHENS_X, T.base(LABEL_HYPHENS_TOP, T.TS_BODY, 1.25f),
            T.TS_BODY, 600, T.G1)
        // Тумблер B4: желоб 138.9×73 (включён — дно G3, ручка справа), как `SettingsKit.toggle`.
        ink.inset(c, TGG_X, TGG_Y, TGG_W, TGG_H, TGG_H / 2f, if (on) T.G3 else T.G12)
        ink.convex(c, if (on) TGG_X + TGG_KNOB_DX_ON else TGG_X + TGG_KNOB_DX_OFF,
            TGG_Y + TGG_KNOB_DY, TGG_KNOB_W, TGG_KNOB_H, TGG_KNOB_W / 2f)
    }

    /** Шрифт образца «Аа»: своя гарнитура клавиши (у «Моно» — системный, см. KDoc). */
    /** Шрифт для «Аа»: каждая клавиша показывает свою гарнитуру. */
    private fun faceFont(i: Int): Typeface = when (i) {
        FACE_SANS -> T.manrope(ink.res, 500)
        FACE_MONO -> Typeface.MONOSPACE
        FACE_PTSERIF -> T.ptserif(ink.res, 400)
        else -> T.literata(ink.res, 500)
    }

    // ------------------------------------------------------------------ касания

    /**
     * Касание в дизайн-px. Возвращает `ACT_*`; новое состояние — в [result] при
     * [ACT_CHANGED] (его и применяет экран). [ACT_NONE] — мимо органов листа.
     */
    fun hit(x: Float, y: Float, s: State): Int {
        result = s
        if (y < SHEET_Y) return ACT_CLOSE          // выше листа — как касание мимо меню
        if (inRect(x, y, DONE_X, DONE_Y, DONE_W, DONE_H)) return ACT_CLOSE
        // Захват, строка заголовка и метки — касание без последствий: лист не закрываем,
        // иначе промах по клавише выкидывает из настроек (как B29 и B31).
        if (y < MINUS_Y - HIT_PAD) return ACT_NONE

        // Кегль: «−», «+» и шкала.
        if (inCircle(x, y, MINUS_X, MINUS_Y, KEY_D)) return sizeOf(s, s.size - SIZE_STEP)
        if (inCircle(x, y, PLUS_X, PLUS_Y, KEY_D)) return sizeOf(s, s.size + SIZE_STEP)
        if (y in TRACK_HIT_Y0..TRACK_HIT_Y1 && x >= TRACK_X && x <= TRACK_X + TRACK_W) {
            val p = ((x - TRACK_X - T.INSET_DX) / TRACK_INNER_W).coerceIn(0f, 1f)
            return sizeOf(s, Math.round(SIZE_MIN + p * (SIZE_MAX - SIZE_MIN)))
        }

        // Гарнитура: три клавиши.
        if (y >= FACE_Y - HIT_PAD && y <= FACE_Y + FACE_H + T.SHADOW_DY + HIT_PAD) {
            for (i in FACE_LABELS.indices) {
                if (x >= FACE_X[i] && x <= FACE_X[i] + FACE_W + T.SHADOW_DX) {
                    if (i == s.face) return ACT_NONE
                    result = s.copy(face = i)
                    return ACT_CHANGED
                }
            }
            return ACT_NONE
        }

        // Интервал и поля: два желоба в одной строке.
        if (y >= SPACING_Y - HIT_PAD && y <= SPACING_Y + SPACING_H + HIT_PAD) {
            val seg = segment(x, SPACING_X, SPACING_W)
            if (seg >= 0 && seg != s.spacing) {
                result = s.copy(spacing = seg)
                return ACT_CHANGED
            }
            val segM = segment(x, MARGIN_X, MARGIN_W)
            if (segM >= 0 && segM != s.margins) {
                result = s.copy(margins = segM)
                return ACT_CHANGED
            }
            return ACT_NONE
        }

        // «Переносы слов»: тумблер и подпись строки целиком.
        if (y >= LABEL_HYPHENS_TOP - HIT_PAD && y <= TGG_Y + TGG_H + HIT_PAD &&
            x >= HYPHENS_X) {
            result = s.copy(hyphens = !s.hyphens)
            return ACT_CHANGED
        }
        return ACT_NONE
    }

    /** Новое значение кегля по «−/+» или шкале: в пределах [SIZE_MIN]…[SIZE_MAX]. */
    private fun sizeOf(s: State, v: Int): Int {
        val n = v.coerceIn(SIZE_MIN, SIZE_MAX)
        if (n == s.size) return ACT_NONE
        result = s.copy(size = n)
        return ACT_CHANGED
    }

    /** Номер сегмента желоба ([gx], ширина [w]) под касанием или −1. */
    private fun segment(x: Float, gx: Float, w: Float): Int {
        if (x < gx || x > gx + w) return -1
        return ((x - gx - SEG_PAD) / SEG_W).toInt().coerceIn(0, 2)
    }

    /** Доля шкалы для кегля [size] (0…1). */
    private fun pct(size: Int): Float =
        ((size - SIZE_MIN).toFloat() / (SIZE_MAX - SIZE_MIN)).coerceIn(0f, 1f)

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x >= rx && x <= rx + rw && y >= ry && y <= ry + rh

    /** Круглая клавиша: [x], [y] — верхний левый угол блика Ø[d]; запас к зоне касания. */
    private fun inCircle(x: Float, y: Float, cx: Float, cy: Float, d: Float): Boolean {
        val dx = x - cx - d / 2f
        val dy = y - cy - d / 2f
        val r = d / 2f + HIT_PAD
        return dx * dx + dy * dy <= r * r
    }

    companion object {
        const val ACT_NONE = 0
        /** «Готово» или касание выше листа. */
        const val ACT_CLOSE = 1
        /** Значения изменились: экран пишет `Prefs`, переверстывает книгу и перерисовывает лист. */
        const val ACT_CHANGED = 2

        /**
         * Гарнитуры — коды натива (`Engine.FACE_*`); здесь только имена для чтения кода листа.
         * Значения совпадают с [Engine.FACE_SERIF], [Engine.FACE_SANS], [Engine.FACE_MONO],
         * [Engine.FACE_PTSERIF] — второй набор нужен, чтобы `companion` листа не зависел от `Engine`.
         */
        const val FACE_SERIF = Engine.FACE_SERIF
        const val FACE_SANS = Engine.FACE_SANS
        const val FACE_MONO = Engine.FACE_MONO
        const val FACE_PTSERIF = Engine.FACE_PTSERIF

        /**
         * Диапазон кегля 8…72 — предел движка (`m_min_font_size`/`m_max_font_size`
         * в `lvdocview.cpp`); шаг «−/+» — 2, шкала ставит любое значение в этих пределах.
         */
        const val SIZE_MIN = 8
        const val SIZE_MAX = 72
        const val SIZE_STEP = 2

        /** Интервал в процентах движка: «Плотный» 90, «Обычный» 100, «Свободный» 115. */
        val INTERLINE = intArrayOf(90, 100, 115)
        /** Поля слева и справа, px окна: «Узкие» 0, «Средние» 60, «Широкие» 120. */
        val MARGIN = intArrayOf(0, 60, 120)

        private const val TITLE = "Типографика"
        private const val DONE = "Готово"
        private const val LABEL_SIZE = "Размер шрифта"
        private const val LABEL_FACE = "Гарнитура"
        private const val LABEL_SPACING = "Интервал"
        private const val LABEL_MARGIN = "Поля"
        private const val LABEL_HYPHENS = "Переносы слов"

        private val FACE_LABELS = arrayOf("Антиква", "Гротекс", "Моно", "PT Serif")
        /** Подписи интервала и полей: их же показывает строка «Интервал и поля» на B39. */
        val SPACING_LABELS = arrayOf("Плотный", "Обычный", "Свободный")
        val MARGIN_LABELS = arrayOf("Узкие", "Средние", "Широкие")

        // Лист: группа −2.7,867.4 1408.6×1106.9 (габарит с тенью); поверхность 1404×1096.9 r 66.
        private const val SHEET_X = -2.7f
        private const val SHEET_Y = 867.4f
        private const val SHEET_W = 1408.6f
        private const val SHEET_H = 1106.9f
        private const val R_SHEET = 65.8125f
        private const val GRIP_X = 643.5f
        private const val GRIP_Y = 892.1f
        private const val GRIP_W = 117f
        private const val GRIP_H = 11f
        private const val R_GRIP = 5.48438f
        private const val TITLE_X = 58.5f
        private const val TITLE_TOP = 930f
        private const val TS_TITLE = 52f

        // «Готово»: тёмная клавиша 1079.5,916.8 267.8×97.8 r 43.875, подпись 38/700 в 939.
        private const val DONE_X = 1079.5f
        private const val DONE_Y = 916.8f
        private const val DONE_W = 267.8f
        private const val DONE_H = 97.8f
        private const val R_DONE = 43.875f
        private const val DONE_TOP = 939f
        private const val TS_DONE = 38f


        // Кегль: «−» Ø117 в 55.8,1108.8 (иконка 45.7×47.6 в 94.1,1148.1);
        // индикатор 204.8,1111.5 175.5×117 r 36.56, число 66/300 в 204.8,1128.7;
        // «+» Ø117 в 1225.8,1108.8 (иконка 43.9×43.9 в 1265.1,1148.1) — тёмная.
        private const val LABEL_SIZE_TOP = 1054.8f
        private const val LABEL_X = 58.5f
        private const val MINUS_X = 55.8f
        private const val MINUS_Y = 1108.8f
        private const val KEY_D = 117f
        private const val MINUS_W = 45.7f
        private const val MINUS_H = 47.6f
        private const val IND_X = 204.8f
        private const val IND_Y = 1111.5f
        private const val IND_W = 175.5f
        private const val IND_H = 117f
        private const val R_IND = 36.5625f
        private const val SIZE_TOP = 1128.7f
        private const val TS_SIZE = 66f
        private const val PLUS_X = 1225.8f
        private const val PLUS_Y = 1108.8f
        private const val PLUS_W = 43.9f
        private const val PLUS_H = 43.9f

        // Ползунок кегля: желоб 416.8,1159 753.2×21.9 (дно 747.7×13.7 от 419.6,1164.5),
        // заполнение 336.5×13.7, ручка Ø65.8 в 720.4,1134.4 (габарит группы 70.4×75.9).
        private const val TRACK_X = 416.8f
        private const val TRACK_Y = 1159f
        private const val TRACK_W = 753.2f
        private const val TRACK_H = 21.9f
        private const val R_TRACK = 10.96875f
        private const val TRACK_INNER_W = 747.7f
        private const val TRACK_INNER_H = 13.7f
        private const val R_FILL = 6.85547f
        private const val HANDLE_Y = 1134.4f
        private const val HANDLE_D = 65.8f
        /** Зона касания шкалы по высоте: ручка Ø65.8 выходит за желоб (1134.4…1200.2). */
        private const val TRACK_HIT_Y0 = 1130f
        private const val TRACK_HIT_Y1 = 1204f

        // Гарнитура: метка 58.5,1259.6; в макете три клавиши 409.5×138.9 в 58.5 / 494.5 / 933.3
        // (правый край 1342.8). С PT Serif их четыре: 301.9 шириной, зазор 26.5 —
        // 58.5 / 386.9 / 715.2 / 1043.6. «Аа» 66/500 в +36.6; название — в макете 36/600
        // в +175.5, но в клавишу 301.9 оно не влезает, поэтому 30/600 в +140 (после «Аа»).
        private const val LABEL_FACE_TOP = 1259.6f
        private const val FACE_Y = 1312.6f
        /** Ненажатая клавиша — обычная выпуклая: габарит группы 1309.9, блик 1312.6. */
        private const val FACE_Y_CONVEX = 1309.9f
        private const val FACE_W = 301.9f
        private const val FACE_H = 138.9f
        private const val R_FACE = 40.2188f
        private val FACE_X = floatArrayOf(58.5f, 386.9f, 715.2f, 1043.6f)
        private val FACE_AA_X = floatArrayOf(95.1f, 423.5f, 751.8f, 1080.2f)
        private const val FACE_AA_TOP = 1341.3f
        /** «Аа» Literata (макет, «Антиква»): рамка выше на 4 — шрифт с другими метриками. */
        private const val FACE_AA_TOP_SERIF = 1337.3f
        private const val TS_AA = 66f
        private val FACE_NAME_X = floatArrayOf(198.5f, 526.9f, 855.2f, 1183.6f)
        private const val TS_FACE_NAME = 30f

        // Желоба «Интервал» и «Поля»: 58.5 / 731.3, y 1528.3, 614.2×102.4 r 51.19;
        // метки 58.5 / 731.3 от 1478.9. Активный сегмент — плашка 199.9×87.7 в 265.7,1535.6.
        private const val LABEL_SPACING_TOP = 1478.9f
        private const val SPACING_X = 58.5f
        private const val SPACING_Y = 1528.3f
        private const val SPACING_W = 614.2f
        private const val SPACING_H = 102.4f
        private const val R_GROOVE = 51.1875f
        private const val MARGIN_X = 731.3f
        private const val MARGIN_W = 614.2f
        /** Группа плашки (с тенью): верх на +4.6 от желоба, высота 97.8. */
        private const val SEG_DY = 4.6f
        private const val SEG_GROUP_H = 97.8f
        /** Шаг сегмента и ширина поверхности/подписи 199.9, первый — на +7.3 от края желоба. */
        private const val SEG_W = 199.9f
        private const val SEG_PAD = 7.3f
        private const val R_SEG = 43.875f
        private const val TS_SEG = 28f
        /** Верх рамки подписи 28/1.25 — на +32.8 от желоба (макет 1561.1 − 1528.3). */
        private const val SEG_TEXT_DY = 32.8f

        // «Переносы слов»: подпись 731.3,1688.8 34/600, тумблер 1206.6,1674.6 138.9×73,
        // ручка 63.1×68.6 — справа (+70) включён, слева (+4) выключен.
        private const val LABEL_HYPHENS_TOP = 1688.8f
        private const val HYPHENS_X = 731.3f
        private const val TGG_X = 1206.6f
        private const val TGG_Y = 1674.6f
        private const val TGG_W = 138.9f
        private const val TGG_H = 73f
        private const val TGG_KNOB_W = 63.1f
        private const val TGG_KNOB_H = 68.6f
        private const val TGG_KNOB_DX_ON = 70f
        private const val TGG_KNOB_DX_OFF = 4f
        private const val TGG_KNOB_DY = 4.5f

        /** Запас к зоне касания: палец на E-Ink крупнее клавиши (как B29 и B31). */
        private const val HIT_PAD = 8f
    }
}



