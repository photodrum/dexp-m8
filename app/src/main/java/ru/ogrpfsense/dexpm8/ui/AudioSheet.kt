package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.AudioPlayer
import ru.ogrpfsense.dexpm8.core.Prefs

/**
 * B35 · «Таймер и скорость» — нижний лист плеера (п. 12). Числа — `design/v5/B35.json`.
 *
 *   лист      −3,760 1409×1242 (r 66), захват 644,785 117×11 G11
 *   шапка     «Таймер и скорость» 59,816 Manrope/60/700; «Готово» тёмная 1080,802.6 273×108
 *   таймер    метка 59,940 30/600 G5; желоб 56,990 1292×95: «Выкл», «15 мин», «30 мин»,
 *             «60 мин», «Конец главы» (активный сегмент — выпуклая плашка)
 *   подсказка 59,1110 32/600 G4 («Остановится через 24 мин — в середине главы 14»)
 *   скорость  метка 59,1200; «−» 56,1250 115×120; значение 196,1250 240×116 (вдавленное, r 38);
 *             ползунок 470,1262 730×98 (трек 37, заполнение G3, ручка Ø88); «+» 1238,1250
 *   пресеты   желоб 56,1420 1292×88, пять подписей 32/600
 *   паузы     строка 59,1566: «Пропускать паузы» 42/700, описание 32/600 G4, тумблер 1206,1578
 *
 * Высота листа 1242, а подписи макета кончаются на 1659 — строка «Пропускать паузы» стоит
 * вплотную к нижнему краю. Рисуем как в макете: её нижняя граница 1659 при дне 2002.
 *
 * Скорость меняется сразу (звук идёт с новой скоростью на ходу); таймер взводится по
 * «Готово» — в макете это одна кнопка на весь лист.
 */
internal class AudioSheet(private val ink: Ink, private val icons: Icons) {

    /** Что выбрано на листе. */
    var speed = 100
    var sleep = 0

    /** «Пропускать паузы» (B35): пишется в `Prefs.audioSkipPauses`, читает сканер/плеер. */
    var skipPauses = false

    /**
     * Признак «на листе что-то поменяли»: экран читает его после [hit] и переносит
     * значения в плеер. null — нажатие мимо или не меняющее значения.
     */
    var result: Boolean? = null

    fun draw(c: Canvas, speed: Int, sleepLeft: Long, chapter: Boolean, timerOn: Boolean) {
        // Лист: тень G10, блик G15, поверхность G14 (r 66); сверху — захват.
        ink.convex(c, -3f, 760f, 1409f, 1242f, 66f)
        ink.roundRect(c, 644f, 785f, 117f, 11f, 5.5f, T.G11)

        ink.text(c, "Таймер и скорость", 59f, T.base(816f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.convexDark(c, 1080f, 802.6f, 273f, 108f, T.R_BUTTON.toFloat())
        ink.textCenter(c, "Готово", 1083f, 268f, T.base(828.3f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)

        // Таймер сна: метка и пять сегментов.
        ink.text(c, "ТАЙМЕР СНА", 59f, T.base(940f, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
        segments(c, 990f, 95f, SLEEP_LABELS, sleepIndex())
        ink.text(c, hint(sleepLeft, chapter, timerOn), 59f, T.base(1110f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)

        // Скорость: «−», значение, ползунок, «+» и пять пресетов.
        ink.text(c, "СКОРОСТЬ ЧТЕНИЯ", 59f, T.base(1200f, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
        ink.convex(c, 56f, 1250f, 115f, 120f, 55f)
        icons.drawRound(c, "minus", 56f, 1250f, 115f, 120f, 30.9f, 2.2f)
        ink.inset(c, 196f, 1250f, 240f, 116f, 40f, T.G12)
        ink.textCenter(c, Prefs.Speed.label(speed), 199f, 235f, T.base(1278.3f, 48f, 1.2f), 48f, 700, T.G1)
        ink.convex(c, 1238f, 1250f, 115f, 120f, 55f)
        icons.drawRound(c, "plus", 1238f, 1250f, 115f, 120f, 30.9f, 33.1f)

        // Ползунок 470,1262: трек 730×37, заполнение G3, ручка Ø88.
        val pct = (speed - Prefs.Speed.MIN).toFloat() / (Prefs.Speed.MAX - Prefs.Speed.MIN)
        ink.inset(c, 470f, 1290f, 730f, 37f, 18f, T.G12)
        val fillW = 715f * pct
        if (fillW >= 10f) ink.roundRect(c, 473f, 1296f, fillW, 28f, 14f, T.G3)
        ink.convex(c, 470f + fillW, 1262f, 93f, 98f, 44f)

        segments(c, 1420f, 88f, Prefs.Speed.LABELS, Prefs.Speed.PRESETS.indexOf(speed))

        // «Пропускать паузы»: строка у нижнего края листа.
        ink.text(c, "Пропускать паузы", 59f, T.base(1566f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, "Сокращает долгие паузы между фразами", 59f,
            T.base(1619f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.inset(c, 1206f, 1578f, 139f, 73f, 37f, if (skipPauses) T.G3 else T.G12)
        ink.convex(c, if (skipPauses) 1276f else 1210f, 1582f, 63f, 68f, 29f)
    }

    /** Пять сегментов в желобе 56,[y] 1292×[h]; активный — выпуклая плашка. */
    private fun segments(c: Canvas, y: Float, h: Float, labels: Array<String>, active: Int) {
        ink.inset(c, 56f, y, 1292f, h, h / 2f, T.G12)
        val step = 1287f / labels.size
        for (i in labels.indices) {
            val x = 59f + step * i
            val on = i == active
            if (on) ink.convex(c, x, y + 4f, step - 5f, h - 15f, (h - 15f) / 2f)
            ink.textCenter(c, labels[i], x, step - 5f, T.base(y + 26f, T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, if (on) 700 else 600, if (on) T.G1 else T.G5)
        }
    }

    /** Номер сегмента таймера по текущему значению; «Выкл» — 0. */
    private fun sleepIndex(): Int = when {
        sleep == AudioPlayer.SLEEP_CHAPTER -> 4
        sleep <= 0 -> 0
        sleep <= 15 -> 1
        sleep <= 30 -> 2
        else -> 3
    }

    /**
     * Подсказка под сегментами: «Остановится через 24 мин — в середине главы 14».
     * Когда таймера нет — что делает «Конец главы» (в макете подписи нет, вопрос дизайну
     * не нужен: смысл понятен из названия сегмента).
     */
    private fun hint(leftMs: Long, chapter: Boolean, on: Boolean): String = when {
        !on -> "Выберите время — музыка остановится сама"
        chapter -> "Остановится в конце главы"
        else -> {
            val min = Math.max(1L, (leftMs + 59_999L) / 60_000L)
            "Остановится через $min мин"
        }
    }

    // ------------------------------------------------------------------ касания

    /** Что нажали: [ACT_CLOSE] — «Готово», [ACT_CHANGED] — значение изменилось. */
    fun hit(x: Float, y: Float): Int {
        result = null
        if (x >= 1080f && x <= 1353f && y >= 802.6f && y <= 910.6f) return ACT_CLOSE

        // Таймер: сегмент 1..5.
        if (y >= 985f && y <= 1090f && x >= 56f && x <= 1348f) {
            val step = 1287f / SLEEP_VALUES.size
            val i = ((x - 59f) / step).toInt().coerceIn(0, SLEEP_VALUES.size - 1)
            if (sleep != SLEEP_VALUES[i]) {
                sleep = SLEEP_VALUES[i]
                result = true
            }
            return ACT_CHANGED
        }
        // «−» / «+» — соседний пресет.
        if (y >= 1250f && y <= 1370f) {
            when {
                x in 56f..171f -> step(-1)
                x in 1238f..1353f -> step(1)
            }
            return if (result == true) ACT_CHANGED else 0
        }
        // Ползунок: касание дорожки — ближайший пресет из пяти.
        if (y >= 1262f && y <= 1360f && x in 470f..1200f) {
            val pct = ((x - 473f) / 715f).coerceIn(0f, 1f)
            val v = Prefs.Speed.MIN + pct * (Prefs.Speed.MAX - Prefs.Speed.MIN)
            val near = Prefs.Speed.PRESETS.minBy { Math.abs(it - v).toInt() }
            if (near != speed) {
                speed = near
                result = true
            }
            return ACT_CHANGED
        }
        // Пресеты скорости.
        if (y >= 1415f && y <= 1513f && x >= 56f && x <= 1348f) {
            val step = 1287f / Prefs.Speed.PRESETS.size
            val i = ((x - 59f) / step).toInt().coerceIn(0, Prefs.Speed.PRESETS.size - 1)
            if (Prefs.Speed.PRESETS[i] != speed) {
                speed = Prefs.Speed.PRESETS[i]
                result = true
            }
            return ACT_CHANGED
        }
        // «Пропускать паузы» — тумблер строки.
        if (y >= 1560f && y <= 1660f) {
            skipPauses = !skipPauses
            result = true
        }
        return 0
    }

    /** Сдвиг скорости на один пресет вверх ([dir] = 1) или вниз. */
    private fun step(dir: Int) {
        val i = Prefs.Speed.PRESETS.indexOf(speed).let { if (it < 0) 1 else it }
        val n = (i + dir).coerceIn(0, Prefs.Speed.PRESETS.size - 1)
        if (Prefs.Speed.PRESETS[n] != speed) {
            speed = Prefs.Speed.PRESETS[n]
            result = true
        }
    }

    companion object {
        const val ACT_CLOSE = 1
        const val ACT_CHANGED = 2

        /** Подписи сегментов таймера из макета B35. */
        val SLEEP_LABELS = arrayOf("Выкл", "15 мин", "30 мин", "60 мин", "Конец главы")

        /** Значения тех же сегментов: минуты или [AudioPlayer.SLEEP_CHAPTER]. */
        val SLEEP_VALUES = intArrayOf(0, 15, 30, 60, AudioPlayer.SLEEP_CHAPTER)
    }
}
