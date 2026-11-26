package ru.ogrpfsense.dexpm8.chess

// Партия по сети (п. 25.3): своя [Game] (лист SAN, законные ходы для касаний, шах) держится
// в согласии с позициями сервера ([Style12]). Сервер — главный: его позиция всегда побеждает.
//
// - Свой ход делается сразу ([play]) — на E-Ink ждать ответа сервера ради кадра незачем; сервер
//   подтверждает его той же позицией (ничего не меняется) или отвергает («Illegal move» и
//   прежняя позиция — ход откатывается сам).
// - Чужой ход приходит позицией с подробной записью последнего хода — он проигрывается в [Game],
//   и лист SAN растёт как в партии с компьютером.
// - Позиция не сошлась (пропущенная строка, возврат хода, партия не с начала) — [Game]
//   собирается заново из FEN сервера; лист ходов до этого места теряется ([resyncs] растёт).
//
// Часы: сервер присылает остаток в мс с каждой позицией; между позициями идут часы той стороны,
// чей ход, если сервер их пустил ([Style12.ticking]: до первого хода каждой стороны стоят).
// Время приёма — монотонное (System.nanoTime / 1e6), его даёт вызывающий — так проверяется на хосте.
// Пакет без android.*.

class NetGame(
    /** Наше имя на сервере (из [FicsEvent.LoggedIn]). */
    val me: String,
    /** Номер партии на сервере. */
    val id: Int,
) {
    var game = Game()
        private set

    /** Полуходов до начала [game] (если её пришлось собрать из FEN посреди партии). */
    private var basePly = 0

    var white = ""
        private set
    var black = ""
        private set

    /** Наш цвет: [Color.WHITE] или [Color.BLACK]. */
    var myColor = Color.WHITE
        private set

    var minutes = 0
        private set
    var increment = 0
        private set

    /** Сколько раз историю пришлось бросить и собрать позицию из FEN. */
    var resyncs = 0
        private set

    /** Позиций сервера принято. */
    var boards = 0
        private set

    private var whiteMs = 0L
    private var blackMs = 0L
    private var serverSide = Color.WHITE
    private var ticking = false
    private var stamp = 0L

    val opponent: String get() = if (myColor == Color.WHITE) black else white

    /** Чей ход по нашей [game] (после своего хода — уже соперника, не дожидаясь сервера). */
    val myTurn: Boolean get() = game.position.side == myColor && game.status == Status.PLAYING

    /**
     * Принять позицию сервера в момент [now] (мс, монотонно). Чужая партия — false и ничего.
     * true — позиция на экране могла смениться (перерисовать).
     */
    fun apply(s: Style12, now: Long): Boolean {
        if (s.game != id) return false
        boards++
        white = s.white
        black = s.black
        myColor = if (s.black.equals(me, ignoreCase = true)) Color.BLACK else Color.WHITE
        minutes = s.minutes
        increment = s.increment
        whiteMs = s.whiteMs
        blackMs = s.blackMs
        serverSide = s.side
        ticking = s.ticking
        stamp = now

        val want = try {
            key(Fen.write(Fen.parse(s.fen())))
        } catch (e: IllegalArgumentException) {
            return false // позиция не из шахмат (варианты) — оставляем прежнюю
        }
        if (key(Fen.write(game.position)) == want) return true

        // Возврат хода или отвергнутый свой ход: отматываем до номера полухода сервера.
        while (basePly + game.ply > s.ply && game.ply > 0) game.undo()
        if (key(Fen.write(game.position)) == want) return true

        // Ровно один полуход вперёд — проигрываем запись сервера.
        val uci = s.lastUci()
        if (uci != null && basePly + game.ply == s.ply - 1) {
            try {
                game.playUci(uci)
            } catch (e: IllegalArgumentException) {
            }
            if (key(Fen.write(game.position)) == want) return true
        }

        // Не сошлось — позиция сервера целиком.
        game = Game(s.fen())
        basePly = s.ply
        resyncs++
        return true
    }

    /**
     * Наш ход касанием: делается сразу, возвращается команда для сервера («e2e4», «a7a8=q»).
     * Не наш ход или ход незаконен — null.
     */
    fun play(m: Int): String? {
        if (!myTurn || !game.isLegal(m)) return null
        game.play(m)
        return Fics.moveCommand(m)
    }

    /** Остаток на часах [color] в момент [now], мс (не меньше нуля). */
    fun clockMs(color: Int, now: Long): Long {
        val base = if (color == Color.WHITE) whiteMs else blackMs
        val run = if (ticking && color == serverSide) now - stamp else 0L
        return (base - run).coerceAtLeast(0L)
    }

    /** Идут ли сейчас часы [color]. */
    fun running(color: Int): Boolean = ticking && color == serverSide

    companion object {
        /** Сравниваем расстановку, очередь, рокировки и взятие на проходе — счётчики не важны. */
        private fun key(fen: String): String = fen.split(' ').take(4).joinToString(" ")

        /** «11:58», «4:05», «0:07»; от часа — «1:02:03». Доли секунды отбрасываются. */
        fun clockText(ms: Long): String {
            val s = (ms.coerceAtLeast(0L) / 1000).toInt()
            val h = s / 3600
            val m = s / 60 % 60
            val sec = s % 60
            val mm = if (h > 0) "%d:%02d".format(h, m) else m.toString()
            return "%s:%02d".format(mm, sec)
        }
    }
}
