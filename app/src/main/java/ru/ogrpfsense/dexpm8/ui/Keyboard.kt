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
 * Раскладки (B72): русская, английская, украинская, немецкая, французская плюс цифры и знаки.
 * Включённые хранятся маской `Prefs.layouts` (биты [LAYOUTS]); клавиша «RU/EN» перебирает только
 * включённые, а её подпись — код текущей ([Layout.code]). Состояние — внутри; экран находит
 * клавишу через [hit], вызывает [press] и сам обрабатывает [K_DEL] и [K_DONE]. Системную
 * клавиатуру Android не используем.
 *
 * Сетка букв повторяет национальные раскладки и макет B72: русская и украинская — 12/11/9,
 * немецкая — 11/11/8, французская — 10/10/6, английская — 10/9/7. «ґ» (украинская) и «œ»
 * (французская) в срез Manrope не входят (`res/font/manrope_*.ttf`, 300 символов), а длинного
 * нажатия у своей клавиатуры нет — этих букв на клавишах нет вовсе.
 *
 * [onKey] — «клавиша нажата»: звук касания и полный кадр при вводе (B72, `EinkActivity.keyPressed`).
 * [langs] — текущая маска включённых раскладок (читается в момент нажатия, поэтому смена
 * настроек на B72 сразу видна и в уже созданных листах).
 */
class Keyboard(private val ink: Ink, private val langs: () -> Int, private val onKey: () -> Unit) {

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

    /** Номер раскладки в [LAYOUTS]: [LANG_RU] … [LANG_FR]. */
    private var lang = LANG_RU
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

    /**
     * Раскладка при открытии: [LANG_RU], [LANG_EN] и т. д. По умолчанию русская; английскую ставит
     * лист пароля Wi-Fi (B47). Выключенная на B72 раскладка не открывается — берётся первая
     * включённая. Цифры и регистр сбрасываются.
     */
    fun setLang(l: Int) {
        val want = l.coerceIn(0, LAYOUTS.size - 1)
        val v = if (enabled(want)) want else firstEnabled()
        if (v != lang || sym || shift) {
            lang = v
            sym = false
            shift = false
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
                lang = nextLang()
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

    /**
     * Клавиша под точкой [x], [y] или null. Каждое попадание — это нажатие: здесь же просим звук
     * и полный кадр ввода ([onKey]), иначе мимо этой точки их звать некому, а листы зовут только
     * [hit] (и только для настоящей клавиши — промах звучать не должен).
     */
    fun hit(x: Float, y: Float): Key? {
        build()
        for (k in keys) {
            val inX = x >= k.x - PAD_X && x <= k.x + k.w + PAD_X
            val inY = y >= k.y - PAD_Y && y <= k.y + KEY_H + PAD_Y
            if (inX && inY) {
                onKey()
                return k
            }
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
        val rows = if (sym) SYM_ROWS else LAYOUTS[lang].rows
        addRow(rows[0], Y0)
        addRow(rows[1], Y0 + STEP_Y)
        // Ряд 3: служебные клавиши по краям, знаки между ними.
        val y3 = Y0 + 2 * STEP_Y
        keys.add(Key(K_SHIFT, "", "↑", 30f, y3, 162.5f))
        addRow(rows[2], y3)
        keys.add(Key(K_DEL, "", "←", 1216.5f, y3, 162.5f))
        val y4 = Y0 + 3 * STEP_Y
        val symLabel = if (!sym) "?123" else if (lang == LANG_RU || lang == LANG_UK) "абв" else "abc"
        keys.add(Key(K_SYM, "", symLabel, 30f, y4, 185f))
        keys.add(Key(K_LANG, "", LAYOUTS[lang].code, 222f, y4, 155f))
        keys.add(Key(K_SPACE, " ", if (lang == LANG_RU || lang == LANG_UK) "пробел" else "space",
            384f, y4, 570f))
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

        /** Раскладки для [setLang] и номер бита в `Prefs.layouts`; порядок — как на B72. */
        const val LANG_RU = 0
        const val LANG_EN = 1
        const val LANG_UK = 2
        const val LANG_DE = 3
        const val LANG_FR = 4

        /**
         * Раскладка: код для клавиши «RU/EN» ([code]), подпись строки на B72 ([name]) и разметка
         * клавиш ([desc]), три ряда букв.
         */
        class Layout(
            val code: String,
            val name: String,
            val desc: String,
            val rows: Array<String>,
        )

        /**
         * Пять раскладок в порядке B72. Английская — QWERTY; украинская держит ту же сетку, что
         * русская (ЙЦУКЕН), но без «ё», «ъ», «ы», «э» и с «і», «ї», «є»; «ґ» нет — в срезе
         * Manrope её нет, см. комментарий класса.
         */
        val LAYOUTS = arrayOf(
            Layout("RU", "Русская", "ЙЦУКЕН",
                arrayOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")),
            Layout("EN", "Английская", "QWERTY",
                arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")),
            Layout("УК", "Украинская", "ЙЦУКЕН",
                arrayOf("йцукенгшщзхї", "фівапролджє", "ячсмитьбю")),
            Layout("DE", "Немецкая", "QWERTZ",
                arrayOf("qwertzuiopü", "asdfghjklöä", "yxcvbnmß")),
            Layout("FR", "Французская", "AZERTY",
                arrayOf("azertyuiop", "qsdfghjklm", "wxcvbn")),
        )

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

        private val SYM_ROWS = arrayOf("1234567890", "-/:;()&@\"", "?!,'")
    }

    /** Включена ли раскладка [i] по маске [langs]. */
    private fun enabled(i: Int) = (langs() shr i) and 1 == 1

    /** Первая включённая раскладка; не включено ничего — русская (экран B72 такого не допускает). */
    private fun firstEnabled(): Int {
        val m = langs()
        for (i in LAYOUTS.indices) if ((m shr i) and 1 == 1) return i
        return LANG_RU
    }

    /**
     * Следующая включённая раскладка после [lang]. Символьный ряд — не раскладка: «RU/EN»
     * из него возвращает в буквы.
     */
    private fun nextLang(): Int {
        val m = langs()
        for (d in 1..LAYOUTS.size) {
            val i = (lang + d) % LAYOUTS.size
            if ((m shr i) and 1 == 1) return i
        }
        return lang
    }
}
