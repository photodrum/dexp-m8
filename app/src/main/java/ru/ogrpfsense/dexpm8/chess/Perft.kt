package ru.ogrpfsense.dexpm8.chess

// Perft — счёт всех законных партий до заданной глубины; перенос Perft.java из Pulse Chess
// (Copyright (C) 2013-2023 Phokham Nonava, MIT, PULSE-LICENSE.txt рядом).
// Проверка генератора ходов: числа сверяются с эталоном chessprogramming.org.
// На хосте — tools/chess_test.kt; на книге — замер скорости (шаг 25.1г).

object Perft {
    fun count(pos: Position, depth: Int): Long {
        if (depth <= 0) return 1
        val gens = Array(depth) { MoveGen() }
        return walk(pos, depth, 0, gens)
    }

    /** По каждому первому ходу — сколько узлов под ним («divide»): для поиска расхождения. */
    fun divide(pos: Position, depth: Int): List<Pair<String, Long>> {
        val gens = Array(maxOf(depth, 1)) { MoveGen() }
        val l = gens[0].legal(pos)
        val out = ArrayList<Pair<String, Long>>(l.size)
        for (i in 0 until l.size) {
            val m = l.moves[i]
            pos.makeMove(m)
            out.add(Move.uci(m) to if (depth > 1) walk(pos, depth - 1, 1, gens) else 1L)
            pos.undoMove(m)
        }
        return out.sortedBy { it.first }
    }

    private fun walk(pos: Position, depth: Int, ply: Int, gens: Array<MoveGen>): Long {
        val l = gens[ply].pseudo(pos, depth, pos.isCheck())
        var total = 0L
        for (i in 0 until l.size) {
            val m = l.moves[i]
            pos.makeMove(m)
            if (!pos.isCheck(Color.opposite(pos.side))) total += if (depth == 1) 1 else walk(pos, depth - 1, ply + 1, gens)
            pos.undoMove(m)
        }
        return total
    }
}
