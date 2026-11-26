package ru.ogrpfsense.dexpm8.chess

// Стандартная алгебраическая запись (SAN, как в PGN): «e4», «Nbd2», «exd5», «e8=Q», «O-O», «Qh4#».
// В Pulse этого нет (там только UCI) — написано заново для п. 25.1б.
// Запись нужна для листа партии и сохранения, разбор — для ходов с FICS и из PGN.
// Обе функции на время работы делают и отменяют ходы на [Position]: звать из того же потока,
// что владеет позицией (поиск компьютера работает на своей копии).
// Буквы фигур — английские (N B R Q K), как в PGN и у FICS; русские (К С Л Ф Кр) — забота экрана.

object San {
    /** Буквы типов фигур по [PieceType]; у пешки буквы в SAN нет. */
    private const val LETTERS = "PNBRQK"

    /** SAN законного хода [m] в позиции [pos]; незаконный ход — [IllegalArgumentException]. */
    fun write(pos: Position, m: Int): String {
        val legal = MoveGen().legal(pos)
        var found = false
        var ambiguous = false
        var sameFile = false
        var sameRank = false
        val from = Move.origin(m)
        val to = Move.target(m)
        val piece = Move.originPiece(m)
        for (i in 0 until legal.size) {
            val o = legal.moves[i]
            if (o == m) {
                found = true
            } else if (Move.originPiece(o) == piece && Move.target(o) == to && Move.origin(o) != from &&
                Move.type(o) != Move.CASTLING
            ) {
                // Та же фигура с другого поля идёт на то же поле — нужно уточнение.
                // (Превращения одной пешки в разные фигуры сюда не попадают: поле откуда то же.)
                ambiguous = true
                if (Square.file(Move.origin(o)) == Square.file(from)) sameFile = true
                if (Square.rank(Move.origin(o)) == Square.rank(from)) sameRank = true
            }
        }
        require(found) { "SAN: ход ${Move.uci(m)} незаконен в позиции ${Fen.write(pos)}" }

        val sb = StringBuilder(8)
        val type = Move.type(m)
        val pt = Piece.type(piece)
        val capture = Move.targetPiece(m) != Piece.NONE
        when {
            type == Move.CASTLING -> sb.append(if (Square.file(to) == 6) "O-O" else "O-O-O")
            pt == PieceType.PAWN -> {
                if (capture) sb.append('a' + Square.file(from)).append('x')
                sb.append(Square.name(to))
                if (type == Move.PROMOTION) sb.append('=').append(LETTERS[Move.promotion(m)])
            }
            else -> {
                sb.append(LETTERS[pt])
                if (ambiguous) {
                    when {
                        !sameFile -> sb.append('a' + Square.file(from))
                        !sameRank -> sb.append('1' + Square.rank(from))
                        else -> sb.append(Square.name(from))
                    }
                }
                if (capture) sb.append('x')
                sb.append(Square.name(to))
            }
        }

        pos.makeMove(m)
        if (pos.isCheck()) sb.append(if (MoveGen().legal(pos).size == 0) '#' else '+')
        pos.undoMove(m)
        return sb.toString()
    }

    /**
     * Законный ход по SAN. Допускается: «+», «#», «!», «?» в конце, «e.p.», нули в рокировке («0-0»),
     * превращение без «=» («e8Q», «e8q»), лишнее или пропущенное «x», полное поле откуда
     * («Ng1f3», «Ng1-f3»). Нет такого хода или он неоднозначен — [IllegalArgumentException].
     */
    fun parse(pos: Position, text: String): Int {
        var s = text.replace("e.p.", "").trim()
        while (s.isNotEmpty() && s.last() in "+#!?") s = s.dropLast(1)
        s = s.trim()
        require(s.isNotEmpty()) { "SAN: пустой ход" }
        val legal = MoveGen().legal(pos)

        val castle = s.replace('0', 'O')
        if (castle == "O-O" || castle == "O-O-O") {
            val file = if (castle.length == 3) 6 else 2
            for (i in 0 until legal.size) {
                val o = legal.moves[i]
                if (Move.type(o) == Move.CASTLING && Square.file(Move.target(o)) == file) return o
            }
            throw IllegalArgumentException("SAN: рокировка «$text» сейчас невозможна")
        }

        var body = s
        var promo = PieceType.NONE
        val eq = body.indexOf('=')
        if (eq >= 0) {
            require(eq == body.length - 2) { "SAN: превращение «$text»" }
            promo = promotionType(body.last())
            require(promo != PieceType.NONE) { "SAN: превращение «$text»" }
            body = body.substring(0, eq)
        } else if (body.length >= 3 && body[body.length - 2] in '1'..'8' &&
            promotionType(body.last()) != PieceType.NONE
        ) {
            promo = promotionType(body.last())
            body = body.dropLast(1)
        }

        var type = PieceType.PAWN
        if (body.isNotEmpty() && body[0] in "PNBRQK") {
            type = LETTERS.indexOf(body[0])
            body = body.substring(1)
        }
        body = body.filter { it != 'x' && it != 'X' && it != '-' && it != ':' }
        require(body.length in 2..4) { "SAN: не разобрать «$text»" }
        val to = Square.parse(body.takeLast(2))
        require(to != Square.NONE) { "SAN: поле «${body.takeLast(2)}» в «$text»" }

        var file = -1
        var rank = -1
        for (ch in body.dropLast(2)) {
            when (ch) {
                in 'a'..'h' -> { require(file < 0) { "SAN: не разобрать «$text»" }; file = ch - 'a' }
                in '1'..'8' -> { require(rank < 0) { "SAN: не разобрать «$text»" }; rank = ch - '1' }
                else -> throw IllegalArgumentException("SAN: лишний знак «$ch» в «$text»")
            }
        }
        // Пешка без вертикали — только ход вперёд: взятие в SAN всегда пишется с вертикалью.
        if (type == PieceType.PAWN && file < 0) file = Square.file(to)

        var found = Move.NONE
        var n = 0
        for (i in 0 until legal.size) {
            val o = legal.moves[i]
            if (Move.type(o) == Move.CASTLING) continue
            if (Piece.type(Move.originPiece(o)) != type || Move.target(o) != to) continue
            if (file >= 0 && Square.file(Move.origin(o)) != file) continue
            if (rank >= 0 && Square.rank(Move.origin(o)) != rank) continue
            val op = if (Move.type(o) == Move.PROMOTION) Move.promotion(o) else PieceType.NONE
            if (op != promo) continue
            found = o
            n++
        }
        require(n > 0) { "SAN: нет такого хода «$text»" }
        require(n == 1) { "SAN: ход «$text» неоднозначен" }
        return found
    }

    /** Законный ход по длинной записи «e2e4», «e7e8q», «e1g1» (UCI); нет такого — [IllegalArgumentException]. */
    fun fromUci(pos: Position, text: String): Int {
        val s = text.trim().lowercase()
        val legal = MoveGen().legal(pos)
        for (i in 0 until legal.size) if (Move.uci(legal.moves[i]) == s) return legal.moves[i]
        throw IllegalArgumentException("UCI: нет такого хода «$text»")
    }

    private fun promotionType(ch: Char) = when (ch.uppercaseChar()) {
        'N' -> PieceType.KNIGHT
        'B' -> PieceType.BISHOP
        'R' -> PieceType.ROOK
        'Q' -> PieceType.QUEEN
        else -> PieceType.NONE
    }
}
