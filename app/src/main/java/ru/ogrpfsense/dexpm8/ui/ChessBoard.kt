package ru.ogrpfsense.dexpm8.ui

import android.graphics.Canvas
import ru.ogrpfsense.dexpm8.chess.Move
import ru.ogrpfsense.dexpm8.chess.Piece
import ru.ogrpfsense.dexpm8.chess.PieceType
import ru.ogrpfsense.dexpm8.chess.Position
import ru.ogrpfsense.dexpm8.chess.Square

/**
 * Доска B79 и её отметки. Числа — `design/v5/B79.json`, `B79а`, `B80`:
 *
 *   рамка       93,353 1218×1218 (вдавленная, r 24)
 *   клетки      102,362; 150×150; светлая G14, тёмная G10, левая верхняя — светлая
 *   координаты  26/700: цифра ряда слева в клетке (+8,+4), буква вертикали справа внизу
 *               (−8 от края, y +112); цвет обратный клетке (на светлой G8, на тёмной G14)
 *   последний ход  рамка G3 4 px, отступ 6 (138×138)
 *   шах         кольцо G7 6 px 134×134 (+8) и внутреннее G13 3 px 122×122 (+14)
 *   выбрана     рамка G7 10 px 140×140 (+5) и внутренняя G13 3 px 127×127 (+11.5)
 *   ход         точка G9 40×40 с кольцом G13 3 px
 *   фигура      иконка `chess[W|B][k|q|r|b|n|p]` 150×150 на клетку, 1:1
 *   взятые      те же иконки 44×44 (≈ 0.293), шаг 38
 *
 * Отступление от макета: взятие на клетке с фигурой отмечено кольцом G5 8 px вместо точки —
 * под фигурой точки не видно, такого состояния в макете нет (`docs/DESIGN_QUESTIONS.md`).
 *
 * [flipped] = false — белые внизу (a1 слева), true — чёрные внизу (h1 слева).
 */
internal class ChessBoard(private val ink: Ink, private val icons: Icons) {

    fun draw(c: Canvas, pos: Position, flipped: Boolean, last: Int, selected: Int, targets: IntArray) {
        ink.inset(c, FRAME_X, FRAME_Y, FRAME, FRAME, 24f)
        for (row in 0..7) for (col in 0..7) {
            ink.rect(c, X0 + col * CELL, Y0 + row * CELL, CELL, CELL,
                if ((row + col) % 2 == 0) T.G14 else T.G10)
        }

        // Отметки лежат под координатами и фигурами.
        if (last != Move.NONE) {
            mark(c, Move.origin(last), flipped)
            mark(c, Move.target(last), flipped)
        }
        if (pos.isCheck()) {
            val k = pos.kingSquare(pos.side)
            val x = cellX(k, flipped)
            val y = cellY(k, flipped)
            ink.ring(c, x + 8f, y + 8f, 134f, 134f, 67f, T.G7, 6f)
            ink.ring(c, x + 14f, y + 14f, 122f, 122f, 61f, T.G13, 3f)
        }
        if (selected != Square.NONE) {
            val x = cellX(selected, flipped)
            val y = cellY(selected, flipped)
            ink.ring(c, x + 5f, y + 5f, 140f, 140f, 0f, T.G7, 10f)
            ink.ring(c, x + 11.5f, y + 11.5f, 127f, 127f, 0f, T.G13, 3f)
        }

        coordinates(c, flipped)

        for (row in 0..7) for (col in 0..7) {
            val p = pos.board[squareAt(col, row, flipped)]
            if (p != Piece.NONE) icons.draw(c, NAMES[p], X0 + col * CELL, Y0 + row * CELL, CELL, CELL)
        }

        // Возможные ходы — поверх фигур.
        for (sq in targets) {
            val x = cellX(sq, flipped)
            val y = cellY(sq, flipped)
            if (pos.board[sq] == Piece.NONE) {
                ink.ellipsoid(c, x + 55f, y + 55f, 40f, 40f, T.G9)
                ink.ring(c, x + 55f, y + 55f, 40f, 40f, 20f, T.G13, 3f)
            } else {
                ink.ring(c, x + 8f, y + 8f, 134f, 134f, 67f, T.G5, 8f)
            }
        }
    }

    private fun mark(c: Canvas, sq: Int, flipped: Boolean) {
        ink.ring(c, cellX(sq, flipped) + 6f, cellY(sq, flipped) + 6f, 138f, 138f, 0f, T.G3, 4f)
    }

    private fun coordinates(c: Canvas, flipped: Boolean) {
        for (row in 0..7) {
            val rank = if (flipped) row + 1 else 8 - row
            ink.text(c, rank.toString(), X0 + 8f, T.base(Y0 + row * CELL + 4f, 26f, 1.25f), 26f, 700,
                if (row % 2 == 0) T.G8 else T.G14)
        }
        for (col in 0..7) {
            val s = ('a' + (if (flipped) 7 - col else col)).toString()
            val w = ink.measure(s, 26f, 700)
            ink.text(c, s, X0 + (col + 1) * CELL - 8f - w, T.base(Y0 + 7 * CELL + 112f, 26f, 1.25f),
                26f, 700, if ((col + 7) % 2 == 0) T.G8 else T.G14)
        }
    }

    /**
     * Полоса «Взятые»: фигуры цвета [lostColor], которых нет на доске, от ферзя к пешке.
     * [y0] — верх коробки иконки 44×44 (макет: 302 у верхней полосы, 1634 у нижней).
     * Лишних (после превращения) не рисуем: `lost` меньше нуля даёт пустой цикл.
     */
    fun captured(c: Canvas, pos: Position, lostColor: Int, y0: Float) {
        var i = 0
        for (t in CAPTURE_ORDER) {
            val lost = INITIAL[t] - pos.count(lostColor, t)
            for (k in 0 until lost) {
                if (i >= MAX_CAPTURED) return
                icons.draw(c, NAMES[Piece.of(lostColor, t)], 63f + 38f * i - 22f, y0, 44f, 44f)
                i++
            }
        }
    }

    /** Поле 0x88 под касанием или [Square.NONE]. */
    fun cellAt(x: Float, y: Float, flipped: Boolean): Int {
        if (x < X0 || x >= X0 + 8 * CELL || y < Y0 || y >= Y0 + 8 * CELL) return Square.NONE
        val col = ((x - X0) / CELL).toInt().coerceIn(0, 7)
        val row = ((y - Y0) / CELL).toInt().coerceIn(0, 7)
        return squareAt(col, row, flipped)
    }

    private fun squareAt(col: Int, row: Int, flipped: Boolean): Int =
        if (flipped) Square.of(7 - col, row) else Square.of(col, 7 - row)

    private fun cellX(sq: Int, flipped: Boolean): Float =
        X0 + (if (flipped) 7 - Square.file(sq) else Square.file(sq)) * CELL

    private fun cellY(sq: Int, flipped: Boolean): Float =
        Y0 + (if (flipped) Square.rank(sq) else 7 - Square.rank(sq)) * CELL

    companion object {
        private const val FRAME_X = 93f
        private const val FRAME_Y = 353f
        private const val FRAME = 1218f
        private const val X0 = 102f
        private const val Y0 = 362f
        private const val CELL = 150f

        /** Больше пятнадцати взятых в полосу (до x 660) не помещается. */
        private const val MAX_CAPTURED = 15

        private val CAPTURE_ORDER = intArrayOf(
            PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT, PieceType.PAWN)

        /** Фигур каждого типа в начале партии (индекс — [PieceType]). */
        private val INITIAL = intArrayOf(8, 2, 2, 2, 1, 1)

        /**
         * Имя иконки по номеру фигуры (порядок `Piece`: PNBRQKpnbrqk). Имена записаны буквально:
         * `tools/check_icons.py` ищет их в коде по тексту.
         */
        private val NAMES = arrayOf(
            "chessWp", "chessWn", "chessWb", "chessWr", "chessWq", "chessWk",
            "chessBp", "chessBn", "chessBb", "chessBr", "chessBq", "chessBk",
        )

        /** Имя иконки фигуры типа [type] цвета [color]. */
        fun icon(color: Int, type: Int): String = NAMES[Piece.of(color, type)]
    }
}
