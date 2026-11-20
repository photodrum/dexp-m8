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
 *             «+» 1238,1250
 *   пресеты   желоб 56,1420 1292×88, пять подписей 32/600
 *   паузы     строка 59,1566: «Пропускать паузы» 42/700, описание 32/600 G4, тумблер 1206,1578
 *
 * Высота листа 1242, а подписи макета кончаются на 1659 — строка «Пропускать паузы» стоит
 * вплотную к нижнему краю. Рисуем как в макете: её нижняя граница 1659 при дне 2002.
 *
 * Скорость меняется сразу (звук идёт с новой скоростью на ходу); таймер взводится по
 * «Готово» — в макете это одна кнопка на весь лист.
 *
 * **Ползунок скорости убран (решение 2026-11-20).** В макете скорость задаётся тремя способами
 * сразу: «−/+», ползунком 470…1200 и линейкой пресетов. Ползунок дублировал остальные два и на
 * E-Ink требовал попадания в дорожку 37 px, поэтому удалён вместе с зоной касания; линейка
 * фиксированных значений 0,75× … 2× внизу осталась единственным способом выбора, «−/+» —
 * шагом по ней. Место ползунка между значением и «+» остаётся пустым, как в макете.
 */
internal class AudioSheet(private val ink: Ink) {

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

        // Скорость: «−», значение, «+» и пять пресетов. Ползунок макета 470…1200 не рисуем
        // (решение 2026-11-20): он дублирует «−/+» и линейку пресетов.
        ink.text(c, "СКОРОСТЬ ЧТЕНИЯ", 59f, T.base(1200f, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
        ink.convex(c, 56f, 1250f, 115f, 120f, 55f)
        // «−» из снимка (minus~bxdhcj): штрих 3, путь 30.92, в макете стоит в 98.5,1308.
        ink.line(c, 98.5f, 1308f, 98.5f + 30.92f, 1308f, 3f, T.G4)
        ink.inset(c, 196f, 1250f, 240f, 116f, 40f, T.G12)
        ink.textCenter(c, Prefs.Speed.label(speed), 199f, 235f, T.base(1278.3f, 48f, 1.2f), 48f, 700, T.G1)
        ink.convex(c, 1238f, 1250f, 115f, 120f, 55f)
        // «+» из снимка: 30.9×30.9, штрих 3, центр 1295.96 / 1307.96 (макет 1280.5,1292.5
        // + 15.46). Раньше рисовался линиями 25.6 со сдвигом вверх и влево.
        ink.line(c, 1295.96f, 1307.96f - 15.46f, 1295.96f, 1307.96f + 15.46f, 3f, T.G4)
        ink.line(c, 1295.96f - 15.46f, 1307.96f, 1295.96f + 15.46f, 1307.96f, 3f, T.G4)

        segments(c, 1420f, 88f, Prefs.Speed.LABELS, Prefs.Speed.PRESETS.indexOf(speed))

        // «Пропускать паузы»: строка у нижнего края листа.
        ink.text(c, "Пропускать паузы", 59f, T.base(1566f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, "Сокращает долгие паузы между фразами", 59f,
            T.base(1619f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.inset(c, 1206f, 1578f, 139f, 73f, 37f, if (skipPauses) T.G3 else T.G12)
        ink.convex(c, if (skipPauses) 1276f else 1210f, 1582f, 63f, 68f, 29f)
    }

    /**
     * Пять сегментов в желобе 56,[y] 1292×[h]; активный — выпуклая плашка.
     *
     * Числа из B35.json: подпись 60,1016 251.8×40 (шаг 256.8), плашка 573.6,994 256.8×90
     * (у пресетов 573.6,1424 256.8×83), блик плашки — 251.8 × (h−15) с радиусом h/2−5.
     *
     * Отличия от первой версии (замечание 2026-11-20): шаг был 1287/5 = 257.4 вместо 256.8
     * (к пятому сегменту набегало 2.4 px), плашка — step−5 × h−15 вместо 256.8 × h−5 (ниже
     * макета на 10 px), подпись — по центру своей рамки 251.8, а не по центру плашки, и на
     * 3.5 px ниже у пресетов. Активный сегмент в макете стоит на 1 px ниже соседей: жирное
     * начертание (700) садится выше — компенсируем.
     */
    private fun segments(c: Canvas, y: Float, h: Float, labels: Array<String>, active: Int) {
        ink.inset(c, 56f, y, 1292f, h, h / 2f, T.G12)
        val boxW = SEG_STEP - 5f          // 251.8 — рамка подписи и блик плашки
        val plateH = h - 5f               // 90 у таймера, 83 у пресетов
        for (i in labels.indices) {
            val x = SEG_X0 + SEG_STEP * i
            val on = i == active
            if (on) ink.convex(c, x, y + 4f, SEG_STEP, plateH, (plateH - T.SHADOW_DY) / 2f)
            val top = y + h / 2f - 21.5f + if (on) 1f else 0f
            ink.textCenter(c, labels[i], x, boxW, T.base(top, T.TS_BODY_S, 1.25f),
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
            val i = ((x - SEG_X0) / SEG_STEP).toInt().coerceIn(0, SLEEP_VALUES.size - 1)
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
        // Пресеты скорости. Ползунка в ряду скорости больше нет (решение 2026-11-20):
        // место между значением и «+» не отвечает на касание.
        if (y >= 1415f && y <= 1513f && x >= 56f && x <= 1348f) {
            val i = ((x - SEG_X0) / SEG_STEP).toInt().coerceIn(0, Prefs.Speed.PRESETS.size - 1)
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

        /** Левая граница первого сегмента и шаг из B35.json: подписи 60, 316.8, 573.6 … */
        private const val SEG_X0 = 60f
        private const val SEG_STEP = 256.8f

        /** Подписи сегментов таймера из макета B35. */
        val SLEEP_LABELS = arrayOf("Выкл", "15 мин", "30 мин", "60 мин", "Конец главы")

        /** Значения тех же сегментов: минуты или [AudioPlayer.SLEEP_CHAPTER]. */
        val SLEEP_VALUES = intArrayOf(0, 15, 30, 60, AudioPlayer.SLEEP_CHAPTER)
    }
}
