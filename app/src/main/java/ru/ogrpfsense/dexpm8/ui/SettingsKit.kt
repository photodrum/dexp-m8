package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas

/**
 * Общие элементы экранов «Настройки» B37–B43. Числа — из design/v5/B37.json (остальные борды
 * этого раздела собраны из тех же деталей).
 *
 *   шапка      «Назад» 41,113.5 (107×112); заголовок 176,105 60/700; подзаголовок 176,181 32/600 G4
 *   раздел     подпись 59,y 30/700 G5 капсом; панель x 41, 1321 шириной, радиус R_TILE
 *   строка     название 42/700 G1 от x 88; значение 38/700 справа до 1316; описание 32/600 G4
 *   ползунок   дорожка 160 × 1060 (вдавленная), заполнение G3, ручка 93×98
 *   сегменты   желоб 1221×95, активный сегмент — выпуклая плашка 404.3×90
 *   тумблер    желоб 139×73; включён — дно G3, ручка справа; выключен — дно G12, ручка слева
 *
 * Только рисование: значения передаёт экран, ввод не обрабатывается (запись — шаг 4.6).
 */
class SettingsKit(private val ink: Ink, private val icons: Icons) {

    // ---------------------------------------------------------------- шапка

    fun header(c: Canvas, title: String, subtitle: String) {
        ink.convex(c, 41f, 113.5f, 107f, 112f, 51.2f)
        icons.draw(c, "back@12.3x26.6", 87.9f, 155.3f, 12.3f, 26.6f)
        ink.text(c, title, 176f, T.base(105f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, subtitle, 176f, T.base(181f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Попадание в «Назад». */
    fun isBack(x: Float, y: Float) = x >= 41f && x <= 148f && y >= 113.5f && y <= 225.5f

    // ---------------------------------------------------------------- разделы

    fun section(c: Canvas, label: String, y: Float) {
        ink.text(c, label, 59f, T.base(y, T.TS_META, 1.25f), T.TS_META, 700, T.G5)
    }

    fun panel(c: Canvas, y: Float, h: Float) {
        ink.convex(c, 41f, y, 1321f, h, T.R_TILE)
    }

    // ---------------------------------------------------------------- строки

    fun title(c: Canvas, s: String, top: Float) {
        ink.text(c, s, 88f, T.base(top, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
    }

    fun desc(c: Canvas, s: String, top: Float) {
        ink.text(c, s, 88f, T.base(top, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
    }

    /** Крупное значение (проценты у ползунка): 38/700 G1, правый край 1316. */
    fun valueBig(c: Canvas, s: String, top: Float) =
        right(c, s, 1316f, top, T.TS_LABEL_L, 700, T.G1)

    /** Значение строки со стрелкой: 34/600 G4, правый край 1267.6. */
    fun valueRow(c: Canvas, s: String, top: Float) =
        right(c, s, 1267.6f, top, T.TS_BODY, 600, T.G4)

    fun next(c: Canvas, y: Float) {
        icons.draw(c, "next@11x23.8", 1305f, y, 11f, 23.8f)
    }

    fun divider(c: Canvas, y: Float) {
        ink.rect(c, 88f, y, 1228f, 2f, T.G11)
    }

    private fun right(c: Canvas, s: String, rx: Float, top: Float, fs: Float, fw: Int, color: Int) {
        val w = ink.measure(s, fs, fw)
        ink.text(c, s, rx - w, T.base(top, fs, 1.25f), fs, fw, color)
    }

    // ---------------------------------------------------------------- органы

    /**
     * Ползунок. [handleY] — верх ручки (в макете: яркость 408.5, тёплый свет 624);
     * [pct] — 0…100. Дорожка на 28 ниже ручки, иконки по краям — по макету.
     */
    fun slider(c: Canvas, handleY: Float, pct: Int, warm: Boolean) {
        val p = pct.coerceIn(0, 100)
        val fillW = 1055f * p / 100f
        icons.draw(c, "sunLow", 103f, handleY + 38.4f, 16.1f, 18f)
        ink.inset(c, 160f, handleY + 28f, 1060f, 37f, 18f, T.G12)
        if (fillW >= 28f) ink.roundRect(c, 163f, handleY + 34f, fillW, 28f, 14f, T.G3)
        ink.convex(c, 163f + fillW - 46f, handleY, 93f, 98f, 44f)
        if (warm) icons.draw(c, "moon@29.1x31.1", 1286.9f, handleY + 31.9f, 29.1f, 31.1f)
        else icons.draw(c, "sunHigh", 1275f, handleY + 26f, 41f, 42.9f)
    }

    /** Три сегмента в желобе; [active] — номер активного, [labels] — подписи. */
    fun segments(c: Canvas, y: Float, labels: Array<String>, active: Int) {
        ink.inset(c, 88f, y, 1221f, 95f, 47.5f, T.G12)
        val step = 404.4f
        for (i in labels.indices) {
            val x = 92f + step * i
            val on = i == active
            if (on) ink.convex(c, x, y + 4f, 404.3f, 90f, 40f)
            ink.textCenter(c, labels[i], x, 399.3f, T.base(y + (if (on) 27f else 26f), T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, if (on) 700 else 600, if (on) T.G1 else T.G5)
        }
    }

    /** Тумблер 139×73 с верхним левым углом в (1177, y). */
    fun toggle(c: Canvas, y: Float, on: Boolean) {
        ink.inset(c, 1177f, y, 139f, 73f, 37f, if (on) T.G3 else T.G12)
        ink.convex(c, if (on) 1247f else 1181f, y + 4f, 63f, 68f, 29f)
    }
}
