package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import kotlin.random.Random
import ru.ogrpfsense.dexpm8.chess.Color
import ru.ogrpfsense.dexpm8.chess.Level

/**
 * B78 · Шахматы · новая партия с компьютером. Числа — design/v5/B78.json.
 *
 *   «УРОВЕНЬ»    59,250 30/700 G5; «слабее» 56,300 115×120 (круглая, `back`); значение — вдавленная
 *                196,300 240×116 r 40, цифра 48/700 по центру (y 328.3); «сильнее» 470,300;
 *                название 625,306 42/700; описание 625,360 32/600 G4
 *   «ЦВЕТ»       59,480; желоб 56,530 1292×95 r 47.5, три сегмента по 428 (плашка 428×90 на y+4);
 *                подпись 32/700 G1 (активная, y+27) и 32/600 G5 (y+26)
 *   пояснение    59, 680 (в макете 850: см. отступление)
 *   кнопка       «Начать партию» тёмная 41,1694 1321×142, подпись 42/700 G15
 *
 * **Отступление от макета (25.2):** раздела «ЧАСЫ» (Без часов / 15 + 10 / 30 + 0) нет — на B79 нет
 * места под часы партии с компьютером, а без макета часов их не нарисовать (`DESIGN_QUESTIONS.md`);
 * пояснение про сон книги с часами убрано, остальное поднято на место раздела. Уровень и цвет
 * запоминаются в `Prefs` и подставляются при следующем входе.
 */
class ChessNewScreen(act: EinkActivity) : Screen(act) {

    private val status = StatusBar(act, this, ink, icons)
    private val kit = SettingsKit(ink, icons)
    private var level = 4
    private var color = 0

    override fun render(c: Canvas) {
        ink.rect(c, 0f, 0f, W, H, T.BG)
        kit.header(c, "С компьютером", "Новая партия")

        kit.section(c, "УРОВЕНЬ", 250f)
        ink.convex(c, 56f, 300f, 115f, 120f, 55f)
        icons.drawRound(c, "back@54.8x57.1", 56f, 300f, 115f, 120f, 53f, 55.2f)
        ink.inset(c, 196f, 300f, 240f, 116f, 40f)
        ink.textCenter(c, level.toString(), 199f, 236f, T.base(328.3f, 48f, 1.2f), 48f, 700, T.G1)
        ink.convex(c, 470f, 300f, 115f, 120f, 55f)
        icons.drawRound(c, "next@53x53", 470f, 300f, 115f, 120f, 53f, 53f)
        ink.text(c, "Уровень $level из ${Level.COUNT}", 625f, T.base(306f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G1)
        ink.text(c, ink.ellipsize(DESC[level - 1], 735f, T.TS_BODY_S, 600), 625f,
            T.base(360f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        kit.section(c, "ЦВЕТ", 480f)
        trough(c, 530f, arrayOf("Белые", "Чёрные", "Случайно"), color)

        ink.text(c, "Партия сохраняется сама: из неё можно выйти в любой момент.", 59f,
            T.base(680f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)

        ink.convexDark(c, 41f, 1694f, 1321f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Начать партию", 44f, 1316f, T.base(1737f, T.TS_ACTION, 1.25f),
            T.TS_ACTION, 700, T.G15)
        status.draw(c)
    }

    /** Желоб из трёх сегментов (макет B78 — шире, чем у настроек: 1292 вместо 1221). */
    private fun trough(c: Canvas, y: Float, labels: Array<String>, active: Int) {
        ink.inset(c, 56f, y, 1292f, 95f, 47.5f)
        for (i in labels.indices) {
            val x = 60f + 428f * i
            val on = i == active
            if (on) ink.convex(c, x, y + 4f, 428f, 90f, 40f)
            ink.textCenter(c, labels[i], x, 423f, T.base(y + (if (on) 27f else 26f), T.TS_BODY_S, 1.25f),
                T.TS_BODY_S, if (on) 700 else 600, if (on) T.G1 else T.G5)
        }
    }

    override fun onUp(x: Float, y: Float) {
        if (kit.isBack(x, y)) {
            act.pop()
            return
        }
        when {
            // «Слабее» / «сильнее» — по клавишам, зона шире на 20 px (тач ≥ 96 px).
            y in 290f..430f && x in 46f..181f -> setLevel(level - 1)
            y in 290f..430f && x in 460f..595f -> setLevel(level + 1)
            y in 520f..635f && x in 56f..1348f -> {
                val i = ((x - 60f) / 428f).toInt().coerceIn(0, 2)
                if (i != color) {
                    color = i
                    act.prefs.chessColor = i
                    redraw()
                }
            }
            y in 1694f..1836f && x in 41f..1362f -> start()
        }
    }

    private fun setLevel(v: Int) {
        val n = v.coerceIn(1, Level.COUNT)
        if (n == level) return
        level = n
        act.prefs.chessLevel = n
        redraw()
    }

    private fun start() {
        val me = when (color) {
            0 -> Color.WHITE
            1 -> Color.BLACK
            else -> if (Random.nextBoolean()) Color.WHITE else Color.BLACK
        }
        ChessSession.start(level, me)
        act.prefs.chessSave = ChessSession.encode()
        act.go(EinkActivity.SCR_CHESS)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        status.attach()
        level = act.prefs.chessLevel
        color = act.prefs.chessColor
    }

    override fun onDetachedFromWindow() {
        status.detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Описание уровня — по числам `Level` (глубина, время, неточность). */
        val DESC = arrayOf(
            "Играет почти наугад · для знакомства",
            "Часто зевает фигуры",
            "Простые ошибки · ход за секунду",
            "Ход за доли секунды · иногда ошибается",
            "Считает на 4 хода · редко ошибается",
            "Считает на 4 хода · без ошибок",
            "Думает до 8 секунд",
            "Сильнейший · думает до 20 секунд",
        )
    }
}
