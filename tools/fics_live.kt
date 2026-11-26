// Живая проверка FICS (п. 25.3): два гостя — два FicsClient + NetGame — играют друг с другом
// на freechess.org через «match» (чужих не трогаем). Нужна сеть. Запуск — docs/CHECKS.md,
// «Шахматы на хосте». Ждём «=== ВСЁ ОК (N проверок)».
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import ru.ogrpfsense.dexpm8.chess.Fen
import ru.ogrpfsense.dexpm8.chess.FicsClient
import ru.ogrpfsense.dexpm8.chess.FicsEnd
import ru.ogrpfsense.dexpm8.chess.FicsEvent
import ru.ogrpfsense.dexpm8.chess.NetGame
import ru.ogrpfsense.dexpm8.chess.San

private var fails = 0
private var oks = 0

private fun check(name: String, got: Any?, want: Any?) {
    if (got == want) { oks++; println("OK   $name") }
    else { fails++; println("FAIL $name\n  ждали: $want\n  вышло: $got") }
}

private val t0 = System.nanoTime()
private fun now() = (System.nanoTime() - t0) / 1_000_000

private class Side(val tag: String) {
    val q = LinkedBlockingQueue<FicsEvent>()
    val c = FicsClient("live-test", listener = { e ->
        if (e is FicsEvent.Text) println("  $tag: ${e.line.take(100)}")
        q.offer(e)
    })
    var net: NetGame? = null

    /** Ждать событие, подходящее под [ok]; позиции по пути отдаются [net]. */
    fun <T> wait(what: String, ms: Long = 20_000, ok: (FicsEvent) -> T?): T? {
        val end = now() + ms
        while (true) {
            val left = end - now()
            if (left <= 0) { println("  $tag: не дождались — $what"); return null }
            val e = q.poll(left, TimeUnit.MILLISECONDS) ?: continue
            if (e is FicsEvent.Board) net?.apply(e.s, now())
            ok(e)?.let { return it }
        }
    }

    /** Ждать, пока партия дойдёт до полухода [ply]. */
    fun ply(ply: Int) = wait("полуход $ply") { if ((net?.game?.ply ?: -1) >= ply) true else null }

    fun move(uci: String) {
        val n = net!!
        val cmd = n.play(San.fromUci(n.game.position, uci))
        check("$tag: ход $uci принят на своей доске", cmd != null, true)
        if (cmd != null) c.send(cmd)
    }
}

fun main() {
    val a = Side("A")
    val b = Side("B")
    a.c.start()
    b.c.start()
    val na = a.wait("вход", 40_000) { (it as? FicsEvent.LoggedIn)?.name }
    val nb = b.wait("вход", 40_000) { (it as? FicsEvent.LoggedIn)?.name }
    check("вход гостем: оба получили имя Guest…", na?.startsWith("Guest") == true && nb?.startsWith("Guest") == true, true)
    if (na == null || nb == null) exitProcess(1)
    Thread.sleep(2000) // настройки дошли

    a.c.send("match $nb 15 10 u white")
    check("open 0: вызов не принимается",
        a.wait("отказ") { e -> (e as? FicsEvent.Text)?.line?.takeIf { "not open" in it } } != null, true)
    b.c.send("set open 1")
    Thread.sleep(800)
    a.c.send("match $nb 15 10 u white")
    b.wait("вызов") { e -> (e as? FicsEvent.Text)?.line?.takeIf { it.startsWith("Challenge:") } }
    b.c.send("accept")
    val ga = a.wait("начало") { (it as? FicsEvent.Started)?.game }
    val gb = b.wait("начало") { (it as? FicsEvent.Started)?.game }
    check("партия началась у обоих", ga != null && ga == gb, true)
    a.net = NetGame(na, ga ?: 0)
    b.net = NetGame(nb, gb ?: 0)
    a.ply(0); b.ply(0)

    val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6", "e1g1", "f8c5")
    for ((i, m) in moves.withIndex()) {
        val (me, other) = if (i % 2 == 0) a to b else b to a
        me.move(m)
        me.ply(i + 1)
        other.ply(i + 1)
    }
    Thread.sleep(1000)
    a.wait("тишина", 500) { null }
    b.wait("тишина", 500) { null }
    val fa = Fen.write(a.net!!.game.position)
    check("позиции совпадают у обоих", fa, Fen.write(b.net!!.game.position))
    check("лист SAN с рокировкой", a.net!!.game.san.toList(), listOf("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "O-O", "Bc5"))
    check("без пересборок", a.net!!.resyncs + b.net!!.resyncs, 0)
    check("часы идут у белых", a.net!!.running(0), true)

    a.c.send("a1a5")
    check("незаконный ход", a.wait("Illegal") { (it as? FicsEvent.Illegal)?.move }, "a1a5")
    b.c.send("draw")
    check("ничья: предложение дошло", a.wait("draw") { (it as? FicsEvent.DrawOffer)?.who }, nb)
    a.c.send("decline")
    check("ничья: отказ дошёл", b.wait("decline") { (it as? FicsEvent.DrawDeclined)?.who }, na)
    a.c.send("resign")
    val ea = a.wait("конец") { it as? FicsEvent.Ended }
    val eb = b.wait("конец") { it as? FicsEvent.Ended }
    check("сдача: у обоих RESIGN 0-1", listOf(ea?.kind, ea?.result, eb?.kind),
        listOf(FicsEnd.RESIGN, "0-1", FicsEnd.RESIGN))

    // Поиск при open 0 (A так и не открывался): выставить и сразу снять.
    a.c.send("seek 15 10 u")
    val posted = a.wait("seek") { e ->
        when (e) {
            is FicsEvent.SeekPosted -> "posted"
            is FicsEvent.Started -> { a.c.send("abort"); "game" }
            is FicsEvent.Text -> e.line.takeIf { "seek" in it.lowercase() }
            else -> null
        }
    }
    a.c.send("unseek")
    println("  seek при open 0: $posted")
    check("seek при open 0 выставляется", posted, "posted")
    check("unseek снимает", a.wait("unseek") { if (it === FicsEvent.SeekRemoved) true else null }, true)

    a.c.close()
    b.c.close()
    check("закрытие A без ошибки", a.wait("closed") { (it as? FicsEvent.Closed)?.let { c -> c.error ?: "ok" } }, "ok")
    check("закрытие B без ошибки", b.wait("closed") { (it as? FicsEvent.Closed)?.let { c -> c.error ?: "ok" } }, "ok")
    println(if (fails == 0) "=== ВСЁ ОК ($oks проверок)" else "=== ПРОВАЛОВ: $fails из ${oks + fails}")
    if (fails != 0) exitProcess(1)
}
