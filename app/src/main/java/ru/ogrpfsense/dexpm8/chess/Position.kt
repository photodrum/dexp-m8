package ru.ogrpfsense.dexpm8.chess

// Позиция и ход с отменой — перенос Position.java и Bitboard.java из Pulse Chess
// (Copyright (C) 2013-2023 Phokham Nonava, MIT, PULSE-LICENSE.txt рядом).
// Отличия от Pulse:
// - ключи Zobrist из генератора с постоянным зерном (splitmix64), а не SecureRandom:
//   один и тот же ключ у позиции между запусками, удобно для проверок;
// - ключ рокировок — по маске прав целиком (16 значений), смена прав — XOR старого и нового;
// - поле взятия на проходе ставится, только если рядом есть пешка, способная взять
//   (так же пишут FEN lichess и Stockfish), иначе одна позиция получала бы два ключа
//   и троекратное повторение не засчитывалось бы;
// - стек отмены растёт сам, а не ограничен 1280 полуходами.

/** Битборд: бит 0…63 для поля 0x88 — чтобы перебирать фигуры без обхода доски. */
internal object Bb {
    fun bit(sq: Int) = 1L shl (((sq and 7.inv()) ushr 1) or (sq and 7))
    fun next(bb: Long): Int {
        val i = bb.countTrailingZeroBits()
        return ((i and 7.inv()) shl 1) or (i and 7)
    }
}

/** Случайные ключи Zobrist: постоянное зерно — одинаковые ключи между запусками. */
internal object Zobrist {
    private var seed = 0x2545F4914F6CDD1DL

    private fun next(): Long {
        seed += -0x61C8864680B583EBL // 0x9E3779B97F4A7C15
        var z = seed
        z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    val board = Array(Piece.COUNT) { LongArray(Square.COUNT) { next() } }
    val ep = LongArray(Square.COUNT) { next() }
    val side = next()
    val castling: LongArray = run {
        val base = LongArray(4) { next() }
        LongArray(16) { mask ->
            var k = 0L
            for (i in 0..3) if (((mask shr i) and 1) == 1) k = k xor base[i]
            k
        }
    }
}

class Position {
    /** Фигура на поле 0x88 или [Piece.NONE]. */
    val board = IntArray(Square.COUNT) { Piece.NONE }

    /** Битборды по фигурам (индекс — [Piece]). */
    val pieces = LongArray(Piece.COUNT)

    /** Сумма цен фигур по цвету, король включён. */
    val material = IntArray(2)

    var castling = Castling.NONE
        private set
    var epSquare = Square.NONE
        private set
    var side = Color.WHITE
        private set
    var halfmoveClock = 0
        private set
    var key = 0L
        private set

    private var halfmoveNumber = 2
    val fullmoveNumber: Int get() = halfmoveNumber / 2

    // Стек для отмены хода.
    private var stKey = LongArray(64)
    private var stCastling = IntArray(64)
    private var stEp = IntArray(64)
    private var stClock = IntArray(64)
    private var stSize = 0

    // --- Расстановка (FEN) ---

    internal fun changeSide(c: Int) {
        if (side != c) {
            side = c
            key = key xor Zobrist.side
        }
    }

    internal fun addCastling(mask: Int) {
        val nc = castling or mask
        key = key xor Zobrist.castling[castling] xor Zobrist.castling[nc]
        castling = nc
    }

    internal fun changeEp(sq: Int) {
        if (epSquare != Square.NONE) key = key xor Zobrist.ep[epSquare]
        epSquare = sq
        if (sq != Square.NONE) key = key xor Zobrist.ep[sq]
    }

    /** Часы 50 ходов и номер хода; цвет хода уже должен быть поставлен. */
    internal fun setClocks(halfmove: Int, fullmove: Int) {
        halfmoveClock = halfmove
        halfmoveNumber = fullmove * 2 + if (side == Color.BLACK) 1 else 0
    }

    internal fun put(piece: Int, sq: Int) {
        board[sq] = piece
        pieces[piece] = pieces[piece] or Bb.bit(sq)
        material[Piece.color(piece)] += PieceType.VALUE[Piece.type(piece)]
        key = key xor Zobrist.board[piece][sq]
    }

    internal fun remove(sq: Int): Int {
        val piece = board[sq]
        board[sq] = Piece.NONE
        pieces[piece] = pieces[piece] and Bb.bit(sq).inv()
        material[Piece.color(piece)] -= PieceType.VALUE[Piece.type(piece)]
        key = key xor Zobrist.board[piece][sq]
        return piece
    }

    /** Может ли пешка цвета [color] побить на поле [ep] (без проверки связки). */
    internal fun epCapturable(ep: Int, color: Int): Boolean {
        val pawn = Piece.of(color, PieceType.PAWN)
        val dirs = Square.PAWN_DIRS[color]
        for (i in 1..2) {
            val sq = ep - dirs[i]
            if (Square.isValid(sq) && board[sq] == pawn) return true
        }
        return false
    }

    // --- Ход и отмена ---

    fun makeMove(m: Int) {
        if (stSize == stKey.size) grow()
        stKey[stSize] = key
        stCastling[stSize] = castling
        stEp[stSize] = epSquare
        stClock[stSize] = halfmoveClock
        stSize++

        val type = Move.type(m)
        val from = Move.origin(m)
        val to = Move.target(m)
        val piece = Move.originPiece(m)
        val color = Piece.color(piece)
        val captured = Move.targetPiece(m)

        if (captured != Piece.NONE) {
            var cs = to
            if (type == Move.EN_PASSANT) cs += if (color == Color.WHITE) Square.S else Square.N
            remove(cs)
            clearCastling(cs)
        }
        remove(from)
        put(if (type == Move.PROMOTION) Piece.of(color, Move.promotion(m)) else piece, to)
        if (type == Move.CASTLING) put(remove(rookFrom(to)), rookTo(to))
        clearCastling(from)

        if (epSquare != Square.NONE) key = key xor Zobrist.ep[epSquare]
        epSquare = Square.NONE
        if (type == Move.PAWN_DOUBLE) {
            val ep = to + if (color == Color.WHITE) Square.S else Square.N
            if (epCapturable(ep, Color.opposite(color))) {
                epSquare = ep
                key = key xor Zobrist.ep[ep]
            }
        }

        side = Color.opposite(side)
        key = key xor Zobrist.side
        halfmoveClock = if (Piece.type(piece) == PieceType.PAWN || captured != Piece.NONE) 0 else halfmoveClock + 1
        halfmoveNumber++
    }

    fun undoMove(m: Int) {
        val type = Move.type(m)
        val from = Move.origin(m)
        val to = Move.target(m)
        val piece = Move.originPiece(m)
        val captured = Move.targetPiece(m)

        halfmoveNumber--
        side = Color.opposite(side)
        if (type == Move.CASTLING) put(remove(rookTo(to)), rookFrom(to))
        remove(to)
        put(piece, from)
        if (captured != Piece.NONE) {
            var cs = to
            if (type == Move.EN_PASSANT) cs += if (Piece.color(piece) == Color.WHITE) Square.S else Square.N
            put(captured, cs)
        }

        stSize--
        halfmoveClock = stClock[stSize]
        epSquare = stEp[stSize]
        castling = stCastling[stSize]
        key = stKey[stSize]
    }

    private fun grow() {
        val n = stKey.size * 2
        stKey = stKey.copyOf(n)
        stCastling = stCastling.copyOf(n)
        stEp = stEp.copyOf(n)
        stClock = stClock.copyOf(n)
    }

    private fun rookFrom(kingTo: Int) = when (kingTo) {
        Square.G1 -> Square.H1
        Square.C1 -> Square.A1
        Square.G8 -> Square.H8
        Square.C8 -> Square.A8
        else -> throw IllegalArgumentException("рокировка на ${Square.name(kingTo)}")
    }

    private fun rookTo(kingTo: Int) = when (kingTo) {
        Square.G1 -> Square.F1
        Square.C1 -> Square.D1
        Square.G8 -> Square.F8
        Square.C8 -> Square.D8
        else -> throw IllegalArgumentException("рокировка на ${Square.name(kingTo)}")
    }

    /** Ход с поля или на поле короля/ладьи снимает права рокировки. */
    private fun clearCastling(sq: Int) {
        val lost = when (sq) {
            Square.A1 -> Castling.WHITE_QUEENSIDE
            Square.H1 -> Castling.WHITE_KINGSIDE
            Square.E1 -> Castling.WHITE_KINGSIDE or Castling.WHITE_QUEENSIDE
            Square.A8 -> Castling.BLACK_QUEENSIDE
            Square.H8 -> Castling.BLACK_KINGSIDE
            Square.E8 -> Castling.BLACK_KINGSIDE or Castling.BLACK_QUEENSIDE
            else -> return
        }
        val nc = castling and lost.inv()
        if (nc != castling) {
            key = key xor Zobrist.castling[castling] xor Zobrist.castling[nc]
            castling = nc
        }
    }

    // --- Шах и атаки ---

    fun kingSquare(color: Int) = Bb.next(pieces[Piece.of(color, PieceType.KING)])

    /** Под шахом ли сторона, чей ход. */
    fun isCheck() = isCheck(side)

    fun isCheck(color: Int) = isAttacked(kingSquare(color), Color.opposite(color))

    /** Бьёт ли цвет [attacker] поле [sq]. */
    fun isAttacked(sq: Int, attacker: Int): Boolean {
        val pawn = Piece.of(attacker, PieceType.PAWN)
        val pd = Square.PAWN_DIRS[attacker]
        for (i in 1..2) {
            val from = sq - pd[i]
            if (Square.isValid(from) && board[from] == pawn) return true
        }
        val queen = Piece.of(attacker, PieceType.QUEEN)
        return attackedStep(sq, Piece.of(attacker, PieceType.KNIGHT), Square.KNIGHT_DIRS) ||
            attackedSlide(sq, Piece.of(attacker, PieceType.BISHOP), queen, Square.BISHOP_DIRS) ||
            attackedSlide(sq, Piece.of(attacker, PieceType.ROOK), queen, Square.ROOK_DIRS) ||
            attackedStep(sq, Piece.of(attacker, PieceType.KING), Square.KING_DIRS)
    }

    private fun attackedStep(sq: Int, piece: Int, dirs: IntArray): Boolean {
        for (d in dirs) {
            val from = sq + d
            if (Square.isValid(from) && board[from] == piece) return true
        }
        return false
    }

    private fun attackedSlide(sq: Int, piece: Int, queen: Int, dirs: IntArray): Boolean {
        for (d in dirs) {
            var from = sq + d
            while (Square.isValid(from)) {
                val p = board[from]
                if (p != Piece.NONE) {
                    if (p == piece || p == queen) return true
                    break
                }
                from += d
            }
        }
        return false
    }

    // --- Для поиска (как в Pulse; правила ничьей по FIDE для партии — Game.kt) ---

    /** Повтор позиции с последнего необратимого хода (для поиска хватает одного повтора). */
    fun isRepetition(): Boolean {
        val stop = maxOf(0, stSize - halfmoveClock)
        var i = stSize - 2
        while (i >= stop) {
            if (stKey[i] == key) return true
            i -= 2
        }
        return false
    }

    /** Грубо, как в Pulse: нет пешек, ладей, ферзей и у каждой стороны не больше одной лёгкой. */
    fun hasInsufficientMaterial(): Boolean {
        for (c in 0..1) {
            if (count(c, PieceType.PAWN) + count(c, PieceType.ROOK) + count(c, PieceType.QUEEN) != 0) return false
            if (count(c, PieceType.KNIGHT) + count(c, PieceType.BISHOP) > 1) return false
        }
        return true
    }

    /** Сколько фигур типа [type] у цвета [color]. */
    fun count(color: Int, type: Int) = pieces[Piece.of(color, type)].countOneBits()
}
