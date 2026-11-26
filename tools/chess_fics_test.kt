// Хост-проверка протокола FICS (п. 25.3) — вызывается из chess_test.kt (ficsChecks).
// Все строки — дословно с сервера freechess.org 2026-11-26: два гостя с хоста сыграли друг
// с другом (ничья, сдача, отмена, мат, флаг, отключение) — docs/archive/DEVLOG-2026-11-26-25.3.md.
// Живая проверка с сокетом — tools/fics_live.kt (docs/CHECKS.md, «Шахматы на хосте»).
import ru.ogrpfsense.dexpm8.chess.Color
import ru.ogrpfsense.dexpm8.chess.Fen
import ru.ogrpfsense.dexpm8.chess.Fics
import ru.ogrpfsense.dexpm8.chess.FicsEnd
import ru.ogrpfsense.dexpm8.chess.FicsEvent
import ru.ogrpfsense.dexpm8.chess.FicsLines
import ru.ogrpfsense.dexpm8.chess.NetGame
import ru.ogrpfsense.dexpm8.chess.San
import ru.ogrpfsense.dexpm8.chess.Status
import ru.ogrpfsense.dexpm8.chess.Style12

private const val P = "<12> "
private const val START = P + "rnbqkbnr pppppppp -------- -------- -------- -------- PPPPPPPP RNBQKBNR W -1 1 1 1 1 0 2 GuestMXXT GuestFLMP 1 15 10 39 39 900000 900000 1 none (0:00.000) none 0 0 0"
private const val F3 = P + "rnbqkbnr pppppppp -------- -------- -------- -----P-- PPPPP-PP RNBQKBNR B -1 1 1 1 1 0 2 GuestMXXT GuestFLMP -1 15 10 39 39 900000 900000 1 P/f2-f3 (0:00.000) f3 0 0 0"
private const val E5 = P + "rnbqkbnr pppp-ppp -------- ----p--- -------- -----P-- PPPPP-PP RNBQKBNR W 4 1 1 1 1 0 2 GuestMXXT GuestFLMP 1 15 10 39 39 900000 900000 2 P/e7-e5 (0:00.000) e5 0 1 0"
private const val E5_AGAIN = P + "rnbqkbnr pppp-ppp -------- ----p--- -------- -----P-- PPPPP-PP RNBQKBNR W 4 1 1 1 1 0 2 GuestMXXT GuestFLMP 1 15 10 39 39 898496 900000 2 P/e7-e5 (0:00.000) e5 0 1 0"
private const val G4 = P + "rnbqkbnr pppp-ppp -------- ----p--- ------P- -----P-- PPPPP--P RNBQKBNR B 6 1 1 1 1 0 2 GuestMXXT GuestFLMP -1 15 10 39 39 906996 900000 2 P/g2-g4 (0:03.004) g4 0 1 0"
private const val MATE = P + "rnb-kbnr pppp-ppp -------- ----p--- ------Pq -----P-- PPPPP--P RNBQKBNR W -1 1 1 1 1 1 2 GuestMXXT GuestFLMP 1 15 10 39 39 906996 907004 3 Q/d8-h4 (0:02.996) Qh4# 0 1 0"
private const val OBS1 = P + "rn-q-rk- ppp-bppp ---p-n-- -------- ---PP-b- --N--N-- PPP--PPP R-BQKB-R W -1 1 1 0 0 1 34 LNO Hathkhola 0 15 10 39 38 779477 912135 7 B/c8-g4 (0:08.381) Bg4 0 1 0"
private const val OBS2 = P + "rn-q-rk- ppp-bppp ---p-n-- -------- ---PP-b- --N--N-- PPP-BPPP R-BQK--R B -1 1 1 0 0 2 34 LNO Hathkhola 0 15 10 39 38 784813 912135 7 B/f1-e2 (0:21.524) Be2 0 1 110"
private const val OO_BLACK = P + "r-bq-rk- pppp-ppp --n--n-- --b-p--- --B-P--- ---P-N-- PPP--PPP RNBQ-RK- W -1 0 0 0 0 1 5 GuestFSMB GuestFSMB 2 0 0 39 39 0 0 6 o-o (0:00.000) O-O 0 0 0"
private const val PROMO = P + "Q------- -------k -------- -------- -------- -------- -------- K------- B -1 0 0 0 0 0 5 GuestFSMB GuestFSMB 2 0 0 47 39 0 0 1 P/a7-a8=Q (0:00.000) a8=Q 0 0 0"

private fun s12(line: String) = Style12.parse(line)!!

private fun feed(l: FicsLines, text: String, out: MutableList<String>) {
    val b = text.toByteArray(Charsets.ISO_8859_1)
    l.feed(b, b.size, out)
}

internal fun ficsChecks(check: (String, Any?, Any?) -> Unit) {
    // Строки: чанк режется посреди строки, «\n\r», IAC WONT ECHO, приглашение «fics% ».
    val l = FicsLines()
    val out = ArrayList<String>()
    feed(l, "\n\r      If you are not a registered player, enter guest or a unique ID.\n\r             (If your return key does n", out)
    feed(l, "ot work, use cntrl-J)\n\r\n\rlogin: ", out)
    check("строки: склейка чанков", out.toList(), listOf(
        "      If you are not a registered player, enter guest or a unique ID.",
        "             (If your return key does not work, use cntrl-J)"))
    check("строки: хвост «login: »", l.tail, "login: ")
    out.clear()
    val l2 = FicsLines()
    val iac = byteArrayOf(0xFF.toByte(), 0xFC.toByte(), 0x01) +
        "\n\r\n\r**** Starting FICS session as GuestFSMB(U) ****\n\rfics% \n\r$START\n\rfics% ".toByteArray(Charsets.ISO_8859_1)
    l2.feed(iac, iac.size, out)
    check("строки: IAC и приглашение убраны", out.toList(), listOf("**** Starting FICS session as GuestFSMB(U) ****", START))
    check("вход: имя из «Press return…»",
        Fics.guestName("Press return to enter the server as \"GuestFSMB\":"), "GuestFSMB")
    check("вход: начало сеанса", Fics.sessionName(out[0]), "GuestFSMB")
    check("вход: «Logging you in…» — не начало сеанса",
        Fics.sessionName("Logging you in as \"GuestFSMB\"; you may use this name to play unrated games."), null)

    // Style12.
    val e5 = s12(E5)
    check("<12>: поля", listOf(e5.game, e5.white, e5.black, e5.relation, e5.minutes, e5.increment,
        e5.whiteMs, e5.blackMs, e5.moveNumber, e5.ply, e5.ticking, e5.side),
        listOf(2, "GuestMXXT", "GuestFLMP", 1, 15, 10, 900000L, 900000L, 2, 2, true, Color.WHITE))
    check("<12>: FEN как есть", e5.fen(), "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq e6 0 2")
    check("<12>: FEN нормализован (e6 взять некому)", Fen.write(Fen.parse(e5.fen())),
        "rnbqkbnr/pppp1ppp/8/4p3/8/5P2/PPPPP1PP/RNBQKBNR w KQkq - 0 2")
    check("<12>: последний ход e7e5", e5.lastUci(), "e7e5")
    check("<12>: часы стоят у первого хода", s12(F3).ticking, false)
    check("<12>: рокировка чёрных o-o → e8g8", s12(OO_BLACK).lastUci(), "e8g8")
    check("<12>: превращение P/a7-a8=Q → a7a8q", s12(PROMO).lastUci(), "a7a8q")
    check("<12>: ходов нет — null", s12(START).lastUci(), null)
    check("<12>: наблюдение, время в мс и задержка", s12(OBS2).let { listOf(it.relation, it.whiteMs, it.ply) },
        listOf<Any>(0, 784813L, 13))
    check("<12>: рокировка из флагов «1 1 0 0»", s12(OBS1).fen().split(' ')[2], "KQ")
    check("<12>: битая строка", Style12.parse("<12> rnbqkbnr W"), null)
    check("<12>: не <12>", Style12.parse("Style 12 set."), null)

    endChecks(check)
    netChecks(check)
}

/** Строки сервера → события и команды для сервера. */
private fun endChecks(check: (String, Any?, Any?) -> Unit) {
    val st = Fics.parse("{Game 28 (GuestMXXT vs. GuestFLMP) Creating unrated standard match.}")
    check("начало партии", (st as? FicsEvent.Started)?.let { listOf(it.game, it.white, it.black) },
        listOf(28, "GuestMXXT", "GuestFLMP"))
    for ((line, want) in listOf(
        "{Game 28 (GuestMXXT vs. GuestFLMP) Game drawn by mutual agreement} 1/2-1/2" to (FicsEnd.DRAW to "1/2-1/2"),
        "{Game 2 (GuestFLMP vs. GuestMXXT) GuestMXXT resigns} 1-0" to (FicsEnd.RESIGN to "1-0"),
        "{Game 2 (GuestMXXT vs. GuestFLMP) Game aborted on move 1} *" to (FicsEnd.ABORT to "*"),
        "{Game 2 (GuestMXXT vs. GuestFLMP) GuestMXXT checkmated} 0-1" to (FicsEnd.MATE to "0-1"),
        "{Game 2 (GuestMXXT vs. GuestFLMP) GuestMXXT forfeits on time} 0-1" to (FicsEnd.FLAG to "0-1"),
        "{Game 15 (GuestMXXT vs. GuestFLMP) GuestMXXT forfeits by disconnection} 0-1" to (FicsEnd.DISCONNECT to "0-1"),
    )) {
        val e = Fics.parse(line) as? FicsEvent.Ended
        check("конец: ${line.substringAfter(") ")}", e?.let { it.kind to it.result }, want)
    }
    check("поиск: выставлен", (Fics.parse("Your seek has been posted with index 22.") as? FicsEvent.SeekPosted)?.index, 22)
    check("поиск: снят", Fics.parse("Your seeks have been removed.") === FicsEvent.SeekRemoved, true)
    check("ничья: предложение", (Fics.parse("GuestFLMP offers you a draw.") as? FicsEvent.DrawOffer)?.who, "GuestFLMP")
    check("ничья: отказ", (Fics.parse("GuestMXXT declines the draw request.") as? FicsEvent.DrawDeclined)?.who, "GuestMXXT")
    check("возврат хода: просьба",
        (Fics.parse("GuestFLMP would like to take back 1 half move(s).") as? FicsEvent.TakebackAsk)?.who, "GuestFLMP")
    check("незаконный ход", (Fics.parse("Illegal move (e2e5).") as? FicsEvent.Illegal)?.move, "e2e5")
    check("прочее — текст", Fics.parse("Draw request sent.") is FicsEvent.Text, true)
    check("<12> — позиция", Fics.parse(MATE) is FicsEvent.Board, true)

    val start = Fen.parse(Fen.START)
    check("команда: e2e4", Fics.moveCommand(San.fromUci(start, "e2e4")), "e2e4")
    check("команда: рокировка — ходом короля",
        Fics.moveCommand(San.fromUci(Fen.parse("4k3/8/8/8/8/8/8/4K2R w K - 0 1"), "e1g1")), "e1g1")
    check("команда: превращение «=n»",
        Fics.moveCommand(San.fromUci(Fen.parse("8/P6k/8/8/8/8/8/K7 w - - 0 1"), "a7a8n")), "a7a8=n")
    check("команда: поиск", Fics.seekCommand(15, 10), "seek 15 10 u")
    check("настройки: style 12 первым, open 0", Fics.setup("1.4.3").let { it[0] to ("set open 0" in it) },
        "set style 12" to true)
}

/** NetGame: дурацкий мат из настоящей партии (глазами белых), отказ, возврат, чужая позиция, часы. */
private fun netChecks(check: (String, Any?, Any?) -> Unit) {
    val g = NetGame("GuestMXXT", 2)
    check("сеть: начальная позиция", g.apply(s12(START), 0) && g.game.ply == 0 && g.myTurn, true)
    check("сеть: цвет и соперник", g.myColor to g.opponent, Color.WHITE to "GuestFLMP")
    val f3 = g.play(San.fromUci(g.game.position, "f2f3"))
    check("сеть: свой ход — команда и сразу на доске", f3 to g.game.ply, "f2f3" to 1)
    check("сеть: не свой ход — null", g.play(San.fromUci(g.game.position, "e7e5")), null)
    g.apply(s12(F3), 10)
    check("сеть: сервер подтвердил ход", g.game.ply to g.resyncs, 1 to 0)
    g.apply(s12(E5), 20)
    check("сеть: ход соперника проигран", g.game.san.toList(), listOf("f3", "e5"))
    g.play(San.fromUci(g.game.position, "d2d4"))
    g.apply(s12(E5_AGAIN), 30)
    check("сеть: отвергнутый ход откатан", g.game.ply to g.game.san.toList(), 2 to listOf("f3", "e5"))
    g.apply(s12(G4), 40)
    g.apply(s12(MATE), 50)
    check("сеть: мат, лист SAN, без пересборки",
        listOf(g.game.status, g.game.san.toList(), g.resyncs, g.boards),
        listOf(Status.CHECKMATE, listOf("f3", "e5", "g4", "Qh4#"), 0, 6))
    g.apply(s12(E5), 60)
    check("сеть: возврат на 2 полухода", g.game.ply to g.resyncs, 2 to 0)
    check("сеть: чужая партия не трогает", g.apply(s12(OBS1), 70), false)

    val b = NetGame("GuestFLMP", 2)
    b.apply(s12(START), 0)
    check("сеть: чёрными — цвет и чужой ход", b.myColor to b.myTurn, Color.BLACK to false)

    val o = NetGame("Hathkhola", 34)
    o.apply(s12(OBS1), 0)
    check("сеть: середина партии — пересборка из FEN", o.resyncs to o.game.startFen,
        1 to Fen.write(Fen.parse(s12(OBS1).fen())))
    o.apply(s12(OBS2), 1000)
    check("сеть: дальше ход проигрывается", listOf(o.resyncs, o.game.ply, o.game.san.toList()),
        listOf(1, 1, listOf("Be2")))

    // Часы: идут у той стороны, чей ход, от момента приёма позиции.
    check("часы: чёрные идут", o.running(Color.BLACK) to o.running(Color.WHITE), true to false)
    check("часы: −2,5 с у идущих", o.clockMs(Color.BLACK, 3500), 912135L - 2500)
    check("часы: стоящие не меняются", o.clockMs(Color.WHITE, 3500), 784813L)
    check("часы: не меньше нуля", o.clockMs(Color.BLACK, 10_000_000), 0L)
    check("часы: не идут до первых ходов", g.apply(s12(F3), 0) && !g.running(Color.BLACK), true)
    for ((ms, want) in listOf(900000L to "15:00", 718000L to "11:58", 245999L to "4:05",
        7000L to "0:07", 3723000L to "1:02:03", -1535L to "0:00")) check("часы: $ms мс → $want", NetGame.clockText(ms), want)
}
