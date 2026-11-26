// Замер скорости шахматного ядра на книге (п. 25.1г): perft, поиск на заданную глубину и уровни.
// Не тест — проверок нет, только числа. Собирается вместе с chess/ в .dex и запускается через
// app_process, без APK; как — docs/CHECKS.md, «Шахматы на книге». На хосте запускается так же,
// как chess_test.kt (класс Chess_benchKt) — для сравнения.

import ru.ogrpfsense.dexpm8.chess.Fen
import ru.ogrpfsense.dexpm8.chess.Level
import ru.ogrpfsense.dexpm8.chess.Limits
import ru.ogrpfsense.dexpm8.chess.Move
import ru.ogrpfsense.dexpm8.chess.Perft
import ru.ogrpfsense.dexpm8.chess.Search
import kotlin.random.Random

private const val KIWIPETE = "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1"

/** Три позиции: дебют, насыщенный миттельшпиль, ладейный эндшпиль (позиция 3 chessprogramming). */
private val POSITIONS = listOf(
    "итальянская" to "r1bqk2r/pppp1ppp/2n2n2/2b1p3/2B1P3/2N2N2/PPPP1PPP/R1BQK2R w KQkq - 6 5",
    "Kiwipete" to KIWIPETE,
    "эндшпиль" to "8/2p5/3p4/KP5r/1R3p1k/8/4P1P1/8 w - - 0 1",
)

/** Предел одного прогона «на глубину»: дальше глубину не увеличиваем. */
private const val DEPTH_STOP_MS = 8000L

private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

private fun perft(name: String, fen: String, depth: Int) {
    val t0 = System.nanoTime()
    val n = Perft.count(Fen.parse(fen), depth)
    val t = ms(t0)
    println("perft: $name, глубина $depth — $n узлов за $t мс (${if (t > 0) n / t else 0} узлов/мс)")
}

/** Без аргументов — всё; аргумент `levels` — только уровни (проверка чисел Level, ~1,5 мин на книге). */
fun main(args: Array<String>) {
    val all = System.nanoTime()
    val rt = Runtime.getRuntime()
    println("bench: процессоров ${rt.availableProcessors()}, куча до ${rt.maxMemory() / 1048576} МБ, " +
        "VM ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}")

    // Прогрев: JIT компилирует горячие методы не сразу.
    val w0 = System.nanoTime()
    repeat(3) { Perft.count(Fen.parse(Fen.START), 3) }
    Search().think(Fen.parse(KIWIPETE), Limits(depth = 2))
    println("bench: прогрев ${ms(w0)} мс")

    if ("levels" !in args) speed()
    levels()
    println("bench: всего ${ms(all) / 1000} с, куча занята ${(rt.totalMemory() - rt.freeMemory()) / 1048576} МБ")
}

/** perft и поиск на заданную глубину. */
private fun speed() {
    perft("начальная", Fen.START, 4)
    perft("начальная", Fen.START, 5)
    perft("Kiwipete", KIWIPETE, 3)
    perft("Kiwipete", KIWIPETE, 4)

    // Поиск на заданную глубину: сколько стоит каждая следующая глубина.
    for ((name, fen) in POSITIONS) {
        for (d in 1..12) {
            val r = Search().think(Fen.parse(fen), Limits(depth = d))
            val nps = if (r.ms > 0) r.nodes / r.ms else 0
            println("depth: $name, глубина ${r.depth} — ${r.nodes} узлов за ${r.ms} мс ($nps узлов/мс), " +
                "ход ${Move.uci(r.move)}, оценка ${r.value}")
            if (r.ms > DEPTH_STOP_MS) break
        }
    }
}

/** Уровни 1–8 с их настоящими пределами (Level.of): глубина, время, ход. */
private fun levels() {
    for (n in 1..Level.COUNT) {
        val lim = Level.of(n)
        for ((name, fen) in POSITIONS) {
            val r = Search(Random(n)).think(Fen.parse(fen), lim)
            println("level: $n (глубина ${if (lim.depth > 100) "—" else lim.depth}, ${lim.timeMs} мс, " +
                "неточность ${lim.noise}), $name — глубина ${r.depth}, ${r.nodes} узлов за ${r.ms} мс, " +
                "ход ${Move.uci(r.move)}")
        }
    }
}
