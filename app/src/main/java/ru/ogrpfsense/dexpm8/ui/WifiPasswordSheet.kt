package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * B47 · Пароль Wi-Fi · нижний лист. Числа — из design/v5/B47.json:
 *
 *   лист       выпуклый −3,840 1409×1172 (r 66); захват 644,865 117×11 G11
 *   заголовок  SSID 59,902 60/700 G1; «Защищена паролем · WPA2» 59,982 32/600 G4
 *   метка      «Пароль» 59,1055 30/600 G5
 *   поле       вдавленное 56,1102 1292×124 (r 48, дно G12); ввод 48/700 в 100,1133;
 *              курсор 4×56 в 378,1135 — сразу за текстом; «Показать» 1108,1119 219×94
 *   тумблер    «Подключаться автоматически» 1214,1267.4 139×73; подпись 59,1279.5 38/700
 *   клавиатура [Keyboard], верх 1410; тёмная клавиша справа внизу — «Подключить»
 *
 * Состояние внутри: [text], [hidden] («Показать»/«Скрыть»). Экран зовёт [open], рисует [draw],
 * передаёт касания в [hit] и сам подключается по [ACT_CONNECT].
 */
class WifiPasswordSheet(private val ink: Ink, private val act: EinkActivity) {

    var text = ""
        private set

    /** Тумблер «Подключаться автоматически»; экран передаёт его в `WifiScan.connect`. */
    var auto = true
        private set

    /**
     * Ошибка справа на строке метки «Пароль»: короткий пароль или отказ системы.
     * Сбрасывается при вводе.
     */
    var error = ""

    private var ssid = ""
    private var note = ""
    private var shown = false
    private val kb = Keyboard(ink, { act.prefs.layouts }, act::keyPressed)

    fun open(ssid: String, note: String) {
        this.ssid = ssid
        this.note = note
        text = ""
        error = ""
        auto = true
        shown = false
        kb.setAction("Подключить")
        // Пароли Wi-Fi почти всегда латиницей: лист открывается с английской раскладкой.
        kb.setLang(Keyboard.LANG_EN)
    }

    fun draw(c: Canvas) {
        ink.convex(c, -3f, TOP, 1409f, 1172f, 66f)
        ink.roundRect(c, 644f, 865f, 117f, 11f, 5f, T.G11)
        ink.text(c, ink.ellipsize(ssid, 1286f, 60f, 700), 59f, T.base(902f, 60f, 1.25f), 60f, 700, T.G1)
        ink.text(c, ink.ellipsize(note, 1286f, T.TS_BODY_S, 600), 59f, T.base(982f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)
        ink.text(c, "Пароль", 59f, T.base(1055f, T.TS_META, 1.27f), T.TS_META, 600, T.G5)

        ink.inset(c, 56f, 1102f, 1292f, 124f, 48f, T.G12)
        val base = T.base(1133f, 48f, 1.25f)
        val shownText = if (shown) text else "•".repeat(text.length)
        if (shownText.isEmpty()) {
            // Курсор 378,1135 стоит сразу за текстом; у пустого поля — в начале, подсказка за ним.
            ink.rect(c, 100f, 1135f, 4f, 56f, T.G1)
            ink.text(c, "Введите пароль сети", 114f, base, 40f, 500, T.G6)
        } else {
            val s = ink.ellipsize(shownText, 950f, 48f, 700)
            ink.text(c, s, 100f, base, 48f, 700, T.G1)
            ink.rect(c, 104f + ink.measure(s, 48f, 700), 1135f, 4f, 56f, T.G1)
        }
        showKey(c, if (shown) "Скрыть" else "Показать")
        // Ошибка — справа на строке метки «Пароль» (59,1055): под полем её строка легла бы
        // вплотную к тумблеру «Подключаться автоматически» (1267.4). 30/700 G1, как у NameSheet.
        if (error.isNotEmpty()) {
            val s = ink.ellipsize(error, 1050f, T.TS_META, 700)
            ink.text(c, s, 1292f - ink.measure(s, T.TS_META, 700), T.base(1055f, T.TS_META, 1.27f),
                T.TS_META, 700, T.G1)
        }

        toggle(c, 1214f, 1267.4f, auto)
        ink.text(c, "Подключаться автоматически", 59f, T.base(1279.5f, T.TS_LABEL_L, 1.25f),
            T.TS_LABEL_L, 700, T.G1)

        kb.draw(c)
    }

    /** «Показать» 1108,1119 219×94 — выпуклая клавиша, подпись 32/700 по центру поверхности 214×84. */
    private fun showKey(c: Canvas, label: String) {
        ink.convex(c, 1108f, 1119f, 219f, 94f, 50f)
        ink.textCenter(c, label, 1111f, 214f, ink.baselineIn(1122f, 84f, T.TS_BODY_S, 700),
            T.TS_BODY_S, 700, T.G1)
    }

    /**
     * Касание: [ACT_NONE], [ACT_CHANGED] (перерисовать), [ACT_CANCEL] (мимо листа),
     * [ACT_CONNECT] (тёмная клавиша «Подключить»).
     */
    fun hit(x: Float, y: Float): Int {
        if (y < TOP) return ACT_CANCEL
        if (y < Keyboard.TOP - 5f) {
            when {
                // Строка тумблера: вся полоса 1206…1362 по x, высота 100.
                y >= 1255f && y <= 1355f && x >= 1186f -> {
                    auto = !auto
                    return ACT_CHANGED
                }
                y >= 1119f && y <= 1213f && x >= 1090f -> {
                    shown = !shown
                    return ACT_CHANGED
                }
                else -> return ACT_NONE
            }
        }
        val k = kb.hit(x, y) ?: return ACT_NONE
        when (k.kind) {
            Keyboard.K_DONE -> {
                if (text.isEmpty()) return ACT_NONE
                // Пароль WPA2 — от 8 символов: короче система не примет (`validatePsk`), а лист
                // закрылся бы молча. Проверено на книге 2026-11-19.
                if (text.length < MIN) {
                    error = "Пароль Wi-Fi — не короче $MIN символов"
                    return ACT_CHANGED
                }
                return ACT_CONNECT
            }
            Keyboard.K_DEL -> {
                if (text.isEmpty()) return ACT_NONE
                text = text.dropLast(1)
            }
            else -> {
                val s = kb.press(k)
                if (s != null && text.length < MAX) text += s
            }
        }
        error = ""
        return ACT_CHANGED
    }

    /** Тумблер листа: 139×73, включён — дно G3 и ручка справа. */
    private fun toggle(c: Canvas, x: Float, y: Float, on: Boolean) {
        ink.inset(c, x, y, 139f, 73f, 37f, if (on) T.G3 else T.G12)
        ink.convex(c, if (on) x + 70f else x + 4f, y + 4f, 63f, 68f, 29f)
    }

    companion object {
        const val ACT_NONE = 0
        const val ACT_CHANGED = 1
        const val ACT_CANCEL = 2
        const val ACT_CONNECT = 3

        /** Длина пароля WPA2 — от 8 до 63 символов (иначе система отвергает конфиг). */
        const val MIN = 8
        const val MAX = 63

        /** Верх листа 840 (на экране 1872, высота 1172 — до низа). */
        private const val TOP = 840f
    }
}
