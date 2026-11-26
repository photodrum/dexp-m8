package ru.ogrpfsense.dexpm8.chess

import kotlin.math.abs
import kotlin.random.Random

// Поиск хода — перенос Search.java и Value.java из Pulse Chess
// (Copyright (C) 2013-2023 Phokham Nonava, MIT, PULSE-LICENSE.txt рядом).
// Как в Pulse: итеративное углубление, альфа-бета (negamax) с главной линией, поиск покоя
// (взятия, а под шахом — все ходы), ничья при повторе, 50 ходах и голом материале.
// Отличия от Pulse:
// - нет своих потоков, Timer и UCI: think() считает в потоке вызова (экран 25.2 зовёт его
//   из рабочего потока), время и stop() проверяются раз в 1024 узла;
// - позиция копируется (Position.copy() со стеком отмены) — повторения партии видны поиску,
//   а сама партия не трогается;
// - прерванная итерация не пропадает: лучший уже досчитанный в ней ход берётся;
// - новая итерация не начинается, если прошла половина времени (следующая дольше всех прежних);
// - «неточность» для слабых уровней ([Limits.noise]): корень считается полным окном, ход
//   выбирается случайно среди тех, что хуже лучшего не больше чем на noise сотых пешки.

/** Оценки (сотые пешки) и мат — как Value.java в Pulse. */
object Value {
    /** Предел глубины от корня вместе с поиском покоя (в Pulse 256; здесь меньше — меньше памяти). */
    const val MAX_PLY = 128
    const val INFINITE = 200000
    const val CHECKMATE = 100000
    const val CHECKMATE_THRESHOLD = CHECKMATE - MAX_PLY
    const val DRAW = 0

    fun isMate(v: Int) = abs(v) in CHECKMATE_THRESHOLD..CHECKMATE

    /** Мат через столько ходов: > 0 — ставим мы, < 0 — ставят нам, 0 — оценка не мат. */
    fun mateIn(v: Int): Int {
        if (!isMate(v)) return 0
        val moves = (CHECKMATE - abs(v) + 1) / 2
        return if (v > 0) moves else -moves
    }
}

/**
 * Пределы поиска; что наступит раньше. [timeMs] = 0 — без предела по времени.
 * [noise] > 0 — слабая игра: случайный ход среди не хуже лучшего на [noise] сотых пешки.
 */
data class Limits(
    val depth: Int = Value.MAX_PLY,
    val nodes: Long = Long.MAX_VALUE,
    val timeMs: Long = 0,
    val noise: Int = 0,
)

/**
 * Итог поиска. [move] = [Move.NONE] — ходов нет (мат или пат). [value] — со стороны того, чей ход;
 * [depth] — последняя полностью досчитанная итерация; [pv] — главная линия начиная с [move].
 */
class SearchResult(
    val move: Int,
    val ponder: Int,
    val value: Int,
    val depth: Int,
    val nodes: Long,
    val ms: Long,
    val pv: IntArray,
)

/** Один объект — одна партия; [think] не реентерабелен. [random] — для «неточности» (в проверках — с зерном). */
class Search(private val random: Random = Random.Default) {
    @Volatile
    private var stopped = false

    private val gens = Array(Value.MAX_PLY) { MoveGen() }
    private val pv = Array(Value.MAX_PLY + 1) { IntArray(Value.MAX_PLY + 1) }
    private val pvSize = IntArray(Value.MAX_PLY + 1)
    private val root = MoveList()

    private var pos = Position()
    private var nodes = 0L
    private var nodeLimit = Long.MAX_VALUE
    private var deadline = Long.MAX_VALUE
    private var abort = false

    /** Остановить идущий [think] из другого потока: он вернёт лучший найденный ход. */
    fun stop() {
        stopped = true
    }

    /**
     * Лучший ход в позиции [position]. Позиция копируется в начале — в этот момент её не должен
     * менять другой поток (иначе передать `position.copy()`). Сама [position] не меняется.
     */
    fun think(position: Position, limits: Limits = Limits()): SearchResult {
        val t0 = System.nanoTime()
        pos = position.copy()
        stopped = false
        abort = false
        nodes = 0
        nodeLimit = limits.nodes
        deadline = if (limits.timeMs > 0) t0 + limits.timeMs * 1_000_000 else Long.MAX_VALUE
        val full = limits.noise > 0

        root.size = 0
        val l = gens[0].legal(pos)
        for (i in 0 until l.size) root.add(l.moves[i])
        if (root.size == 0) {
            val v = if (pos.isCheck()) -Value.CHECKMATE else Value.DRAW
            return SearchResult(Move.NONE, Move.NONE, v, 0, 0, msSince(t0), IntArray(0))
        }

        var best = root.moves[0]
        var bestValue = Eval.evaluate(pos)
        var bestPv = intArrayOf(best)
        var completed = 0
        // Оценки корня последней полной итерации — для «неточности».
        val doneMoves = IntArray(root.size)
        val doneValues = IntArray(root.size)
        var done = 0

        for (depth in 1..limits.depth.coerceIn(1, Value.MAX_PLY)) {
            tick(0)
            if (abort) break
            for (i in 0 until root.size) root.values[i] = -Value.INFINITE
            var alpha = -Value.INFINITE
            var iterBest = -1
            for (i in 0 until root.size) {
                val m = root.moves[i]
                pos.makeMove(m)
                val v = -search(depth - 1, -Value.INFINITE, if (full) Value.INFINITE else -alpha, 1)
                pos.undoMove(m)
                if (abort) break
                if (full) root.values[i] = v
                if (v > alpha) {
                    alpha = v
                    root.values[i] = v
                    iterBest = i
                    savePv(m, 1, 0)
                }
            }
            if (iterBest >= 0) {
                best = root.moves[iterBest]
                bestValue = alpha
                bestPv = pv[0].copyOf(pvSize[0])
            }
            // Устойчиво по убыванию: лучший — первым в следующей итерации, недосчитанные — в прежнем порядке.
            root.sort()
            if (abort) break
            completed = depth
            if (full) {
                root.moves.copyInto(doneMoves, 0, 0, root.size)
                root.values.copyInto(doneValues, 0, 0, root.size)
                done = root.size
            }
            if (root.size == 1) break
            if (Value.isMate(bestValue) && depth >= Value.CHECKMATE - abs(bestValue)) break
            if (limits.timeMs > 0 && System.nanoTime() - t0 > limits.timeMs * 500_000) break
        }

        var move = best
        var value = bestValue
        var line = bestPv
        if (full && done > 0) {
            var top = -Value.INFINITE
            for (i in 0 until done) if (doneValues[i] > top) top = doneValues[i]
            var n = 0
            for (i in 0 until done) if (doneValues[i] >= top - limits.noise) n++
            var k = random.nextInt(n)
            var pick = 0
            for (i in 0 until done) {
                if (doneValues[i] >= top - limits.noise && k-- == 0) {
                    pick = i
                    break
                }
            }
            if (doneMoves[pick] != best) {
                move = doneMoves[pick]
                value = doneValues[pick]
                line = intArrayOf(move)
            }
        }
        val ponder = if (line.size > 1) line[1] else Move.NONE
        return SearchResult(move, ponder, value, completed, nodes, msSince(t0), line)
    }

    private fun msSince(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    /** Узел: счёт, пределы, сброс главной линии этого уровня (как updateSearch в Pulse). */
    private fun tick(ply: Int) {
        pvSize[ply] = 0
        nodes++
        if (nodes >= nodeLimit) abort = true
        else if ((nodes and 1023L) == 0L && (stopped || System.nanoTime() >= deadline)) abort = true
    }

    private fun search(depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        if (depth <= 0) return quiescent(0, alphaIn, beta, ply)
        tick(ply)
        if (abort || ply >= Value.MAX_PLY) return Eval.evaluate(pos)
        if (pos.isRepetition() || pos.hasInsufficientMaterial() || pos.halfmoveClock >= 100) return Value.DRAW

        var alpha = alphaIn
        var best = -Value.INFINITE
        var searched = 0
        val check = pos.isCheck()
        val l = gens[ply].pseudo(pos, depth, check)
        for (i in 0 until l.size) {
            val m = l.moves[i]
            var v = best
            pos.makeMove(m)
            if (!pos.isCheck(Color.opposite(pos.side))) {
                searched++
                v = -search(depth - 1, -beta, -alpha, ply + 1)
            }
            pos.undoMove(m)
            if (abort) return best
            if (v > best) {
                best = v
                if (v > alpha) {
                    alpha = v
                    savePv(m, ply + 1, ply)
                    if (v >= beta) break
                }
            }
        }
        if (searched == 0) return if (check) -Value.CHECKMATE + ply else Value.DRAW
        return best
    }

    private fun quiescent(depth: Int, alphaIn: Int, beta: Int, ply: Int): Int {
        tick(ply)
        if (abort || ply >= Value.MAX_PLY) return Eval.evaluate(pos)
        if (pos.isRepetition() || pos.hasInsufficientMaterial() || pos.halfmoveClock >= 100) return Value.DRAW

        var alpha = alphaIn
        var best = -Value.INFINITE
        var searched = 0
        val check = pos.isCheck()
        // Оценка «стоя на месте»: без шаха можно и не брать.
        if (!check) {
            best = Eval.evaluate(pos)
            if (best > alpha) {
                alpha = best
                if (best >= beta) return best
            }
        }
        val l = gens[ply].pseudo(pos, depth, check)
        for (i in 0 until l.size) {
            val m = l.moves[i]
            var v = best
            pos.makeMove(m)
            if (!pos.isCheck(Color.opposite(pos.side))) {
                searched++
                v = -quiescent(depth - 1, -beta, -alpha, ply + 1)
            }
            pos.undoMove(m)
            if (abort) return best
            if (v > best) {
                best = v
                if (v > alpha) {
                    alpha = v
                    savePv(m, ply + 1, ply)
                    if (v >= beta) break
                }
            }
        }
        if (searched == 0 && check) return -Value.CHECKMATE + ply
        return best
    }

    /** Главная линия уровня [dst] = ход [m] + линия уровня [src]. */
    private fun savePv(m: Int, src: Int, dst: Int) {
        pv[dst][0] = m
        pv[src].copyInto(pv[dst], 1, 0, pvSize[src])
        pvSize[dst] = pvSize[src] + 1
    }
}
