// Хост-проверка шахматного ядра chess/ (п. 25.1а): perft на эталонных позициях
// (chessprogramming.org, «Perft Results», и набор Мартина Седлака), FEN туда и обратно,
// отказ на битых FEN, ключ Zobrist после хода и отмены.
// Запуск и сборка — docs/CHECKS.md, «Шахматы на хосте».
import kotlin.system.exitProcess
import ru.ogrpfsense.dexpm8.chess.Fen
import ru.ogrpfsense.dexpm8.chess.Move
import ru.ogrpfsense.dexpm8.chess.MoveGen
import ru.ogrpfsense.dexpm8.chess.Perft
import ru.ogrpfsense.dexpm8.chess.Position

private var fails = 0
private var oks = 0

private fun check(name: String, got: Any?, want: Any?) {
    if (got == want) { oks++; println("OK   $name") }
    else { fails++; println("FAIL $name\n  ждали: $want\n  вышло: $got") }
}

private const val KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"

/** Позиция и perft по глубинам 1, 2, … (последняя — та, до какой считаем). */
private val PERFT = listOf(
    "начальная" to (Fen.START to longArrayOf(20, 400, 8902, 197281, 4865609)),
    "Kiwipete" to (KIWIPETE to longArrayOf(48, 2039, 97862, 4085603)),
    "позиция 3" to ("8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1" to longArrayOf(14, 191, 2812, 43238, 674624)),
    "позиция 4" to ("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1" to
        longArrayOf(6, 264, 9467, 422333)),
    "позиция 4, зеркало" to ("r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1" to
        longArrayOf(6, 264, 9467, 422333)),
    "позиция 5" to ("rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8" to longArrayOf(44, 1486, 62379, 2103487)),
    "позиция 6" to ("r4rk1/1pp1qppp/p1np1n2/2b1p1B1/2B1P1b1/P1NP1N2/1PP1QPPP/R4RK1 w - - 0 10" to
        longArrayOf(46, 2079, 89890, 3894594)),
)

/** Набор Седлака: позиция, глубина, узлы. Ловит взятие на проходе со вскрытым шахом, рокировки под боем и т. п. */
private val EDGE = listOf(
    Triple("8/5bk1/8/2Pp4/8/1K6/8/8 w - d6 0 1", 6, 824064L),
    Triple("8/8/1k6/8/2pP4/8/5BK1/8 b - d3 0 1", 6, 824064L),
    Triple("8/8/1k6/2b5/2pP4/8/5K2/8 b - d3 0 1", 6, 1440467L),
    Triple("8/5k2/8/2Pp4/2B5/1K6/8/8 w - d6 0 1", 6, 1440467L),
    Triple("5k2/8/8/8/8/8/8/4K2R w K - 0 1", 6, 661072L),
    Triple("3k4/8/8/8/8/8/8/R3K3 w Q - 0 1", 6, 803711L),
    Triple("r3k2r/1b4bq/8/8/8/8/7B/R3K2R w KQkq - 0 1", 4, 1274206L),
    Triple("r3k2r/8/3Q4/8/8/5q2/8/R3K2R b KQkq - 0 1", 4, 1720476L),
    Triple("2K2r2/4P3/8/8/8/8/8/3k4 w - - 0 1", 6, 3821001L),
    Triple("8/8/1P2K3/8/2n5/1q6/8/5k2 b - - 0 1", 5, 1004658L),
    Triple("4k3/1P6/8/8/8/8/K7/8 w - - 0 1", 6, 217342L),
    Triple("8/P1k5/K7/8/8/8/8/8 w - - 0 1", 6, 92683L),
    Triple("K1k5/8/P7/8/8/8/8/8 w - - 0 1", 6, 2217L),
    Triple("8/k1P5/8/1K6/8/8/8/8 w - - 0 1", 7, 567584L),
    Triple("8/8/2k5/5q2/5n2/8/5K2/8 b - - 0 1", 4, 23527L),
)

/** Обход дерева: после каждого хода ключ равен ключу той же позиции, прочитанной из FEN; после отмены — FEN прежний. */
private fun keyWalk(pos: Position, depth: Int, gens: Array<MoveGen>, ply: Int = 0): Int {
    if (depth == 0) return 0
    var bad = 0
    val before = Fen.write(pos)
    val key = pos.key
    val l = gens[ply].legal(pos)
    val moves = l.moves.copyOf(l.size)
    for (m in moves) {
        pos.makeMove(m)
        if (Fen.parse(Fen.write(pos)).key != pos.key) { if (bad++ == 0) println("  ключ разошёлся: $before + ${Move.uci(m)}") }
        bad += keyWalk(pos, depth - 1, gens, ply + 1)
        pos.undoMove(m)
        if (Fen.write(pos) != before || pos.key != key) { if (bad++ == 0) println("  отмена не вернула: $before") }
    }
    return bad
}

private fun rejects(fen: String): Boolean = try { Fen.parse(fen); false } catch (e: IllegalArgumentException) { true }

/** Сделать ходы, записанные как «e2e4». */
private fun play(p: Position, vararg ucis: String): Position {
    for (u in ucis) {
        val l = MoveGen().legal(p)
        p.makeMove((0 until l.size).map { l.moves[it] }.first { Move.uci(it) == u })
    }
    return p
}

fun main() {
    var nodes = 0L
    val t0 = System.nanoTime()
    for ((name, v) in PERFT) {
        val (fen, want) = v
        val pos = Fen.parse(fen)
        for (d in 1..want.size) {
            val n = Perft.count(pos, d)
            nodes += n
            check("perft $name, глубина $d", n, want[d - 1])
        }
        check("perft $name: позиция не испорчена", Fen.write(pos), Fen.write(Fen.parse(fen)))
    }
    for ((fen, d, want) in EDGE) {
        val n = Perft.count(Fen.parse(fen), d)
        nodes += n
        check("perft $fen, глубина $d", n, want)
    }
    val ms = (System.nanoTime() - t0) / 1_000_000
    println("perft: $nodes узлов за $ms мс (${if (ms > 0) nodes / ms else 0} узлов/мс, хост)")

    // FEN туда и обратно (поля взятия на проходе — только там, где взять можно).
    for (fen in listOf(
        Fen.START, KIWIPETE,
        "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3",
        "rnbqkbnr/pppp1ppp/8/8/3Pp3/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 2",
        "4k3/8/8/8/8/8/8/4K2R b K - 37 81",
    )) check("FEN туда-обратно: $fen", Fen.write(Fen.parse(fen)), fen)

    // Нормализация.
    check("FEN: e3 без чёрной пешки рядом — «-»",
        Fen.write(Fen.parse("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")),
        "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
    check("FEN: права без ладьи отброшены",
        Fen.write(Fen.parse("4k3/8/8/8/8/8/8/4K2R w KQkq - 0 1")), "4k3/8/8/8/8/8/8/4K2R w K - 0 1")
    check("FEN: 4 поля — часы по умолчанию",
        Fen.write(Fen.parse("4k3/8/8/8/8/8/8/4K3 w - -")), "4k3/8/8/8/8/8/8/4K3 w - - 0 1")
    check("ход e2e4: поля взятия нет (рядом нет чёрной пешки)",
        Fen.write(play(Fen.parse(Fen.START), "e2e4")), "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1")
    check("e2e4 d7d5 e4e5 f7f5: поле f6 есть",
        Fen.write(play(Fen.parse(Fen.START), "e2e4", "d7d5", "e4e5", "f7f5")),
        "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3")

    // Битые FEN.
    for ((why, fen) in listOf(
        "7 рядов" to "8/8/8/8/8/8/8 w - - 0 1",
        "9 полей в ряду" to "rnbqkbnr/ppppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
        "два белых короля" to "4k3/8/8/8/8/8/8/3KK3 w - - 0 1",
        "нет чёрного короля" to "8/8/8/8/8/8/8/4K3 w - - 0 1",
        "пешка на 8-м ряду" to "P3k3/8/8/8/8/8/8/4K3 w - - 0 1",
        "шах не ходящей стороне" to "4k3/8/8/8/8/8/8/4K2r b - - 0 1",
        "чужая буква" to "4k3/8/8/8/8/8/8/4K2x w - - 0 1",
        "цвет хода" to "4k3/8/8/8/8/8/8/4K3 x - - 0 1",
        "рокировка" to "4k3/8/8/8/8/8/8/4K3 w X - 0 1",
        "взятие на проходе не на том ряду" to "4k3/8/8/8/8/8/8/4K3 w - e3 0 1",
        "номер хода 0" to "4k3/8/8/8/8/8/8/4K3 w - - 0 0",
    )) check("FEN отвергнут: $why", rejects(fen), true)

    // Ключи Zobrist.
    for ((name, fen) in listOf(
        "начальная" to Fen.START, "Kiwipete" to KIWIPETE,
        "взятие на проходе" to "8/5bk1/8/2Pp4/8/1K6/8/8 w - d6 0 1",
    )) check("ключ после хода и отмены: $name, глубина 3", keyWalk(Fen.parse(fen), 3, Array(3) { MoveGen() }), 0)
    val back = play(Fen.parse(Fen.START), "g1f3", "g8f6", "f3g1", "f6g8")
    check("Кf3 Кf6 Кg1 Кg8: ключ как у начальной и повтор найден",
        back.key == Fen.parse(Fen.START).key && back.isRepetition(), true)

    println(if (fails == 0) "=== ВСЁ ОК ($oks проверок)" else "=== ПРОВАЛОВ: $fails из ${oks + fails}")
    if (fails != 0) exitProcess(1)
}
