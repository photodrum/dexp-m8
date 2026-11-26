package ru.ogrpfsense.dexpm8.chess

// Шахматное ядро (п. 25): перенос Pulse Chess (Java) на Kotlin.
// Pulse Chess — Copyright (C) 2013-2023 Phokham Nonava, лицензия MIT (PULSE-LICENSE.txt рядом).
// Модель: цвета, фигуры, поля 0x88, рокировки и ход, упакованный в Int.
// Всё — целые числа без объектов: перебор на 1 ГБ ОЗУ не должен плодить мусор.
// Пакет без android.* — проверяется на хосте (docs/CHECKS.md, «Шахматы на хосте»).

/** Цвет: 0 — белые, 1 — чёрные. */
object Color {
    const val WHITE = 0
    const val BLACK = 1
    const val NONE = 2

    fun opposite(c: Int) = c xor 1
}

/** Тип фигуры без цвета. */
object PieceType {
    const val PAWN = 0
    const val KNIGHT = 1
    const val BISHOP = 2
    const val ROOK = 3
    const val QUEEN = 4
    const val KING = 5
    const val NONE = 6

    /** Цена в сотых пешки (как в Pulse). */
    val VALUE = intArrayOf(100, 325, 325, 500, 975, 20000)

    fun isSliding(t: Int) = t == BISHOP || t == ROOK || t == QUEEN
    fun isPromotion(t: Int) = t == KNIGHT || t == BISHOP || t == ROOK || t == QUEEN
}

/** Фигура с цветом: цвет·6 + тип, 0…11; 12 — пусто. */
object Piece {
    const val WHITE_PAWN = 0
    const val WHITE_KING = 5
    const val BLACK_PAWN = 6
    const val BLACK_KING = 11
    const val NONE = 12
    const val COUNT = 12

    /** Буквы FEN по номеру фигуры. */
    const val CHARS = "PNBRQKpnbrqk"

    fun of(color: Int, type: Int) = color * 6 + type
    fun type(p: Int) = p % 6
    fun color(p: Int) = p / 6
    fun isValid(p: Int) = p in 0 until COUNT
}

/**
 * Поле в схеме 0x88: ранг·16 + вертикаль. Поле вне доски — когда `(sq and 0x88) != 0`,
 * поэтому выход за край ловится одной проверкой без таблиц.
 */
object Square {
    const val NONE = 127
    const val COUNT = 128

    const val A1 = 0
    const val B1 = 1
    const val C1 = 2
    const val D1 = 3
    const val E1 = 4
    const val F1 = 5
    const val G1 = 6
    const val H1 = 7
    const val A8 = 112
    const val B8 = 113
    const val C8 = 114
    const val D8 = 115
    const val E8 = 116
    const val F8 = 117
    const val G8 = 118
    const val H8 = 119

    const val N = 16
    const val E = 1
    const val S = -16
    const val W = -1

    /** Все 64 поля от a1 до h8. */
    val ALL = IntArray(64) { ((it shr 3) shl 4) or (it and 7) }

    /** Ходы пешки: [цвет] = {вперёд, взятие вправо, взятие влево}. */
    val PAWN_DIRS = arrayOf(intArrayOf(N, N + E, N + W), intArrayOf(S, S + E, S + W))
    val KNIGHT_DIRS = intArrayOf(N + N + E, N + N + W, N + E + E, N + W + W, S + S + E, S + S + W, S + E + E, S + W + W)
    val BISHOP_DIRS = intArrayOf(N + E, N + W, S + E, S + W)
    val ROOK_DIRS = intArrayOf(N, E, S, W)
    val QUEEN_DIRS = intArrayOf(N, E, S, W, N + E, N + W, S + E, S + W)
    val KING_DIRS = QUEEN_DIRS

    fun isValid(sq: Int) = (sq and 0x88) == 0
    fun of(file: Int, rank: Int) = (rank shl 4) + file
    fun file(sq: Int) = sq and 0xF
    fun rank(sq: Int) = sq ushr 4

    /** «e4» по полю. */
    fun name(sq: Int): String = "${'a' + file(sq)}${'1' + rank(sq)}"

    /** Поле по «e4»; [NONE], если строка не поле. */
    fun parse(s: String): Int {
        if (s.length != 2) return NONE
        val f = s[0] - 'a'
        val r = s[1] - '1'
        return if (f in 0..7 && r in 0..7) of(f, r) else NONE
    }
}

/** Права рокировки — биты. */
object Castling {
    const val NONE = 0
    const val WHITE_KINGSIDE = 1
    const val WHITE_QUEENSIDE = 2
    const val BLACK_KINGSIDE = 4
    const val BLACK_QUEENSIDE = 8
}

/**
 * Ход в одном Int (как в Pulse): биты 0–2 — тип, 3–9 — откуда, 10–16 — куда,
 * 17–21 — какая фигура ходит, 22–26 — взятая фигура, 27–29 — тип превращения.
 */
object Move {
    const val NORMAL = 0
    const val PAWN_DOUBLE = 1
    const val PROMOTION = 2
    const val EN_PASSANT = 3
    const val CASTLING = 4
    const val NO_TYPE = 5

    val NONE = of(NO_TYPE, Square.NONE, Square.NONE, Piece.NONE, Piece.NONE, PieceType.NONE)

    fun of(type: Int, from: Int, to: Int, piece: Int, captured: Int, promotion: Int) =
        type or (from shl 3) or (to shl 10) or (piece shl 17) or (captured shl 22) or (promotion shl 27)

    fun type(m: Int) = m and 0x7
    fun origin(m: Int) = (m ushr 3) and 0x7F
    fun target(m: Int) = (m ushr 10) and 0x7F
    fun originPiece(m: Int) = (m ushr 17) and 0x1F
    fun targetPiece(m: Int) = (m ushr 22) and 0x1F
    fun promotion(m: Int) = (m ushr 27) and 0x7

    /** Длинная запись «e2e4», «e7e8q» (UCI, она же нужна для FICS). */
    fun uci(m: Int): String {
        val s = Square.name(origin(m)) + Square.name(target(m))
        return if (type(m) == PROMOTION) s + "pnbrqk"[promotion(m)] else s
    }
}
