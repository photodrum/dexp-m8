package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.core.AudioPlayer
import ru.ogrpfsense.dexpm8.core.Prefs

/**
 * B35 · «Таймер и скорость» — нижний лист плеера (п. 12). Числа — `design/v5/B35.json`.
 *
 *   лист      −3,760 1409×1242 (r 66), захват 644,785 117×11 G11
 *   шапка     «Таймер и скорость» 59,816 Manrope/60/700; «Готово» тёмная 1080,802.6 273×108
 *   таймер    метка 59,940 30/600 G5; полоска 56,990 1292×95: «Выкл», «15 мин», «30 мин»,
 *             «60 мин», «Конец главы» (активный сегмент — выпуклая плашка)
 *   подсказка 59,1110 32/600 G4 («Остановится через 24 мин — в середине главы 14»)
 *   скорость  метка 59,1200; полоска 56,1250 1292×95: «0,75×» … «2×» (та же система)
 *   паузы     строка 59,1403: «Пропускать паузы» 42/700, описание 32/600 G4, тумблер 1206,1415
 *
 * Скорость меняется сразу (звук идёт с новой скоростью на ходу); таймер взводится по
 * «Готово» — в макете это одна кнопка на весь лист.
 *
 * **Управление скоростью — одна полоска (решения 2026-11-20).** В макете скорость задавалась
 * четырьмя способами: «−/+», ползунком 470…1200, окошком значения и полоской пресетов. Сначала
 * убран ползунок, потом «−/+» и окошко: пять фиксированных значений в полоске и есть управление,
 * на E-Ink это попадание в сегмент 256.8×95 вместо кнопок 115 и дорожки 37 px. Освободившееся
 * место (ряд 1250…1420) занято: блок скорости поднят, «Пропускать паузы» — сразу под ним.
 *
 * **Полоски одной высоты.** В макете таймер сна был 95, пресеты 88. Теперь обе — [SEG_H] = 95
 * с плашкой 90 и одинаковой подписью: у скорости и у таймера один и тот же элемент.
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
        segments(c, 990f, SLEEP_LABELS, sleepIndex())
        ink.text(c, hint(sleepLeft, chapter, timerOn), 59f, T.base(1110f, T.TS_BODY_S, 1.25f),
            T.TS_BODY_S, 600, T.G4)

        // Скорость: только метка и та же полоска — «−/+» и окошко значения убраны
        // (решение 2026-11-20): пять фиксированных значений в полоске и есть управление.
        ink.text(c, "СКОРОСТЬ ЧТЕНИЯ", 59f, T.base(1200f, T.TS_META, 1.25f), T.TS_META, 600, T.G5)
        segments(c, 1250f, Prefs.Speed.LABELS, Prefs.Speed.PRESETS.indexOf(speed))

        // «Пропускать паузы»: строка поднята на освободившееся место, зазор до полоски 58 —
        // как в макете было между пресетами (низ 1508) и этой строкой (1566).
        ink.text(c, "Пропускать паузы", 59f, T.base(1403f, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, T.G1)
        ink.text(c, "Сокращает долгие паузы между фразами", 59f,
            T.base(1456f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        ink.inset(c, 1206f, 1415f, 139f, 73f, 37f, if (skipPauses) T.G3 else T.G12)
        ink.convex(c, if (skipPauses) 1276f else 1210f, 1419f, 63f, 68f, 29f)
    }

    /**
     * Полоска сегментов: желоб 56,[y] 1292×[SEG_H] и выпуклая плашка активного.
     *
     * **Единая система (решение 2026-11-20).** В макете B35 полоски были разной высоты:
     * таймер сна 95 (плашка 90), пресеты 88 (плашка 83). Теперь обе — [SEG_H] = 95, плашка
     * 256.8×90 с радиусом 40, подпись через 26 от верха. Числа взяты у полоски таймера как
     * у первой по макету; пресеты выросли на 7 px, зато полоски читаются как один элемент.
     *
     * Остальное из B35.json: подпись 60,[y+26] 251.8×40 (шаг 256.8), у активной +1 px.
     * Шаг 256.8, а не 1287/5: у старого деления к пятому сегменту набегало 2.4 px.
     */
    private fun segments(c: Canvas, y: Float, labels: Array<String>, active: Int) {
        ink.inset(c, 56f, y, 1292f, SEG_H, SEG_H / 2f, T.G12)
        for (i in labels.indices) {
            val x = SEG_X0 + SEG_STEP * i
            val on = i == active
            if (on) ink.convex(c, x, y + 4f, SEG_STEP, PLATE_H, PLATE_R)
            val top = y + SEG_TEXT_DY + if (on) 1f else 0f
            ink.textCenter(c, labels[i], x, SEG_BOX_W, T.base(top, T.TS_BODY_S, 1.25f),
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
        // Скорость: та же полоска, что у таймера. «−/+» и окошка значения больше нет
        // (решение 2026-11-20) — зоны касания на их месте тоже нет.
        if (y >= 1245f && y <= 1350f && x >= 56f && x <= 1348f) {
            val i = ((x - SEG_X0) / SEG_STEP).toInt().coerceIn(0, Prefs.Speed.PRESETS.size - 1)
            if (Prefs.Speed.PRESETS[i] != speed) {
                speed = Prefs.Speed.PRESETS[i]
                result = true
            }
            return ACT_CHANGED
        }
        // «Пропускать паузы» — тумблер строки (строка поднялась вместе с блоком скорости).
        if (y >= 1395f && y <= 1500f) {
            skipPauses = !skipPauses
            result = true
        }
        return 0
    }

    companion object {
        const val ACT_CLOSE = 1
        const val ACT_CHANGED = 2

        /** Левая граница первого сегмента и шаг из B35.json: подписи 60, 316.8, 573.6 … */
        private const val SEG_X0 = 60f
        private const val SEG_STEP = 256.8f

        /** Единая геометрия полоски (решение 2026-11-20): желоб, плашка, подпись. */
        private const val SEG_H = 95f
        private const val PLATE_H = 90f
        private const val PLATE_R = 40f
        private const val SEG_BOX_W = SEG_STEP - 5f     // 251.8 — рамка подписи
        private const val SEG_TEXT_DY = 26f             // подпись через 26 от верха полоски

        /** Подписи сегментов таймера из макета B35. */
        val SLEEP_LABELS = arrayOf("Выкл", "15 мин", "30 мин", "60 мин", "Конец главы")

        /** Значения тех же сегментов: минуты или [AudioPlayer.SLEEP_CHAPTER]. */
        val SLEEP_VALUES = intArrayOf(0, 15, 30, 60, AudioPlayer.SLEEP_CHAPTER)
    }
}
