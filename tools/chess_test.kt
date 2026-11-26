// Хост-проверка шахматного ядра chess/ (п. 25.1а): perft на эталонных позициях
// (chessprogramming.org, «Perft Results», и набор Мартина Седлака), FEN туда и обратно,
// отказ на битых FEN, ключ Zobrist после хода и отмены.
// П. 25.1б: SAN (запись, разбор, туда-обратно) и окончание партии по правилам FIDE.
// П. 25.1в: оценка, поиск (мат, пределы, stop), уровни и «неточность».
// Запуск и сборка — docs/CHECKS.md, «Шахматы на хосте».
import kotlin.random.Random
import kotlin.system.exitProcess
import ru.ogrpfsense.dexpm8.chess.Eval
import ru.ogrpfsense.dexpm8.chess.Fen
import ru.ogrpfsense.dexpm8.chess.Game
import ru.ogrpfsense.dexpm8.chess.Level
import ru.ogrpfsense.dexpm8.chess.Limits
import ru.ogrpfsense.dexpm8.chess.Move
import ru.ogrpfsense.dexpm8.chess.MoveGen
import ru.ogrpfsense.dexpm8.chess.Perft
import ru.ogrpfsense.dexpm8.chess.Position
import ru.ogrpfsense.dexpm8.chess.San
import ru.ogrpfsense.dexpm8.chess.Search
import ru.ogrpfsense.dexpm8.chess.Square
import ru.ogrpfsense.dexpm8.chess.Status
import ru.ogrpfsense.dexpm8.chess.Value

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

/** SAN хода, записанного как «e2e4», в позиции [fen]. */
private fun sanOf(fen: String, uci: String): String {
    val p = Fen.parse(fen)
    return San.write(p, San.fromUci(p, uci))
}

/** Разбор SAN → UCI; ошибка разбора — «отказ». */
private fun parsed(fen: String, san: String): String =
    try { Move.uci(San.parse(Fen.parse(fen), san)) } catch (e: IllegalArgumentException) { "отказ" }

/** Обход дерева: SAN каждого хода разбирается в тот же ход, UCI — тоже, SAN в позиции не повторяются. */
private fun sanWalk(pos: Position, depth: Int, gens: Array<MoveGen>, ply: Int = 0): Int {
    if (depth == 0) return 0
    var bad = 0
    val l = gens[ply].legal(pos)
    val moves = l.moves.copyOf(l.size)
    val seen = HashSet<String>()
    for (m in moves) {
        val s = San.write(pos, m)
        if (!seen.add(s) || San.parse(pos, s) != m || San.fromUci(pos, Move.uci(m)) != m) {
            if (bad++ == 0) println("  SAN «$s» (${Move.uci(m)}) не сошёлся: ${Fen.write(pos)}")
        }
        pos.makeMove(m)
        bad += sanWalk(pos, depth - 1, gens, ply + 1)
        pos.undoMove(m)
    }
    return bad
}

/** Партия по ходам SAN. */
private fun game(fen: String, vararg sans: String): Game {
    val g = Game(fen)
    for (s in sans) g.playSan(s)
    return g
}

private const val QUEENS = "1k6/8/8/8/4Q2Q/8/8/K6Q w - - 0 1"
private const val PROMO = "2K2r2/4P3/8/8/8/8/8/3k4 w - - 0 1"
private const val CASTLE = "r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1"
private const val PINNED = "4k3/8/8/b7/8/2N3N1/8/4K3 w - - 0 1"
private const val EP = "rnbqkbnr/ppp1p1pp/8/3pPp2/8/8/PPPP1PPP/RNBQKBNR w KQkq f6 0 3"
private const val TWO_N = "4k3/8/8/8/8/5N2/8/1N2K3 w - - 0 1"

/** 25.1б: запись и разбор SAN. */
private fun sanChecks() {
    for ((fen, uci, want) in listOf(
        Triple(Fen.START, "e2e4", "e4"), Triple(Fen.START, "g1f3", "Nf3"),
        Triple(TWO_N, "b1d2", "Nbd2"), Triple(TWO_N, "f3d2", "Nfd2"),
        Triple("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1", "a1a3", "R1a3"),
        Triple("4k3/8/8/R7/8/8/8/R3K3 w - - 0 1", "a5a3", "R5a3"),
        Triple(QUEENS, "h4e1", "Qh4e1"), Triple(QUEENS, "e4e1", "Qee1"), Triple(QUEENS, "h1e1", "Q1e1"),
        Triple(PINNED, "g3e2", "Ne2"), Triple(EP, "e5f6", "exf6"),
        Triple(PROMO, "e7e8q", "e8=Q"), Triple(PROMO, "e7f8n", "exf8=N"),
        Triple(CASTLE, "e1g1", "O-O"), Triple(CASTLE, "e1c1", "O-O-O"),
        Triple("5k2/8/8/8/8/8/8/4K2R w K - 0 1", "e1g1", "O-O+"),
        Triple("4k3/8/8/8/8/8/8/R3K3 w Q - 0 1", "a1a8", "Ra8+"),
        Triple("7k/8/6K1/8/8/8/8/R7 w - - 0 1", "a1a8", "Ra8#"),
    )) check("SAN $uci → $want: $fen", sanOf(fen, uci), want)

    for ((name, fen) in listOf(
        "начальная" to Fen.START, "Kiwipete" to KIWIPETE,
        "позиция 4" to "r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1",
        "позиция 5" to "rnbq1k1r/pp1Pbppp/2p5/8/2B5/8/PPP1NnPP/RNBQK2R w KQ - 1 8",
        "три ферзя" to QUEENS, "превращение" to PROMO,
    )) check("SAN туда-обратно: $name, глубина 2", sanWalk(Fen.parse(fen), 2, Array(2) { MoveGen() }), 0)

    for ((fen, san, want) in listOf(
        Triple(Fen.START, "Nf3+", "g1f3"), Triple(Fen.START, "Ng1-f3", "g1f3"), Triple(Fen.START, "Ng1f3", "g1f3"),
        Triple(Fen.START, "e2-e4", "e2e4"), Triple(Fen.START, "Pe4", "e2e4"), Triple(TWO_N, "Nbd2!?", "b1d2"),
        Triple(CASTLE, "0-0", "e1g1"), Triple(CASTLE, "O-O-O", "e1c1"),
        Triple(PROMO, "e8Q", "e7e8q"), Triple(PROMO, "e8q", "e7e8q"), Triple(PROMO, "exf8N+", "e7f8n"),
        Triple(EP, "exf6 e.p.", "e5f6"), Triple(PINNED, "Ne2", "g3e2"),
        Triple(QUEENS, "Qe1", "отказ"), Triple(Fen.START, "Nd4", "отказ"), Triple(PROMO, "e8", "отказ"),
        Triple(CASTLE, "Kg1", "отказ"), Triple(Fen.START, "O-O", "отказ"), Triple(Fen.START, "z9", "отказ"),
        Triple(Fen.START, "", "отказ"), Triple(Fen.START, "exd3", "отказ"),
    )) check("разбор «$san»", parsed(fen, san), want)
}

/** 25.1б: окончание партии по правилам FIDE. */
private fun ruleChecks() {
    // Мат, итог, лист партии, отмена; пат.
    val fool = game(Fen.START, "f3", "e5", "g4", "Qh4#")
    check("детский мат: статус, итог, лист", listOf(fool.status, fool.result(), fool.san.joinToString(" ")),
        listOf(Status.CHECKMATE, "0-1", "f3 e5 g4 Qh4#"))
    check("после мата ходов нет, ход отвергнут",
        fool.legalMoves().isEmpty() && runCatching { fool.playUci("a2a3") }.isFailure, true)
    check("отмена мата", fool.undo() && fool.status == Status.PLAYING && fool.ply == 3 && fool.result() == "*", true)
    val scholar = game(Fen.START, "e4", "e5", "Bc4", "Nc6", "Qh5", "Nf6", "Qxf7#")
    check("мат Qxf7#: итог", scholar.san.last() to scholar.result(), "Qxf7#" to "1-0")
    check("пат", Game("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1").let { it.status to it.result() }, Status.STALEMATE to "1/2-1/2")

    // 50 и 75 ходов; мат последним ходом важнее 75 ходов.
    val g50 = game("4k3/8/8/8/8/8/8/R3K3 w - - 98 80", "Ra2")
    check("99 полуходов — требовать ничью нельзя", g50.canClaimDraw(), false)
    g50.playSan("Kd7")
    check("100 полуходов — можно, партия идёт", g50.canClaimDraw() to g50.status, true to Status.PLAYING)
    check("150 полуходов — ничья сама", game("4k3/8/8/8/8/8/8/R3K3 w - - 148 80", "Ra2", "Kd7").status, Status.MOVES_75)
    check("мат 150-м полуходом — мат", game("7k/8/6K1/8/8/8/8/R7 w - - 149 100", "Ra8#").status, Status.CHECKMATE)

    // Повторения: 3 — право требовать, 5 — ничья сама.
    val rep = Game()
    val cycle = listOf("Nf3", "Nf6", "Ng1", "Ng8")
    for (s in cycle) rep.playSan(s)
    check("повтор 2: требовать нельзя", rep.repetitions() to rep.canClaimDraw(), 2 to false)
    for (s in cycle) rep.playSan(s)
    check("повтор 3: требовать можно", rep.repetitions() to rep.canClaimDraw(), 3 to true)
    for (s in cycle + cycle) rep.playSan(s)
    check("повтор 5: ничья сама", rep.repetitions() to rep.status, 5 to Status.FIVEFOLD)
    check("отмена пятого повтора", rep.undo() && rep.status == Status.PLAYING, true)

    // Взятие на проходе есть в ключе, но связано — для повторения не считается (FIDE 9.2.3).
    val epg = game("4k3/3p4/8/K3P2r/8/8/8/8 b - - 0 1", "d5")
    val keyEp = epg.position.key
    val hasEp = epg.position.epSquare != Square.NONE
    for (s in listOf("Ka4", "Kf7", "Ka5", "Ke8")) epg.playSan(s)
    check("связанное взятие на проходе: ключи разные, повтор 2",
        listOf(hasEp, epg.position.key != keyEp, epg.repetitions()), listOf(true, true, 2))

    // Мёртвый материал.
    for ((fen, want) in listOf(
        "4k3/8/8/8/8/8/8/4K3 w - - 0 1" to true,
        "4k3/8/8/8/8/8/8/2B1K3 w - - 0 1" to true,
        "4k3/8/8/8/8/8/8/1N2K3 w - - 0 1" to true,
        "4kb2/8/8/8/8/8/8/2B1K3 w - - 0 1" to true,
        "4k3/8/8/8/8/4B3/8/2B1K3 w - - 0 1" to true,
        "2b1k3/8/8/8/8/8/8/2B1K3 w - - 0 1" to false,
        "1n2k3/8/8/8/8/8/8/1N2K3 w - - 0 1" to false,
        "4k3/8/8/8/8/8/8/1N2KN2 w - - 0 1" to false,
        "1n2k3/8/8/8/8/8/8/2B1K3 w - - 0 1" to false,
        "4k3/8/8/8/8/8/4P3/4K3 w - - 0 1" to false,
        "4k3/8/8/8/8/8/8/2BBK3 w - - 0 1" to false,
    )) check("мёртвый материал $want: $fen", Game.isDeadMaterial(Fen.parse(fen)), want)
    check("Kxe2 → ничья по материалу", game("4k3/8/8/8/8/8/4r3/4K3 w - - 0 1", "Kxe2").status, Status.DEAD)
}

/** Законен ли ход [m] в позиции [fen]. */
private fun legalIn(fen: String, m: Int): Boolean {
    val l = MoveGen().legal(Fen.parse(fen))
    return (0 until l.size).any { l.moves[it] == m }
}

/** Сыграть главную линию в партии: статус в конце или −1, если ход незаконен. */
private fun pvPlays(fen: String, pv: IntArray): Int {
    val g = Game(fen)
    for (m in pv) { if (!g.isLegal(m)) return -1; g.play(m) }
    return g.status
}

private fun uciOf(m: Int) = if (m == Move.NONE) "нет хода" else Move.uci(m)

private const val HANGING_Q = "4k3/8/8/3q4/8/8/8/3RK3 w - - 0 1"
private const val MATE_ONLY_KG8 = "7k/8/6K1/8/8/8/8/R7 b - - 0 1"

/** 25.1в: оценка и поиск. */
private fun searchChecks() {
    check("оценка: начальная позиция = темп", Eval.evaluate(Fen.parse(Fen.START)), Eval.TEMPO)
    check("оценка: позиция 4 и её зеркало равны",
        Eval.evaluate(Fen.parse("r3k2r/Pppp1ppp/1b3nbN/nP6/BBP1P3/q4N2/Pp1P2PP/R2Q1RK1 w kq - 0 1")),
        Eval.evaluate(Fen.parse("r2q1rk1/pP1p2pp/Q4n2/bbp1p3/Np6/1B3NBn/pPPP1PPP/R3K2R b KQ - 0 1")))
    check("оценка: лишний ферзь у того, чей ход, > 900 / у соперника < −900",
        Eval.evaluate(Fen.parse("4k3/8/8/8/8/8/8/3QK3 w - - 0 1")) > 900 &&
            Eval.evaluate(Fen.parse("4k3/8/8/8/8/8/8/3QK3 b - - 0 1")) < -900, true)

    val s = Search(Random(1))
    // Мат: ход (если он один), «мат в N», линия ведёт к мату и длиной 2N − 1.
    for ((fen, n, want) in listOf(
        Triple("6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1", 1, "d1d8"),
        Triple("7k/8/6K1/8/8/8/8/R7 w - - 0 1", 1, "a1a8"),
        Triple("7k/8/8/8/8/8/R7/1R4K1 w - - 0 1", 2, ""),
    )) {
        val r = s.think(Fen.parse(fen), Limits(depth = 6))
        check("мат в $n: $fen",
            listOf(want.isEmpty() || uciOf(r.move) == want, Value.mateIn(r.value), r.pv.size, pvPlays(fen, r.pv)),
            listOf(true, n, 2 * n - 1, Status.CHECKMATE))
    }
    val only = s.think(Fen.parse(MATE_ONLY_KG8), Limits(depth = 6))
    check("единственный ход — сразу, без углубления", uciOf(only.move) to only.depth, "h8g8" to 1)
    check("мат нам через ход: «мат в −1»", Value.mateIn(-(Value.CHECKMATE - 2)) to Value.mateIn(150), -1 to 0)
    check("висящий ферзь взят", uciOf(s.think(Fen.parse(HANGING_Q), Limits(depth = 3)).move), "d1d5")
    val mated = s.think(Fen.parse("7k/7Q/6K1/8/8/8/8/8 b - - 0 1"))
    val stale = s.think(Fen.parse("7k/5Q2/6K1/8/8/8/8/8 b - - 0 1"))
    check("мат и пат: хода нет, оценки −мат и 0", listOf(uciOf(mated.move), mated.value, uciOf(stale.move), stale.value),
        listOf("нет хода", -Value.CHECKMATE, "нет хода", Value.DRAW))

    // Пределы: глубина, узлы, время, stop() из другого потока; позиция не портится.
    val kiwi = Fen.parse(KIWIPETE)
    val rd = s.think(Fen.parse(Fen.START), Limits(depth = 4))
    check("предел глубины 4", rd.depth to legalIn(Fen.START, rd.move), 4 to true)
    val rn = s.think(kiwi, Limits(nodes = 5000))
    check("предел 5000 узлов", rn.nodes <= 5000 && legalIn(KIWIPETE, rn.move), true)
    val rt = s.think(kiwi, Limits(timeMs = 300))
    check("предел 300 мс (вышло ${rt.ms} мс, глубина ${rt.depth})", rt.ms <= 450 && rt.depth >= 1 && legalIn(KIWIPETE, rt.move), true)
    Thread { Thread.sleep(200); s.stop() }.start()
    val rs = s.think(kiwi)
    check("stop() через 200 мс (вышло ${rs.ms} мс)", rs.ms < 1000 && legalIn(KIWIPETE, rs.move), true)
    check("позиция после поиска не тронута", Fen.write(kiwi) to kiwi.key, KIWIPETE to Fen.parse(KIWIPETE).key)

    // Копия видит повторения партии; ход на копии не трогает партию.
    val g = game(Fen.START, "Nf3", "Nf6", "Ng1", "Ng8")
    val before = Fen.write(g.position)
    val c = g.position.copy()
    check("копия: повтор виден, ключ и FEN те же", listOf(c.isRepetition(), c.key == g.position.key, Fen.write(c)),
        listOf(true, true, before))
    c.makeMove(San.parse(c, "e4"))
    check("копия: ход на копии партию не трогает", Fen.write(g.position) to g.position.isRepetition(), before to true)

    // Уровни и «неточность».
    for (n in 1..Level.COUNT) {
        val lim = Level.of(n).let { it.copy(timeMs = minOf(it.timeMs, 400)) }
        check("уровень $n: законный ход", legalIn(KIWIPETE, Search(Random(n)).think(kiwi, lim).move), true)
    }
    val weak = Search(Random(7))
    val seen = HashSet<Int>()
    for (i in 1..30) {
        val m = weak.think(Fen.parse(Fen.START), Limits(depth = 1, noise = 400)).move
        if (legalIn(Fen.START, m)) seen.add(m) else seen.add(-1)
    }
    check("неточность 400: ходы законны и разные (${seen.size})", !seen.contains(-1) && seen.size >= 3, true)
    check("неточность 60: ферзя всё равно берёт (10 раз)",
        (1..10).all { uciOf(weak.think(Fen.parse(HANGING_Q), Limits(depth = 2, noise = 60)).move) == "d1d5" }, true)
    check("уровень 1: мат в 1 ставит", uciOf(weak.think(Fen.parse("6k1/5ppp/8/8/8/8/8/3R2K1 w - - 0 1"), Level.of(1)).move),
        "d1d8")

    // Скорость (на книге сравнить в 25.1г).
    for ((name, fen, d) in listOf(Triple("начальная", Fen.START, 5), Triple("Kiwipete", KIWIPETE, 4))) {
        val r = Search().think(Fen.parse(fen), Limits(depth = d))
        println("search: $name, глубина ${r.depth} — ${r.nodes} узлов за ${r.ms} мс, ход ${uciOf(r.move)}, оценка ${r.value}")
        check("search: $name, глубина $d досчитана", r.depth, d)
    }
}

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

    sanChecks()
    ruleChecks()
    searchChecks()

    println(if (fails == 0) "=== ВСЁ ОК ($oks проверок)" else "=== ПРОВАЛОВ: $fails из ${oks + fails}")
    if (fails != 0) exitProcess(1)
}
