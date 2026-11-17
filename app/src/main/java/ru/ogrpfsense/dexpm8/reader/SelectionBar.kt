package ru.ogrpfsense.dexpm8.reader

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.ui.Ink
import ru.ogrpfsense.dexpm8.ui.T

/**
 * B50 · меню выделения. Числа — `design/v5/B50.json`, группа «Меню выделения» 44,412 1321×202:
 *
 *   панель    44,412 1321×202, радиус 50 — тень G10 @ +5,+10, блик G15, поверхность G14 @ +3,+3
 *   клавиша   70,445 248.2×140, шаг 255.2 (70 / 325.2 / 580.4 / 835.6 / 1090.8), тот же пластик
 *   подписи   Manrope 34/700 G1 по центру поверхности 243.2×130: «Цитата», «Заметка»,
 *             «Словарь», «Найти», «Копировать»
 *
 * Панель стоит под выделением с зазором 17.4 (в макете выделение кончается на 394.6, панель
 * начинается на 412). Если вниз места нет — панель встаёт над выделением: у последних абзацев
 * страницы иначе она уехала бы за нижний край.
 *
 * Рисование и касания в одном классе: геометрия у них общая (READER_SPEC, «одна геометрия
 * для рисования и касаний»). Панель перерисовывается только при смене выделения — это E-Ink.
 */
class SelectionBar(private val ink: Ink) {

    /**
     * Верх панели для выделения, занимающего по вертикали [top]…[bottom] (дизайн-px).
     * Под выделением, а если до низа страницы не хватает — над ним.
     */
    fun top(top: Float, bottom: Float): Float {
        val under = bottom + GAP
        return if (under + H <= PAGE_BOTTOM) under else (top - GAP - H).coerceAtLeast(0f)
    }

    fun draw(c: Canvas, y: Float) {
        ink.convex(c, X, y, W, H, R_PANEL)
        for (i in LABELS.indices) {
            val x = KEY_X0 + i * KEY_STEP
            ink.convex(c, x, y + KEY_DY, KEY_W, KEY_H, R_KEY)
            ink.textCenter(c, LABELS[i], x + T.SURFACE_DX, KEY_W - T.SHADOW_DX,
                ink.baselineIn(y + KEY_DY + T.SURFACE_DY, KEY_H - T.SHADOW_DY, TS_LABEL, TW_LABEL),
                TS_LABEL, TW_LABEL, T.G1)
        }
    }

    /**
     * Касание: `ACT_*` или [ACT_NONE]. [y] — тот же верх панели, что в [draw].
     * Между клавишами (шаг 255.2 против ширины 248.2) щели в 7 — попадание считается по
     * касанию с запасом, как у клавиатуры: иначе крайние 3.5 дизайн-px «не нажимаются».
     */
    fun hit(x: Float, y: Float, y0: Float): Int {
        if (y < y0 || y > y0 + H) return ACT_NONE
        for (i in LABELS.indices) {
            val kx = KEY_X0 + i * KEY_STEP
            if (x >= kx - HIT_PAD && x <= kx + KEY_W + HIT_PAD) return ACT_QUOTE + i
        }
        return ACT_NONE
    }

    /**
     * Точка внутри панели — по всему габариту 44…1365 × [y0]…[y0]+202, вместе со щелями между
     * клавишами. Нужно не касанию, а зоне шторки B5: где стоит меню B50, шторка не открывается
     * (замечание 20, 2026-11-17); щели считаются панелью, потому что касание в них снимает
     * выделение, а не листает страницу.
     */
    fun contains(x: Float, y: Float, y0: Float): Boolean =
        y >= y0 && y <= y0 + H && x >= X && x <= X + W

    companion object {
        const val ACT_NONE = 0
        /** Клавиши меню по порядку макета: «Цитата», «Заметка», «Словарь», «Найти», «Копировать». */
        const val ACT_QUOTE = 1
        const val ACT_NOTE = 2
        const val ACT_DICT = 3
        const val ACT_FIND = 4
        const val ACT_COPY = 5

        /** Высота панели вместе с тенью — по ней считается [top] и лишняя перерисовка. */
        const val H = 202f

        private val LABELS = arrayOf("Цитата", "Заметка", "Словарь", "Найти", "Копировать")

        private const val X = 44f
        private const val W = 1321f
        private const val R_PANEL = 50f
        /** Клавиша ниже верха панели на 33 (445 против 412). */
        private const val KEY_DY = 33f
        private const val KEY_X0 = 70f
        private const val KEY_W = 248.2f
        private const val KEY_H = 140f
        private const val KEY_STEP = 255.2f
        private const val R_KEY = 50f
        private const val TS_LABEL = 34f
        private const val TW_LABEL = 700
        /** Зазор между выделением и панелью (412 − 394.6). */
        private const val GAP = 17.4f
        private const val HIT_PAD = 3.5f
        /** Ниже этого панель считается уехавшей за страницу: текст B03 кончается на 1657. */
        private const val PAGE_BOTTOM = 1657f
    }
}
