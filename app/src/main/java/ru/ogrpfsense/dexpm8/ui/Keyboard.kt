package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * Своя экранная клавиатура (B48; общая для поиска B21 и ввода названий полок). Числа — из
 * design/v5/B48.json, группа «Клавиатура» 30,1410 1349×442:
 *
 *   клавиша   106×106 вместе с тенью (поверхность 101×96, радиус 50); шаг 113 по x, 112 по y
 *   ряды      y 1410 / 1522 / 1634 / 1746; буквы ряда центрованы по x 704.5
 *   ряд 3     «↑» 30 и «←» 1216.5 шириной 162.5; буквы между ними
 *   ряд 4     «?123» 30 (185), «RU» 222 (155), пробел 384 (570), «.» 961 (106),
 *      действие 1074 (305, тёмная, G15 38/700)
 *   подписи   Manrope 44/600 (буквы), 34/600 (служебные), по центру поверхности
 *
 * Раскладки: русская, английская, цифры и знаки. Состояние — внутри; экран находит клавишу
 * через [hit], вызывает [press] и сам обрабатывает [K_DEL] и [K_DONE]. Системную клавиатуру
 * Android не используем.
 */
class Keyboard(private val ink: Ink) {

    class Key(
        val kind: Int,
        val code: String,
        val label: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val dark: Boolean = false,
    )

    private val keys = ArrayList<Key>(48)

    private var lang = 0   // 0 = RU, 1 = EN
    private var sym = false       // цифры и знаки
    private var shift = false     // следующая буква заглавная
    private var action = "Готово"
    private var dirty = true

    /** Подпись тёмной клавиши справа внизу («Готово», «Создать»). */
    fun setAction(label: String) {
        if (label != action) {
            action = label
            dirty = true
        }
    }

    /** Нажата клавиша: текст для вставки или null (смена раскладки, «←», действие). */
    fun press(k: Key): String? {
        when (k.kind) {
            K_SHIFT -> if (!sym) {
                shift = !shift
                dirty = true
            }
            K_SYM -> {
                sym = !sym
                shift = false
                dirty = true
            }
            K_LANG -> {
                lang = 1 - lang
                sym = false
                shift = false
                dirty = true
            }
            K_CHAR, K_SPACE -> {
                if (shift) {
                    shift = false
                    dirty = true
                }
                return k.code
            }
        }
        return null
    }

    fun hit(x: Float, y: Float): Key? {
        build()
        for (k in keys) {
            val inX = x >= k.x - PAD_X && x <= k.x + k.w + PAD_X
            val inY = y >= k.y - PAD_Y && y <= k.y + KEY_H + PAD_Y
            if (inX && inY) return k
        }
        return null
    }

    fun draw(c: Canvas) {
        build()
        for (k in keys) {
            val sx = k.x + T.SURFACE_DX
            val sy = k.y + T.SURFACE_DY
            val sw = k.w - T.SHADOW_DX
            val sh = KEY_H - T.SHADOW_DY
            val on = k.dark || (k.kind == K_SHIFT && shift) || (k.kind == K_SYM && sym)
            if (on) {
                ink.convexDark(c, k.x, k.y, k.w, KEY_H, R_KEY)
            } else {
                ink.convex(c, k.x, k.y, k.w, KEY_H, R_KEY)
            }
            val big = k.kind == K_CHAR || k.kind == K_SHIFT || k.kind == K_DEL
            val fs = if (k.dark) 38f else if (big) 44f else 34f
            val weight = if (k.dark) 700 else 600
            ink.textCenter(c, k.label, sx, sw, ink.baselineIn(sy, sh, fs, weight), fs, weight,
                if (on) T.G15 else T.G1)
        }
    }

    private fun build() {
        if (!dirty) return
        dirty = false
        keys.clear()
        val rows = if (sym) SYM_ROWS else if (lang == 0) RU_ROWS else EN_ROWS
        addRow(rows[0], Y0)
        addRow(rows[1], Y0 + STEP_Y)
        // Ряд 3: служебные клавиши по краям, знаки между ними.
        val y3 = Y0 + 2 * STEP_Y
        keys.add(Key(K_SHIFT, "", "↑", 30f, y3, 162.5f))
        addRow(rows[2], y3)
        keys.add(Key(K_DEL, "", "←", 1216.5f, y3, 162.5f))
        val y4 = Y0 + 3 * STEP_Y
        val symLabel = if (!sym) "?123" else if (lang == 0) "абв" else "abc"
        keys.add(Key(K_SYM, "", symLabel, 30f, y4, 185f))
        keys.add(Key(K_LANG, "", if (lang == 0) "RU" else "EN", 222f, y4, 155f))
        keys.add(Key(K_SPACE, " ", if (lang == 0) "пробел" else "space", 384f, y4, 570f))
        keys.add(Key(K_CHAR, ".", ".", 961f, y4, 106f))
        keys.add(Key(K_DONE, "", action, 1074f, y4, 305f, dark = true))
    }

    private fun addRow(chars: String, y: Float) {
        val n = chars.length
        val x0 = CENTER - ((n - 1) * STEP_X + KEY_W) / 2f
        for (i in 0 until n) {
            val ch = chars.substring(i, i + 1)
            val t = if (shift) ch.uppercase() else ch
            keys.add(Key(K_CHAR, t, t, x0 + i * STEP_X, y, KEY_W))
        }
    }

    companion object {
        const val K_CHAR = 0
        const val K_SHIFT = 1
        const val K_DEL = 2
        const val K_SYM = 3
        const val K_LANG = 4
        const val K_SPACE = 5
        const val K_DONE = 6

        /** Верх клавиатуры; высота — до низа экрана (1410 + 442). */
        const val TOP = 1410f

        private const val Y0 = 1410f
        private const val STEP_X = 113f
        private const val STEP_Y = 112f
        private const val KEY_W = 106f
        private const val KEY_H = 106f
        private const val CENTER = 704.5f
        private const val R_KEY = 50f
        private const val PAD_X = 3.5f
        private const val PAD_Y = 3f

        private val RU_ROWS = arrayOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        private val EN_ROWS = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val SYM_ROWS = arrayOf("1234567890", "-/:;()&@\"", "?!,'")
    }
}
