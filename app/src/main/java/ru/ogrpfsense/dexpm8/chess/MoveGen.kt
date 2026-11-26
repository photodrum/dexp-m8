package ru.ogrpfsense.dexpm8.chess

// Генератор ходов — перенос MoveGenerator.java и MoveList.java из Pulse Chess
// (Copyright (C) 2013-2023 Phokham Nonava, MIT, PULSE-LICENSE.txt рядом).
// Псевдозаконные ходы; законность — сделать ход и проверить, не под шахом ли свой король.
// Отличие от Pulse: список — два IntArray (ход и оценка), без объектов на каждый ход.

class MoveList(capacity: Int = 320) {
    val moves = IntArray(capacity)
    val values = IntArray(capacity)
    var size = 0

    fun add(m: Int) {
        moves[size++] = m
    }

    /** Взятия — сначала самые ценные жертвы самыми дешёвыми фигурами (MVV/LVA, как в Pulse). */
    fun rateMvvLva() {
        for (i in 0 until size) {
            val m = moves[i]
            var v = PieceType.VALUE[PieceType.KING] / PieceType.VALUE[Piece.type(Move.originPiece(m))]
            val t = Move.targetPiece(m)
            if (Piece.isValid(t)) v += 10 * PieceType.VALUE[Piece.type(t)]
            values[i] = v
        }
    }

    /** Вставками по убыванию оценки; устойчиво — порядок равных не меняется. */
    fun sort() {
        for (i in 1 until size) {
            val m = moves[i]
            val v = values[i]
            var j = i
            while (j > 0 && values[j - 1] < v) {
                moves[j] = moves[j - 1]
                values[j] = values[j - 1]
                j--
            }
            moves[j] = m
            values[j] = v
        }
    }
}

/** Один генератор — на один уровень перебора: список переиспользуется. */
class MoveGen {
    private val list = MoveList()

    /** Все законные ходы стороны, чей ход. */
    fun legal(pos: Position): MoveList {
        val l = pseudo(pos, 1, pos.isCheck())
        val n = l.size
        l.size = 0
        for (i in 0 until n) {
            val m = l.moves[i]
            pos.makeMove(m)
            if (!pos.isCheck(Color.opposite(pos.side))) {
                l.moves[l.size] = m
                l.values[l.size] = l.values[i]
                l.size++
            }
            pos.undoMove(m)
        }
        return l
    }

    /**
     * Псевдозаконные ходы. [depth] ≤ 0 — поиск покоя: без шаха только взятия
     * (и превращения со взятием), рокировок нет. Отсортированы по MVV/LVA.
     */
    fun pseudo(pos: Position, depth: Int, isCheck: Boolean): MoveList {
        list.size = 0
        addMoves(pos)
        if (depth > 0) {
            if (!isCheck) addCastling(pos)
        } else if (!isCheck) {
            val n = list.size
            list.size = 0
            for (i in 0 until n) {
                val m = list.moves[i]
                if (Move.targetPiece(m) != Piece.NONE) list.add(m)
            }
        }
        list.rateMvvLva()
        list.sort()
        return list
    }

    private fun addMoves(pos: Position) {
        val c = pos.side
        var bb = pos.pieces[Piece.of(c, PieceType.PAWN)]
        while (bb != 0L) {
            addPawnMoves(pos, Bb.next(bb))
            bb = bb and (bb - 1)
        }
        for (t in PieceType.KNIGHT..PieceType.QUEEN) {
            val dirs = DIRS[t]
            bb = pos.pieces[Piece.of(c, t)]
            while (bb != 0L) {
                addPieceMoves(pos, Bb.next(bb), dirs)
                bb = bb and (bb - 1)
            }
        }
        addPieceMoves(pos, pos.kingSquare(c), Square.KING_DIRS)
    }

    private fun addPieceMoves(pos: Position, from: Int, dirs: IntArray) {
        val piece = pos.board[from]
        val sliding = PieceType.isSliding(Piece.type(piece))
        val enemy = Color.opposite(Piece.color(piece))
        for (d in dirs) {
            var to = from + d
            while (Square.isValid(to)) {
                val t = pos.board[to]
                if (t == Piece.NONE) {
                    list.add(Move.of(Move.NORMAL, from, to, piece, Piece.NONE, PieceType.NONE))
                    if (!sliding) break
                    to += d
                } else {
                    if (Piece.color(t) == enemy) list.add(Move.of(Move.NORMAL, from, to, piece, t, PieceType.NONE))
                    break
                }
            }
        }
    }

    private fun addPawnMoves(pos: Position, from: Int) {
        val pawn = pos.board[from]
        val color = Piece.color(pawn)
        val dirs = Square.PAWN_DIRS[color]
        val lastRank = if (color == Color.WHITE) 7 else 0

        for (i in 1..2) {
            val to = from + dirs[i]
            if (!Square.isValid(to)) continue
            val t = pos.board[to]
            if (t != Piece.NONE) {
                if (Piece.color(t) != color) {
                    if (Square.rank(to) == lastRank) addPromotions(from, to, pawn, t)
                    else list.add(Move.of(Move.NORMAL, from, to, pawn, t, PieceType.NONE))
                }
            } else if (to == pos.epSquare) {
                val cs = to + if (color == Color.WHITE) Square.S else Square.N
                list.add(Move.of(Move.EN_PASSANT, from, to, pawn, pos.board[cs], PieceType.NONE))
            }
        }

        var to = from + dirs[0]
        if (Square.isValid(to) && pos.board[to] == Piece.NONE) {
            if (Square.rank(to) == lastRank) {
                addPromotions(from, to, pawn, Piece.NONE)
            } else {
                list.add(Move.of(Move.NORMAL, from, to, pawn, Piece.NONE, PieceType.NONE))
                to += dirs[0]
                val doubleRank = if (color == Color.WHITE) 3 else 4
                if (Square.isValid(to) && pos.board[to] == Piece.NONE && Square.rank(to) == doubleRank)
                    list.add(Move.of(Move.PAWN_DOUBLE, from, to, pawn, Piece.NONE, PieceType.NONE))
            }
        }
    }

    private fun addPromotions(from: Int, to: Int, pawn: Int, captured: Int) {
        for (t in PROMOTIONS) list.add(Move.of(Move.PROMOTION, from, to, pawn, captured, t))
    }

    /** Король не под шахом (проверено снаружи), поле перехода не бито; поле прихода проверит законность. */
    private fun addCastling(pos: Position) {
        val b = pos.board
        val cr = pos.castling
        val empty = Piece.NONE
        if (pos.side == Color.WHITE) {
            val k = Piece.WHITE_KING
            if ((cr and Castling.WHITE_KINGSIDE) != 0 && b[Square.F1] == empty && b[Square.G1] == empty &&
                !pos.isAttacked(Square.F1, Color.BLACK)
            ) list.add(Move.of(Move.CASTLING, Square.E1, Square.G1, k, empty, PieceType.NONE))
            if ((cr and Castling.WHITE_QUEENSIDE) != 0 && b[Square.B1] == empty && b[Square.C1] == empty &&
                b[Square.D1] == empty && !pos.isAttacked(Square.D1, Color.BLACK)
            ) list.add(Move.of(Move.CASTLING, Square.E1, Square.C1, k, empty, PieceType.NONE))
        } else {
            val k = Piece.BLACK_KING
            if ((cr and Castling.BLACK_KINGSIDE) != 0 && b[Square.F8] == empty && b[Square.G8] == empty &&
                !pos.isAttacked(Square.F8, Color.WHITE)
            ) list.add(Move.of(Move.CASTLING, Square.E8, Square.G8, k, empty, PieceType.NONE))
            if ((cr and Castling.BLACK_QUEENSIDE) != 0 && b[Square.B8] == empty && b[Square.C8] == empty &&
                b[Square.D8] == empty && !pos.isAttacked(Square.D8, Color.WHITE)
            ) list.add(Move.of(Move.CASTLING, Square.E8, Square.C8, k, empty, PieceType.NONE))
        }
    }

    private companion object {
        /** Направления по типу фигуры (пешка — отдельно, у неё [Square.PAWN_DIRS]). */
        val DIRS = arrayOf(
            IntArray(0), Square.KNIGHT_DIRS, Square.BISHOP_DIRS, Square.ROOK_DIRS, Square.QUEEN_DIRS, Square.KING_DIRS,
        )
        val PROMOTIONS = intArrayOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT)
    }
}
