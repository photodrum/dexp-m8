package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.chess.PieceType
import ru.ogrpfsense.dexpm8.chess.Square

/**
 * Листы поверх доски B79: B80 превращение пешки и B82 меню партии. Числа — design/v5/B80.json,
 * B82.json. Экран затемняет всё под листом (`Screen.dimBegin`), сам лист рисуется без затемнения.
 *
 *   B80  лист -3,1080 1409×922 (r 66), захват 644,1105; заголовок 59,1136 60/700; подзаголовок
 *        59,1220; четыре клавиши 305×330 на y 1290 (x 56 / 385 / 714 / 1043, r 44) с фигурой 150×150
 *        в (x + 77.5, 1330) и подписью 38/700 на 1510; «Отмена» 56,1694 1292×142
 *   B82  лист -3,860 1409×1142 (r 66), захват 644,885; «Партия» 59,916 60/700; «Закрыть» 1080,902.6
 *        273×108 (r 49); подзаголовок 59,996; пять строк от y 1080 шагом 152 (последняя на 1688)
 *
 * Диалоги B83 и B84 — `ChessDialogs`.
 */
internal class ChessSheets(private val ink: Ink, private val icons: Icons) {

    fun drawPromo(c: Canvas, color: Int, from: Int, to: Int) {
        sheet(c, 1080f, 922f)
        ink.text(c, "Превращение пешки", 59f, T.base(1136f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.text(c, "${Square.name(from)} – ${Square.name(to)} · выберите фигуру", 59f,
            T.base(1220f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        for (i in PROMO.indices) {
            val x = PROMO_X[i]
            ink.convex(c, x, 1290f, 305f, 330f, 44f)
            icons.draw(c, ChessBoard.icon(color, PROMO[i]), x + 77.5f, 1330f, 150f, 150f)
            ink.textCenter(c, PROMO_NAME[i], x + 3f, 300f, T.base(1510f, T.TS_LABEL_L, 1.25f),
                T.TS_LABEL_L, 700, T.G1)
        }
        ink.convex(c, 56f, 1694f, 1292f, 142f, T.R_BUTTON)
        ink.textCenter(c, "Отмена", 59f, 1287f, T.base(1739f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
    }

    /** Тип фигуры под касанием ([PieceType]), [CANCEL] («Отмена» и мимо листа) или [NONE]. */
    fun promoHit(x: Float, y: Float): Int {
        if (y in 1290f..1620f) {
            for (i in PROMO.indices) if (x >= PROMO_X[i] && x <= PROMO_X[i] + 305f) return PROMO[i]
            return NONE
        }
        return if (y < 1080f || inRect(x, y, 56f, 1694f, 1292f, 142f)) CANCEL else NONE
    }

    fun drawMenu(c: Canvas, subtitle: String, flipped: Boolean, canUndo: Boolean) {
        sheet(c, 860f, 1142f)
        ink.text(c, "Партия", 59f, T.base(916f, T.TS_TITLE, 1.25f), T.TS_TITLE, 700, T.G1)
        ink.convex(c, 1080f, 902.6f, 273f, 108f, 49f)
        ink.textCenter(c, "Закрыть", 1083f, 268f, T.base(930.6f, T.TS_LABEL_L, 1.25f), T.TS_LABEL_L, 700, T.G1)
        ink.text(c, subtitle, 59f, T.base(996f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        row(c, 0, "Предложить ничью", "Компьютер решит по оценке позиции", true, T.G1)
        row(c, 1, "Отменить ход", "Вернуть свой последний ход — только с компьютером", true,
            if (canUndo) T.G1 else T.G6)
        row(c, 2, "Перевернуть доску",
            if (flipped) "Смотреть со стороны белых" else "Смотреть со стороны чёрных", true, T.G1)
        row(c, 3, "Новая партия", "Эта партия не сохранится", true, T.G1)
        row(c, 4, "Сдаться", "Партия закончится поражением", false, T.G1)
    }

    private fun row(c: Canvas, k: Int, title: String, desc: String, divider: Boolean, color: Int) {
        val y = MENU_Y + k * 152f
        ink.text(c, title, 59f, T.base(y, T.TS_ACTION, 1.25f), T.TS_ACTION, 700, color)
        ink.text(c, desc, 59f, T.base(y + 53.5f, T.TS_BODY_S, 1.25f), T.TS_BODY_S, 600, T.G4)
        if (divider) ink.rect(c, 59f, y + 124f, 1289f, 2f, T.G11)
    }

    /** Номер строки меню ([MENU_DRAW] … [MENU_RESIGN]), [CANCEL] («Закрыть», мимо листа) или [NONE]. */
    fun menuHit(x: Float, y: Float): Int {
        if (y < 860f || inRect(x, y, 1060f, 890f, 313f, 130f)) return CANCEL
        if (y < MENU_Y - 10f || y > 1790f) return NONE
        return ((y - (MENU_Y - 10f)) / 152f).toInt().coerceIn(0, 4)
    }

    /** Нижний лист: выпуклый, от [top] до низа экрана; захват — 25 px ниже верха. */
    private fun sheet(c: Canvas, top: Float, h: Float) {
        ink.convex(c, -3f, top, 1409f, h, 66f)
        ink.roundRect(c, 644f, top + 25f, 117f, 11f, 5f, T.G11)
    }

    companion object {
        const val NONE = -1
        const val CANCEL = -2

        const val MENU_DRAW = 0
        const val MENU_UNDO = 1
        const val MENU_FLIP = 2
        const val MENU_NEW = 3
        const val MENU_RESIGN = 4

        private const val MENU_Y = 1080f
        private val PROMO = intArrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
        private val PROMO_NAME = arrayOf("Ферзь", "Ладья", "Слон", "Конь")
        private val PROMO_X = floatArrayOf(56f, 385f, 714f, 1043f)

        fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
            x >= rx && x <= rx + rw && y >= ry && y <= ry + rh
    }

    private fun inRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        Companion.inRect(x, y, rx, ry, rw, rh)
}
