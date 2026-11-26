package ru.ogrpfsense.dexpm8.chess

// Позиция FICS строкой «<12>» (style 12, `help style12`), п. 25.3. Строка снята с сервера
// 2026-11-26 (docs/archive/DEVLOG-2026-11-26-25.3.md):
//   <12> rnbqkbnr pppp-ppp -------- ----p--- ----P--- -------- PPPP-PPP RNBQKBNR W 4 1 1 1 1 0
//        28 GuestMXXT GuestFLMP 1 15 10 39 39 900000 900000 2 P/e7-e5 (0:00.000) e5 0 1 0
// Поля после «<12>»: 8 рядов с 8-го по 1-й (`-` — пусто), чей ход, вертикаль двойного хода
// пешки (−1 — нет), 4 права рокировки, полуходы без взятия и хода пешки, номер партии, белые,
// чёрные, наше отношение к партии, контроль (мин + с), материал, часы белых и чёрных (мс при
// `iset ms 1`), номер хода, последний ход подробно («P/e2-e4», «o-o», «P/a7-a8=Q», «none»),
// время на него, он же в SAN, доска перевёрнута, часы идут, задержка.
// Пакет без android.* — проверяется на хосте.

class Style12(
    /** 8 строк по 8 знаков, [0] — 8-й ряд, [0][0] — a8. */
    val rows: List<String>,
    /** [Color.WHITE] или [Color.BLACK]. */
    val side: Int,
    /** Вертикаль 0…7 последнего двойного хода пешки; −1 — не было. */
    val doublePawn: Int,
    /** Права рокировки: биты [Castling]. */
    val castling: Int,
    val halfmove: Int,
    val game: Int,
    val white: String,
    val black: String,
    /** Отношение к партии: см. [MY_MOVE] и соседей. */
    val relation: Int,
    /** Контроль: минут на партию и секунд добавки за ход. */
    val minutes: Int,
    val increment: Int,
    /** Остаток часов, мс; кончилось время — может быть меньше нуля. */
    val whiteMs: Long,
    val blackMs: Long,
    /** Номер хода, который сейчас будет сделан (как в FEN). */
    val moveNumber: Int,
    /** Последний ход подробно: «P/e2-e4», «o-o-o», «none». */
    val verbose: String,
    /** Последний ход в SAN сервера («Qh4#»); «none» — ходов не было. */
    val pretty: String,
    /** Часы идут (у первых ходов обеих сторон часы стоят). */
    val ticking: Boolean,
) {
    /** Полуходов от начальной позиции стандартной партии. */
    val ply: Int get() = (moveNumber - 1) * 2 + side

    /** Позиция в FEN (её нормализует [Fen.parse]: лишняя рокировка и взятие на проходе уходят). */
    fun fen(): String {
        val sb = StringBuilder(90)
        for ((i, row) in rows.withIndex()) {
            var empty = 0
            for (ch in row) {
                if (ch == '-') {
                    empty++
                } else {
                    if (empty > 0) sb.append(empty)
                    empty = 0
                    sb.append(ch)
                }
            }
            if (empty > 0) sb.append(empty)
            if (i < 7) sb.append('/')
        }
        sb.append(if (side == Color.WHITE) " w " else " b ")
        if (castling == Castling.NONE) sb.append('-')
        else for (i in 0..3) if (((castling shr i) and 1) == 1) sb.append("KQkq"[i])
        sb.append(' ')
        if (doublePawn in 0..7) sb.append('a' + doublePawn).append(if (side == Color.WHITE) '6' else '3')
        else sb.append('-')
        sb.append(' ').append(halfmove).append(' ').append(moveNumber)
        return sb.toString()
    }

    /**
     * Последний ход в UCI («e2e4», «e7e8q», рокировка — ходом короля) или null: ходов не было
     * либо запись непонятна (варианты с вводом фигур и т. п.). Ходила сторона, противоположная [side].
     */
    fun lastUci(): String? {
        val moved = Color.opposite(side)
        val back = if (moved == Color.WHITE) '1' else '8'
        return when {
            verbose == "o-o" -> "e${back}g$back"
            verbose == "o-o-o" -> "e${back}c$back"
            verbose.length >= 7 && verbose[1] == '/' && verbose[4] == '-' -> {
                val from = verbose.substring(2, 4)
                val to = verbose.substring(5, 7)
                if (Square.parse(from) == Square.NONE || Square.parse(to) == Square.NONE) return null
                val promo = if (verbose.length == 9 && verbose[7] == '=') verbose[8].lowercaseChar().toString() else ""
                if (promo.isNotEmpty() && promo !in listOf("n", "b", "r", "q")) return null
                from + to + promo
            }
            else -> null
        }
    }

    companion object {
        /** Играем, наш ход. */
        const val MY_MOVE = 1
        /** Играем, ход соперника. */
        const val THEIR_MOVE = -1
        /** Смотрим чужую партию. */
        const val OBSERVING = 0
        /** Разбираем партию сами (examine). */
        const val EXAMINING = 2

        /** Строка «<12> …» или null, если это не она или она битая. */
        fun parse(line: String): Style12? {
            if (!line.startsWith("<12> ")) return null
            val f = line.trim().split(' ').filter { it.isNotEmpty() }
            if (f.size < 31) return null
            val rows = f.subList(1, 9)
            for (r in rows) {
                if (r.length != 8) return null
                for (ch in r) if (ch != '-' && Piece.CHARS.indexOf(ch) < 0) return null
            }
            val side = when (f[9]) {
                "W" -> Color.WHITE
                "B" -> Color.BLACK
                else -> return null
            }
            return try {
                var castling = 0
                for (i in 0..3) if (f[11 + i] == "1") castling = castling or (1 shl i)
                Style12(
                    rows = rows.toList(), side = side, doublePawn = f[10].toInt(), castling = castling,
                    halfmove = f[15].toInt(), game = f[16].toInt(), white = f[17], black = f[18],
                    relation = f[19].toInt(), minutes = f[20].toInt(), increment = f[21].toInt(),
                    whiteMs = f[24].toLong(), blackMs = f[25].toLong(), moveNumber = f[26].toInt(),
                    verbose = f[27], pretty = f[29],
                    ticking = f.size > 31 && f[31] == "1",
                )
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}
