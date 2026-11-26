package ru.ogrpfsense.dexpm8.chess

// Оценка позиции — перенос Evaluation.java из Pulse Chess
// (Copyright (C) 2013-2023 Phokham Nonava, MIT, PULSE-LICENSE.txt рядом).
// Материал (+50 за пару слонов) и подвижность лёгких и тяжёлых фигур, плюс темп.
// Числа и веса — как в Pulse; подвижность, как там, считает и поле со своей фигурой,
// на которое луч упёрся (это «поле под защитой», а не ход).

object Eval {
    /** Надбавка тому, чей ход. */
    const val TEMPO = 1

    private const val MATERIAL_WEIGHT = 100
    private const val MOBILITY_WEIGHT = 80
    private const val MAX_WEIGHT = 100
    private const val BISHOP_PAIR = 50

    /** Оценка в сотых пешки со стороны того, чей ход. */
    fun evaluate(pos: Position): Int {
        val me = pos.side
        val them = Color.opposite(me)
        val material = (material(pos, me) - material(pos, them)) * MATERIAL_WEIGHT / MAX_WEIGHT
        val mobility = (mobility(pos, me) - mobility(pos, them)) * MOBILITY_WEIGHT / MAX_WEIGHT
        return material + mobility + TEMPO
    }

    private fun material(pos: Position, c: Int): Int {
        var m = pos.material[c]
        if (pos.count(c, PieceType.BISHOP) >= 2) m += BISHOP_PAIR
        return m
    }

    private fun mobility(pos: Position, c: Int): Int =
        mobility(pos, c, PieceType.KNIGHT, Square.KNIGHT_DIRS) * 4 +
            mobility(pos, c, PieceType.BISHOP, Square.BISHOP_DIRS) * 5 +
            mobility(pos, c, PieceType.ROOK, Square.ROOK_DIRS) * 2 +
            mobility(pos, c, PieceType.QUEEN, Square.QUEEN_DIRS)

    private fun mobility(pos: Position, c: Int, type: Int, dirs: IntArray): Int {
        val sliding = PieceType.isSliding(type)
        var n = 0
        var bb = pos.pieces[Piece.of(c, type)]
        while (bb != 0L) {
            val from = Bb.next(bb)
            for (d in dirs) {
                var to = from + d
                while (Square.isValid(to)) {
                    n++
                    if (sliding && pos.board[to] == Piece.NONE) to += d else break
                }
            }
            bb = bb and (bb - 1)
        }
        return n
    }
}
