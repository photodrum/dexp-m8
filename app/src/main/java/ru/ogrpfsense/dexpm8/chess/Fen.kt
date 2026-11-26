package ru.ogrpfsense.dexpm8.chess

// FEN: чтение и запись позиции. В Pulse это делала библиотека jcpi (GenericBoard),
// здесь — свой разбор без зависимостей, со строгими проверками: позиция может прийти
// с FICS, из сохранённой партии или из набора задач.
// Нормализация при чтении (как в FEN lichess):
// - права рокировки без короля и ладьи на исходных полях отбрасываются;
// - поле взятия на проходе остаётся, только если пешка противника может взять
//   (иначе ключ позиции отличался бы от той же позиции, полученной ходом).

object Fen {
    const val START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

    /** Позиция по FEN; ошибка — [IllegalArgumentException] с причиной по-русски. */
    fun parse(fen: String): Position {
        val f = fen.trim().split(Regex("\\s+"))
        require(f.size in 4..6) { "FEN: нужно от 4 до 6 полей, а их ${f.size}" }
        val p = Position()

        val rows = f[0].split('/')
        require(rows.size == 8) { "FEN: нужно 8 рядов, а их ${rows.size}" }
        for ((i, row) in rows.withIndex()) {
            val rank = 7 - i
            var file = 0
            for (ch in row) {
                if (ch in '1'..'8') {
                    file += ch - '0'
                } else {
                    val pc = Piece.CHARS.indexOf(ch)
                    require(pc >= 0) { "FEN: неизвестная фигура «$ch»" }
                    require(file < 8) { "FEN: в ряду ${rank + 1} больше 8 полей" }
                    require(Piece.type(pc) != PieceType.PAWN || rank in 1..6) { "FEN: пешка на ${rank + 1}-м ряду" }
                    p.put(pc, Square.of(file, rank))
                    file++
                }
                require(file <= 8) { "FEN: в ряду ${rank + 1} больше 8 полей" }
            }
            require(file == 8) { "FEN: в ряду ${rank + 1} меньше 8 полей" }
        }
        for (c in 0..1) require(p.count(c, PieceType.KING) == 1) { "FEN: нужен ровно один король каждого цвета" }

        p.changeSide(
            when (f[1]) {
                "w" -> Color.WHITE
                "b" -> Color.BLACK
                else -> throw IllegalArgumentException("FEN: цвет хода «${f[1]}»")
            }
        )
        require(!p.isCheck(Color.opposite(p.side))) { "FEN: шах стороне, которая не ходит" }

        if (f[2] != "-") {
            for (ch in f[2]) {
                val i = "KQkq".indexOf(ch)
                require(i >= 0) { "FEN: рокировка «${f[2]}»" }
                val c = if (i < 2) Color.WHITE else Color.BLACK
                val back = if (c == Color.WHITE) 0 else 7
                val rookFile = if (i % 2 == 0) 7 else 0
                if (p.board[Square.of(4, back)] == Piece.of(c, PieceType.KING) &&
                    p.board[Square.of(rookFile, back)] == Piece.of(c, PieceType.ROOK)
                ) p.addCastling(1 shl i)
            }
        }

        if (f[3] != "-") {
            val ep = Square.parse(f[3])
            require(ep != Square.NONE) { "FEN: поле взятия на проходе «${f[3]}»" }
            val wantRank = if (p.side == Color.WHITE) 5 else 2
            require(Square.rank(ep) == wantRank) { "FEN: взятие на проходе на ${f[3]} при этом цвете хода" }
            val moved = Color.opposite(p.side)
            val pawnSq = ep + if (moved == Color.WHITE) Square.N else Square.S
            if (p.board[pawnSq] == Piece.of(moved, PieceType.PAWN) && p.epCapturable(ep, p.side)) p.changeEp(ep)
        }

        val half = if (f.size > 4) f[4].toIntOrNull() else 0
        val full = if (f.size > 5) f[5].toIntOrNull() else 1
        require(half != null && half >= 0) { "FEN: счётчик полуходов «${f[4]}»" }
        require(full != null && full >= 1) { "FEN: номер хода «${f[5]}»" }
        p.setClocks(half, full)
        return p
    }

    fun write(p: Position): String {
        val sb = StringBuilder(90)
        for (rank in 7 downTo 0) {
            var empty = 0
            for (file in 0..7) {
                val pc = p.board[Square.of(file, rank)]
                if (pc == Piece.NONE) {
                    empty++
                } else {
                    if (empty > 0) sb.append(empty)
                    empty = 0
                    sb.append(Piece.CHARS[pc])
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank > 0) sb.append('/')
        }
        sb.append(if (p.side == Color.WHITE) " w " else " b ")
        if (p.castling == Castling.NONE) sb.append('-')
        else for (i in 0..3) if (((p.castling shr i) and 1) == 1) sb.append("KQkq"[i])
        sb.append(' ').append(if (p.epSquare == Square.NONE) "-" else Square.name(p.epSquare))
        sb.append(' ').append(p.halfmoveClock).append(' ').append(p.fullmoveNumber)
        return sb.toString()
    }
}
